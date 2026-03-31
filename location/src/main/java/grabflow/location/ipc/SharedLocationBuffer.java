package grabflow.location.ipc;

import grabflow.common.DriverLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Memory-mapped ring buffer for zero-copy IPC between the GPS ingestion path
 * and the nearest-driver query path.
 *
 * <h3>CS Fundamental: Inter-Process Communication (IPC)</h3>
 * <p>Traditional IPC mechanisms (pipes, sockets, message queues) require data to be
 * copied from the sender's address space into kernel space, then from kernel space
 * into the receiver's address space — two copies per message. Memory-mapped files
 * eliminate both copies: the OS maps the same physical pages into both processes'
 * virtual address spaces, so a write by one process is instantly visible to the other.</p>
 *
 * <h3>How It Works</h3>
 * <pre>
 *   ┌──────────────────────────────────────────────────┐
 *   │           Memory-Mapped File (on disk)            │
 *   │                                                    │
 *   │  ┌────────┬───────┬───────┬───────┬───────┬────┐ │
 *   │  │ Header │ Slot 0│ Slot 1│ Slot 2│  ...  │Slot│ │
 *   │  │writePos│       │       │       │       │ N-1│ │
 *   │  └────────┴───────┴───────┴───────┴───────┴────┘ │
 *   │       ▲                                            │
 *   └───────┼────────────────────────────────────────────┘
 *           │
 *   ┌───────┴────────┐    ┌──────────────────┐
 *   │  Writer Process │    │  Reader Process   │
 *   │  (GPS Ingest)   │    │  (Query Handler)  │
 *   │  MappedByteBuffer│   │  MappedByteBuffer │
 *   │  READ_WRITE     │    │  READ_ONLY        │
 *   └────────────────┘    └──────────────────┘
 * </pre>
 *
 * <h3>Ring Buffer Protocol</h3>
 * <ul>
 *   <li>Header (8 bytes): atomic write position counter</li>
 *   <li>Entries: fixed-size slots of {@value SLOT_SIZE} bytes each</li>
 *   <li>Write pointer wraps: {@code slotIndex = writePos % capacity}</li>
 *   <li>Readers track their own read position and catch up by comparing with writePos</li>
 * </ul>
 *
 * <p>This is the same single-producer/multi-consumer ring buffer pattern used in
 * the Linux kernel's {@code io_uring}, LMAX Disruptor, and shared-memory logging
 * systems.</p>
 *
 * <h3>Comparison with Other IPC Mechanisms</h3>
 * <table>
 *   <tr><th>Mechanism</th><th>Copies</th><th>Latency</th><th>Cross-Machine</th></tr>
 *   <tr><td>Pipe/Socket</td><td>2 (user→kernel→user)</td><td>~μs</td><td>Yes (socket)</td></tr>
 *   <tr><td>Shared Memory (shmget)</td><td>0</td><td>~ns</td><td>No</td></tr>
 *   <tr><td>Memory-Mapped File</td><td>0</td><td>~ns (cached)</td><td>No</td></tr>
 *   <tr><td>This implementation</td><td>0</td><td>~ns</td><td>No</td></tr>
 * </table>
 */
public class SharedLocationBuffer implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SharedLocationBuffer.class);

    /** Header size: 8 bytes for the write position counter */
    static final int HEADER_SIZE = 8;

    /** Fixed size per entry slot (128 bytes for cache-line alignment) */
    static final int SLOT_SIZE = 128;

    /** Maximum driver ID length in bytes */
    static final int MAX_DRIVER_ID_LENGTH = 32;

    private final int capacity;
    private final MappedByteBuffer buffer;
    private final FileChannel channel;
    private final RandomAccessFile file;

    /**
     * Opens or creates a memory-mapped ring buffer backed by a file.
     *
     * @param filePath path to the backing file
     * @param capacity number of entry slots
     * @param mode     READ_WRITE for writers, READ_ONLY for readers
     */
    public SharedLocationBuffer(Path filePath, int capacity, FileChannel.MapMode mode) throws IOException {
        this.capacity = capacity;
        long fileSize = HEADER_SIZE + (long) capacity * SLOT_SIZE;

        String fileMode = mode == FileChannel.MapMode.READ_WRITE ? "rw" : "r";
        this.file = new RandomAccessFile(filePath.toFile(), fileMode);

        if (mode == FileChannel.MapMode.READ_WRITE) {
            file.setLength(fileSize);
        }

        this.channel = file.getChannel();
        this.buffer = channel.map(mode, 0, fileSize);
        log.info("Mapped location buffer: {} ({} slots, {} bytes, mode={})",
                filePath, capacity, fileSize, mode == FileChannel.MapMode.READ_WRITE ? "RW" : "RO");
    }

    /**
     * Convenience constructor for read-write mode.
     */
    public SharedLocationBuffer(Path filePath, int capacity) throws IOException {
        this(filePath, capacity, FileChannel.MapMode.READ_WRITE);
    }

    /**
     * Writes a driver location into the next available slot.
     * Advances the write position atomically.
     *
     * <p>Entry layout (128 bytes):</p>
     * <pre>
     *   Bytes  0- 3: driverId length (int)
     *   Bytes  4-35: driverId (UTF-8, zero-padded to 32 bytes)
     *   Bytes 36-43: lat (double)
     *   Bytes 44-51: lng (double)
     *   Bytes 52-59: heading (double)
     *   Bytes 60-67: speed (double)
     *   Bytes 68-75: timestamp (long)
     *   Bytes 76-83: h3CellId (long)
     *   Bytes 84-127: padding (cache-line alignment)
     * </pre>
     */
    public void write(DriverLocation location) {
        long writePos = getWritePosition();
        int slotIndex = (int) (writePos % capacity);
        int offset = HEADER_SIZE + slotIndex * SLOT_SIZE;

        // Write driver ID (length-prefixed, zero-padded)
        byte[] idBytes = location.driverId().getBytes(StandardCharsets.UTF_8);
        int idLen = Math.min(idBytes.length, MAX_DRIVER_ID_LENGTH);

        synchronized (buffer) {
            buffer.putInt(offset, idLen);
            for (int i = 0; i < MAX_DRIVER_ID_LENGTH; i++) {
                buffer.put(offset + 4 + i, i < idLen ? idBytes[i] : 0);
            }

            buffer.putDouble(offset + 36, location.lat());
            buffer.putDouble(offset + 44, location.lng());
            buffer.putDouble(offset + 52, location.heading());
            buffer.putDouble(offset + 60, location.speed());
            buffer.putLong(offset + 68, location.timestamp());
            buffer.putLong(offset + 76, location.h3CellId());

            // Advance write position (atomic for single writer)
            buffer.putLong(0, writePos + 1);
        }
    }

    /**
     * Reads a driver location from a specific slot.
     *
     * @param index slot index [0, capacity)
     * @return the driver location at that slot
     */
    public DriverLocation read(int index) {
        if (index < 0 || index >= capacity) {
            throw new IndexOutOfBoundsException("Slot index " + index + " out of range [0, " + capacity + ")");
        }
        int offset = HEADER_SIZE + index * SLOT_SIZE;

        synchronized (buffer) {
            int idLen = buffer.getInt(offset);
            byte[] idBytes = new byte[idLen];
            for (int i = 0; i < idLen; i++) {
                idBytes[i] = buffer.get(offset + 4 + i);
            }
            String driverId = new String(idBytes, StandardCharsets.UTF_8);

            double lat = buffer.getDouble(offset + 36);
            double lng = buffer.getDouble(offset + 44);
            double heading = buffer.getDouble(offset + 52);
            double speed = buffer.getDouble(offset + 60);
            long timestamp = buffer.getLong(offset + 68);
            long h3CellId = buffer.getLong(offset + 76);

            return new DriverLocation(driverId, lat, lng, heading, speed, timestamp, h3CellId);
        }
    }

    /**
     * Reads all entries written between two positions.
     *
     * @param fromPos start position (inclusive)
     * @param toPos   end position (exclusive)
     * @return list of driver locations
     */
    public List<DriverLocation> readRange(long fromPos, long toPos) {
        List<DriverLocation> results = new ArrayList<>();
        for (long pos = fromPos; pos < toPos; pos++) {
            int slotIndex = (int) (pos % capacity);
            results.add(read(slotIndex));
        }
        return results;
    }

    /**
     * Returns the current write position (number of entries written).
     */
    public long getWritePosition() {
        return buffer.getLong(0);
    }

    /**
     * Forces the memory-mapped buffer contents to be written to disk.
     * This triggers the OS to flush dirty pages from the page cache to the
     * underlying storage device.
     */
    public void force() {
        buffer.force();
    }

    /**
     * Returns the buffer capacity (number of slots).
     */
    public int capacity() {
        return capacity;
    }

    @Override
    public void close() throws IOException {
        // MappedByteBuffer has no explicit unmap in Java -- GC handles it.
        // But we close the channel and file to release the file descriptor.
        if (channel != null && channel.isOpen()) channel.close();
        if (file != null) file.close();
    }
}
