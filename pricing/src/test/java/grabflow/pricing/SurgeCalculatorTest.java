package grabflow.pricing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.*;

class SurgeCalculatorTest {

    private SurgeCalculator calculator;

    // Arbitrary H3 cell IDs used across tests
    private static final long ZONE_A = 0x01_7000_0000_0000_00L;
    private static final long ZONE_B = 0x01_7000_0000_0000_01L;

    @BeforeEach
    void setUp() {
        calculator = new SurgeCalculator();
    }

    // ── No-surge cases ──────────────────────────────────────────────────────

    @Test
    void noSurgeWhenNoDemandRecorded() {
        calculator.recordSupply(ZONE_A, 5);
        assertThat(calculator.getSurgeMultiplier(ZONE_A)).isEqualTo(1.0);
    }

    @Test
    void noSurgeWhenSupplyMeetsDemand() {
        // demand == supply → ratio 1.0 → no surge
        calculator.recordSupply(ZONE_A, 4);
        for (int i = 0; i < 4; i++) calculator.recordDemand(ZONE_A);
        assertThat(calculator.getSurgeMultiplier(ZONE_A)).isEqualTo(1.0);
    }

    @Test
    void noSurgeWhenSupplyExceedsDemand() {
        calculator.recordSupply(ZONE_A, 10);
        calculator.recordDemand(ZONE_A);
        // ratio = 0.1 → 1.0
        assertThat(calculator.getSurgeMultiplier(ZONE_A)).isEqualTo(1.0);
    }

    // ── Linear ramp (ratio 1..2 → multiplier 1.0..1.5) ────────────────────

    @Test
    void linearRampAtRatioOnePointFive() {
        // ratio 1.5 → 1.0 + (1.5 - 1.0) * 0.5 = 1.25
        calculator.recordSupply(ZONE_A, 2);
        calculator.recordDemand(ZONE_A);
        calculator.recordDemand(ZONE_A);
        calculator.recordDemand(ZONE_A);
        // demand=3, supply=2 → ratio=1.5
        assertThat(calculator.getSurgeMultiplier(ZONE_A)).isCloseTo(1.25, within(0.001));
    }

    @Test
    void linearRampAtRatioTwo() {
        // ratio 2.0 → 1.0 + (2.0 - 1.0) * 0.5 = 1.5
        calculator.recordSupply(ZONE_A, 1);
        calculator.recordDemand(ZONE_A);
        calculator.recordDemand(ZONE_A);
        assertThat(calculator.getSurgeMultiplier(ZONE_A)).isCloseTo(1.5, within(0.001));
    }

    // ── Slower ramp (ratio 2..4 → multiplier 1.5..2.0) ────────────────────

    @Test
    void slowerRampAtRatioThree() {
        // ratio 3.0 → 1.5 + (3.0 - 2.0) * 0.25 = 1.75
        calculator.recordSupply(ZONE_A, 2);
        for (int i = 0; i < 6; i++) calculator.recordDemand(ZONE_A);
        // demand=6, supply=2 → ratio=3.0
        assertThat(calculator.getSurgeMultiplier(ZONE_A)).isCloseTo(1.75, within(0.001));
    }

    @Test
    void slowerRampAtRatioFour() {
        // ratio 4.0 → 1.5 + (4.0 - 2.0) * 0.25 = 2.0
        calculator.recordSupply(ZONE_A, 1);
        for (int i = 0; i < 4; i++) calculator.recordDemand(ZONE_A);
        assertThat(calculator.getSurgeMultiplier(ZONE_A)).isCloseTo(2.0, within(0.001));
    }

    // ── Cap at 3.0x ────────────────────────────────────────────────────────

    @Test
    void cappedAtMaxMultiplierForExtremeRatio() {
        // ratio = 100/1 = 100 → min(3.0, 1.5 + 100*0.2) = 3.0
        calculator.recordSupply(ZONE_A, 1);
        for (int i = 0; i < 100; i++) calculator.recordDemand(ZONE_A);
        assertThat(calculator.getSurgeMultiplier(ZONE_A)).isEqualTo(SurgeCalculator.MAX_MULTIPLIER);
    }

    @Test
    void surgeAboveFourButBelowCap() {
        // ratio = 5/1 = 5 → min(3.0, 1.5 + 5*0.2) = min(3.0, 2.5) = 2.5
        calculator.recordSupply(ZONE_A, 1);
        for (int i = 0; i < 5; i++) calculator.recordDemand(ZONE_A);
        assertThat(calculator.getSurgeMultiplier(ZONE_A)).isCloseTo(2.5, within(0.001));
    }

    // ── Zero-supply edge case ───────────────────────────────────────────────

    @Test
    void zeroSupplyTreatedAsOne() {
        // supply=0 → effectiveSupply=1; demand=3 → ratio=3 → 1.5+(3-2)*0.25=1.75
        calculator.recordSupply(ZONE_A, 0);
        calculator.recordDemand(ZONE_A);
        calculator.recordDemand(ZONE_A);
        calculator.recordDemand(ZONE_A);
        assertThat(calculator.getSurgeMultiplier(ZONE_A)).isCloseTo(1.75, within(0.001));
    }

    @Test
    void noSupplyRecordedDefaultsToOneDriver() {
        // no supply call at all → effective supply = 1
        calculator.recordDemand(ZONE_A);
        calculator.recordDemand(ZONE_A);
        // demand=2, supply=1 → ratio=2 → 1.5
        assertThat(calculator.getSurgeMultiplier(ZONE_A)).isCloseTo(1.5, within(0.001));
    }

    // ── reset() ────────────────────────────────────────────────────────────

    @Test
    void resetClearsAllZoneMetrics() {
        calculator.recordSupply(ZONE_A, 1);
        calculator.recordDemand(ZONE_A);
        calculator.recordDemand(ZONE_A);
        assertThat(calculator.getSurgeMultiplier(ZONE_A)).isGreaterThan(1.0);

        calculator.reset();

        assertThat(calculator.getSurgeMultiplier(ZONE_A)).isEqualTo(1.0);
        assertThat(calculator.getZoneMetrics(ZONE_A).demandCount()).isZero();
        assertThat(calculator.getZoneMetrics(ZONE_A).supplyCount()).isZero();
    }

    @Test
    void resetAllowsAccumulationAfterward() {
        for (int i = 0; i < 5; i++) calculator.recordDemand(ZONE_A);
        calculator.reset();
        // After reset, single demand with no supply = ratio 1.0/1 → no surge
        calculator.recordSupply(ZONE_A, 2);
        calculator.recordDemand(ZONE_A);
        assertThat(calculator.getSurgeMultiplier(ZONE_A)).isEqualTo(1.0);
    }

    // ── getAllSurgeZones() ─────────────────────────────────────────────────

    @Test
    void getAllSurgeZonesReturnsOnlySurgingZones() {
        // ZONE_A: surge (demand > supply)
        calculator.recordSupply(ZONE_A, 1);
        calculator.recordDemand(ZONE_A);
        calculator.recordDemand(ZONE_A);
        // ZONE_B: no surge (supply meets demand)
        calculator.recordSupply(ZONE_B, 5);
        calculator.recordDemand(ZONE_B);

        Map<Long, Double> surgeZones = calculator.getAllSurgeZones();
        assertThat(surgeZones).containsKey(ZONE_A);
        assertThat(surgeZones).doesNotContainKey(ZONE_B);
        assertThat(surgeZones.get(ZONE_A)).isGreaterThan(1.0);
    }

    @Test
    void getAllSurgeZonesIsUnmodifiable() {
        calculator.recordDemand(ZONE_A);
        Map<Long, Double> zones = calculator.getAllSurgeZones();
        assertThatThrownBy(() -> zones.put(ZONE_B, 2.0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── Concurrent access ──────────────────────────────────────────────────

    @Test
    void concurrentDemandRecordingIsAccurate() throws InterruptedException {
        int threads = 8;
        int demandsPerThread = 250;
        var latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Thread.ofVirtual().start(() -> {
                for (int i = 0; i < demandsPerThread; i++) {
                    calculator.recordDemand(ZONE_A);
                }
                latch.countDown();
            });
        }

        latch.await();
        int totalDemand = calculator.getZoneMetrics(ZONE_A).demandCount();
        assertThat(totalDemand).isEqualTo(threads * demandsPerThread);
    }

    // ── computeMultiplier boundary tests ──────────────────────────────────

    @Test
    void computeMultiplierAtExactBoundaries() {
        assertThat(SurgeCalculator.computeMultiplier(1.0)).isEqualTo(1.0);
        assertThat(SurgeCalculator.computeMultiplier(2.0)).isCloseTo(1.5, within(0.0001));
        assertThat(SurgeCalculator.computeMultiplier(4.0)).isCloseTo(2.0, within(0.0001));
    }

    @Test
    void illegalSupplyThrows() {
        assertThatThrownBy(() -> calculator.recordSupply(ZONE_A, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
