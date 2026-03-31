package grabflow.location;

import grabflow.common.DriverLocation;
import grabflow.common.NearbyDriversRequest;
import grabflow.common.NearbyDriversResponse;
import grabflow.location.geo.H3Index;
import grabflow.location.geo.SpatialIndex;
import grabflow.location.ipc.SharedLocationBuffer;
import grabflow.location.scheduler.DriverTimeoutManager;
import grabflow.location.tracking.Crc32Checksum;
import grabflow.location.tracking.GpsPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Location Service — real-time driver GPS tracking and nearest-driver queries.
 *
 * <h3>Architecture</h3>
 * <pre>
 *   Driver GPS (WebSocket)
 *       │
 *       ▼
 *   ┌─────────────────┐
 *   │ CRC-32 Verify    │  Detect corrupted packets
 *   └────────┬────────┘
 *            ▼
 *   ┌─────────────────┐
 *   │ H3 Cell Compute  │  lat/lng → hexagonal cell ID
 *   └────────┬────────┘
 *            ▼
 *   ┌─────────────────┐     ┌──────────────────┐
 *   │ Spatial Index    │────▶│ Nearest Driver    │
 *   │ Update           │     │ Query Handler     │
 *   └────────┬────────┘     └──────────────────┘
 *            │
 *       ┌────┴────┐
 *       ▼         ▼
 *   ┌────────┐ ┌──────────┐
 *   │ IPC    │ │ Timeout  │
 *   │ Buffer │ │ Manager  │
 *   └────────┘ └──────────┘
 * </pre>
 *
 * <h3>CS Fundamentals Demonstrated</h3>
 * <ul>
 *   <li><b>CRC-32</b>: From-scratch table-driven checksum on every GPS packet</li>
 *   <li><b>H3 Geospatial</b>: Hexagonal grid indexing with k-ring neighbor traversal</li>
 *   <li><b>IPC</b>: Memory-mapped ring buffer for zero-copy write/read path sharing</li>
 *   <li><b>Timing Wheel</b>: O(1) driver inactivity timeout scheduling</li>
 * </ul>
 */
public class LocationServer implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(LocationServer.class);

    private final LocationConfig config;
    private final SpatialIndex spatialIndex;
    private final DriverTimeoutManager timeoutManager;
    private final SharedLocationBuffer ipcBuffer;
    private final Crc32Checksum crc;
    private final AtomicLong packetsProcessed = new AtomicLong();
    private final AtomicLong packetsCorrupted = new AtomicLong();

    public LocationServer(LocationConfig config) throws IOException {
        this.config = config;
        this.crc = new Crc32Checksum();
        this.spatialIndex = new SpatialIndex();
        this.timeoutManager = new DriverTimeoutManager(
                config.driverTimeoutMs(),
                this::onDriverTimeout
        );
        this.ipcBuffer = new SharedLocationBuffer(
                config.ipcDirectory().resolve("location-buffer.dat"),
                config.ipcBufferCapacity()
        );
    }

    /**
     * Starts the location service (timeout ticker thread).
     */
    public void start() {
        timeoutManager.start();
        log.info("Location service started (timeout={}ms, ipcCapacity={})",
                config.driverTimeoutMs(), config.ipcBufferCapacity());
    }

    /**
     * Processes a raw GPS packet from a driver.
     * This is the main ingestion pipeline entry point.
     *
     * @param rawData serialized GPS packet bytes (with CRC-32 trailer)
     */
    public void processGpsPacket(byte[] rawData) {
        GpsPacket packet;
        try {
            packet = GpsPacket.fromBytes(rawData, crc);
        } catch (GpsPacket.CorruptedPacketException e) {
            packetsCorrupted.incrementAndGet();
            log.warn("Corrupted GPS packet: expected CRC={}, actual CRC={}",
                    Integer.toHexString(e.getExpected()),
                    Integer.toHexString(e.getActual()));
            return;
        }

        // Compute H3 cell for this coordinate
        long h3CellId = H3Index.latLngToCell(packet.lat(), packet.lng(), 9);

        // Create domain object
        var location = new DriverLocation(
                packet.driverId(), packet.lat(), packet.lng(),
                packet.heading(), packet.speed(), packet.timestamp(), h3CellId
        );

        // Update spatial index
        spatialIndex.update(location);

        // Write to IPC buffer for the query path
        ipcBuffer.write(location);

        // Reset heartbeat timer
        timeoutManager.heartbeat(packet.driverId());

        packetsProcessed.incrementAndGet();
    }

    /**
     * Finds nearby drivers (the query path).
     */
    public NearbyDriversResponse findNearbyDrivers(NearbyDriversRequest request) {
        long startNanos = System.nanoTime();

        List<DriverLocation> drivers = spatialIndex.findNearest(
                request.lat(), request.lng(),
                request.radiusMeters(), request.maxResults()
        );

        long queryTimeMs = (System.nanoTime() - startNanos) / 1_000_000;
        return new NearbyDriversResponse(drivers, queryTimeMs);
    }

    /**
     * Called when a driver's heartbeat timer expires.
     */
    private void onDriverTimeout(String driverId) {
        spatialIndex.remove(driverId);
        log.info("Driver {} timed out and removed from spatial index", driverId);
    }

    // ── Accessors ──

    public SpatialIndex getSpatialIndex() { return spatialIndex; }
    public DriverTimeoutManager getTimeoutManager() { return timeoutManager; }
    public SharedLocationBuffer getIpcBuffer() { return ipcBuffer; }
    public long getPacketsProcessed() { return packetsProcessed.get(); }
    public long getPacketsCorrupted() { return packetsCorrupted.get(); }

    @Override
    public void close() throws IOException {
        timeoutManager.stop();
        ipcBuffer.close();
        log.info("Location service stopped (processed={}, corrupted={})",
                packetsProcessed.get(), packetsCorrupted.get());
    }

    /**
     * Location service configuration.
     */
    public record LocationConfig(
            int port,
            long driverTimeoutMs,
            int ipcBufferCapacity,
            Path ipcDirectory
    ) {
        public static LocationConfig defaults() {
            return new LocationConfig(8082, 30_000, 100_000, Path.of("/tmp/grabflow"));
        }

        public static LocationConfig forTesting(Path tempDir) {
            return new LocationConfig(0, 5_000, 1000, tempDir);
        }
    }
}
