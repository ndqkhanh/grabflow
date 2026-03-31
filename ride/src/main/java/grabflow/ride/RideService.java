package grabflow.ride;

import grabflow.common.DriverLocation;
import grabflow.ride.dsl.MatchingEngine;
import grabflow.ride.state.RideStateMachine;
import grabflow.ride.state.RideStateMachine.Ride;
import grabflow.ride.state.RideStateMachine.RideStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Ride Service — orchestrates ride lifecycle from request to completion.
 *
 * <h3>CS Fundamentals Demonstrated</h3>
 * <ul>
 *   <li><b>Compiler/DSL</b>: Matching DSL with lexer, parser, AST, interpreter</li>
 *   <li><b>State Machine</b>: Validated ride status transitions</li>
 *   <li><b>Normalization</b>: 3NF data model (Ride, Driver, Rider as separate entities)</li>
 * </ul>
 */
public class RideService {

    private static final Logger log = LoggerFactory.getLogger(RideService.class);

    private final RideStateMachine stateMachine;
    private final MatchingEngine matchingEngine;
    private final MatchingEngine.DistanceCalculator distanceCalculator;

    public RideService(MatchingEngine.DistanceCalculator distanceCalculator) {
        this.stateMachine = new RideStateMachine();
        this.matchingEngine = new MatchingEngine();
        this.distanceCalculator = distanceCalculator;
    }

    /**
     * Requests a new ride and attempts to find matching drivers.
     */
    public Ride requestRide(String rideId, String riderId,
                            double pickupLat, double pickupLng,
                            double dropoffLat, double dropoffLng,
                            List<DriverLocation> availableDrivers,
                            String matchQuery) {
        // Create ride in REQUESTED state
        Ride ride = stateMachine.createRide(rideId, riderId,
                pickupLat, pickupLng, dropoffLat, dropoffLng);

        // Run matching DSL
        List<DriverLocation> matched = matchingEngine.match(
                matchQuery, availableDrivers,
                pickupLat, pickupLng, distanceCalculator);

        if (!matched.isEmpty()) {
            // Match with the best driver
            DriverLocation bestDriver = matched.getFirst();
            ride = stateMachine.matchDriver(rideId, bestDriver.driverId());
            log.info("Ride {} matched with driver {}", rideId, bestDriver.driverId());
        }

        return ride;
    }

    /**
     * Advances ride to DRIVER_ARRIVING.
     */
    public Ride driverArriving(String rideId) {
        return stateMachine.transition(rideId, RideStatus.DRIVER_ARRIVING);
    }

    /**
     * Starts the ride (driver picked up rider).
     */
    public Ride startRide(String rideId) {
        return stateMachine.transition(rideId, RideStatus.IN_PROGRESS);
    }

    /**
     * Completes the ride.
     */
    public Ride completeRide(String rideId) {
        return stateMachine.transition(rideId, RideStatus.COMPLETED);
    }

    /**
     * Cancels the ride.
     */
    public Ride cancelRide(String rideId) {
        return stateMachine.transition(rideId, RideStatus.CANCELLED);
    }

    public Ride getRide(String rideId) {
        return stateMachine.getRide(rideId);
    }

    public int activeRideCount() {
        return stateMachine.activeRideCount();
    }
}
