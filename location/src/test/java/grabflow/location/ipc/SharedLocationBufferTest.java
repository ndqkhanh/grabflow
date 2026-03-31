package grabflow.location.ipc;

import grabflow.common.DriverLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SharedLocationBufferTest {

    @TempDir
    Path tempDir;

    @Test
    void writeAndReadRoundTrip() throws Exception {
        var buffer = new SharedLocationBuffer(tempDir.resolve("test.buf"), 100);

        var location = new DriverLocation("driver-1", 10.7769, 106.7009, 90.0, 35.5,
                System.currentTimeMillis(), 0x0123456789ABCDEFL);
        buffer.write(location);

        DriverLocation read = buffer.read(0);
        assertThat(read.driverId()).isEqualTo("driver-1");
        assertThat(read.lat()).isEqualTo(10.7769);
        assertThat(read.lng()).isEqualTo(106.7009);
        assertThat(read.heading()).isEqualTo(90.0);
        assertThat(read.speed()).isEqualTo(35.5);
        assertThat(read.h3CellId()).isEqualTo(0x0123456789ABCDEFL);

        buffer.close();
    }

    @Test
    void writePositionAdvancesMonotonically() throws Exception {
        var buffer = new SharedLocationBuffer(tempDir.resolve("test.buf"), 100);

        assertThat(buffer.getWritePosition()).isZero();

        for (int i = 0; i < 5; i++) {
            buffer.write(makeLocation("d" + i));
        }

        assertThat(buffer.getWritePosition()).isEqualTo(5);
        buffer.close();
    }

    @Test
    void ringBufferWrapsCorrectly() throws Exception {
        int capacity = 4;
        var buffer = new SharedLocationBuffer(tempDir.resolve("test.buf"), capacity);

        // Write capacity + 2 entries (wraps around)
        for (int i = 0; i < capacity + 2; i++) {
            buffer.write(makeLocation("driver-" + i));
        }

        assertThat(buffer.getWritePosition()).isEqualTo(capacity + 2);

        // Slot 0 should now have driver-4 (overwritten)
        DriverLocation slot0 = buffer.read(0);
        assertThat(slot0.driverId()).isEqualTo("driver-4");

        // Slot 1 should have driver-5
        DriverLocation slot1 = buffer.read(1);
        assertThat(slot1.driverId()).isEqualTo("driver-5");

        // Slot 2 should still have driver-2 (not yet overwritten)
        DriverLocation slot2 = buffer.read(2);
        assertThat(slot2.driverId()).isEqualTo("driver-2");

        buffer.close();
    }

    @Test
    void readRangeReturnsCorrectEntries() throws Exception {
        var buffer = new SharedLocationBuffer(tempDir.resolve("test.buf"), 100);

        for (int i = 0; i < 5; i++) {
            buffer.write(makeLocation("driver-" + i));
        }

        List<DriverLocation> range = buffer.readRange(1, 4);
        assertThat(range).hasSize(3);
        assertThat(range.get(0).driverId()).isEqualTo("driver-1");
        assertThat(range.get(1).driverId()).isEqualTo("driver-2");
        assertThat(range.get(2).driverId()).isEqualTo("driver-3");

        buffer.close();
    }

    @Test
    void twoMappingsShareData() throws Exception {
        Path filePath = tempDir.resolve("shared.buf");
        var writer = new SharedLocationBuffer(filePath, 100, FileChannel.MapMode.READ_WRITE);
        writer.write(makeLocation("shared-driver"));
        writer.force(); // flush to disk

        // Open a second read-only mapping of the same file
        var reader = new SharedLocationBuffer(filePath, 100, FileChannel.MapMode.READ_ONLY);

        // Reader should see the data written by writer
        assertThat(reader.getWritePosition()).isEqualTo(1);
        DriverLocation read = reader.read(0);
        assertThat(read.driverId()).isEqualTo("shared-driver");

        writer.close();
        reader.close();
    }

    @Test
    void forceFlushesToDisk() throws Exception {
        Path filePath = tempDir.resolve("persist.buf");
        var buffer = new SharedLocationBuffer(filePath, 100);
        buffer.write(makeLocation("persistent"));
        buffer.force();
        buffer.close();

        // Reopen and verify data persisted
        var reopened = new SharedLocationBuffer(filePath, 100, FileChannel.MapMode.READ_ONLY);
        assertThat(reopened.getWritePosition()).isEqualTo(1);
        assertThat(reopened.read(0).driverId()).isEqualTo("persistent");
        reopened.close();
    }

    @Test
    void capacityReportsCorrectly() throws Exception {
        var buffer = new SharedLocationBuffer(tempDir.resolve("test.buf"), 42);
        assertThat(buffer.capacity()).isEqualTo(42);
        buffer.close();
    }

    @Test
    void readOutOfBoundsThrows() throws Exception {
        var buffer = new SharedLocationBuffer(tempDir.resolve("test.buf"), 10);
        assertThatThrownBy(() -> buffer.read(-1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> buffer.read(10))
                .isInstanceOf(IndexOutOfBoundsException.class);
        buffer.close();
    }

    @Test
    void longDriverIdTruncatedToMax() throws Exception {
        var buffer = new SharedLocationBuffer(tempDir.resolve("test.buf"), 100);
        String longId = "a".repeat(50); // longer than MAX_DRIVER_ID_LENGTH (32)
        buffer.write(makeLocation(longId));

        DriverLocation read = buffer.read(0);
        assertThat(read.driverId()).hasSize(SharedLocationBuffer.MAX_DRIVER_ID_LENGTH);
        assertThat(read.driverId()).isEqualTo("a".repeat(32));

        buffer.close();
    }

    @Test
    void multipleWritesPreserveAllFields() throws Exception {
        var buffer = new SharedLocationBuffer(tempDir.resolve("test.buf"), 100);

        var loc1 = new DriverLocation("d1", 10.5, 106.5, 45.0, 30.0, 1000L, 111L);
        var loc2 = new DriverLocation("d2", 21.0, 105.8, 180.0, 60.0, 2000L, 222L);

        buffer.write(loc1);
        buffer.write(loc2);

        DriverLocation r1 = buffer.read(0);
        DriverLocation r2 = buffer.read(1);

        assertThat(r1.driverId()).isEqualTo("d1");
        assertThat(r1.lat()).isEqualTo(10.5);
        assertThat(r1.heading()).isEqualTo(45.0);
        assertThat(r1.timestamp()).isEqualTo(1000L);
        assertThat(r1.h3CellId()).isEqualTo(111L);

        assertThat(r2.driverId()).isEqualTo("d2");
        assertThat(r2.lat()).isEqualTo(21.0);
        assertThat(r2.heading()).isEqualTo(180.0);
        assertThat(r2.timestamp()).isEqualTo(2000L);
        assertThat(r2.h3CellId()).isEqualTo(222L);

        buffer.close();
    }

    // ── Helper ──

    private DriverLocation makeLocation(String driverId) {
        return new DriverLocation(driverId, 10.7769, 106.7009, 0, 30, System.currentTimeMillis(), 0L);
    }
}
