package grabflow.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main service that orchestrates notification rendering, routing, and queuing.
 *
 * <h3>Send Flow</h3>
 * <ol>
 *   <li>Render the message body using {@link TemplateRenderer} with caller-supplied data.</li>
 *   <li>Construct an immutable {@link grabflow.notification.NotificationRouter.Notification}.</li>
 *   <li>Route the notification to the user's preferred delivery channels via
 *       {@link NotificationRouter}.</li>
 *   <li>Enqueue the notification in {@link NotificationQueue} for asynchronous delivery.</li>
 *   <li>Record the notification in per-user history for later retrieval.</li>
 * </ol>
 *
 * <h3>Priority Assignment</h3>
 * <p>Notification types are assigned a fixed priority used when enqueuing:</p>
 * <ul>
 *   <li>RIDE_MATCHED — 10 (highest)</li>
 *   <li>DRIVER_ARRIVING — 9</li>
 *   <li>RIDE_STARTED — 8</li>
 *   <li>RIDE_COMPLETED — 7</li>
 *   <li>PAYMENT_RECEIPT — 5</li>
 *   <li>SURGE_ALERT — 3</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>History and stats counters use concurrent data structures, making
 * {@link #send}, {@link #getHistory}, and {@link #getStats} safe for concurrent use.</p>
 */
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    // -------------------------------------------------------------------------
    // Stats record
    // -------------------------------------------------------------------------

    /**
     * A snapshot of notification delivery statistics.
     *
     * @param sent    total notifications sent (routed to at least one channel)
     * @param failed  total notifications where all channel deliveries failed
     * @param pending current number of notifications waiting in the queue
     */
    public record NotificationStats(long sent, long failed, long pending) {}

    // -------------------------------------------------------------------------
    // Priority map
    // -------------------------------------------------------------------------

    private static final Map<NotificationRouter.NotificationType, Integer> PRIORITIES;

    static {
        Map<NotificationRouter.NotificationType, Integer> p = new EnumMap<>(NotificationRouter.NotificationType.class);
        p.put(NotificationRouter.NotificationType.RIDE_MATCHED,    10);
        p.put(NotificationRouter.NotificationType.DRIVER_ARRIVING,  9);
        p.put(NotificationRouter.NotificationType.RIDE_STARTED,     8);
        p.put(NotificationRouter.NotificationType.RIDE_COMPLETED,   7);
        p.put(NotificationRouter.NotificationType.PAYMENT_RECEIPT,  5);
        p.put(NotificationRouter.NotificationType.SURGE_ALERT,      3);
        PRIORITIES = Collections.unmodifiableMap(p);
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final NotificationRouter router;
    private final NotificationQueue queue;
    private final TemplateRenderer renderer;

    /** Per-user notification history; values are thread-safe lists. */
    private final Map<String, List<NotificationRouter.Notification>> history =
            new ConcurrentHashMap<>();

    private final AtomicLong sentCount   = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructs a {@code NotificationService} with explicit collaborator injection.
     *
     * @param router   routes notifications to delivery channels
     * @param queue    buffers notifications for asynchronous delivery
     * @param renderer renders message bodies from type-specific templates
     */
    public NotificationService(NotificationRouter router,
                               NotificationQueue queue,
                               TemplateRenderer renderer) {
        this.router   = Objects.requireNonNull(router,   "router");
        this.queue    = Objects.requireNonNull(queue,    "queue");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Sends a notification to {@code userId} of the given {@code type}.
     *
     * <p>The call is synchronous up to queuing; actual channel delivery happens
     * asynchronously via the {@link NotificationQueue}.
     *
     * @param userId the recipient user identifier
     * @param type   the notification type (drives template selection and priority)
     * @param data   key/value pairs used for template variable substitution
     */
    public void send(String userId,
                     NotificationRouter.NotificationType type,
                     Map<String, String> data) {

        String body = renderer.render(type, data);
        String title = titleFor(type);
        String id = UUID.randomUUID().toString();

        NotificationRouter.Notification notification = new NotificationRouter.Notification(
                id, userId, type, title, body,
                data == null ? Map.of() : Map.copyOf(data),
                Instant.now()
        );

        List<NotificationRouter.DeliveryResult> results = router.route(notification);

        boolean anySuccess = results.stream().anyMatch(NotificationRouter.DeliveryResult::success);
        if (anySuccess) {
            sentCount.incrementAndGet();
        } else {
            failedCount.incrementAndGet();
            log.warn("All channel deliveries failed for notification id={} userId={}", id, userId);
        }

        int priority = PRIORITIES.getOrDefault(type, 1);
        queue.enqueue(notification, priority);

        history.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>())
               .add(notification);

        log.info("Sent notification id={} userId={} type={} channels={}",
                id, userId, type, results.size());
    }

    /**
     * Returns the most recent notifications sent to {@code userId}, newest first.
     *
     * @param userId the user whose history to retrieve
     * @param limit  maximum number of notifications to return; must be positive
     * @return an unmodifiable list of up to {@code limit} notifications, newest first
     */
    public List<NotificationRouter.Notification> getHistory(String userId, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");

        List<NotificationRouter.Notification> userHistory = history.getOrDefault(userId, List.of());
        int size = userHistory.size();
        int fromIndex = Math.max(0, size - limit);
        // Sublist ordered oldest→newest; reverse for newest-first
        List<NotificationRouter.Notification> slice = new ArrayList<>(userHistory.subList(fromIndex, size));
        Collections.reverse(slice);
        return Collections.unmodifiableList(slice);
    }

    /**
     * Returns a point-in-time snapshot of notification delivery statistics.
     *
     * @return current {@link NotificationStats}
     */
    public NotificationStats getStats() {
        return new NotificationStats(sentCount.get(), failedCount.get(), queue.size());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Returns a human-readable title for the notification type used as push headline. */
    private static String titleFor(NotificationRouter.NotificationType type) {
        return switch (type) {
            case RIDE_MATCHED    -> "Driver Matched";
            case DRIVER_ARRIVING -> "Driver Arriving";
            case RIDE_STARTED    -> "Ride Started";
            case RIDE_COMPLETED  -> "Ride Completed";
            case PAYMENT_RECEIPT -> "Payment Receipt";
            case SURGE_ALERT     -> "Surge Pricing Alert";
        };
    }
}
