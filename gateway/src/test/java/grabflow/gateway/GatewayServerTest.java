package grabflow.gateway;

import grabflow.common.GatewayRequest;
import grabflow.common.GatewayResponse;
import grabflow.common.ServiceEndpoint;
import grabflow.gateway.GatewayServer.GatewayConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class GatewayServerTest {

    private GatewayServer server;

    @BeforeEach
    void setup() {
        var config = GatewayConfig.withPort(0); // ephemeral port
        server = new GatewayServer(config);
    }

    @AfterEach
    void teardown() throws Exception {
        if (server != null) server.close();
    }

    @Test
    void handlesHealthEndpoint() throws Exception {
        var request = makeRequest("GET", "/health");
        GatewayResponse response = server.handleRequest(request);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body())).contains("gateway-health");
    }

    @Test
    void routesRideServiceRequests() throws Exception {
        // Register a ride-service endpoint
        server.getServiceRegistry().register("ride-service",
                new ServiceEndpoint("localhost", 8081));

        var request = makeRequest("GET", "/api/v1/rides/123");
        GatewayResponse response = server.handleRequest(request);

        assertThat(response.statusCode()).isEqualTo(200);
        String body = new String(response.body());
        assertThat(body).contains("ride-service");
        assertThat(body).contains("/123");
    }

    @Test
    void returns404ForUnknownRoute() throws Exception {
        var request = makeRequest("GET", "/unknown/path");
        GatewayResponse response = server.handleRequest(request);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void returns404WhenServiceHasNoEndpoints() throws Exception {
        // ride-service route exists but no endpoints registered
        var request = makeRequest("GET", "/api/v1/rides/123");
        GatewayResponse response = server.handleRequest(request);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void rateLimitsBlockedIps() throws Exception {
        // Add an IP to the blocklist
        server.getRateLimitFilter().blockIp("10.0.0.99");

        var request = makeRequest("GET", "/health", "10.0.0.99");
        GatewayResponse response = server.handleRequest(request);

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void rateLimitsExcessiveRequests() throws Exception {
        // Config allows 100 requests per client
        server.getServiceRegistry().register("ride-service",
                new ServiceEndpoint("localhost", 8081));

        // Exhaust the rate limit
        for (int i = 0; i < 100; i++) {
            server.handleRequest(makeRequest("GET", "/api/v1/rides/1"));
        }

        // 101st request should be rate-limited
        GatewayResponse response = server.handleRequest(makeRequest("GET", "/api/v1/rides/1"));
        assertThat(response.statusCode()).isEqualTo(429);
    }

    @Test
    void serviceRegistryIsAccessible() {
        assertThat(server.getServiceRegistry()).isNotNull();
        assertThat(server.getServiceRegistry().serviceCount()).isGreaterThan(0);
    }

    @Test
    void routerHasDefaultRoutes() {
        assertThat(server.getRouter().routeCount()).isGreaterThanOrEqualTo(6);
    }

    @Test
    void configDefaults() {
        var config = GatewayConfig.defaults();
        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.dnsServer()).isEqualTo("8.8.8.8");
        assertThat(config.rateLimitCapacity()).isEqualTo(100);
    }

    @Test
    void multipleServicesRouteCorrectly() throws Exception {
        server.getServiceRegistry().register("ride-service",
                new ServiceEndpoint("localhost", 8081));
        server.getServiceRegistry().register("pricing-service",
                new ServiceEndpoint("localhost", 8083));

        var rideResponse = server.handleRequest(makeRequest("GET", "/api/v1/rides/1"));
        var priceResponse = server.handleRequest(makeRequest("GET", "/api/v1/pricing/surge"));

        assertThat(new String(rideResponse.body())).contains("ride-service");
        assertThat(new String(priceResponse.body())).contains("pricing-service");
    }

    // ── Helpers ──

    private GatewayRequest makeRequest(String method, String path) throws Exception {
        return makeRequest(method, path, "127.0.0.1");
    }

    private GatewayRequest makeRequest(String method, String path, String ip) throws Exception {
        return new GatewayRequest(
                method, path, "HTTP/1.1",
                Map.of("host", List.of("localhost")),
                new byte[0],
                InetAddress.getByName(ip),
                12345
        );
    }
}
