package grabflow.location.tracking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link GpsPacket}.
 */
class GpsPacketTest {

    private Crc32Checksum crc;

    @BeforeEach
    void setUp() {
        crc = new Crc32Checksum();
    }

    /** Serialise then deserialise — all fields must survive the round-trip exactly. */
    @Test
    void serializeAndDeserializeRoundTrip() {
        GpsPacket original = GpsPacket.create(
                "driver-42", 10.762622, 106.660172, 270.0, 30.5,
                1_700_000_000_000L, crc);

        byte[] wire = original.toBytes();
        GpsPacket restored = GpsPacket.fromBytes(wire, crc);

        assertThat(restored.driverId()).isEqualTo(original.driverId());
        assertThat(restored.lat()).isCloseTo(original.lat(), within(1e-9));
        assertThat(restored.lng()).isCloseTo(original.lng(), within(1e-9));
        assertThat(restored.heading()).isCloseTo(original.heading(), within(1e-9));
        assertThat(restored.speed()).isCloseTo(original.speed(), within(1e-9));
        assertThat(restored.timestamp()).isEqualTo(original.timestamp());
        assertThat(restored.crc32()).isEqualTo(original.crc32());
    }

    /** Flipping a single byte in the wire data must cause {@code fromBytes} to throw. */
    @Test
    void corruptedPacketThrows() {
        GpsPacket original = GpsPacket.create(
                "driver-99", 1.0, 2.0, 0.0, 50.0, 1_000L, crc);

        byte[] wire = original.toBytes();
        wire[5] ^= 0xFF; // corrupt one byte inside the driverId area

        assertThatThrownBy(() -> GpsPacket.fromBytes(wire, crc))
                .isInstanceOf(GpsPacket.CorruptedPacketException.class);
    }

    /** {@code GpsPacket.create()} must produce a packet whose CRC verifies correctly. */
    @Test
    void factoryComputesCrcAutomatically() {
        GpsPacket packet = GpsPacket.create(
                "auto-crc", 3.14, 2.71, 90.0, 60.0, 999L, crc);

        byte[] wire = packet.toBytes();
        // The last 4 bytes are the stored CRC; everything before is the payload.
        int payloadLen = wire.length - 4;
        int computedCrc = crc.compute(wire, 0, payloadLen);

        assertThat(packet.crc32()).isEqualTo(computedCrc);
    }

    /** An empty {@code driverId} string must be serialised and recovered without error. */
    @Test
    void emptyDriverIdHandled() {
        GpsPacket original = GpsPacket.create("", 0.0, 0.0, 0.0, 0.0, 0L, crc);
        byte[] wire = original.toBytes();
        GpsPacket restored = GpsPacket.fromBytes(wire, crc);

        assertThat(restored.driverId()).isEqualTo("");
    }

    /** A 32-character {@code driverId} must be serialised and recovered without truncation. */
    @Test
    void longDriverIdHandled() {
        String longId = "A".repeat(32);
        GpsPacket original = GpsPacket.create(longId, 1.1, 2.2, 3.3, 4.4, 12345L, crc);
        byte[] wire = original.toBytes();
        GpsPacket restored = GpsPacket.fromBytes(wire, crc);

        assertThat(restored.driverId()).isEqualTo(longId);
        assertThat(restored.driverId()).hasSize(32);
    }

    /**
     * Verifies the exact byte layout of the wire format for a simple known packet.
     *
     * <p>Wire format:
     * <pre>
     *   [0..3]   driverId length (int, big-endian)
     *   [4..4+N) driverId UTF-8 bytes
     *   [4+N ..) lat, lng, heading, speed (double, big-endian), timestamp (long, big-endian)
     *   [last 4] CRC-32 (int, big-endian)
     * </pre>
     */
    @Test
    void wireFormatLayout() {
        String driverId = "ab";
        byte[] idBytes = driverId.getBytes(StandardCharsets.UTF_8);
        int idLen = idBytes.length; // 2

        GpsPacket packet = GpsPacket.create(
                driverId, 10.0, 20.0, 90.0, 55.0, 100L, crc);
        byte[] wire = packet.toBytes();

        ByteBuffer buf = ByteBuffer.wrap(wire);

        // Bytes 0-3: driverId length = 2
        assertThat(buf.getInt()).isEqualTo(idLen);

        // Bytes 4-5: driverId "ab"
        byte[] readId = new byte[idLen];
        buf.get(readId);
        assertThat(new String(readId, StandardCharsets.UTF_8)).isEqualTo(driverId);

        // Next 8: lat
        assertThat(buf.getDouble()).isCloseTo(10.0, within(1e-9));
        // Next 8: lng
        assertThat(buf.getDouble()).isCloseTo(20.0, within(1e-9));
        // Next 8: heading
        assertThat(buf.getDouble()).isCloseTo(90.0, within(1e-9));
        // Next 8: speed
        assertThat(buf.getDouble()).isCloseTo(55.0, within(1e-9));
        // Next 8: timestamp
        assertThat(buf.getLong()).isEqualTo(100L);
        // Last 4: CRC-32
        assertThat(buf.getInt()).isEqualTo(packet.crc32());

        // No remaining bytes
        assertThat(buf.hasRemaining()).isFalse();
    }
}
