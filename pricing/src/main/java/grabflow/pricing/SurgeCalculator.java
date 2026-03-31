package grabflow.pricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Calculates dynamic surge pricing multipliers based on supply/demand ratios per zone.
 *
 * <h3>Design</h3>
 * <p>Each zone is identified by an H3 cell ID at resolution 7 (city-district level,
 * ~1.2 km edge length, ~5 km² area). For each zone the calculator tracks:</p>
 * <ul>
 *   <li><em>Demand</em> – number of ride requests received in the current time window</li>
 *   <li><em>Supply</em> – number of available drivers reported for the zone</li>
 * </ul>
 *
 * <h3>Surge Formula</h3>
 * <pre>
 *   ratio = demand / max(supply, 1)
 *
 *   ratio &le; 1.0  →  1.0x  (no surge, supply meets demand)
 *   ratio &le; 2.0  →  1.0 + (ratio - 1.0) * 0.5   (linear ramp: 1.0x → 1.5x)
 *   ratio &le; 4.0  →  1.5 + (ratio - 2.0) * 0.25  (slower ramp:  1.5x → 2.0x)
 *   ratio  &gt; 4.0  →  min(3.0, 1.5 + ratio * 0.2) (capped at 3.0x)
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * <p>All mutation methods are thread-safe. {@link #recordDemand} uses
 * {@link AtomicInteger} increments; {@link #recordSupply} and {@link #reset}
 * use the {@link ConcurrentHashMap} guarantees. The {@link ZoneMetrics} record
 * itself is immutable – supply updates replace the entry atomically.</p>
 *
 * <h3>Time-Window Rotation</h3>
 * <p>Call {@link #reset()} at the start of each pricing window (typically every
 * 1-5 minutes) to discard stale demand counts before the next accumulation cycle.</p>
 */
public class SurgeCalculator {

    private static final Logger log = LoggerFactory.getLogger(SurgeCalculator.class);

    /** Maximum surge multiplier regardless of demand/supply ratio. */
    static final double MAX_MULTIPLIER = 3.0;

    /**
     * Per-zone metrics. {@code demandCount} is maintained by a separate
     * {@link AtomicInteger} stored in {@link #demandCounters} to allow
     * lock-free increments. {@code supplyCount} is the last driver count
     * reported by the fleet tracker.
     *
     * @param demandCount  number of ride requests recorded this window
     * @param supplyCount  number of available drivers last reported
     */
    public record ZoneMetrics(int demandCount, int supplyCount) {}

    private final ConcurrentHashMap<Long, AtomicInteger> demandCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> supplyCounters = new ConcurrentHashMap<>();

    /**
     * Records a ride request (demand event) in the given zone.
     *
     * @param h3CellId H3 cell ID at resolution 7 identifying the pickup zone
     */
    public void recordDemand(long h3CellId) {
        demandCounters.computeIfAbsent(h3CellId, k -> new AtomicInteger(0))
                      .incrementAndGet();
        log.debug("Demand recorded for zone {}", h3CellId);
    }

    /**
     * Sets (or replaces) the available driver count for a zone.
     * Called by the fleet-tracker when driver availability changes.
     *
     * @param h3CellId    H3 cell ID at resolution 7
     * @param driverCount number of available drivers currently in the zone (must be &ge; 0)
     */
    public void recordSupply(long h3CellId, int driverCount) {
        if (driverCount < 0) throw new IllegalArgumentException("Driver count must be >= 0");
        supplyCounters.put(h3CellId, driverCount);
        log.debug("Supply updated for zone {}: {} drivers", h3CellId, driverCount);
    }

    /**
     * Calculates the surge multiplier for the given zone.
     *
     * <p>If no demand has been recorded, returns 1.0. Supply defaults to 1 when
     * no supply data is available (conservative: treats missing data as minimal supply).</p>
     *
     * @param h3CellId H3 cell ID at resolution 7
     * @return surge multiplier in the range [1.0, 3.0]
     */
    public double getSurgeMultiplier(long h3CellId) {
        AtomicInteger demandCounter = demandCounters.get(h3CellId);
        int demand = (demandCounter == null) ? 0 : demandCounter.get();
        if (demand == 0) return 1.0;

        int supply = supplyCounters.getOrDefault(h3CellId, 1);
        // Avoid division by zero: treat zero supply as 1 (scarcest case)
        int effectiveSupply = Math.max(supply, 1);

        double ratio = (double) demand / effectiveSupply;
        double multiplier = computeMultiplier(ratio);
        log.debug("Zone {} surge: demand={}, supply={}, ratio={}, multiplier={}",
                h3CellId, demand, supply, ratio, multiplier);
        return multiplier;
    }

    /**
     * Clears all zone metrics. Call at the start of each time-window rotation
     * so that stale demand counts do not carry over into the next window.
     */
    public void reset() {
        demandCounters.clear();
        supplyCounters.clear();
        log.info("SurgeCalculator reset: all zone metrics cleared");
    }

    /**
     * Returns a snapshot of all zones currently experiencing surge (multiplier &gt; 1.0).
     *
     * @return unmodifiable map from H3 cell ID to surge multiplier
     */
    public Map<Long, Double> getAllSurgeZones() {
        Map<Long, Double> result = new HashMap<>();
        for (Long cellId : demandCounters.keySet()) {
            double multiplier = getSurgeMultiplier(cellId);
            if (multiplier > 1.0) {
                result.put(cellId, multiplier);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns the current metrics snapshot for a zone, or empty metrics if none recorded.
     *
     * @param h3CellId H3 cell ID
     * @return zone metrics (demand and supply counts)
     */
    public ZoneMetrics getZoneMetrics(long h3CellId) {
        AtomicInteger demandCounter = demandCounters.get(h3CellId);
        int demand = (demandCounter == null) ? 0 : demandCounter.get();
        int supply = supplyCounters.getOrDefault(h3CellId, 0);
        return new ZoneMetrics(demand, supply);
    }

    // ── Surge Formula ──

    /**
     * Applies the piecewise-linear surge formula to a demand/supply ratio.
     *
     * @param ratio demand / supply
     * @return surge multiplier in [1.0, MAX_MULTIPLIER]
     */
    static double computeMultiplier(double ratio) {
        if (ratio <= 1.0) {
            return 1.0;
        } else if (ratio <= 2.0) {
            // Linear ramp from 1.0x to 1.5x
            return 1.0 + (ratio - 1.0) * 0.5;
        } else if (ratio <= 4.0) {
            // Slower ramp from 1.5x to 2.0x
            return 1.5 + (ratio - 2.0) * 0.25;
        } else {
            // Steep but capped at MAX_MULTIPLIER
            return Math.min(MAX_MULTIPLIER, 1.5 + ratio * 0.2);
        }
    }
}
