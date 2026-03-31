package grabflow.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link TemplateRenderer}.
 */
class TemplateRendererTest {

    private TemplateRenderer renderer;

    @BeforeEach
    void setup() {
        renderer = new TemplateRenderer();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void rendersVariablesCorrectly() {
        renderer.registerTemplate(NotificationRouter.NotificationType.DRIVER_ARRIVING,
                "Driver {{driverName}} arrives in {{eta}} min");

        String result = renderer.render(
                NotificationRouter.NotificationType.DRIVER_ARRIVING,
                Map.of("driverName", "Ali", "eta", "5"));

        assertThat(result).isEqualTo("Driver Ali arrives in 5 min");
    }

    @Test
    void missingVariablePreservesPlaceholder() {
        renderer.registerTemplate(NotificationRouter.NotificationType.SURGE_ALERT,
                "Surge {{multiplier}}x active in {{area}}");

        // "area" is not supplied
        String result = renderer.render(
                NotificationRouter.NotificationType.SURGE_ALERT,
                Map.of("multiplier", "2.5"));

        assertThat(result).isEqualTo("Surge 2.5x active in {{area}}");
    }

    @Test
    void emptyVariableMapPreservesAllPlaceholders() {
        renderer.registerTemplate(NotificationRouter.NotificationType.RIDE_MATCHED,
                "Driver {{driverName}} matched");

        String result = renderer.render(NotificationRouter.NotificationType.RIDE_MATCHED, Map.of());

        assertThat(result).isEqualTo("Driver {{driverName}} matched");
    }

    @Test
    void multiplePlaceholdersReplacedInSinglePass() {
        renderer.registerTemplate(NotificationRouter.NotificationType.RIDE_COMPLETED,
                "Arrived at {{dest}}. Fare: {{amount}} {{currency}}.");

        String result = renderer.render(
                NotificationRouter.NotificationType.RIDE_COMPLETED,
                Map.of("dest", "Airport", "amount", "12.50", "currency", "USD"));

        assertThat(result).isEqualTo("Arrived at Airport. Fare: 12.50 USD.");
    }

    @Test
    void registerTemplateSupersedesPreviousTemplate() {
        // Override default RIDE_STARTED template
        renderer.registerTemplate(NotificationRouter.NotificationType.RIDE_STARTED,
                "Custom: {{info}}");

        String result = renderer.render(
                NotificationRouter.NotificationType.RIDE_STARTED,
                Map.of("info", "hello"));

        assertThat(result).isEqualTo("Custom: hello");
    }

    @Test
    void registerTemplateWithNullThrows() {
        assertThatThrownBy(() ->
                renderer.registerTemplate(NotificationRouter.NotificationType.PAYMENT_RECEIPT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(NotificationRouter.NotificationType.class)
    void allDefaultTemplatesAreRegistered(NotificationRouter.NotificationType type) {
        // render() must not throw — proving a template exists for every type
        assertThatCode(() -> renderer.render(type, Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void templateWithNoPlaceholdersReturnedVerbatim() {
        renderer.registerTemplate(NotificationRouter.NotificationType.RIDE_COMPLETED,
                "Your ride is complete.");

        String result = renderer.render(
                NotificationRouter.NotificationType.RIDE_COMPLETED,
                Map.of("ignored", "value"));

        assertThat(result).isEqualTo("Your ride is complete.");
    }
}
