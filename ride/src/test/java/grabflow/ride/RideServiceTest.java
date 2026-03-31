package grabflow.ride;

import grabflow.common.DriverLocation;
import grabflow.ride.dsl.MatchingEngine;
import grabflow.ride.state.RideStateMachine.Ride;
import grabflow.ride.state.RideStateMachine.RideStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RideServiceTest {

    // Simple Euclidean-ish distance: ~111 km per degree
    private static final MatchingEngine.DistanceCalculator DISTANCE =
            (lat1, lng1, lat2, lng2) -> {
                double dLat = (lat2 - lat1) * 111_000;
                double dLng = (lng2 - lng1) * 111_000;
                return Math.sqrt(dLat * dLat + dLng * dLng);
            };

    private RideService service;

    @BeforeEach
    void setup() {
        service = new RideService(DISTANCE);
    }

    // -----------------------------------------------------------------------
    // Helper factories
    // -----------------------------------------------------------------------

    private static DriverLocation driver(String id, double lat, double lng, double speed) {
        return new DriverLocation(id, lat, lng, 0, speed, System.currentTimeMillis(), 0);
    }

    private static DriverLocation driver(String id, double lat, double lng) {
        return driver(id, lat, lng, 40.0);
    }

    // -----------------------------------------------------------------------
    // 1. fullRideLifecycleWithMatching
    // -----------------------------------------------------------------------

    @Test
    void fullRideLifecycleWithMatching() {
        List<DriverLocation> drivers = List.of(
                driver("d1", 10.001, 106.0),   // ~111 m away
                driver("d2", 10.1,   106.0)    // ~11 km away
        );

        // Request: only drivers within 5 km qualify; d1 wins
        Ride requested = service.requestRide(
                "ride-1", "rider-1",
                10.0, 106.0, 10.5, 106.5,
                drivers,
                "MATCH driver WHERE distance < 5000 ORDER BY distance ASC LIMIT 1");

        assertThat(requested.status()).isEqualTo(RideStatus.MATCHED);
        assertThat(requested.driverId()).isEqualTo("d1");

        // Driver is on the way
        Ride arriving = service.driverArriving("ride-1");
        assertThat(arriving.status()).isEqualTo(RideStatus.DRIVER_ARRIVING);

        // Driver picked up rider
        Ride inProgress = service.startRide("ride-1");
        assertThat(inProgress.status()).isEqualTo(RideStatus.IN_PROGRESS);

        // Ride ends
        Ride completed = service.completeRide("ride-1");
        assertThat(completed.status()).isEqualTo(RideStatus.COMPLETED);
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.rideId()).isEqualTo("ride-1");
        assertThat(completed.riderId()).isEqualTo("rider-1");
        assertThat(completed.driverId()).isEqualTo("d1");
    }

    // -----------------------------------------------------------------------
    // 2. rideWithNoMatchingDrivers
    // -----------------------------------------------------------------------

    @Test
    void rideWithNoMatchingDrivers() {
        // All drivers are > 20 km away; strict distance < 1 km will match nobody
        List<DriverLocation> drivers = List.of(
                driver("d-far-1", 10.5, 106.0),
                driver("d-far-2", 11.0, 106.0)
        );

        Ride ride = service.requestRide(
                "ride-2", "rider-2",
                10.0, 106.0, 10.1, 106.1,
                drivers,
                "MATCH driver WHERE distance < 1000 ORDER BY distance ASC LIMIT 1");

        // No driver matched, ride stays in REQUESTED
        assertThat(ride.status()).isEqualTo(RideStatus.REQUESTED);
        assertThat(ride.driverId()).isNull();
    }

    // -----------------------------------------------------------------------
    // 3. cancelRideAtVariousStages
    // -----------------------------------------------------------------------

    @Test
    void cancelRideAtVariousStages() {
        List<DriverLocation> nearDriver = List.of(driver("d-near", 10.001, 106.0));
        String matchQuery = "MATCH driver WHERE distance < 5000 ORDER BY distance ASC LIMIT 1";

        // --- cancel from REQUESTED (no drivers available) ---
        service.requestRide("ride-cancel-req", "rider-a",
                10.0, 106.0, 10.1, 106.1,
                List.of(), matchQuery);
        Ride cancelledReq = service.cancelRide("ride-cancel-req");
        assertThat(cancelledReq.status()).isEqualTo(RideStatus.CANCELLED);

        // --- cancel from MATCHED ---
        service.requestRide("ride-cancel-matched", "rider-b",
                10.0, 106.0, 10.1, 106.1,
                nearDriver, matchQuery);
        assertThat(service.getRide("ride-cancel-matched").status()).isEqualTo(RideStatus.MATCHED);
        Ride cancelledMatched = service.cancelRide("ride-cancel-matched");
        assertThat(cancelledMatched.status()).isEqualTo(RideStatus.CANCELLED);

        // --- cancel from IN_PROGRESS ---
        service.requestRide("ride-cancel-progress", "rider-c",
                10.0, 106.0, 10.1, 106.1,
                nearDriver, matchQuery);
        service.driverArriving("ride-cancel-progress");
        service.startRide("ride-cancel-progress");
        assertThat(service.getRide("ride-cancel-progress").status()).isEqualTo(RideStatus.IN_PROGRESS);
        Ride cancelledInProgress = service.cancelRide("ride-cancel-progress");
        assertThat(cancelledInProgress.status()).isEqualTo(RideStatus.CANCELLED);
    }

    // -----------------------------------------------------------------------
    // 4. multipleActiveRides
    // -----------------------------------------------------------------------

    @Test
    void multipleActiveRides() {
        String q = "MATCH driver WHERE distance < 5000 ORDER BY distance ASC LIMIT 1";
        List<DriverLocation> drivers = List.of(driver("dx", 10.001, 106.0));

        service.requestRide("r-a", "u1", 10.0, 106.0, 10.1, 106.1, drivers, q);
        service.requestRide("r-b", "u2", 10.0, 106.0, 10.1, 106.1, drivers, q);
        service.requestRide("r-c", "u3", 10.0, 106.0, 10.1, 106.1, drivers, q);

        assertThat(service.activeRideCount()).isEqualTo(3);

        // Complete one ride
        service.driverArriving("r-a");
        service.startRide("r-a");
        service.completeRide("r-a");
        assertThat(service.activeRideCount()).isEqualTo(2);

        // Cancel another
        service.cancelRide("r-b");
        assertThat(service.activeRideCount()).isEqualTo(1);

        // r-c is still active
        assertThat(service.getRide("r-c").status()).isEqualTo(RideStatus.MATCHED);
    }

    // -----------------------------------------------------------------------
    // 5. matchingDslFiltersCorrectly
    // -----------------------------------------------------------------------

    @Test
    void matchingDslFiltersCorrectly() {
        // Pickup at (10.0, 106.0)
        // d-close: ~111 m, speed 50 — satisfies distance < 2km AND speed > 20
        // d-slow:  ~333 m, speed 10 — distance OK but speed too low
        // d-far:   ~5.5 km          — distance > 2km
        List<DriverLocation> drivers = List.of(
                driver("d-far",   10.05, 106.0, 60.0),
                driver("d-slow",  10.003, 106.0, 10.0),
                driver("d-close", 10.001, 106.0, 50.0)
        );

        Ride ride = service.requestRide(
                "ride-dsl", "rider-dsl",
                10.0, 106.0, 10.5, 106.5,
                drivers,
                "MATCH driver WHERE distance < 2km AND speed > 20 ORDER BY distance ASC LIMIT 1");

        // d-close is the only driver satisfying both predicates and is closest
        assertThat(ride.status()).isEqualTo(RideStatus.MATCHED);
        assertThat(ride.driverId()).isEqualTo("d-close");
    }
}
