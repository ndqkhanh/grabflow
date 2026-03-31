package grabflow.ride;

import grabflow.common.DriverLocation;
import grabflow.notification.NotificationQueue;
import grabflow.notification.NotificationRouter;
import grabflow.notification.NotificationService;
import grabflow.notification.TemplateRenderer;
import grabflow.payment.IdempotencyStore;
import grabflow.payment.PaymentLedger;
import grabflow.payment.PaymentService;
import grabflow.payment.SagaOrchestrator;
import grabflow.pricing.FareEstimator;
import grabflow.pricing.FareEstimator.PricingConfig;
import grabflow.pricing.PricingService;
import grabflow.pricing.PromoCodeValidator;
import grabflow.pricing.SurgeCalculator;
import grabflow.ride.dsl.MatchingEngine;
import grabflow.ride.state.RideStateMachine.Ride;
import grabflow.ride.state.RideStateMachine.RideStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class EndToEndRideTest {

    // Simple Euclidean-ish distance: ~111 km per degree latitude/longitude
    private static final MatchingEngine.DistanceCalculator DISTANCE =
            (lat1, lng1, lat2, lng2) -> {
                double dLat = (lat2 - lat1) * 111_000;
                double dLng = (lng2 - lng1) * 111_000;
                return Math.sqrt(dLat * dLat + dLng * dLng);
            };

    // Arbitrary H3 cell ID used throughout these tests
    private static final long ZONE_CELL = 0x872830828FFFFFFL;

    // -----------------------------------------------------------------------
    // Services under test (fresh for each test)
    // -----------------------------------------------------------------------

    private RideService rideService;
    private PricingService pricingService;
    private SurgeCalculator surgeCalculator;
    private PromoCodeValidator promoValidator;
    private FareEstimator fareEstimator;
    private PaymentService paymentService;
    private PaymentLedger ledger;
    private IdempotencyStore idempotencyStore;
    private NotificationService notificationService;

    @BeforeEach
    void setup() {
        // Ride
        rideService = new RideService(DISTANCE);

        // Pricing
        surgeCalculator = new SurgeCalculator();
        fareEstimator   = new FareEstimator(PricingConfig.defaults());
        promoValidator  = new PromoCodeValidator();
        pricingService  = new PricingService(surgeCalculator, fareEstimator, promoValidator);

        // Payment
        ledger           = new PaymentLedger();
        idempotencyStore = new IdempotencyStore();
        paymentService   = new PaymentService(new SagaOrchestrator(), idempotencyStore, ledger);

        // Notification
        notificationService = new NotificationService(
                new NotificationRouter(),
                new NotificationQueue(),
                new TemplateRenderer());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static DriverLocation driver(String id, double lat, double lng, double speed) {
        return new DriverLocation(id, lat, lng, 0, speed, System.currentTimeMillis(), 0);
    }

    // -----------------------------------------------------------------------
    // 1. completeRideWithPricingAndPayment
    // -----------------------------------------------------------------------

    @Test
    void completeRideWithPricingAndPayment() {
        // a. Drivers available near the pickup
        List<DriverLocation> drivers = List.of(
                driver("driver-alpha", 10.001, 106.0, 45.0),   // ~111 m
                driver("driver-beta",  10.05,  106.0, 40.0)    // ~5.5 km
        );

        // b. Request ride; DSL selects closest driver within 5 km
        Ride ride = rideService.requestRide(
                "e2e-ride-1", "rider-001",
                10.0, 106.0, 10.3, 106.3,
                drivers,
                "MATCH driver WHERE distance < 5000 ORDER BY distance ASC LIMIT 1");

        assertThat(ride.status()).isEqualTo(RideStatus.MATCHED);
        assertThat(ride.driverId()).isEqualTo("driver-alpha");

        // Advance lifecycle to COMPLETED
        rideService.driverArriving("e2e-ride-1");
        rideService.startRide("e2e-ride-1");
        Ride completed = rideService.completeRide("e2e-ride-1");
        assertThat(completed.status()).isEqualTo(RideStatus.COMPLETED);

        // c. Calculate surge price (no demand recorded => multiplier = 1.0)
        PricingService.PriceQuote quote = pricingService.calculatePrice(
                ZONE_CELL, 5.0, 12.0, null);
        assertThat(quote.fare().surgeMultiplier()).isEqualTo(1.0);
        assertThat(quote.finalPrice()).isGreaterThan(0);

        // d. Process payment
        PaymentService.ChargeRequest req = new PaymentService.ChargeRequest(
                "e2e-ride-1", "rider-001", "driver-alpha",
                quote.finalPrice(), "idem-key-e2e-1");
        PaymentService.ChargeResult result = paymentService.processCharge(req);
        assertThat(result.status()).isEqualTo(PaymentLedger.PaymentStatus.CAPTURED);
        assertThat(result.paymentId()).isNotNull();

        // Ledger has exactly one entry for this ride
        List<PaymentLedger.PaymentRecord> records = ledger.findByRideId("e2e-ride-1");
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().status()).isEqualTo(PaymentLedger.PaymentStatus.CAPTURED);
        assertThat(records.getFirst().amount()).isCloseTo(quote.finalPrice(), within(0.001));

        // e. Send notifications to rider
        notificationService.send("rider-001", NotificationRouter.NotificationType.RIDE_COMPLETED,
                Map.of("destination", "10.3, 106.3"));
        notificationService.send("rider-001", NotificationRouter.NotificationType.PAYMENT_RECEIPT,
                Map.of("amount", String.valueOf(quote.finalPrice()),
                       "currency", "USD",
                       "date", Instant.now().toString(),
                       "referenceId", result.paymentId()));

        // f. Verify notification history
        List<NotificationRouter.Notification> history =
                notificationService.getHistory("rider-001", 10);
        assertThat(history).hasSize(2);
        // getHistory returns newest-first
        assertThat(history.get(0).type()).isEqualTo(NotificationRouter.NotificationType.PAYMENT_RECEIPT);
        assertThat(history.get(1).type()).isEqualTo(NotificationRouter.NotificationType.RIDE_COMPLETED);

        NotificationService.NotificationStats stats = notificationService.getStats();
        assertThat(stats.sent()).isEqualTo(2);
        assertThat(stats.failed()).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // 2. rideWithSurgeAndPromoDiscount
    // -----------------------------------------------------------------------

    @Test
    void rideWithSurgeAndPromoDiscount() {
        // a. Record high demand (5 requests, 1 driver => ratio 5 => surge > 1)
        for (int i = 0; i < 5; i++) {
            surgeCalculator.recordDemand(ZONE_CELL);
        }
        surgeCalculator.recordSupply(ZONE_CELL, 1);

        double surge = surgeCalculator.getSurgeMultiplier(ZONE_CELL);
        assertThat(surge).isGreaterThan(1.0);

        // b. Request and complete a ride
        List<DriverLocation> drivers = List.of(driver("surge-driver", 10.001, 106.0, 50.0));
        Ride ride = rideService.requestRide(
                "surge-ride-1", "rider-002",
                10.0, 106.0, 10.2, 106.2,
                drivers,
                "MATCH driver WHERE distance < 5000 ORDER BY distance ASC LIMIT 1");
        assertThat(ride.status()).isEqualTo(RideStatus.MATCHED);

        rideService.driverArriving("surge-ride-1");
        rideService.startRide("surge-ride-1");
        rideService.completeRide("surge-ride-1");

        // c. Register a 20% promo code
        promoValidator.addPromoCode(new PromoCodeValidator.PromoCode(
                "GRAB20", 20.0, Instant.now().plusSeconds(3600)));

        // d. Calculate price with surge and promo
        double distKm  = 3.0;
        double minutes = 10.0;
        PricingService.PriceQuote quote = pricingService.calculatePrice(
                ZONE_CELL, distKm, minutes, "GRAB20");

        // Verify: promo was applied
        assertThat(quote.hasPromo()).isTrue();
        assertThat(quote.appliedPromo()).isEqualTo("GRAB20");

        // e. Compute expected price manually
        // defaults: base=1.00, perKm=0.40, perMin=0.08, minFare=2.00
        double raw    = 1.00 + distKm * 0.40 + minutes * 0.08;  // 1 + 1.2 + 0.8 = 3.0
        double surged = raw * surge;
        double afterPromo = surged * 0.80; // 20% off
        double expected = Math.max(afterPromo, 2.00);

        assertThat(quote.finalPrice()).isCloseTo(expected, within(0.001));
        assertThat(quote.finalPrice()).isLessThan(quote.fare().totalFare()); // promo reduced it
    }

    // -----------------------------------------------------------------------
    // 3. idempotentPaymentOnRetry
    // -----------------------------------------------------------------------

    @Test
    void idempotentPaymentOnRetry() {
        // a. Complete a ride
        List<DriverLocation> drivers = List.of(driver("idem-driver", 10.001, 106.0, 40.0));
        Ride idemRide = rideService.requestRide(
                "idem-ride-1", "rider-003",
                10.0, 106.0, 10.2, 106.2,
                drivers,
                "MATCH driver WHERE distance < 5000 ORDER BY distance ASC LIMIT 1");
        assertThat(idemRide.status()).isEqualTo(RideStatus.MATCHED);
        rideService.driverArriving("idem-ride-1");
        rideService.startRide("idem-ride-1");
        rideService.completeRide("idem-ride-1");

        PricingService.PriceQuote quote = pricingService.calculatePrice(
                ZONE_CELL, 2.0, 8.0, null);

        // b. First charge with idempotency key
        String idemKey = "idem-key-ride-003";
        PaymentService.ChargeRequest req = new PaymentService.ChargeRequest(
                "idem-ride-1", "rider-003", "idem-driver",
                quote.finalPrice(), idemKey);

        PaymentService.ChargeResult first = paymentService.processCharge(req);
        assertThat(first.status()).isEqualTo(PaymentLedger.PaymentStatus.CAPTURED);

        // c. Retry with the same idempotency key
        PaymentService.ChargeResult retry = paymentService.processCharge(req);

        // Same payment ID returned (cached result)
        assertThat(retry.paymentId()).isEqualTo(first.paymentId());
        assertThat(retry.status()).isEqualTo(PaymentLedger.PaymentStatus.CAPTURED);

        // d. Ledger has only ONE entry (no duplicate charge)
        List<PaymentLedger.PaymentRecord> records = ledger.findByRideId("idem-ride-1");
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().paymentId()).isEqualTo(first.paymentId());
    }
}
