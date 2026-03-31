package grabflow.gateway.net;

import java.nio.ByteBuffer;

/**
 * Detects the application-layer protocol from the first bytes of a connection.
 *
 * <h3>Detection Logic</h3>
 * <ul>
 *   <li><b>HTTP/2</b>: Starts with the connection preface "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
 *       (24 bytes, RFC 7540 Section 3.5). The first 3 bytes are "PRI" (0x50 0x52 0x49).</li>
 *   <li><b>TLS</b>: Starts with a TLS record header. First byte is the content type
 *       (0x16 = Handshake), followed by version bytes (0x03 0x01 for TLS 1.0 compat,
 *       0x03 0x03 for TLS 1.2/1.3). Note: TLS 1.3 still uses 0x03 0x01 in the record layer
 *       for backwards compatibility.</li>
 *   <li><b>HTTP/1.x</b>: Starts with an HTTP method: "GET ", "POST", "PUT ", "HEAD",
 *       "DELETE", "PATCH", "OPTIONS", "CONNECT".</li>
 *   <li><b>WebSocket</b>: Detected after HTTP upgrade, not from initial bytes.</li>
 * </ul>
 */
public class ProtocolDetector {

    /** HTTP/2 connection preface: "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n" */
    private static final byte[] H2_PREFACE_PREFIX = {0x50, 0x52, 0x49}; // "PRI"

    /** TLS handshake content type */
    private static final byte TLS_HANDSHAKE = 0x16;

    /**
     * Detects the protocol from the first bytes of a connection.
     * Requires at least 3 bytes in the buffer (position 0).
     *
     * @param buf buffer with initial connection bytes (must have at least 3 bytes remaining)
     * @return detected protocol
     */
    public static Protocol detect(ByteBuffer buf) {
        if (buf.remaining() < 3) {
            return Protocol.UNKNOWN;
        }

        int pos = buf.position();
        byte b0 = buf.get(pos);
        byte b1 = buf.get(pos + 1);
        byte b2 = buf.get(pos + 2);

        // TLS: starts with handshake content type (0x16) + version
        if (b0 == TLS_HANDSHAKE && b1 == 0x03 && (b2 >= 0x01 && b2 <= 0x04)) {
            return Protocol.TLS;
        }

        // HTTP/2 direct (h2c / prior knowledge): "PRI"
        if (b0 == H2_PREFACE_PREFIX[0] && b1 == H2_PREFACE_PREFIX[1] && b2 == H2_PREFACE_PREFIX[2]) {
            return Protocol.HTTP2_PRIOR_KNOWLEDGE;
        }

        // HTTP/1.x: starts with a method name (ASCII uppercase letter)
        if (isHttpMethodStart(b0, b1, b2)) {
            return Protocol.HTTP1;
        }

        return Protocol.UNKNOWN;
    }

    /**
     * Checks if the first bytes look like an HTTP method.
     * Common methods: GET, POST, PUT, HEAD, DELETE, PATCH, OPTIONS, CONNECT
     */
    private static boolean isHttpMethodStart(byte b0, byte b1, byte b2) {
        // All HTTP methods start with uppercase ASCII
        if (b0 < 'A' || b0 > 'Z') return false;

        return switch (b0) {
            case 'G' -> b1 == 'E' && b2 == 'T';           // GET
            case 'P' -> (b1 == 'O' && b2 == 'S')           // POST
                    || (b1 == 'U' && b2 == 'T')             // PUT
                    || (b1 == 'A' && b2 == 'T');             // PATCH
            case 'H' -> b1 == 'E' && b2 == 'A';             // HEAD
            case 'D' -> b1 == 'E' && b2 == 'L';             // DELETE
            case 'O' -> b1 == 'P' && b2 == 'T';             // OPTIONS
            case 'C' -> b1 == 'O' && b2 == 'N';             // CONNECT
            default -> false;
        };
    }

    public enum Protocol {
        /** TLS-encrypted connection (perform TLS handshake, then re-detect) */
        TLS,
        /** HTTP/2 with prior knowledge (no TLS, direct h2c) */
        HTTP2_PRIOR_KNOWLEDGE,
        /** HTTP/1.0 or HTTP/1.1 plaintext */
        HTTP1,
        /** Cannot determine protocol from available bytes */
        UNKNOWN
    }
}
