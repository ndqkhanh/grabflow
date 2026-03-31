package grabflow.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP response returned through the gateway pipeline.
 *
 * @param statusCode  HTTP status code (200, 404, 503, etc.)
 * @param headers     Response headers
 * @param body        Response body bytes
 */
public record GatewayResponse(
        int statusCode,
        Map<String, String> headers,
        byte[] body
) {
    public static GatewayResponse of(int statusCode, String contentType, byte[] body) {
        var headers = new LinkedHashMap<String, String>();
        headers.put("content-type", contentType);
        headers.put("content-length", String.valueOf(body.length));
        return new GatewayResponse(statusCode, headers, body);
    }

    public static GatewayResponse ok(String json) {
        return of(200, "application/json", json.getBytes());
    }

    public static GatewayResponse error(int statusCode, String message) {
        var json = "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        return of(statusCode, "application/json", json.getBytes());
    }
}
