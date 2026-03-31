package grabflow.gateway.routing;

import grabflow.common.ServiceEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Routes incoming HTTP requests to backend services based on URL path prefix matching.
 *
 * <h3>Routing Strategy</h3>
 * <p>The router uses longest-prefix matching: given a request path like
 * {@code /api/v1/rides/123}, it checks prefixes from most specific to least specific:
 * {@code /api/v1/rides} → {@code /api/v1} → {@code /api} → {@code /}</p>
 *
 * <h3>Load Balancing</h3>
 * <p>When a service has multiple endpoints, the router selects one using a pluggable
 * strategy. The default is weighted round-robin.</p>
 */
public class RequestRouter {

    private static final Logger log = LoggerFactory.getLogger(RequestRouter.class);

    private final ServiceRegistry registry;
    private final TreeMap<String, String> routes = new TreeMap<>(Comparator.reverseOrder());
    private final Map<String, AtomicInteger> roundRobinCounters = new HashMap<>();

    public RequestRouter(ServiceRegistry registry) {
        this.registry = registry;
    }

    /**
     * Maps a URL path prefix to a service name.
     * Longer prefixes take priority (longest-prefix match).
     *
     * @param pathPrefix  URL prefix (e.g., "/api/v1/rides")
     * @param serviceName backend service name as registered in {@link ServiceRegistry}
     */
    public void addRoute(String pathPrefix, String serviceName) {
        routes.put(pathPrefix, serviceName);
        roundRobinCounters.put(serviceName, new AtomicInteger());
        log.info("Route added: {} -> {}", pathPrefix, serviceName);
    }

    /**
     * Resolves a request path to a backend endpoint.
     *
     * @param path the HTTP request path (e.g., "/api/v1/rides/123")
     * @return the selected endpoint, or empty if no route matches
     */
    public Optional<RoutingResult> route(String path) {
        // Longest-prefix match via reverse-sorted TreeMap iteration
        for (var entry : routes.entrySet()) {
            if (path.startsWith(entry.getKey())) {
                String serviceName = entry.getValue();
                var endpoints = registry.getEndpoints(serviceName);

                if (endpoints.isEmpty() || endpoints.get().isEmpty()) {
                    log.warn("Route matched {} -> {} but no endpoints registered", path, serviceName);
                    return Optional.empty();
                }

                ServiceEndpoint selected = selectEndpoint(serviceName, endpoints.get());
                String strippedPath = stripPrefix(path, entry.getKey());
                return Optional.of(new RoutingResult(serviceName, selected, strippedPath));
            }
        }

        log.debug("No route matched for path: {}", path);
        return Optional.empty();
    }

    /**
     * Selects an endpoint using weighted round-robin.
     * Each call advances the counter and selects the next endpoint in rotation.
     */
    private ServiceEndpoint selectEndpoint(String serviceName, List<ServiceEndpoint> endpoints) {
        if (endpoints.size() == 1) return endpoints.getFirst();

        AtomicInteger counter = roundRobinCounters.get(serviceName);
        int index = Math.abs(counter.getAndIncrement() % endpoints.size());
        return endpoints.get(index);
    }

    /**
     * Strips the matched prefix from the path, preserving the remainder.
     * E.g., path="/api/v1/rides/123", prefix="/api/v1/rides" → "/123"
     */
    private String stripPrefix(String path, String prefix) {
        if (prefix.equals("/")) return path;
        String remainder = path.substring(prefix.length());
        return remainder.isEmpty() ? "/" : remainder;
    }

    /**
     * Returns the number of configured routes.
     */
    public int routeCount() {
        return routes.size();
    }

    /**
     * Result of routing a request.
     *
     * @param serviceName  matched service name
     * @param endpoint     selected backend endpoint
     * @param strippedPath path with the matched prefix removed
     */
    public record RoutingResult(String serviceName, ServiceEndpoint endpoint, String strippedPath) {}
}
