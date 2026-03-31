package grabflow.location.tracking;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Immutable GPS data frame with CRC-32 integrity protection.
 *
 * <p>A {@code GpsPacket} is a Java record that captures a single GPS telemetry
 * snapshot from a driver and serialises it to/from a compact binary wire format.
 * CRC-32 integrity (see {@link Crc32Checksum}) ensures that corrupted frames are
 * detected immediately on deserialisation rather than silently propagating bad
 * coordinates into downstream services.
 *
 * <h2>Wire Format</h2>
 * <pre>
 *   Offset  Length  Field
 *   ------  ------  -----
 *   0       4       driverId length (big-endian int)
 *   4       N       driverId UTF-8 bytes
 *   4+N     8       lat      (big-endian double)
 *   12+N    8       lng      (big-endian double)
 *   20+N    8       heading  (big-endian double)
 *   28+N    8       speed    (big-endian double)
 *   36+N    8       timestamp (big-endian long)
 *   44+N    4       CRC-32 of all preceding bytes (big-endian int)
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   Crc32Checksum crc = new Crc32Checksum();
 *
 *   // Create a packet — CRC is computed automatically
 *   GpsPacket p = GpsPacket.create("driver-42", 10.762622, 106.660172,
 *                                   270.0, 30.5, System.currentTimeMillis(), crc);
 *
 *   // Serialise to bytes for transmission
 *   byte[] wire = p.toBytes();
 *
 *   // Deserialise and verify CRC on the other end
 *   GpsPacket received = GpsPacket.fromBytes(wire, crc);
 * }</pre>
 *
 * @param driverId  unique driver identifier (non-null, may be empty)
 * @param lat       latitude in decimal degrees
 * @param lng       longitude in decimal degrees
 * @param heading   bearing in degrees [0, 360)
 * @param speed     speed in km/h
 * @param timestamp epoch-milliseconds UTC
 * @param crc32     CRC-32 checksum of the serialised payload (all fields except
 *                  this one)
 */
public record GpsPacket(
        String driverId,
        double lat,
        double lng,
        double heading,
        double speed,
        long timestamp,
        int crc32
) {

    /**
     * Thrown when {@link #fromBytes} detects a CRC mismatch, indicating that the
     * received byte stream has been corrupted or tampered with.
     */
    public static final class CorruptedPacketException extends RuntimeException {

        private final int expected;
        private final int actual;

        /**
         * @param expected the CRC value stored in the packet
         * @param actual   the CRC value computed from the received bytes
         */
        public CorruptedPacketException(int expected, int actual) {
            super(String.format(
                    "CRC mismatch: expected 0x%08X but computed 0x%08X",
                    Integer.toUnsignedLong(expected),
                    Integer.toUnsignedLong(actual)));
            this.expected = expected;
            this.actual = actual;
        }

        /** @return the CRC stored in the received packet */
        public int getExpected() {
            return expected;
        }

        /** @return the CRC computed from the received bytes */
        public int getActual() {
            return actual;
        }
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code GpsPacket} and automatically computes its CRC-32.
     *
     * @param driverId  unique driver identifier
     * @param lat       latitude in decimal degrees
     * @param lng       longitude in decimal degrees
     * @param heading   bearing in degrees
     * @param speed     speed in km/h
     * @param timestamp epoch-milliseconds UTC
     * @param crc       checksum engine used to compute the CRC
     * @return a new {@code GpsPacket} with a valid {@code crc32} field
     */
    public static GpsPacket create(
            String driverId,
            double lat,
            double lng,
            double heading,
            double speed,
            long timestamp,
            Crc32Checksum crc) {

        byte[] payload = serializePayload(driverId, lat, lng, heading, speed, timestamp);
        int checksum = crc.compute(payload);
        return new GpsPacket(driverId, lat, lng, heading, speed, timestamp, checksum);
    }

    // -------------------------------------------------------------------------
    // Serialisation
    // -------------------------------------------------------------------------

    /**
     * Serialises this packet to its wire format.
     *
     * <p>The returned array contains all fields followed by the 4-byte CRC-32
     * stored big-endian:
     * <pre>
     *   [driverId-len(4)][driverId-bytes(N)][lat(8)][lng(8)][heading(8)][speed(8)][timestamp(8)][crc32(4)]
     * </pre>
     *
     * @return a freshly allocated byte array representing the wire frame
     */
    public byte[] toBytes() {
        byte[] idBytes = driverId.getBytes(StandardCharsets.UTF_8);
        // 4 (id length) + N (id bytes) + 8*4 (doubles) + 8 (long) + 4 (crc)
        int capacity = 4 + idBytes.length + 8 + 8 + 8 + 8 + 8 + 4;
        ByteBuffer buf = ByteBuffer.allocate(capacity);
        buf.putInt(idBytes.length);
        buf.put(idBytes);
        buf.putDouble(lat);
        buf.putDouble(lng);
        buf.putDouble(heading);
        buf.putDouble(speed);
        buf.putLong(timestamp);
        buf.putInt(crc32);
        return buf.array();
    }

    /**
     * Deserialises a {@code GpsPacket} from wire bytes and verifies its CRC-32.
     *
     * @param data the raw wire frame produced by {@link #toBytes()}
     * @param crc  checksum engine used to verify integrity
     * @return a fully-populated {@code GpsPacket}
     * @throws CorruptedPacketException if the stored CRC does not match the
     *                                   CRC computed from the payload bytes
     */
    public static GpsPacket fromBytes(byte[] data, Crc32Checksum crc) {
        ByteBuffer buf = ByteBuffer.wrap(data);

        int idLen = buf.getInt();
        byte[] idBytes = new byte[idLen];
        buf.get(idBytes);
        String driverId = new String(idBytes, StandardCharsets.UTF_8);

        double lat = buf.getDouble();
        double lng = buf.getDouble();
        double heading = buf.getDouble();
        double speed = buf.getDouble();
        long timestamp = buf.getLong();
        int storedCrc = buf.getInt();

        // The payload for CRC verification is everything before the CRC field.
        int payloadLength = data.length - 4;
        int computedCrc = crc.compute(data, 0, payloadLength);

        if (storedCrc != computedCrc) {
            throw new CorruptedPacketException(storedCrc, computedCrc);
        }

        return new GpsPacket(driverId, lat, lng, heading, speed, timestamp, storedCrc);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Serialises all fields except {@code crc32} into a byte array so that the
     * CRC can be computed before the record is constructed.
     */
    private static byte[] serializePayload(
            String driverId,
            double lat,
            double lng,
            double heading,
            double speed,
            long timestamp) {

        byte[] idBytes = driverId.getBytes(StandardCharsets.UTF_8);
        int capacity = 4 + idBytes.length + 8 + 8 + 8 + 8 + 8;
        ByteBuffer buf = ByteBuffer.allocate(capacity);
        buf.putInt(idBytes.length);
        buf.put(idBytes);
        buf.putDouble(lat);
        buf.putDouble(lng);
        buf.putDouble(heading);
        buf.putDouble(speed);
        buf.putLong(timestamp);
        return buf.array();
    }
}
