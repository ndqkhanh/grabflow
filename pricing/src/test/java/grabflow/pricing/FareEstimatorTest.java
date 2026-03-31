package grabflow.pricing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class FareEstimatorTest {

    private FareEstimator estimator;
    private FareEstimator.PricingConfig config;

    @BeforeEach
    void setUp() {
        config    = FareEstimator.PricingConfig.defaults();
        estimator = new FareEstimator(config);
    }

    // ── Basic fare calculation ─────────────────────────────────────────────

    @Test
    void baseFareOnlyForZeroDistanceAndTime() {
        // 0 km, 0 min, no surge → base fare, but subject to minimum
        FareEstimator.FareBreakdown fare = estimator.estimate(0, 0, 1.0);
        // raw = 1.00 + 0 + 0 = 1.00; surged = 1.00; total = max(1.00, 2.00) = 2.00
        assertThat(fare.baseFare()).isEqualTo(config.baseFare());
        assertThat(fare.distanceFare()).isEqualTo(0.0);
        assertThat(fare.timeFare()).isEqualTo(0.0);
        assertThat(fare.surgeMultiplier()).isEqualTo(1.0);
        assertThat(fare.totalFare()).isEqualTo(config.minimumFare());
    }

    @Test
    void fareCalculationWithDistanceAndTime() {
        // 5 km, 10 min, no surge
        // raw = 1.00 + 5*0.40 + 10*0.08 = 1.00 + 2.00 + 0.80 = 3.80
        FareEstimator.FareBreakdown fare = estimator.estimate(5.0, 10.0, 1.0);
        assertThat(fare.distanceFare()).isCloseTo(2.0, within(0.001));
        assertThat(fare.timeFare()).isCloseTo(0.80, within(0.001));
        assertThat(fare.rawFare()).isCloseTo(3.80, within(0.001));
        assertThat(fare.totalFare()).isCloseTo(3.80, within(0.001));
    }

    // ── Surge multiplier ──────────────────────────────────────────────────

    @Test
    void surgeMultiplierDoublesFare() {
        FareEstimator.FareBreakdown noSurge = estimator.estimate(5.0, 10.0, 1.0);
        FareEstimator.FareBreakdown withSurge = estimator.estimate(5.0, 10.0, 2.0);
        assertThat(withSurge.totalFare()).isCloseTo(noSurge.rawFare() * 2.0, within(0.001));
    }

    @Test
    void surgeIsAppliedBeforeMinimumFareCheck() {
        // Very short trip: raw = 1.00 + 0 + 0 = 1.00; with 3x surge = 3.00 > minimum 2.00
        FareEstimator.FareBreakdown fare = estimator.estimate(0, 0, 3.0);
        assertThat(fare.surgeMultiplier()).isEqualTo(3.0);
        // surged = 1.00 * 3 = 3.00; max(3.00, 2.00) = 3.00
        assertThat(fare.totalFare()).isCloseTo(3.0, within(0.001));
    }

    @Test
    void surgeMultiplierStoredInBreakdown() {
        FareEstimator.FareBreakdown fare = estimator.estimate(2.0, 5.0, 1.5);
        assertThat(fare.surgeMultiplier()).isEqualTo(1.5);
    }

    // ── Minimum fare ──────────────────────────────────────────────────────

    @Test
    void minimumFareEnforcedForVeryShortTrip() {
        // 0.1 km, 1 min, no surge
        // raw = 1.00 + 0.1*0.40 + 1*0.08 = 1.12; max(1.12, 2.00) = 2.00
        FareEstimator.FareBreakdown fare = estimator.estimate(0.1, 1.0, 1.0);
        assertThat(fare.totalFare()).isEqualTo(config.minimumFare());
    }

    @Test
    void minimumFareNotAppliedWhenRawExceedsIt() {
        // 10 km, 20 min, no surge
        // raw = 1.00 + 10*0.40 + 20*0.08 = 1.00 + 4.00 + 1.60 = 6.60 > 2.00
        FareEstimator.FareBreakdown fare = estimator.estimate(10.0, 20.0, 1.0);
        assertThat(fare.totalFare()).isCloseTo(6.60, within(0.001));
    }

    // ── Custom PricingConfig ──────────────────────────────────────────────

    @Test
    void customConfigApplied() {
        var custom = new FareEstimator.PricingConfig(0.50, 0.20, 0.05, 1.00);
        var est = new FareEstimator(custom);
        // 2 km, 8 min, no surge: raw = 0.50 + 2*0.20 + 8*0.05 = 0.50+0.40+0.40=1.30
        FareEstimator.FareBreakdown fare = est.estimate(2.0, 8.0, 1.0);
        assertThat(fare.totalFare()).isCloseTo(1.30, within(0.001));
    }

    @Test
    void defaultsReturnSensibleValues() {
        assertThat(config.baseFare()).isPositive();
        assertThat(config.perKmRate()).isPositive();
        assertThat(config.perMinuteRate()).isPositive();
        assertThat(config.minimumFare()).isPositive();
    }

    // ── FareBreakdown.rawFare() helper ────────────────────────────────────

    @Test
    void rawFareEqualsComponentSum() {
        FareEstimator.FareBreakdown fare = estimator.estimate(3.0, 6.0, 1.8);
        double expectedRaw = fare.baseFare() + fare.distanceFare() + fare.timeFare();
        assertThat(fare.rawFare()).isCloseTo(expectedRaw, within(0.0001));
    }

    // ── Validation ────────────────────────────────────────────────────────

    @Test
    void negativeDistanceThrows() {
        assertThatThrownBy(() -> estimator.estimate(-1.0, 10.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeDurationThrows() {
        assertThatThrownBy(() -> estimator.estimate(5.0, -1.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void surgeBelowOneThrows() {
        assertThatThrownBy(() -> estimator.estimate(5.0, 10.0, 0.9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullConfigThrows() {
        assertThatThrownBy(() -> new FareEstimator(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidPricingConfigThrows() {
        assertThatThrownBy(() -> new FareEstimator.PricingConfig(-1, 0.40, 0.08, 2.00))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FareEstimator.PricingConfig(1.00, -0.10, 0.08, 2.00))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FareEstimator.PricingConfig(1.00, 0.40, -0.01, 2.00))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
