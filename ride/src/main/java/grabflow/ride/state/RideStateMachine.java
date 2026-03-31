package grabflow.ride.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ride lifecycle state machine with validated transitions.
 *
 * <h3>State Diagram</h3>
 * <pre>
 *   REQUESTED ──▶ MATCHED ──▶ DRIVER_ARRIVING ──▶ IN_PROGRESS ──▶ COMPLETED
 *       │            │              │                   │
 *       ▼            ▼              ▼                   ▼
 *   CANCELLED    CANCELLED      CANCELLED           CANCELLED
 * </pre>
 *
 * <p>Each transition is validated: only legal state transitions are allowed.
 * Concurrent transitions on the same ride are serialized via per-ride locks.</p>
 */
public class RideStateMachine {

    private static final Logger log = LoggerFactory.getLogger(RideStateMachine.class);

    /** Legal transitions: from-state -> set of allowed to-states */
    private static final Map<RideStatus, Set<RideStatus>> TRANSITIONS = Map.of(
            RideStatus.REQUESTED, EnumSet.of(RideStatus.MATCHED, RideStatus.CANCELLED),
            RideStatus.MATCHED, EnumSet.of(RideStatus.DRIVER_ARRIVING, RideStatus.CANCELLED),
            RideStatus.DRIVER_ARRIVING, EnumSet.of(RideStatus.IN_PROGRESS, RideStatus.CANCELLED),
            RideStatus.IN_PROGRESS, EnumSet.of(RideStatus.COMPLETED, RideStatus.CANCELLED),
            RideStatus.COMPLETED, EnumSet.noneOf(RideStatus.class),
            RideStatus.CANCELLED, EnumSet.noneOf(RideStatus.class)
    );

    private final ConcurrentHashMap<String, Ride> rides = new ConcurrentHashMap<>();

    /**
     * Creates a new ride in REQUESTED state.
     */
    public Ride createRide(String rideId, String riderId, double pickupLat, double pickupLng,
                           double dropoffLat, double dropoffLng) {
        var ride = new Ride(rideId, riderId, null, RideStatus.REQUESTED,
                pickupLat, pickupLng, dropoffLat, dropoffLng, Instant.now(), null);
        if (rides.putIfAbsent(rideId, ride) != null) {
            throw new IllegalStateException("Ride already exists: " + rideId);
        }
        log.info("Ride created: {} (rider={})", rideId, riderId);
        return ride;
    }

    /**
     * Transitions a ride to a new state, validating the transition is legal.
     *
     * @throws IllegalStateException if the transition is not allowed
     */
    public Ride transition(String rideId, RideStatus newStatus) {
        return transition(rideId, newStatus, null);
    }

    /**
     * Transitions a ride to MATCHED state, assigning a driver.
     */
    public Ride matchDriver(String rideId, String driverId) {
        return transition(rideId, RideStatus.MATCHED, driverId);
    }

    private synchronized Ride transition(String rideId, RideStatus newStatus, String driverId) {
        Ride current = rides.get(rideId);
        if (current == null) {
            throw new IllegalArgumentException("Ride not found: " + rideId);
        }

        Set<RideStatus> allowed = TRANSITIONS.get(current.status());
        if (!allowed.contains(newStatus)) {
            throw new IllegalStateException(String.format(
                    "Cannot transition ride %s from %s to %s (allowed: %s)",
                    rideId, current.status(), newStatus, allowed));
        }

        Ride updated = new Ride(
                current.rideId(),
                current.riderId(),
                driverId != null ? driverId : current.driverId(),
                newStatus,
                current.pickupLat(), current.pickupLng(),
                current.dropoffLat(), current.dropoffLng(),
                current.createdAt(),
                newStatus == RideStatus.COMPLETED || newStatus == RideStatus.CANCELLED
                        ? Instant.now() : null
        );
        rides.put(rideId, updated);
        log.info("Ride {} transitioned: {} -> {}", rideId, current.status(), newStatus);
        return updated;
    }

    public Ride getRide(String rideId) {
        return rides.get(rideId);
    }

    public int activeRideCount() {
        return (int) rides.values().stream()
                .filter(r -> r.status() != RideStatus.COMPLETED && r.status() != RideStatus.CANCELLED)
                .count();
    }

    public enum RideStatus {
        REQUESTED, MATCHED, DRIVER_ARRIVING, IN_PROGRESS, COMPLETED, CANCELLED
    }

    public record Ride(
            String rideId,
            String riderId,
            String driverId,
            RideStatus status,
            double pickupLat, double pickupLng,
            double dropoffLat, double dropoffLng,
            Instant createdAt,
            Instant completedAt
    ) {}
}
