package grabflow.ride.dsl;

import grabflow.common.DriverLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MatchingEngineTest {

    private final MatchingEngine engine = new MatchingEngine();

    // Simple Euclidean-ish distance for testing (not real Haversine, but predictable)
    private final MatchingEngine.DistanceCalculator testDistance = (lat1, lng1, lat2, lng2) -> {
        double dLat = (lat2 - lat1) * 111_000; // ~111km per degree
        double dLng = (lng2 - lng1) * 111_000;
        return Math.sqrt(dLat * dLat + dLng * dLng);
    };

    @Test
    void matchesDriversWithinDistance() {
        List<DriverLocation> drivers = List.of(
                makeDriver("near", 10.001, 106.0),   // ~111m away
                makeDriver("far", 10.1, 106.0)        // ~11.1km away
        );

        List<DriverLocation> result = engine.match(
                "MATCH driver WHERE distance < 5000",
                drivers, 10.0, 106.0, testDistance);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().driverId()).isEqualTo("near");
    }

    @Test
    void ordersResultsByDistance() {
        List<DriverLocation> drivers = List.of(
                makeDriver("far", 10.05, 106.0),
                makeDriver("near", 10.001, 106.0),
                makeDriver("mid", 10.01, 106.0)
        );

        List<DriverLocation> result = engine.match(
                "MATCH driver WHERE distance < 50000 ORDER BY distance ASC",
                drivers, 10.0, 106.0, testDistance);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).driverId()).isEqualTo("near");
        assertThat(result.get(1).driverId()).isEqualTo("mid");
        assertThat(result.get(2).driverId()).isEqualTo("far");
    }

    @Test
    void limitsResults() {
        List<DriverLocation> drivers = List.of(
                makeDriver("a", 10.001, 106.0),
                makeDriver("b", 10.002, 106.0),
                makeDriver("c", 10.003, 106.0)
        );

        List<DriverLocation> result = engine.match(
                "MATCH driver WHERE distance < 50000 ORDER BY distance ASC LIMIT 2",
                drivers, 10.0, 106.0, testDistance);

        assertThat(result).hasSize(2);
    }

    @Test
    void andConditionFilters() {
        List<DriverLocation> drivers = List.of(
                makeDriver("slow-near", 10.001, 106.0, 20),  // speed 20
                makeDriver("fast-near", 10.002, 106.0, 60)   // speed 60
        );

        List<DriverLocation> result = engine.match(
                "MATCH driver WHERE distance < 5000 AND speed > 30",
                drivers, 10.0, 106.0, testDistance);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().driverId()).isEqualTo("fast-near");
    }

    @Test
    void noWhereClauseReturnsAll() {
        List<DriverLocation> drivers = List.of(
                makeDriver("a", 10.0, 106.0),
                makeDriver("b", 20.0, 106.0)
        );

        List<DriverLocation> result = engine.match(
                "MATCH driver",
                drivers, 10.0, 106.0, testDistance);

        assertThat(result).hasSize(2);
    }

    @Test
    void emptyDriverListReturnsEmpty() {
        List<DriverLocation> result = engine.match(
                "MATCH driver WHERE distance < 5000",
                List.of(), 10.0, 106.0, testDistance);

        assertThat(result).isEmpty();
    }

    @Test
    void distanceUnitKmConvertsToMeters() {
        List<DriverLocation> drivers = List.of(
                makeDriver("within5km", 10.04, 106.0), // ~4.4km
                makeDriver("beyond5km", 10.06, 106.0)  // ~6.7km
        );

        List<DriverLocation> result = engine.match(
                "MATCH driver WHERE distance < 5km ORDER BY distance ASC",
                drivers, 10.0, 106.0, testDistance);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().driverId()).isEqualTo("within5km");
    }

    @Test
    void orderByDescending() {
        List<DriverLocation> drivers = List.of(
                makeDriver("near", 10.001, 106.0),
                makeDriver("far", 10.01, 106.0)
        );

        List<DriverLocation> result = engine.match(
                "MATCH driver WHERE distance < 50000 ORDER BY distance DESC",
                drivers, 10.0, 106.0, testDistance);

        assertThat(result.get(0).driverId()).isEqualTo("far");
        assertThat(result.get(1).driverId()).isEqualTo("near");
    }

    private DriverLocation makeDriver(String id, double lat, double lng) {
        return new DriverLocation(id, lat, lng, 0, 30, System.currentTimeMillis(), 0);
    }

    private DriverLocation makeDriver(String id, double lat, double lng, double speed) {
        return new DriverLocation(id, lat, lng, 0, speed, System.currentTimeMillis(), 0);
    }
}
