package grabflow.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link NotificationRouter}.
 */
class NotificationRouterTest {

    private NotificationRouter router;

    @BeforeEach
    void setup() {
        router = new NotificationRouter();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private NotificationRouter.Notification notification(String userId, NotificationRouter.NotificationType type) {
        return new NotificationRouter.Notification(
                "notif-1", userId, type, "Title", "Body", Map.of(), Instant.now());
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void defaultChannelsUsedWhenNoPreferenceRegistered() {
        var results = router.route(notification("user-unknown", NotificationRouter.NotificationType.RIDE_MATCHED));

        assertThat(results).hasSize(2);
        var channels = results.stream().map(NotificationRouter.DeliveryResult::channel).toList();
        assertThat(channels).containsExactlyInAnyOrder(
                NotificationRouter.DeliveryChannel.PUSH,
                NotificationRouter.DeliveryChannel.IN_APP);
    }

    @Test
    void defaultChannelsUsedForUnknownUser() {
        // Explicitly verify: a user who has never registered any preference gets defaults
        var n = notification("brand-new-user", NotificationRouter.NotificationType.SURGE_ALERT);
        var results = router.route(n);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(NotificationRouter.DeliveryResult::success);
    }

    @Test
    void customPreferenceOverridesDefault() {
        router.registerPreference("user-1", NotificationRouter.NotificationType.RIDE_MATCHED,
                Set.of(NotificationRouter.DeliveryChannel.SMS));

        var results = router.route(notification("user-1", NotificationRouter.NotificationType.RIDE_MATCHED));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().channel()).isEqualTo(NotificationRouter.DeliveryChannel.SMS);
        assertThat(results.getFirst().success()).isTrue();
    }

    @Test
    void multipleChannelsInPreferenceAllDelivered() {
        router.registerPreference("user-2", NotificationRouter.NotificationType.PAYMENT_RECEIPT,
                Set.of(NotificationRouter.DeliveryChannel.EMAIL,
                       NotificationRouter.DeliveryChannel.SMS,
                       NotificationRouter.DeliveryChannel.IN_APP));

        var results = router.route(notification("user-2", NotificationRouter.NotificationType.PAYMENT_RECEIPT));

        assertThat(results).hasSize(3);
        var channels = results.stream().map(NotificationRouter.DeliveryResult::channel).toList();
        assertThat(channels).containsExactlyInAnyOrder(
                NotificationRouter.DeliveryChannel.EMAIL,
                NotificationRouter.DeliveryChannel.SMS,
                NotificationRouter.DeliveryChannel.IN_APP);
    }

    @Test
    void preferenceForOneTypeDoesNotAffectAnotherType() {
        // user-3 has a custom preference for RIDE_MATCHED only
        router.registerPreference("user-3", NotificationRouter.NotificationType.RIDE_MATCHED,
                Set.of(NotificationRouter.DeliveryChannel.EMAIL));

        // For DRIVER_ARRIVING (no preference) should fall back to defaults
        var results = router.route(notification("user-3", NotificationRouter.NotificationType.DRIVER_ARRIVING));

        assertThat(results).hasSize(2);
        var channels = results.stream().map(NotificationRouter.DeliveryResult::channel).toList();
        assertThat(channels).containsExactlyInAnyOrder(
                NotificationRouter.DeliveryChannel.PUSH,
                NotificationRouter.DeliveryChannel.IN_APP);
    }

    @Test
    void deliveryResultsContainCorrectNotificationId() {
        var n = notification("user-4", NotificationRouter.NotificationType.RIDE_COMPLETED);
        var results = router.route(n);

        assertThat(results).allMatch(r -> r.notificationId().equals("notif-1"));
    }

    @Test
    void registerPreferenceWithEmptyChannelsThrows() {
        assertThatThrownBy(() ->
                router.registerPreference("user-5", NotificationRouter.NotificationType.SURGE_ALERT, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerPreferenceWithNullChannelsThrows() {
        assertThatThrownBy(() ->
                router.registerPreference("user-6", NotificationRouter.NotificationType.SURGE_ALERT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
