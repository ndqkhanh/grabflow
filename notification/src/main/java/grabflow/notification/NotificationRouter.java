package grabflow.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * Routes notifications to the correct delivery channel(s) based on notification type
 * and per-user channel preferences.
 *
 * <h3>Default Behaviour</h3>
 * <p>When no preference has been registered for a user/type combination, the router
 * falls back to {@link DeliveryChannel#PUSH} and {@link DeliveryChannel#IN_APP}.</p>
 *
 * <h3>Preference Registration</h3>
 * <p>Call {@link #registerPreference(String, NotificationType, Set)} to override the
 * default for a specific {@code (userId, type)} pair.  Preferences are stored in-memory
 * and take effect immediately for subsequent {@link #route(Notification)} calls.</p>
 */
public class NotificationRouter {

    private static final Logger log = LoggerFactory.getLogger(NotificationRouter.class);

    /** Default channels used when no explicit preference is configured. */
    private static final Set<DeliveryChannel> DEFAULT_CHANNELS =
            Set.of(DeliveryChannel.PUSH, DeliveryChannel.IN_APP);

    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------

    /**
     * The type of notification being sent, used to determine routing priority
     * and which templates to apply.
     */
    public enum NotificationType {
        /** A driver has been matched to the passenger's ride request. */
        RIDE_MATCHED,
        /** The matched driver is approaching the pickup point. */
        DRIVER_ARRIVING,
        /** The ride is underway — passenger has been picked up. */
        RIDE_STARTED,
        /** The ride has ended at the destination. */
        RIDE_COMPLETED,
        /** A payment receipt for a completed trip. */
        PAYMENT_RECEIPT,
        /** Dynamic pricing is elevated in the passenger's area. */
        SURGE_ALERT
    }

    /**
     * Delivery channels available for pushing notifications to users.
     */
    public enum DeliveryChannel {
        /** Mobile push notification (APNs / FCM). */
        PUSH,
        /** Short Message Service. */
        SMS,
        /** In-app notification centre. */
        IN_APP,
        /** Electronic mail. */
        EMAIL
    }

    // -------------------------------------------------------------------------
    // Records
    // -------------------------------------------------------------------------

    /**
     * An immutable notification payload ready for delivery.
     *
     * @param id        unique identifier for this notification (e.g. UUID)
     * @param userId    identifier of the recipient user
     * @param type      the semantic type of the notification
     * @param title     short headline shown in push banners and in-app previews
     * @param body      full message body
     * @param data      arbitrary key/value metadata (deep-link URIs, ride IDs, etc.)
     * @param createdAt wall-clock timestamp when the notification was created
     */
    public record Notification(
            String id,
            String userId,
            NotificationType type,
            String title,
            String body,
            Map<String, String> data,
            Instant createdAt
    ) {}

    /**
     * Result of a single delivery attempt on one channel.
     *
     * @param notificationId the {@link Notification#id()} that was delivered
     * @param channel        the channel used for delivery
     * @param success        {@code true} if delivery succeeded (or was simulated successfully)
     * @param message        human-readable status detail (e.g. "delivered", error description)
     */
    public record DeliveryResult(
            String notificationId,
            DeliveryChannel channel,
            boolean success,
            String message
    ) {}

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /**
     * Outer key: userId. Inner key: NotificationType. Value: set of preferred channels.
     */
    private final Map<String, Map<NotificationType, Set<DeliveryChannel>>> preferences =
            new HashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Registers delivery channel preferences for a user/notification-type pair.
     *
     * <p>Calling this method again with the same {@code (userId, type)} replaces the
     * previous preference entirely.
     *
     * @param userId   the recipient user
     * @param type     the notification type to configure
     * @param channels the set of channels to use; must not be empty
     */
    public void registerPreference(String userId, NotificationType type, Set<DeliveryChannel> channels) {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("channels must not be empty");
        }
        preferences
                .computeIfAbsent(userId, k -> new HashMap<>())
                .put(type, Set.copyOf(channels));
        log.debug("Preference registered: user={} type={} channels={}", userId, type, channels);
    }

    /**
     * Routes {@code notification} to all channels determined by the recipient's preferences.
     *
     * <p>Each channel produces one {@link DeliveryResult}. In this implementation delivery
     * is simulated (always succeeds), which allows the router to be tested without real
     * push/SMS infrastructure.
     *
     * @param notification the notification to deliver
     * @return an unmodifiable list of per-channel delivery results, one per selected channel
     */
    public List<DeliveryResult> route(Notification notification) {
        Set<DeliveryChannel> channels = resolveChannels(notification.userId(), notification.type());
        log.info("Routing notification id={} userId={} type={} channels={}",
                notification.id(), notification.userId(), notification.type(), channels);

        List<DeliveryResult> results = new ArrayList<>();
        for (DeliveryChannel channel : channels) {
            DeliveryResult result = deliver(notification, channel);
            results.add(result);
            log.debug("Delivered id={} channel={} success={}", notification.id(), channel, result.success());
        }
        return Collections.unmodifiableList(results);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the delivery channels for the given user and type.
     * Falls back to {@link #DEFAULT_CHANNELS} when no preference is registered.
     */
    private Set<DeliveryChannel> resolveChannels(String userId, NotificationType type) {
        Map<NotificationType, Set<DeliveryChannel>> userPrefs = preferences.get(userId);
        if (userPrefs == null) {
            return DEFAULT_CHANNELS;
        }
        Set<DeliveryChannel> specific = userPrefs.get(type);
        return specific != null ? specific : DEFAULT_CHANNELS;
    }

    /**
     * Simulates delivery of {@code notification} on {@code channel}.
     * Real implementations would call FCM, Twilio, SES, etc.
     */
    private DeliveryResult deliver(Notification notification, DeliveryChannel channel) {
        // Simulate successful delivery for all channels
        return new DeliveryResult(notification.id(), channel, true, "delivered");
    }
}
