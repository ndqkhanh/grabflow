package grabflow.ride.state;

import grabflow.ride.state.RideStateMachine.RideStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RideStateMachineTest {

    private RideStateMachine sm;

    @BeforeEach
    void setup() {
        sm = new RideStateMachine();
    }

    @Test
    void createRideStartsInRequested() {
        var ride = sm.createRide("r1", "rider1", 10.0, 106.0, 10.1, 106.1);
        assertThat(ride.status()).isEqualTo(RideStatus.REQUESTED);
        assertThat(ride.riderId()).isEqualTo("rider1");
        assertThat(ride.driverId()).isNull();
    }

    @Test
    void happyPathLifecycle() {
        sm.createRide("r1", "rider1", 10.0, 106.0, 10.1, 106.1);

        var matched = sm.matchDriver("r1", "driver1");
        assertThat(matched.status()).isEqualTo(RideStatus.MATCHED);
        assertThat(matched.driverId()).isEqualTo("driver1");

        var arriving = sm.transition("r1", RideStatus.DRIVER_ARRIVING);
        assertThat(arriving.status()).isEqualTo(RideStatus.DRIVER_ARRIVING);

        var inProgress = sm.transition("r1", RideStatus.IN_PROGRESS);
        assertThat(inProgress.status()).isEqualTo(RideStatus.IN_PROGRESS);

        var completed = sm.transition("r1", RideStatus.COMPLETED);
        assertThat(completed.status()).isEqualTo(RideStatus.COMPLETED);
        assertThat(completed.completedAt()).isNotNull();
    }

    @Test
    void cancelFromRequested() {
        sm.createRide("r1", "rider1", 10.0, 106.0, 10.1, 106.1);
        var cancelled = sm.transition("r1", RideStatus.CANCELLED);
        assertThat(cancelled.status()).isEqualTo(RideStatus.CANCELLED);
    }

    @Test
    void cancelFromMatched() {
        sm.createRide("r1", "rider1", 10.0, 106.0, 10.1, 106.1);
        sm.matchDriver("r1", "driver1");
        var cancelled = sm.transition("r1", RideStatus.CANCELLED);
        assertThat(cancelled.status()).isEqualTo(RideStatus.CANCELLED);
    }

    @Test
    void cancelFromInProgress() {
        sm.createRide("r1", "rider1", 10.0, 106.0, 10.1, 106.1);
        sm.matchDriver("r1", "driver1");
        sm.transition("r1", RideStatus.DRIVER_ARRIVING);
        sm.transition("r1", RideStatus.IN_PROGRESS);
        var cancelled = sm.transition("r1", RideStatus.CANCELLED);
        assertThat(cancelled.status()).isEqualTo(RideStatus.CANCELLED);
    }

    @Test
    void invalidTransitionThrows() {
        sm.createRide("r1", "rider1", 10.0, 106.0, 10.1, 106.1);
        // Cannot go directly from REQUESTED to IN_PROGRESS
        assertThatThrownBy(() -> sm.transition("r1", RideStatus.IN_PROGRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot transition");
    }

    @Test
    void completedRideCannotTransition() {
        sm.createRide("r1", "rider1", 10.0, 106.0, 10.1, 106.1);
        sm.matchDriver("r1", "driver1");
        sm.transition("r1", RideStatus.DRIVER_ARRIVING);
        sm.transition("r1", RideStatus.IN_PROGRESS);
        sm.transition("r1", RideStatus.COMPLETED);

        assertThatThrownBy(() -> sm.transition("r1", RideStatus.CANCELLED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelledRideCannotTransition() {
        sm.createRide("r1", "rider1", 10.0, 106.0, 10.1, 106.1);
        sm.transition("r1", RideStatus.CANCELLED);

        assertThatThrownBy(() -> sm.transition("r1", RideStatus.MATCHED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateRideIdThrows() {
        sm.createRide("r1", "rider1", 10.0, 106.0, 10.1, 106.1);
        assertThatThrownBy(() -> sm.createRide("r1", "rider2", 10.0, 106.0, 10.1, 106.1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonexistentRideThrows() {
        assertThatThrownBy(() -> sm.transition("nope", RideStatus.MATCHED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activeRideCountExcludesTerminal() {
        sm.createRide("r1", "rider1", 10.0, 106.0, 10.1, 106.1);
        sm.createRide("r2", "rider2", 10.0, 106.0, 10.1, 106.1);
        sm.createRide("r3", "rider3", 10.0, 106.0, 10.1, 106.1);

        assertThat(sm.activeRideCount()).isEqualTo(3);

        sm.transition("r1", RideStatus.CANCELLED);
        assertThat(sm.activeRideCount()).isEqualTo(2);

        sm.matchDriver("r2", "d1");
        sm.transition("r2", RideStatus.DRIVER_ARRIVING);
        sm.transition("r2", RideStatus.IN_PROGRESS);
        sm.transition("r2", RideStatus.COMPLETED);
        assertThat(sm.activeRideCount()).isEqualTo(1);
    }

    @Test
    void getRideReturnsLatestState() {
        sm.createRide("r1", "rider1", 10.0, 106.0, 10.1, 106.1);
        sm.matchDriver("r1", "driver1");
        var ride = sm.getRide("r1");
        assertThat(ride.status()).isEqualTo(RideStatus.MATCHED);
        assertThat(ride.driverId()).isEqualTo("driver1");
    }
}
