package grabflow.gateway.routing;

import grabflow.common.ServiceEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RequestRouterTest {

    private ServiceRegistry registry;
    private RequestRouter router;

    @BeforeEach
    void setup() {
        registry = new ServiceRegistry();
        router = new RequestRouter(registry);

        // Register services with endpoints
        registry.register("ride-service", new ServiceEndpoint("localhost", 8081));
        registry.register("location-service", new ServiceEndpoint("localhost", 8082));
        registry.register("pricing-service", new ServiceEndpoint("localhost", 8083));

        // Configure routes
        router.addRoute("/api/v1/rides", "ride-service");
        router.addRoute("/api/v1/locations", "location-service");
        router.addRoute("/api/v1/pricing", "pricing-service");
        router.addRoute("/health", "ride-service");
    }

    @Test
    void routesExactPrefixMatch() {
        var result = router.route("/api/v1/rides");
        assertThat(result).isPresent();
        assertThat(result.get().serviceName()).isEqualTo("ride-service");
    }

    @Test
    void routesSubpathToCorrectService() {
        var result = router.route("/api/v1/rides/123");
        assertThat(result).isPresent();
        assertThat(result.get().serviceName()).isEqualTo("ride-service");
        assertThat(result.get().strippedPath()).isEqualTo("/123");
    }

    @Test
    void routesDifferentServicesCorrectly() {
        assertThat(router.route("/api/v1/locations/nearby").get().serviceName())
                .isEqualTo("location-service");
        assertThat(router.route("/api/v1/pricing/surge").get().serviceName())
                .isEqualTo("pricing-service");
    }

    @Test
    void longestPrefixWins() {
        // Add a more specific route
        registry.register("ride-history", new ServiceEndpoint("localhost", 8084));
        router.addRoute("/api/v1/rides/history", "ride-history");

        // /api/v1/rides/history/abc should match the longer prefix
        var result = router.route("/api/v1/rides/history/abc");
        assertThat(result).isPresent();
        assertThat(result.get().serviceName()).isEqualTo("ride-history");

        // /api/v1/rides/123 should still match the shorter prefix
        var result2 = router.route("/api/v1/rides/123");
        assertThat(result2).isPresent();
        assertThat(result2.get().serviceName()).isEqualTo("ride-service");
    }

    @Test
    void noRouteMatchReturnsEmpty() {
        assertThat(router.route("/unknown/path")).isEmpty();
    }

    @Test
    void noEndpointsReturnsEmpty() {
        router.addRoute("/api/v1/payments", "payment-service");
        // payment-service has no endpoints registered
        assertThat(router.route("/api/v1/payments/charge")).isEmpty();
    }

    @Test
    void strippedPathPreservesSlash() {
        var result = router.route("/api/v1/rides");
        assertThat(result).isPresent();
        assertThat(result.get().strippedPath()).isEqualTo("/");
    }

    @Test
    void healthEndpointRoutes() {
        var result = router.route("/health");
        assertThat(result).isPresent();
        assertThat(result.get().serviceName()).isEqualTo("ride-service");
    }

    @Test
    void roundRobinLoadBalancing() {
        // Add a second endpoint for ride-service
        registry.register("ride-service", new ServiceEndpoint("localhost", 8091));

        var result1 = router.route("/api/v1/rides/1");
        var result2 = router.route("/api/v1/rides/2");

        assertThat(result1).isPresent();
        assertThat(result2).isPresent();
        // Two different endpoints should be selected
        assertThat(result1.get().endpoint().port())
                .isNotEqualTo(result2.get().endpoint().port());
    }

    @Test
    void routeCountReflectsConfiguration() {
        assertThat(router.routeCount()).isEqualTo(4);
    }

    @Test
    void serviceRegistryTracksEndpoints() {
        assertThat(registry.serviceCount()).isEqualTo(3);
        assertThat(registry.endpointCount()).isEqualTo(3);
        assertThat(registry.serviceNames()).containsExactlyInAnyOrder(
                "ride-service", "location-service", "pricing-service");
    }

    @Test
    void deregisterRemovesEndpoint() {
        var endpoint = new ServiceEndpoint("localhost", 8082);
        registry.deregister("location-service", endpoint);

        assertThat(registry.getEndpoints("location-service")).isEmpty();
        assertThat(router.route("/api/v1/locations/nearby")).isEmpty();
    }
}
