package grabflow.pricing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class PricingServiceTest {

    private static final long PICKUP_ZONE = 0x01_7000_0000_0000_42L;

    private SurgeCalculator      surgeCalculator;
    private FareEstimator        fareEstimator;
    private PromoCodeValidator   promoValidator;
    private PricingService       service;

    @BeforeEach
    void setUp() {
        surgeCalculator = new SurgeCalculator();
        fareEstimator   = new FareEstimator(FareEstimator.PricingConfig.defaults());
        promoValidator  = new PromoCodeValidator();
        service = new PricingService(surgeCalculator, fareEstimator, promoValidator);
    }

    // ── No surge, no promo ─────────────────────────────────────────────────

    @Test
    void standardFareWithNoSurgeNoPromo() {
        // balanced zone: ratio <= 1 → no surge
        surgeCalculator.recordSupply(PICKUP_ZONE, 5);
        for (int i = 0; i < 3; i++) surgeCalculator.recordDemand(PICKUP_ZONE);

        PricingService.PriceQuote quote = service.calculatePrice(PICKUP_ZONE, 5.0, 10.0, null);

        assertThat(quote.fare().surgeMultiplier()).isEqualTo(1.0);
        assertThat(quote.hasPromo()).isFalse();
        assertThat(quote.appliedPromo()).isNull();
        // raw = 1.00 + 5*0.40 + 10*0.08 = 3.80
        assertThat(quote.finalPrice()).isCloseTo(3.80, within(0.01));
    }

    @Test
    void minimumFareAppliedForShortTrip() {
        surgeCalculator.recordSupply(PICKUP_ZONE, 10);
        PricingService.PriceQuote quote = service.calculatePrice(PICKUP_ZONE, 0.1, 1.0, null);
        assertThat(quote.finalPrice()).isGreaterThanOrEqualTo(FareEstimator.PricingConfig.defaults().minimumFare());
    }

    // ── With surge ─────────────────────────────────────────────────────────

    @Test
    void surgeMultiplierIsAppliedToFare() {
        // demand=4, supply=1 → ratio=4 → multiplier=2.0
        surgeCalculator.recordSupply(PICKUP_ZONE, 1);
        for (int i = 0; i < 4; i++) surgeCalculator.recordDemand(PICKUP_ZONE);

        PricingService.PriceQuote withSurge = service.calculatePrice(PICKUP_ZONE, 5.0, 10.0, null);

        // Compute no-surge fare for comparison
        FareEstimator noSurgeEstimator = new FareEstimator(FareEstimator.PricingConfig.defaults());
        double noSurgeFare = noSurgeEstimator.estimate(5.0, 10.0, 1.0).totalFare();

        assertThat(withSurge.fare().surgeMultiplier()).isCloseTo(2.0, within(0.001));
        assertThat(withSurge.finalPrice()).isGreaterThan(noSurgeFare);
    }

    // ── With promo ─────────────────────────────────────────────────────────

    @Test
    void validPromoCodeReducesFinalPrice() {
        promoValidator.addPromoCode(new PromoCodeValidator.PromoCode(
                "SAVE20", 20.0, Instant.now().plusSeconds(3600)));

        PricingService.PriceQuote withPromo =
                service.calculatePrice(PICKUP_ZONE, 5.0, 10.0, "SAVE20");

        PricingService.PriceQuote withoutPromo =
                service.calculatePrice(PICKUP_ZONE, 5.0, 10.0, null);

        assertThat(withPromo.hasPromo()).isTrue();
        assertThat(withPromo.appliedPromo()).isEqualTo("SAVE20");
        assertThat(withPromo.finalPrice()).isLessThan(withoutPromo.finalPrice());
        assertThat(withPromo.finalPrice())
                .isCloseTo(withoutPromo.finalPrice() * 0.80, within(0.01));
    }

    @Test
    void expiredPromoCodeNotApplied() {
        promoValidator.addPromoCode(new PromoCodeValidator.PromoCode(
                "OLD", 30.0, Instant.now().minusSeconds(1)));

        PricingService.PriceQuote quote = service.calculatePrice(PICKUP_ZONE, 5.0, 10.0, "OLD");

        assertThat(quote.hasPromo()).isFalse();
        assertThat(quote.appliedPromo()).isNull();
    }

    @Test
    void unknownPromoCodeNotApplied() {
        PricingService.PriceQuote quote =
                service.calculatePrice(PICKUP_ZONE, 5.0, 10.0, "NOSUCHCODE");
        assertThat(quote.hasPromo()).isFalse();
    }

    @Test
    void blankPromoCodeSkipped() {
        PricingService.PriceQuote quote =
                service.calculatePrice(PICKUP_ZONE, 5.0, 10.0, "   ");
        assertThat(quote.hasPromo()).isFalse();
    }

    // ── Surge + promo combined ─────────────────────────────────────────────

    @Test
    void surgeAndPromoAppliedTogether() {
        // Set up surge (demand=3, supply=1 → ratio=3 → multiplier=1.75)
        surgeCalculator.recordSupply(PICKUP_ZONE, 1);
        for (int i = 0; i < 3; i++) surgeCalculator.recordDemand(PICKUP_ZONE);

        promoValidator.addPromoCode(new PromoCodeValidator.PromoCode(
                "HALF", 50.0, Instant.now().plusSeconds(3600)));

        PricingService.PriceQuote quote =
                service.calculatePrice(PICKUP_ZONE, 4.0, 8.0, "HALF");

        assertThat(quote.fare().surgeMultiplier()).isCloseTo(1.75, within(0.001));
        assertThat(quote.hasPromo()).isTrue();

        // Expected: raw = 1.00 + 4*0.40 + 8*0.08 = 1.00 + 1.60 + 0.64 = 3.24
        // surged = 3.24 * 1.75 = 5.67; after 50% promo = 2.835
        double expectedSurged = 3.24 * 1.75;
        double expectedFinal  = expectedSurged * 0.50;
        assertThat(quote.finalPrice()).isCloseTo(expectedFinal, within(0.05));
    }

    @Test
    void promoAppliedCaseInsensitively() {
        promoValidator.addPromoCode(new PromoCodeValidator.PromoCode(
                "GRAB10", 10.0, Instant.now().plusSeconds(3600)));

        PricingService.PriceQuote lower = service.calculatePrice(PICKUP_ZONE, 5.0, 10.0, "grab10");
        PricingService.PriceQuote upper = service.calculatePrice(PICKUP_ZONE, 5.0, 10.0, "GRAB10");

        assertThat(lower.hasPromo()).isTrue();
        assertThat(upper.hasPromo()).isTrue();
        assertThat(lower.finalPrice()).isCloseTo(upper.finalPrice(), within(0.001));
    }

    // ── PriceQuote structure ───────────────────────────────────────────────

    @Test
    void priceQuoteFareBreakdownIsComplete() {
        PricingService.PriceQuote quote = service.calculatePrice(PICKUP_ZONE, 3.0, 7.0, null);
        assertThat(quote.fare().baseFare()).isPositive();
        assertThat(quote.fare().distanceFare()).isPositive();
        assertThat(quote.fare().timeFare()).isPositive();
        assertThat(quote.fare().surgeMultiplier()).isGreaterThanOrEqualTo(1.0);
        assertThat(quote.finalPrice()).isGreaterThanOrEqualTo(quote.fare().rawFare()); // surge or min-fare
    }

    // ── Null guard ─────────────────────────────────────────────────────────

    @Test
    void nullSurgeCalculatorThrows() {
        assertThatThrownBy(() -> new PricingService(null, fareEstimator, promoValidator))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullFareEstimatorThrows() {
        assertThatThrownBy(() -> new PricingService(surgeCalculator, null, promoValidator))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPromoValidatorThrows() {
        assertThatThrownBy(() -> new PricingService(surgeCalculator, fareEstimator, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
