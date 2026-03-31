package grabflow.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Priority queue for notification delivery with built-in retry support.
 *
 * <h3>Ordering</h3>
 * <p>Entries are ordered first by <em>descending priority</em> (higher integer value =
 * dequeued sooner) and then by <em>ascending {@code deliverAfter}</em> timestamp so
 * that within the same priority level earlier-scheduled items come first.</p>
 *
 * <h3>Retry Support</h3>
 * <p>Failed deliveries can be re-enqueued via
 * {@link #enqueueForRetry(grabflow.notification.NotificationRouter.Notification, int, java.time.Duration)}.
 * The retry entry carries the original priority minus a small penalty so retries do not
 * starve fresh notifications of the same type.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>{@link PriorityBlockingQueue} is used internally; all public methods are safe for
 * concurrent access.</p>
 */
public class NotificationQueue {

    private static final Logger log = LoggerFactory.getLogger(NotificationQueue.class);

    // -------------------------------------------------------------------------
    // Inner record
    // -------------------------------------------------------------------------

    /**
     * An entry in the priority queue wrapping a notification with scheduling metadata.
     *
     * @param notification the notification payload
     * @param priority     delivery urgency (higher = more urgent); e.g. 10 for RIDE_MATCHED
     * @param retryCount   number of previous delivery attempts (0 for a fresh enqueue)
     * @param deliverAfter earliest wall-clock time at which this entry may be dequeued
     */
    public record QueueEntry(
            NotificationRouter.Notification notification,
            int priority,
            int retryCount,
            Instant deliverAfter
    ) implements Comparable<QueueEntry> {

        /**
         * Natural ordering: highest priority first; ties broken by earliest deliverAfter.
         */
        @Override
        public int compareTo(QueueEntry other) {
            int cmp = Integer.compare(other.priority(), this.priority()); // descending
            if (cmp != 0) return cmp;
            return this.deliverAfter().compareTo(other.deliverAfter()); // ascending
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final PriorityBlockingQueue<QueueEntry> queue = new PriorityBlockingQueue<>();
    private final AtomicInteger pendingRetries = new AtomicInteger();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Adds a notification to the queue for immediate delivery.
     *
     * @param notification the notification to enqueue
     * @param priority     delivery urgency (higher integer = higher priority)
     */
    public void enqueue(NotificationRouter.Notification notification, int priority) {
        QueueEntry entry = new QueueEntry(notification, priority, 0, Instant.now());
        queue.offer(entry);
        log.debug("Enqueued id={} priority={}", notification.id(), priority);
    }

    /**
     * Non-blocking poll: removes and returns the highest-priority entry whose
     * {@code deliverAfter} is not in the future, or empty if no such entry exists.
     *
     * @return the next deliverable entry, or {@link Optional#empty()} if the queue
     *         is empty or all entries are scheduled for a future time
     */
    public Optional<NotificationRouter.Notification> dequeue() {
        Instant now = Instant.now();
        // Peek first to avoid removing an entry that is not yet deliverable
        QueueEntry head = queue.peek();
        if (head == null || head.deliverAfter().isAfter(now)) {
            return Optional.empty();
        }
        QueueEntry entry = queue.poll();
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.retryCount() > 0) {
            pendingRetries.decrementAndGet();
        }
        log.debug("Dequeued id={} retryCount={}", entry.notification().id(), entry.retryCount());
        return Optional.of(entry.notification());
    }

    /**
     * Re-enqueues a notification for a delayed retry attempt.
     *
     * <p>The entry is placed at a slightly reduced priority
     * ({@code originalPriority - 1}) so that retries do not starve fresh notifications
     * of the same urgency level.
     *
     * @param notification the notification that failed delivery
     * @param retryCount   the current retry attempt number (1-based)
     * @param delay        how long to wait before the entry becomes deliverable
     */
    public void enqueueForRetry(NotificationRouter.Notification notification, int retryCount, java.time.Duration delay) {
        Instant deliverAfter = Instant.now().plus(delay);
        // Reduce priority by 1 per retry so retries yield to fresh work of equal urgency
        int retryPriority = Math.max(0, 5 - retryCount);
        QueueEntry entry = new QueueEntry(notification, retryPriority, retryCount, deliverAfter);
        queue.offer(entry);
        pendingRetries.incrementAndGet();
        log.info("Scheduled retry #{} id={} deliverAfter={}", retryCount, notification.id(), deliverAfter);
    }

    /**
     * Returns the total number of entries currently in the queue (including retries).
     *
     * @return current queue size
     */
    public int size() {
        return queue.size();
    }

    /**
     * Returns the number of entries that are retry attempts (i.e. {@code retryCount > 0}).
     *
     * @return count of pending retry entries
     */
    public int pendingRetries() {
        return pendingRetries.get();
    }
}
