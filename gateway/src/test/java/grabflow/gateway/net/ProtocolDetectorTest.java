package grabflow.gateway.net;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class ProtocolDetectorTest {

    @Test
    void detectsTlsHandshake() {
        // TLS record: content_type=0x16 (Handshake), version=0x03 0x01 (TLS 1.0 compat)
        ByteBuffer buf = ByteBuffer.wrap(new byte[]{0x16, 0x03, 0x01, 0x00, 0x05});
        assertThat(ProtocolDetector.detect(buf)).isEqualTo(ProtocolDetector.Protocol.TLS);
    }

    @Test
    void detectsTls12Handshake() {
        // TLS 1.2: version=0x03 0x03
        ByteBuffer buf = ByteBuffer.wrap(new byte[]{0x16, 0x03, 0x03, 0x00, 0x10});
        assertThat(ProtocolDetector.detect(buf)).isEqualTo(ProtocolDetector.Protocol.TLS);
    }

    @Test
    void detectsTls13Handshake() {
        // TLS 1.3 still uses 0x03 0x03 in record layer for compatibility
        ByteBuffer buf = ByteBuffer.wrap(new byte[]{0x16, 0x03, 0x03, 0x01, 0x00});
        assertThat(ProtocolDetector.detect(buf)).isEqualTo(ProtocolDetector.Protocol.TLS);
    }

    @Test
    void detectsHttp2PriorKnowledge() {
        // HTTP/2 connection preface starts with "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
        byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.wrap(preface);
        assertThat(ProtocolDetector.detect(buf)).isEqualTo(ProtocolDetector.Protocol.HTTP2_PRIOR_KNOWLEDGE);
    }

    @Test
    void detectsGetRequest() {
        ByteBuffer buf = ByteBuffer.wrap("GET / HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(ProtocolDetector.detect(buf)).isEqualTo(ProtocolDetector.Protocol.HTTP1);
    }

    @Test
    void detectsPostRequest() {
        ByteBuffer buf = ByteBuffer.wrap("POST /api HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(ProtocolDetector.detect(buf)).isEqualTo(ProtocolDetector.Protocol.HTTP1);
    }

    @Test
    void detectsPutRequest() {
        ByteBuffer buf = ByteBuffer.wrap("PUT /res HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(ProtocolDetector.detect(buf)).isEqualTo(ProtocolDetector.Protocol.HTTP1);
    }

    @Test
    void detectsDeleteRequest() {
        ByteBuffer buf = ByteBuffer.wrap("DELETE /res/1 HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(ProtocolDetector.detect(buf)).isEqualTo(ProtocolDetector.Protocol.HTTP1);
    }

    @Test
    void detectsPatchRequest() {
        ByteBuffer buf = ByteBuffer.wrap("PATCH /res HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(ProtocolDetector.detect(buf)).isEqualTo(ProtocolDetector.Protocol.HTTP1);
    }

    @Test
    void detectsHeadRequest() {
        ByteBuffer buf = ByteBuffer.wrap("HEAD / HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(ProtocolDetector.detect(buf)).isEqualTo(ProtocolDetector.Protocol.HTTP1);
    }

    @Test
    void detectsOptionsRequest() {
        ByteBuffer buf = ByteBuffer.wrap("OPTIONS * HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(ProtocolDetector.detect(buf)).isEqualTo(ProtocolDetector.Protocol.HTTP1);
    }

    @Test
    void detectsConnectRequest() {
        ByteBuffer buf = ByteBuffer.wrap("CONNECT host:443 HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(ProtocolDetector.detect(buf)).isEqualTo(ProtocolDetector.Protocol.HTTP1);
    }

    @Test
    void returnsUnknownForTooFewBytes() {
        ByteBuffer buf = ByteBuffer.wrap(new byte[]{0x16, 0x03});
        assertThat(ProtocolDetector.detect(buf)).isEqualTo(ProtocolDetector.Protocol.UNKNOWN);
    }

    @Test
    void returnsUnknownForEmptyBuffer() {
        ByteBuffer buf = ByteBuffer.allocate(0);
        assertThat(ProtocolDetector.detect(buf)).isEqualTo(ProtocolDetector.Protocol.UNKNOWN);
    }

    @Test
    void returnsUnknownForRandomBytes() {
        ByteBuffer buf = ByteBuffer.wrap(new byte[]{0x00, 0x01, 0x02, 0x03});
        assertThat(ProtocolDetector.detect(buf)).isEqualTo(ProtocolDetector.Protocol.UNKNOWN);
    }

    @Test
    void doesNotConsumeBufferPosition() {
        ByteBuffer buf = ByteBuffer.wrap("GET / HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
        int posBefore = buf.position();
        ProtocolDetector.detect(buf);
        assertThat(buf.position()).isEqualTo(posBefore);
    }

    @Test
    void worksWithBufferOffset() {
        // Buffer with data starting at a non-zero position
        ByteBuffer buf = ByteBuffer.allocate(20);
        buf.put(new byte[]{0x00, 0x00}); // 2 bytes of padding
        buf.put("GET /".getBytes(StandardCharsets.US_ASCII));
        buf.position(2); // skip padding
        buf.limit(7);
        assertThat(ProtocolDetector.detect(buf)).isEqualTo(ProtocolDetector.Protocol.HTTP1);
    }
}
