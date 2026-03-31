package grabflow.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders notification message bodies from named templates with variable substitution.
 *
 * <h3>Template Format</h3>
 * <p>Variables are enclosed in double curly braces: {@code {{variableName}}}.
 * Any variable present in the template but absent from the provided map is left
 * as-is in the output (the placeholder is preserved), making partial rendering
 * safe and predictable.</p>
 *
 * <h3>Default Templates</h3>
 * <p>A default template for every {@link grabflow.notification.NotificationRouter.NotificationType}
 * is registered during construction.  Callers may override individual templates at
 * any time via {@link #registerTemplate(grabflow.notification.NotificationRouter.NotificationType, String)}.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Template registration is <em>not</em> synchronized; it is expected to occur at
 * startup before any concurrent rendering begins.  The {@link #render} method is
 * read-only after registration and therefore safe for concurrent use.</p>
 */
public class TemplateRenderer {

    private static final Logger log = LoggerFactory.getLogger(TemplateRenderer.class);

    /** Matches {@code {{variableName}}} placeholders in a template string. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    private final Map<NotificationRouter.NotificationType, String> templates =
            new EnumMap<>(NotificationRouter.NotificationType.class);

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructs a {@code TemplateRenderer} and pre-registers default templates for
     * all notification types.
     */
    public TemplateRenderer() {
        registerDefaults();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Registers (or replaces) the template for the given notification type.
     *
     * <p>Variables must be written as {@code {{variableName}}} — double curly braces
     * with no surrounding whitespace inside the braces.
     *
     * @param type     the notification type this template applies to
     * @param template the template string; must not be {@code null}
     */
    public void registerTemplate(NotificationRouter.NotificationType type, String template) {
        if (template == null) {
            throw new IllegalArgumentException("template must not be null");
        }
        templates.put(type, template);
        log.debug("Template registered for type={}", type);
    }

    /**
     * Renders the template for {@code type} by substituting {@code variables}.
     *
     * <p>Any {@code {{key}}} placeholder whose key is absent from {@code variables}
     * is preserved verbatim in the output.
     *
     * @param type      the notification type whose template to render
     * @param variables key/value pairs to substitute into the template
     * @return the rendered string
     * @throws IllegalStateException if no template has been registered for {@code type}
     */
    public String render(NotificationRouter.NotificationType type, Map<String, String> variables) {
        String template = templates.get(type);
        if (template == null) {
            throw new IllegalStateException("No template registered for type: " + type);
        }

        StringBuffer sb = new StringBuffer();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = variables.getOrDefault(key, matcher.group(0)); // preserve if missing
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);

        String result = sb.toString();
        log.debug("Rendered template type={} result='{}'", type, result);
        return result;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Registers the built-in default templates for every NotificationType. */
    private void registerDefaults() {
        templates.put(NotificationRouter.NotificationType.RIDE_MATCHED,
                "Great news! Driver {{driverName}} ({{vehicleModel}}) has been matched to your ride.");
        templates.put(NotificationRouter.NotificationType.DRIVER_ARRIVING,
                "Your driver {{driverName}} is arriving in {{eta}} minutes. Look for {{vehicleModel}} ({{licensePlate}}).");
        templates.put(NotificationRouter.NotificationType.RIDE_STARTED,
                "Your ride has started. Estimated arrival at {{destination}} in {{eta}} minutes.");
        templates.put(NotificationRouter.NotificationType.RIDE_COMPLETED,
                "You have arrived at {{destination}}. Hope you enjoyed the ride!");
        templates.put(NotificationRouter.NotificationType.PAYMENT_RECEIPT,
                "Payment of {{amount}} {{currency}} for your ride on {{date}} has been processed. Ref: {{referenceId}}.");
        templates.put(NotificationRouter.NotificationType.SURGE_ALERT,
                "Surge pricing is active in your area. Current multiplier: {{multiplier}}x. Fares will return to normal soon.");
    }
}
