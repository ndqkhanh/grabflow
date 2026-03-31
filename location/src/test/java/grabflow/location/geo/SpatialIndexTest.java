package grabflow.location.geo;

import grabflow.common.DriverLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class SpatialIndexTest {

    private SpatialIndex index;

    // Ho Chi Minh City area coordinates
    static final double HCMC_LAT = 10.7769;
    static final double HCMC_LNG = 106.7009;

    @BeforeEach
    void setup() {
        index = new SpatialIndex();
    }

    @Test
    void findNearestReturnsClosestFirst() {
        // Three drivers at increasing distances from query point
        addDriver("near", HCMC_LAT + 0.001, HCMC_LNG);         // ~111m away
        addDriver("medium", HCMC_LAT + 0.005, HCMC_LNG);       // ~555m away
        addDriver("far", HCMC_LAT + 0.01, HCMC_LNG);           // ~1.1km away

        List<DriverLocation> result = index.findNearest(HCMC_LAT, HCMC_LNG, 2000, 10);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).driverId()).isEqualTo("near");
        assertThat(result.get(1).driverId()).isEqualTo("medium");
        assertThat(result.get(2).driverId()).isEqualTo("far");
    }

    @Test
    void radiusFilterExcludesDistantDrivers() {
        addDriver("close", HCMC_LAT + 0.001, HCMC_LNG);        // ~111m
        addDriver("far", HCMC_LAT + 0.05, HCMC_LNG);           // ~5.5km

        // Search within 500m -- should only find "close"
        List<DriverLocation> result = index.findNearest(HCMC_LAT, HCMC_LNG, 500, 10);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().driverId()).isEqualTo("close");
    }

    @Test
    void maxResultsLimitsOutput() {
        // Add 10 nearby drivers
        for (int i = 0; i < 10; i++) {
            addDriver("driver-" + i, HCMC_LAT + 0.001 * (i + 1), HCMC_LNG);
        }

        List<DriverLocation> result = index.findNearest(HCMC_LAT, HCMC_LNG, 5000, 3);
        assertThat(result).hasSize(3);
    }

    @Test
    void updateMovesDriverBetweenCells() {
        // Place driver in initial position
        addDriver("mover", HCMC_LAT, HCMC_LNG);
        assertThat(index.driverCount()).isEqualTo(1);

        // Move driver far away (different H3 cell)
        double newLat = HCMC_LAT + 0.1; // ~11km north
        long newCell = H3Index.latLngToCell(newLat, HCMC_LNG, 9);
        index.update(new DriverLocation("mover", newLat, HCMC_LNG, 0, 30, System.currentTimeMillis(), newCell));

        // Should NOT be found near original position
        List<DriverLocation> nearOriginal = index.findNearest(HCMC_LAT, HCMC_LNG, 500, 10);
        assertThat(nearOriginal).isEmpty();

        // Should be found near new position
        List<DriverLocation> nearNew = index.findNearest(newLat, HCMC_LNG, 500, 10);
        assertThat(nearNew).hasSize(1);
        assertThat(nearNew.getFirst().driverId()).isEqualTo("mover");
    }

    @Test
    void removeDriverExcludesFromResults() {
        addDriver("toRemove", HCMC_LAT, HCMC_LNG);
        addDriver("stays", HCMC_LAT + 0.001, HCMC_LNG);

        index.remove("toRemove");

        List<DriverLocation> result = index.findNearest(HCMC_LAT, HCMC_LNG, 2000, 10);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().driverId()).isEqualTo("stays");
    }

    @Test
    void emptyIndexReturnsEmptyList() {
        List<DriverLocation> result = index.findNearest(HCMC_LAT, HCMC_LNG, 5000, 10);
        assertThat(result).isEmpty();
    }

    @Test
    void driverCountTracksCorrectly() {
        assertThat(index.driverCount()).isZero();

        addDriver("a", HCMC_LAT, HCMC_LNG);
        addDriver("b", HCMC_LAT + 0.01, HCMC_LNG);
        assertThat(index.driverCount()).isEqualTo(2);

        index.remove("a");
        assertThat(index.driverCount()).isEqualTo(1);
    }

    @Test
    void cellCountReflectsOccupiedCells() {
        assertThat(index.cellCount()).isZero();

        // Two drivers in same cell
        addDriver("a", HCMC_LAT, HCMC_LNG);
        addDriver("b", HCMC_LAT + 0.0001, HCMC_LNG); // very close, likely same cell
        // One driver in a different cell
        addDriver("c", HCMC_LAT + 0.1, HCMC_LNG); // ~11km away, different cell

        assertThat(index.cellCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void removeNonexistentDriverIsNoOp() {
        addDriver("exists", HCMC_LAT, HCMC_LNG);
        index.remove("nonexistent"); // should not throw
        assertThat(index.driverCount()).isEqualTo(1);
    }

    @Test
    void updateSameCellReplacesLocation() {
        addDriver("driver", HCMC_LAT, HCMC_LNG);

        // Update with slightly different position but same cell
        double newLat = HCMC_LAT + 0.00001; // ~1m, same cell
        long cell = H3Index.latLngToCell(newLat, HCMC_LNG, 9);
        index.update(new DriverLocation("driver", newLat, HCMC_LNG, 90, 40, System.currentTimeMillis(), cell));

        // Should still be exactly one driver
        assertThat(index.driverCount()).isEqualTo(1);

        List<DriverLocation> result = index.findNearest(HCMC_LAT, HCMC_LNG, 500, 10);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().heading()).isEqualTo(90.0);
    }

    @Test
    void concurrentUpdatesAndQueries() throws Exception {
        int writers = 8;
        int readers = 4;
        int opsPerThread = 100;
        var errors = new AtomicInteger();
        var latch = new CountDownLatch(writers + readers);

        // Writer threads
        for (int w = 0; w < writers; w++) {
            int writerId = w;
            Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        String id = "w" + writerId + "-d" + i;
                        double lat = HCMC_LAT + (writerId * 0.01) + (i * 0.0001);
                        addDriver(id, lat, HCMC_LNG);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Reader threads
        for (int r = 0; r < readers; r++) {
            Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        index.findNearest(HCMC_LAT, HCMC_LNG, 50_000, 10);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        assertThat(errors.get()).isZero();
        assertThat(index.driverCount()).isEqualTo(writers * opsPerThread);
    }

    // ── Helper ──

    private void addDriver(String id, double lat, double lng) {
        long cell = H3Index.latLngToCell(lat, lng, 9);
        index.update(new DriverLocation(id, lat, lng, 0, 30, System.currentTimeMillis(), cell));
    }
}
