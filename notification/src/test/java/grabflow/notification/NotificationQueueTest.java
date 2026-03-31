package grabflow.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link NotificationQueue}.
 */
class NotificationQueueTest {

    private NotificationQueue queue;

    @BeforeEach
    void setup() {
        queue = new NotificationQueue();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private NotificationRouter.Notification notification(String id, NotificationRouter.NotificationType type) {
        return new NotificationRouter.Notification(
                id, "user-1", type, "Title", "Body", Map.of(), Instant.now());
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void emptyQueueReturnsEmpty() {
        assertThat(queue.dequeue()).isEmpty();
    }

    @Test
    void enqueuedItemCanBeDequeued() {
        var n = notification("n-1", NotificationRouter.NotificationType.RIDE_MATCHED);
        queue.enqueue(n, 5);

        Optional<NotificationRouter.Notification> result = queue.dequeue();
        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("n-1");
    }

    @Test
    void higherPriorityDequeuedFirst() {
        var low  = notification("low",  NotificationRouter.NotificationType.SURGE_ALERT);
        var high = notification("high", NotificationRouter.NotificationType.RIDE_MATCHED);

        queue.enqueue(low,  3);
        queue.enqueue(high, 10);

        var first = queue.dequeue();
        assertThat(first).isPresent();
        assertThat(first.get().id()).isEqualTo("high");

        var second = queue.dequeue();
        assertThat(second).isPresent();
        assertThat(second.get().id()).isEqualTo("low");
    }

    @Test
    void samePriorityPreservesInsertionOrder() {
        // Within the same priority, deliverAfter (Instant.now()) ordering ensures
        // first-enqueued is first-dequeued (FIFO within a tier).
        var first  = notification("first",  NotificationRouter.NotificationType.PAYMENT_RECEIPT);
        var second = notification("second", NotificationRouter.NotificationType.PAYMENT_RECEIPT);

        queue.enqueue(first,  5);
        // Tiny sleep to ensure Instant.now() differs for the second entry
        try { Thread.sleep(2); } catch (InterruptedException ignored) {}
        queue.enqueue(second, 5);

        assertThat(queue.dequeue().map(NotificationRouter.Notification::id)).contains("first");
        assertThat(queue.dequeue().map(NotificationRouter.Notification::id)).contains("second");
    }

    @Test
    void sizeReflectsEnqueueCount() {
        assertThat(queue.size()).isEqualTo(0);

        queue.enqueue(notification("a", NotificationRouter.NotificationType.RIDE_STARTED), 8);
        queue.enqueue(notification("b", NotificationRouter.NotificationType.RIDE_STARTED), 8);

        assertThat(queue.size()).isEqualTo(2);
    }

    @Test
    void sizeDecreasesAfterDequeue() {
        queue.enqueue(notification("x", NotificationRouter.NotificationType.DRIVER_ARRIVING), 9);
        queue.dequeue();
        assertThat(queue.size()).isEqualTo(0);
    }

    @Test
    void retryEnqueueIncrementsPendingRetries() {
        assertThat(queue.pendingRetries()).isEqualTo(0);

        queue.enqueueForRetry(
                notification("r-1", NotificationRouter.NotificationType.PAYMENT_RECEIPT),
                1, Duration.ofMillis(1));

        assertThat(queue.pendingRetries()).isEqualTo(1);
        assertThat(queue.size()).isEqualTo(1);
    }

    @Test
    void retryEntryNotDeliverableBeforeDelay() {
        queue.enqueueForRetry(
                notification("r-2", NotificationRouter.NotificationType.RIDE_COMPLETED),
                1, Duration.ofHours(1)); // far future

        // Queue has 1 entry but it should not be returned yet
        assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.dequeue()).isEmpty();
    }

    @Test
    void retryEntryDeliverableAfterDelay() throws InterruptedException {
        queue.enqueueForRetry(
                notification("r-3", NotificationRouter.NotificationType.SURGE_ALERT),
                1, Duration.ofMillis(1));

        Thread.sleep(10); // wait past the 1 ms delay
        Optional<NotificationRouter.Notification> result = queue.dequeue();
        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("r-3");
        assertThat(queue.pendingRetries()).isEqualTo(0);
    }
}
