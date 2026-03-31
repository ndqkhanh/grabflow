package grabflow.common;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

/**
 * Parsed HTTP request flowing through the gateway pipeline.
 *
 * @param method      HTTP method (GET, POST, PUT, DELETE, etc.)
 * @param path        Request path (e.g., "/api/v1/rides")
 * @param httpVersion Protocol version (e.g., "HTTP/1.1", "HTTP/2")
 * @param headers     Header map (header names are case-insensitive, stored lowercase)
 * @param body        Request body bytes (empty array if no body)
 * @param clientIp    Client's IP address (IPv4 or IPv6)
 * @param clientPort  Client's source port
 */
public record GatewayRequest(
        String method,
        String path,
        String httpVersion,
        Map<String, List<String>> headers,
        byte[] body,
        InetAddress clientIp,
        int clientPort
) {
    public String header(String name) {
        var values = headers.get(name.toLowerCase());
        return values != null && !values.isEmpty() ? values.getFirst() : null;
    }

    public boolean isWebSocketUpgrade() {
        return "websocket".equalsIgnoreCase(header("upgrade"))
                && "upgrade".equalsIgnoreCase(header("connection"));
    }
}
