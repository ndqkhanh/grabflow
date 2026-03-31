package grabflow.location.geo;

import grabflow.common.DriverLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cell-based spatial index for fast nearest-driver queries.
 *
 * <h3>CS Fundamental: Spatial Hashing</h3>
 * <p>Instead of a tree-based index (R-tree, k-d tree) which requires O(log n)
 * lookups and complex rebalancing, this index uses H3 hexagonal cells as hash
 * buckets. Each driver is placed in exactly one cell based on their GPS coordinates.
 * A nearest-driver query expands outward in concentric hex rings (k-ring) until
 * enough candidates are found, then ranks by Haversine distance.</p>
 *
 * <h3>Why Hexagons?</h3>
 * <ul>
 *   <li>Uniform adjacency: every cell has exactly 6 neighbors (squares have 4 edge + 4 corner)</li>
 *   <li>Equidistant centers: all neighbor centers are the same distance from the cell center</li>
 *   <li>Minimal edge effects: hexagons approximate circles better than squares</li>
 * </ul>
 *
 * <h3>Query Algorithm</h3>
 * <pre>
 *   1. Compute query point's H3 cell at resolution 9
 *   2. Determine k-ring radius: k = ceil(radiusMeters / cellEdgeLength)
 *   3. Fetch all drivers from cells in the k-ring
 *   4. Compute Haversine distance to each driver
 *   5. Filter by radius, sort by distance, take maxResults
 * </pre>
 */
public class SpatialIndex {

    private static final Logger log = LoggerFactory.getLogger(SpatialIndex.class);

    /** Default H3 resolution for street-level matching (~174m edge) */
    private static final int RESOLUTION = 9;

    /** Cell ID -> list of drivers in that cell */
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<DriverLocation>> cells =
            new ConcurrentHashMap<>();

    /** Driver ID -> current cell ID (for efficient cell migration on move) */
    private final ConcurrentHashMap<String, Long> driverCells = new ConcurrentHashMap<>();

    /**
     * Inserts or updates a driver's position in the index.
     *
     * <p>If the driver moved to a different H3 cell, they are removed from the old
     * cell and added to the new one. If they stayed in the same cell, the location
     * record is updated in place.</p>
     *
     * @param location driver's current position (must have h3CellId pre-computed)
     */
    public void update(DriverLocation location) {
        long newCellId = location.h3CellId();
        Long oldCellId = driverCells.put(location.driverId(), newCellId);

        if (oldCellId != null && oldCellId != newCellId) {
            // Driver moved cells -- remove from old
            removeFromCell(oldCellId, location.driverId());
        }

        if (oldCellId != null && oldCellId == newCellId) {
            // Same cell -- update in place
            var cellDrivers = cells.get(newCellId);
            if (cellDrivers != null) {
                cellDrivers.removeIf(d -> d.driverId().equals(location.driverId()));
            }
        }

        // Add to new cell
        cells.computeIfAbsent(newCellId, k -> new CopyOnWriteArrayList<>())
                .add(location);
    }

    /**
     * Removes a driver from the index entirely (e.g., when they go offline).
     */
    public void remove(String driverId) {
        Long cellId = driverCells.remove(driverId);
        if (cellId != null) {
            removeFromCell(cellId, driverId);
        }
    }

    /**
     * Finds the nearest drivers to a query point within a given radius.
     *
     * @param lat          query latitude
     * @param lng          query longitude
     * @param radiusMeters search radius
     * @param maxResults   maximum drivers to return
     * @return drivers sorted by distance (closest first)
     */
    public List<DriverLocation> findNearest(double lat, double lng,
                                            double radiusMeters, int maxResults) {
        long queryCellId = H3Index.latLngToCell(lat, lng, RESOLUTION);
        double edgeLength = H3Index.cellEdgeLengthMeters(RESOLUTION);

        // Determine k-ring radius to cover the search area
        int k = Math.max(1, (int) Math.ceil(radiusMeters / edgeLength));

        // Collect candidate drivers from all cells in the k-ring
        long[] ringCells = H3Index.kRing(queryCellId, k);
        List<DriverWithDistance> candidates = new ArrayList<>();

        for (long cellId : ringCells) {
            var cellDrivers = cells.get(cellId);
            if (cellDrivers == null) continue;

            for (DriverLocation driver : cellDrivers) {
                double distance = GeoUtils.haversineMeters(lat, lng, driver.lat(), driver.lng());
                if (distance <= radiusMeters) {
                    candidates.add(new DriverWithDistance(driver, distance));
                }
            }
        }

        // Sort by distance and take top N
        candidates.sort(Comparator.comparingDouble(DriverWithDistance::distance));
        return candidates.stream()
                .limit(maxResults)
                .map(DriverWithDistance::driver)
                .toList();
    }

    /**
     * Returns the total number of indexed drivers.
     */
    public int driverCount() {
        return driverCells.size();
    }

    /**
     * Returns the number of occupied cells.
     */
    public int cellCount() {
        return cells.size();
    }

    private void removeFromCell(long cellId, String driverId) {
        var cellDrivers = cells.get(cellId);
        if (cellDrivers != null) {
            cellDrivers.removeIf(d -> d.driverId().equals(driverId));
            if (cellDrivers.isEmpty()) {
                cells.remove(cellId);
            }
        }
    }

    private record DriverWithDistance(DriverLocation driver, double distance) {}
}
