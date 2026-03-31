package grabflow.pricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates surge pricing, fare estimation, and promotional discount application
 * to produce a final price quote for a ride.
 *
 * <h3>Flow</h3>
 * <pre>
 *   pickupH3Cell ──► SurgeCalculator.getSurgeMultiplier()
 *                                    │
 *                                    ▼
 *   (distanceKm, estMinutes) ──► FareEstimator.estimate()
 *                                    │
 *                                    ▼
 *   promoCode ──────────────► PromoCodeValidator.applyDiscount()
 *                                    │
 *                                    ▼
 *                             PriceQuote (fare breakdown + final price)
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   PricingService svc = new PricingService(surgeCalculator, fareEstimator, promoValidator);
 *   PriceQuote quote = svc.calculatePrice(zoneCell, 5.2, 14.0, "GRAB20");
 *   System.out.println(quote.finalPrice());
 * }</pre>
 */
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    private final SurgeCalculator surgeCalculator;
    private final FareEstimator   fareEstimator;
    private final PromoCodeValidator promoValidator;

    /**
     * @param surgeCalculator  calculates surge multipliers per H3 zone
     * @param fareEstimator    estimates fares from distance, time, and surge
     * @param promoValidator   validates and applies promo code discounts
     */
    public PricingService(
            SurgeCalculator surgeCalculator,
            FareEstimator fareEstimator,
            PromoCodeValidator promoValidator) {

        if (surgeCalculator == null) throw new IllegalArgumentException("surgeCalculator must not be null");
        if (fareEstimator   == null) throw new IllegalArgumentException("fareEstimator must not be null");
        if (promoValidator  == null) throw new IllegalArgumentException("promoValidator must not be null");

        this.surgeCalculator = surgeCalculator;
        this.fareEstimator   = fareEstimator;
        this.promoValidator  = promoValidator;
    }

    /**
     * Calculates the full price quote for a ride.
     *
     * <ol>
     *   <li>Looks up the surge multiplier for the pickup zone.</li>
     *   <li>Estimates the base + surge fare from distance and duration.</li>
     *   <li>Applies the promo code discount if the code is valid.</li>
     * </ol>
     *
     * @param pickupH3Cell   H3 cell ID (resolution 7) for the pickup location
     * @param distanceKm     estimated ride distance in kilometres (&ge; 0)
     * @param estMinutes     estimated ride duration in minutes (&ge; 0)
     * @param promoCode      promotional code to apply, or {@code null} / blank to skip
     * @return a {@link PriceQuote} with the full fare breakdown and final price
     */
    public PriceQuote calculatePrice(
            long pickupH3Cell,
            double distanceKm,
            double estMinutes,
            String promoCode) {

        // Step 1: surge multiplier for pickup zone
        double surge = surgeCalculator.getSurgeMultiplier(pickupH3Cell);

        // Step 2: fare estimate (base + distance + time, multiplied by surge)
        FareEstimator.FareBreakdown fare = fareEstimator.estimate(distanceKm, estMinutes, surge);

        // Step 3: apply promo discount
        String appliedPromo = null;
        double finalPrice = fare.totalFare();

        if (promoCode != null && !promoCode.isBlank()) {
            double discounted = promoValidator.applyDiscount(fare.totalFare(), promoCode);
            if (discounted < fare.totalFare()) {
                appliedPromo = promoCode.trim().toUpperCase();
                finalPrice   = discounted;
            }
        }

        PriceQuote quote = new PriceQuote(fare, appliedPromo, finalPrice);
        log.info("Price quote: zone={}, dist={} km, time={} min, surge={}, promo={}, final={}",
                pickupH3Cell, distanceKm, estMinutes, surge, appliedPromo, finalPrice);
        return quote;
    }

    // ── Nested types ──

    /**
     * The final price quote returned to the caller.
     *
     * @param fare          full fare breakdown (base, distance, time, surge)
     * @param appliedPromo  normalised promo code that was applied, or {@code null} if none
     * @param finalPrice    the price the passenger will be charged
     */
    public record PriceQuote(
            FareEstimator.FareBreakdown fare,
            String appliedPromo,
            double finalPrice) {

        /** Returns {@code true} if a promo code was successfully applied. */
        public boolean hasPromo() {
            return appliedPromo != null;
        }
    }
}
