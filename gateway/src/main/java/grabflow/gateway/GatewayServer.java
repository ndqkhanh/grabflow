package grabflow.gateway;

import grabflow.common.GatewayRequest;
import grabflow.common.GatewayResponse;
import grabflow.common.ServiceEndpoint;
import grabflow.gateway.dns.DnsCache;
import grabflow.gateway.dns.DnsResolver;
import grabflow.gateway.net.DualStackListener;
import grabflow.gateway.net.GatewayEventLoop;
import grabflow.gateway.ratelimit.BloomFilter;
import grabflow.gateway.ratelimit.RateLimitFilter;
import grabflow.gateway.ratelimit.TokenBucket;
import grabflow.gateway.routing.RequestRouter;
import grabflow.gateway.routing.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;

/**
 * GrabFlow API Gateway -- the single entry point for all client traffic.
 *
 * <h3>Request Pipeline</h3>
 * <pre>
 *   Client Request
 *       │
 *       ▼
 *   ┌─────────────────┐
 *   │ DualStackListener│  Accept IPv4/IPv6 connections
 *   └────────┬────────┘
 *            ▼
 *   ┌─────────────────┐
 *   │ Rate Limit Filter│  Bloom filter + token bucket
 *   └────────┬────────┘
 *            ▼
 *   ┌─────────────────┐
 *   │ Request Router   │  Longest-prefix match → backend service
 *   └────────┬────────┘
 *            ▼
 *   ┌─────────────────┐
 *   │ Backend Service  │  (ride-service, location-service, etc.)
 *   └─────────────────┘
 * </pre>
 *
 * <h3>CS Fundamentals Demonstrated</h3>
 * <ul>
 *   <li><b>IPv4/IPv6</b>: Dual-stack listener with CIDR-based IP filtering</li>
 *   <li><b>DNS</b>: Custom DNS resolver with TTL-based caching for service discovery</li>
 *   <li><b>TLS 1.3</b>: ECDHE key exchange, ALPN negotiation, cert hot-rotation</li>
 *   <li><b>Bloom Filter</b>: Probabilistic DDoS IP filtering in O(k) time</li>
 * </ul>
 */
public class GatewayServer implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    private final GatewayConfig config;
    private final ServiceRegistry serviceRegistry;
    private final RequestRouter router;
    private final RateLimitFilter rateLimitFilter;
    private final DnsResolver dnsResolver;
    private GatewayEventLoop eventLoop;

    public GatewayServer(GatewayConfig config) {
        this.config = config;

        // Initialize DNS resolver with caching
        var dnsCache = new DnsCache(10_000); // max 10K cached domains
        this.dnsResolver = new DnsResolver(config.dnsServer(), dnsCache);

        // Initialize rate limiting: Bloom filter (DDoS) + token bucket (per-client)
        var bloomFilter = BloomFilter.forRateLimiting(config.expectedBadIps());
        var tokenBucket = new TokenBucket(config.rateLimitCapacity(), config.rateLimitRefillRate());
        this.rateLimitFilter = new RateLimitFilter(bloomFilter, tokenBucket);

        // Initialize routing
        this.serviceRegistry = new ServiceRegistry();
        this.router = new RequestRouter(serviceRegistry);

        // Configure default routes eagerly so handleRequest() works without start()
        configureRoutes();
    }

    /**
     * Starts the gateway server.
     */
    public void start() throws IOException {
        // Create NIO event loop with the request handler pipeline
        var listener = new DualStackListener(config.port());
        eventLoop = new GatewayEventLoop(listener, this::handleRequest);
        eventLoop.start();

        log.info("GrabFlow API Gateway started on port {}", config.port());
    }

    /**
     * The request handler pipeline: rate-limit → route → respond.
     */
    GatewayResponse handleRequest(GatewayRequest request) {
        String clientIp = request.clientIp().getHostAddress();

        // Step 1: Rate limiting
        var decision = rateLimitFilter.check(clientIp);
        return switch (decision) {
            case BLOCKED -> GatewayResponse.error(403, "Forbidden");
            case RATE_LIMITED -> GatewayResponse.error(429, "Too Many Requests");
            case ALLOWED -> routeRequest(request);
        };
    }

    private GatewayResponse routeRequest(GatewayRequest request) {
        // Step 2: Route to backend service
        var routingResult = router.route(request.path());
        if (routingResult.isEmpty()) {
            return GatewayResponse.error(404, "No route found for: " + request.path());
        }

        var result = routingResult.get();
        log.debug("{} {} -> {} ({})",
                request.method(), request.path(),
                result.serviceName(), result.endpoint().address());

        // Step 3: For now, return a gateway response indicating the route
        // In a full implementation, this would forward to the backend via gRPC
        String json = String.format(
                "{\"service\":\"%s\",\"endpoint\":\"%s\",\"path\":\"%s\",\"method\":\"%s\"}",
                result.serviceName(),
                result.endpoint().address(),
                result.strippedPath(),
                request.method()
        );
        return GatewayResponse.ok(json);
    }

    private void configureRoutes() {
        // Default GrabFlow service routes
        router.addRoute("/api/v1/rides", "ride-service");
        router.addRoute("/api/v1/locations", "location-service");
        router.addRoute("/api/v1/pricing", "pricing-service");
        router.addRoute("/api/v1/payments", "payment-service");
        router.addRoute("/api/v1/notifications", "notification-service");
        router.addRoute("/health", "gateway-health");

        // Register gateway health endpoint
        serviceRegistry.register("gateway-health", new ServiceEndpoint("localhost", config.port()));
    }

    /**
     * Returns the service registry for external service registration.
     */
    public ServiceRegistry getServiceRegistry() {
        return serviceRegistry;
    }

    /**
     * Returns the rate limit filter for adding IPs to the blocklist.
     */
    public RateLimitFilter getRateLimitFilter() {
        return rateLimitFilter;
    }

    /**
     * Returns the DNS resolver.
     */
    public DnsResolver getDnsResolver() {
        return dnsResolver;
    }

    /**
     * Returns the request router.
     */
    public RequestRouter getRouter() {
        return router;
    }

    @Override
    public void close() throws IOException {
        if (eventLoop != null) {
            eventLoop.close();
        }
        log.info("GrabFlow API Gateway stopped");
    }

    /**
     * Gateway configuration.
     */
    public record GatewayConfig(
            int port,
            String dnsServer,
            int expectedBadIps,
            int rateLimitCapacity,
            double rateLimitRefillRate
    ) {
        public static GatewayConfig defaults() {
            return new GatewayConfig(8080, "8.8.8.8", 10_000, 100, 10.0);
        }

        public static GatewayConfig withPort(int port) {
            return new GatewayConfig(port, "8.8.8.8", 10_000, 100, 10.0);
        }
    }

    /**
     * Main entry point.
     */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        var config = GatewayConfig.withPort(port);

        try (var server = new GatewayServer(config)) {
            server.start();
            log.info("Press Ctrl+C to stop");
            Thread.currentThread().join(); // block until interrupted
        }
    }
}
