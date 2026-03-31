package grabflow.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link NotificationService}.
 */
class NotificationServiceTest {

    private NotificationRouter router;
    private NotificationQueue  queue;
    private TemplateRenderer   renderer;
    private NotificationService service;

    @BeforeEach
    void setup() {
        router   = new NotificationRouter();
        queue    = new NotificationQueue();
        renderer = new TemplateRenderer();
        service  = new NotificationService(router, queue, renderer);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void sendRoutesAndQueuesNotification() {
        service.send("user-1", NotificationRouter.NotificationType.RIDE_MATCHED,
                Map.of("driverName", "Ahmad", "vehicleModel", "Toyota"));

        // Notification should be in the queue
        assertThat(queue.size()).isEqualTo(1);
    }

    @Test
    void sendRecordsHistoryForUser() {
        service.send("user-2", NotificationRouter.NotificationType.DRIVER_ARRIVING,
                Map.of("driverName", "Budi", "eta", "3", "vehicleModel", "Honda", "licensePlate", "B-1234"));

        List<NotificationRouter.Notification> history = service.getHistory("user-2", 10);
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().type())
                .isEqualTo(NotificationRouter.NotificationType.DRIVER_ARRIVING);
    }

    @Test
    void historyReturnedNewestFirst() {
        service.send("user-3", NotificationRouter.NotificationType.RIDE_STARTED, Map.of("destination", "Home", "eta", "10"));
        service.send("user-3", NotificationRouter.NotificationType.RIDE_COMPLETED, Map.of("destination", "Home"));

        List<NotificationRouter.Notification> history = service.getHistory("user-3", 10);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).type()).isEqualTo(NotificationRouter.NotificationType.RIDE_COMPLETED);
        assertThat(history.get(1).type()).isEqualTo(NotificationRouter.NotificationType.RIDE_STARTED);
    }

    @Test
    void historyRespectedLimit() {
        for (int i = 0; i < 5; i++) {
            service.send("user-4", NotificationRouter.NotificationType.SURGE_ALERT,
                    Map.of("multiplier", String.valueOf(i + 1)));
        }

        List<NotificationRouter.Notification> history = service.getHistory("user-4", 3);
        assertThat(history).hasSize(3);
    }

    @Test
    void historyEmptyForUnknownUser() {
        List<NotificationRouter.Notification> history = service.getHistory("no-such-user", 5);
        assertThat(history).isEmpty();
    }

    @Test
    void statsSentCountIncrementsPerSend() {
        service.send("user-5", NotificationRouter.NotificationType.PAYMENT_RECEIPT,
                Map.of("amount", "9.99", "currency", "SGD", "date", "2026-03-30", "referenceId", "R001"));
        service.send("user-5", NotificationRouter.NotificationType.RIDE_COMPLETED,
                Map.of("destination", "Office"));

        NotificationService.NotificationStats stats = service.getStats();
        assertThat(stats.sent()).isEqualTo(2);
        assertThat(stats.failed()).isEqualTo(0);
    }

    @Test
    void statsPendingReflectsQueueSize() {
        service.send("user-6", NotificationRouter.NotificationType.RIDE_MATCHED,
                Map.of("driverName", "Citra", "vehicleModel", "Civic"));

        NotificationService.NotificationStats stats = service.getStats();
        assertThat(stats.pending()).isEqualTo(1);
    }

    @Test
    void sendWithCustomChannelPreference() {
        router.registerPreference("user-7",
                NotificationRouter.NotificationType.SURGE_ALERT,
                Set.of(NotificationRouter.DeliveryChannel.EMAIL));

        service.send("user-7", NotificationRouter.NotificationType.SURGE_ALERT,
                Map.of("multiplier", "1.8"));

        // Sent successfully with EMAIL channel
        assertThat(service.getStats().sent()).isEqualTo(1);
        assertThat(service.getHistory("user-7", 1)).hasSize(1);
    }

    @Test
    void multipleUserHistoriesAreIsolated() {
        service.send("alice", NotificationRouter.NotificationType.RIDE_MATCHED,
                Map.of("driverName", "D1", "vehicleModel", "Vios"));
        service.send("bob", NotificationRouter.NotificationType.PAYMENT_RECEIPT,
                Map.of("amount", "5.00", "currency", "MYR", "date", "2026-03-30", "referenceId", "R002"));

        assertThat(service.getHistory("alice", 10)).hasSize(1);
        assertThat(service.getHistory("bob",   10)).hasSize(1);
        assertThat(service.getHistory("alice", 10).getFirst().type())
                .isEqualTo(NotificationRouter.NotificationType.RIDE_MATCHED);
    }

    @Test
    void getHistoryWithInvalidLimitThrows() {
        assertThatThrownBy(() -> service.getHistory("user-8", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsNullDependencies() {
        assertThatThrownBy(() -> new NotificationService(null, queue, renderer))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NotificationService(router, null, renderer))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NotificationService(router, queue, null))
                .isInstanceOf(NullPointerException.class);
    }
}
