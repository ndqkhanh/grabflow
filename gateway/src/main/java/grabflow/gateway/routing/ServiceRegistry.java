package grabflow.gateway.routing;

import grabflow.common.ServiceEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry mapping service names to their backend endpoints.
 *
 * <p>In a production system, this would integrate with a service discovery
 * mechanism (DNS, Consul, etcd, Kubernetes services). For GrabFlow, services
 * register themselves on startup and the gateway routes based on this registry.</p>
 */
public class ServiceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ServiceRegistry.class);

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ServiceEndpoint>> services =
            new ConcurrentHashMap<>();

    /**
     * Registers an endpoint for a service.
     * Multiple endpoints per service are supported for load balancing.
     */
    public void register(String serviceName, ServiceEndpoint endpoint) {
        services.computeIfAbsent(serviceName, k -> new CopyOnWriteArrayList<>())
                .add(endpoint);
        log.info("Registered endpoint for {}: {}", serviceName, endpoint.address());
    }

    /**
     * Removes an endpoint from a service.
     */
    public void deregister(String serviceName, ServiceEndpoint endpoint) {
        var endpoints = services.get(serviceName);
        if (endpoints != null) {
            endpoints.remove(endpoint);
            if (endpoints.isEmpty()) {
                services.remove(serviceName);
            }
            log.info("Deregistered endpoint for {}: {}", serviceName, endpoint.address());
        }
    }

    /**
     * Returns all endpoints for a service.
     */
    public Optional<List<ServiceEndpoint>> getEndpoints(String serviceName) {
        var endpoints = services.get(serviceName);
        if (endpoints == null || endpoints.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(endpoints));
    }

    /**
     * Returns all registered service names.
     */
    public List<String> serviceNames() {
        return List.copyOf(services.keySet());
    }

    /**
     * Returns total number of registered services.
     */
    public int serviceCount() {
        return services.size();
    }

    /**
     * Returns total number of endpoints across all services.
     */
    public int endpointCount() {
        return services.values().stream().mapToInt(List::size).sum();
    }
}
