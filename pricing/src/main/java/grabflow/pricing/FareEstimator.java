package grabflow.pricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Estimates ride fares based on distance, duration, and a surge multiplier.
 *
 * <h3>Fare Formula</h3>
 * <pre>
 *   raw = baseFare + distanceKm * perKmRate + minutes * perMinuteRate
 *   surged = raw * surgeMultiplier
 *   total = max(surged, minimumFare)
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   FareEstimator estimator = new FareEstimator(PricingConfig.defaults());
 *   FareBreakdown fare = estimator.estimate(4.5, 12.0, 1.5);
 *   System.out.println(fare.totalFare()); // e.g. 3.98
 * }</pre>
 */
public class FareEstimator {

    private static final Logger log = LoggerFactory.getLogger(FareEstimator.class);

    private final PricingConfig config;

    /**
     * @param config pricing configuration (rates and minimum fare)
     */
    public FareEstimator(PricingConfig config) {
        if (config == null) throw new IllegalArgumentException("PricingConfig must not be null");
        this.config = config;
    }

    /**
     * Estimates the fare for a ride.
     *
     * @param distanceKm        estimated ride distance in kilometres (&ge; 0)
     * @param estimatedMinutes  estimated ride duration in minutes (&ge; 0)
     * @param surgeMultiplier   surge multiplier (&ge; 1.0)
     * @return a full {@link FareBreakdown} including the final total
     */
    public FareBreakdown estimate(double distanceKm, double estimatedMinutes, double surgeMultiplier) {
        if (distanceKm < 0) throw new IllegalArgumentException("Distance must be >= 0");
        if (estimatedMinutes < 0) throw new IllegalArgumentException("Duration must be >= 0");
        if (surgeMultiplier < 1.0) throw new IllegalArgumentException("Surge multiplier must be >= 1.0");

        double baseFare     = config.baseFare();
        double distanceFare = distanceKm * config.perKmRate();
        double timeFare     = estimatedMinutes * config.perMinuteRate();

        double raw    = baseFare + distanceFare + timeFare;
        double surged = raw * surgeMultiplier;
        double total  = Math.max(surged, config.minimumFare());

        FareBreakdown breakdown = new FareBreakdown(baseFare, distanceFare, timeFare, surgeMultiplier, total);
        log.debug("Fare estimated: dist={} km, time={} min, surge={} -> total={}",
                distanceKm, estimatedMinutes, surgeMultiplier, total);
        return breakdown;
    }

    /**
     * Returns the pricing configuration used by this estimator.
     */
    public PricingConfig getConfig() {
        return config;
    }

    // ── Nested types ──

    /**
     * A full breakdown of a fare estimate.
     *
     * @param baseFare         fixed base charge (currency units)
     * @param distanceFare     distance-based component before surge
     * @param timeFare         time-based component before surge
     * @param surgeMultiplier  multiplier applied to the raw fare
     * @param totalFare        final charge (after surge and minimum-fare floor)
     */
    public record FareBreakdown(
            double baseFare,
            double distanceFare,
            double timeFare,
            double surgeMultiplier,
            double totalFare) {

        /** Raw fare before surge is applied. */
        public double rawFare() {
            return baseFare + distanceFare + timeFare;
        }
    }

    /**
     * Pricing configuration for a vehicle category or market.
     *
     * @param baseFare       fixed flag-fall charge (currency units)
     * @param perKmRate      charge per kilometre
     * @param perMinuteRate  charge per minute of ride time
     * @param minimumFare    floor on the total fare (after surge)
     */
    public record PricingConfig(
            double baseFare,
            double perKmRate,
            double perMinuteRate,
            double minimumFare) {

        /**
         * Validates that all rates are non-negative and the minimum fare is positive.
         */
        public PricingConfig {
            if (baseFare < 0)      throw new IllegalArgumentException("baseFare must be >= 0");
            if (perKmRate < 0)     throw new IllegalArgumentException("perKmRate must be >= 0");
            if (perMinuteRate < 0) throw new IllegalArgumentException("perMinuteRate must be >= 0");
            if (minimumFare < 0)   throw new IllegalArgumentException("minimumFare must be >= 0");
        }

        /**
         * Returns sensible default rates for a standard ride-sharing service
         * (roughly calibrated to Southeast Asian ride-hailing markets).
         *
         * <ul>
         *   <li>Base fare: $1.00</li>
         *   <li>Per-km rate: $0.40</li>
         *   <li>Per-minute rate: $0.08</li>
         *   <li>Minimum fare: $2.00</li>
         * </ul>
         *
         * @return default {@link PricingConfig}
         */
        public static PricingConfig defaults() {
            return new PricingConfig(1.00, 0.40, 0.08, 2.00);
        }
    }
}
