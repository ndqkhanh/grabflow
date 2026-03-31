package grabflow.common;

import java.net.InetAddress;

/**
 * A backend service endpoint that the gateway can route requests to.
 *
 * @param host   Hostname or IP address
 * @param port   Service port
 * @param weight Routing weight for weighted load balancing (higher = more traffic)
 */
public record ServiceEndpoint(String host, int port, int weight) {

    public ServiceEndpoint(String host, int port) {
        this(host, port, 1);
    }

    public String address() {
        return host + ":" + port;
    }
}
