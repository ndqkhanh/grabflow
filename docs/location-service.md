# GrabFlow — Location Service: Real-Time Geospatial Intelligence

> **Deep dive** into GPS tracking, H3 indexing, and the location data pipeline.
> Platform: Java 21 — H3 hex grid, CRC-32, memory-mapped IPC, hierarchical timing wheel.
> Performance targets: sub-50ms nearest-driver queries across 100K+ concurrent drivers.

---

## Table of Contents

1. [H3 Hexagonal Grid](#1-h3-hexagonal-grid)
2. [Spatial Index](#2-spatial-index)
3. [GPS Packet Pipeline](#3-gps-packet-pipeline)
4. [CRC-32 Implementation](#4-crc-32-implementation)
5. [Memory-Mapped IPC](#5-memory-mapped-ipc)
6. [Hierarchical Timing Wheel](#6-hierarchical-timing-wheel)
7. [Driver Timeout Manager](#7-driver-timeout-manager)
8. [See Also](#8-see-also)

---

## 1. H3 Hexagonal Grid

### 1.1 Why Hexagons Over Squares?

Geospatial systems partition the Earth into cells to enable fast nearest-neighbor queries. Grid choices:

| Grid | Adjacency | Neighbors | Edge Effects | Use Case |
|------|-----------|-----------|--------------|----------|
| **Square** | 8-directional (4 edge + 4 corner) | 4 same-distance, 4 farther | Poor | Raster images, GIS |
| **Triangle** | 6-directional | 3 same-distance, 3 farther | Fair | Tessellation, fluid sims |
| **Hexagon** | 6-directional | 6 equidistant | Excellent | Geospatial (H3, Uber) |

Hexagons have **uniform adjacency**: all six neighboring cells are equidistant from the center. This minimizes the "edge effects" of square grids, where diagonal neighbors are ~1.4x farther than edge neighbors.

H3 (developed by Uber) hierarchically partitions the globe into hexagons at 16 resolutions (0–15):

| Resolution | Edge (m) | Area (km²) | Coverage | Use Case |
|------------|----------|-----------|----------|----------|
| 0 | 1,107,712 | 425,165 | ~121 cells (entire globe) | Continental |
| 5 | 8,544 | 252 | ~2 million cells | Regional (city-scale) |
| 7 | 1,220 | 5.16 | ~100 million cells | City district |
| 9 | 174 | 0.105 | ~4 billion cells | Street-level (GrabFlow) |
| 11 | 25 | 0.002 | ~200+ billion cells | Building-level |

GrabFlow uses **resolution 9** (~174m edge) for driver matching: precise enough to distinguish nearby drivers, yet coarse enough to keep memory footprint manageable at 100K+ drivers.

### 1.2 Axial Coordinates and Cube Rounding

H3 uses **axial (offset) coordinates** internally, which simplify neighbor traversal.

In axial notation, each hexagon is identified by `(q, r)` where:
- `q` increments to the right
- `r` increments downward-left

```
       _____
      /     \
 ____/ (0,-1)\____
/    \       /    \
|(-1,0)     |(1,-1)|
\____/ (0,0) \____/
/    \       /    \
|(-1,1)     |(1, 0)|
\____/ (0,1) \____/
     \       /
      \_____/
```

The **cube coordinate system** adds a third coordinate `s = -q - r` (implicit). This ensures `q + r + s = 0`, making rounding easier.

**Cube rounding algorithm** (from Red Blob Games):

```
1. Convert fractional axial (q_f, r_f) to cube: s_f = -q_f - r_f
2. Round each to nearest integer: q_i, r_i, s_i
3. Check if q_i + r_i + s_i ≠ 0 (rounding error)
4. Find the coordinate with the largest rounding error
5. Reset that coordinate to -sum of the other two
```

```java
static int[] hexRound(double q_f, double r_f) {
    double s_f = -q_f - r_f;

    int q = (int) Math.round(q_f);
    int r = (int) Math.round(r_f);
    int s = (int) Math.round(s_f);

    double qDiff = Math.abs(q - q_f);
    double rDiff = Math.abs(r - r_f);
    double sDiff = Math.abs(s - s_f);

    if (qDiff > rDiff && qDiff > sDiff) {
        q = -r - s;
    } else if (rDiff > sDiff) {
        r = -q - s;
    }
    // else: s is implicitly reset (we only need q, r for axial)

    return new int[]{q, r};
}
```

This ensures the rounded coordinates remain on the hex grid (i.e., all integer positions are valid hex centers).

### 1.3 lat/lng to Cell ID: Mercator Projection

Converting a GPS coordinate `(lat, lng)` to an H3 cell ID:

```
Algorithm:
1. Project lat/lng to planar (x, y) in meters using Mercator
2. Compute hex size (edge length) for the given resolution
3. Convert (x, y) to fractional axial coordinates (q_f, r_f)
4. Round (q_f, r_f) to nearest integer hex (q, r) using cube rounding
5. Encode (resolution, q, r) into a 64-bit cell ID
```

**Mercator projection** (simplified for moderate latitudes):

```java
static double lngToX(double lng) {
    return EARTH_RADIUS_METERS * Math.toRadians(lng);
}

static double latToY(double lat) {
    return EARTH_RADIUS_METERS * Math.toRadians(lat);
}
```

**Fractional axial coordinates** (for pointy-top hexagons):

```
q_f = (sqrt(3)/3 * x - 1/3 * y) / hexSize
r_f = (2/3 * y) / hexSize
```

**Cell ID encoding** (64-bit layout):

```
Bits 63-56: mode (0x01 for cell mode) + reserved
Bits 55-52: resolution (0-15)
Bits 51-26: q coordinate (signed 26 bits, range ±33 million)
Bits 25-0:  r coordinate (signed 26 bits, range ±33 million)
```

```java
static long encodeCellId(int resolution, int q, int r) {
    long mode = 0x01L;
    long res = resolution & 0xFL;
    long qBits = (q + (1 << 25)) & 0x3FFFFFFL;  // Offset to handle negatives
    long rBits = (r + (1 << 25)) & 0x3FFFFFFL;
    return (mode << 56) | (res << 52) | (qBits << 26) | rBits;
}
```

### 1.4 k-Ring Traversal

A **k-ring** is a set of all cells within `k` rings of a center cell, forming a hexagonal "bull's eye" pattern.

```
Ring 0 (center):    1 cell
Ring 1 (neighbors): 6 cells
Ring 2:            12 cells
Ring 3:            18 cells

Total for k rings: 3k² + 3k + 1 cells
```

**Algorithm**: Start at the center, walk outward in concentric rings. For each ring, start at a direction and walk along 6 sides:

```java
public static long[] kRing(long cellId, int k) {
    if (k == 0) return new long[]{cellId};

    int resolution = getResolution(cellId);
    int[] center = decodeAxial(cellId);
    int totalCells = 3 * k * k + 3 * k + 1;
    long[] result = new long[totalCells];
    int idx = 0;

    result[idx++] = cellId;  // Center

    int q = center[0];
    int r = center[1];

    // Walk k rings
    for (int ring = 1; ring <= k; ring++) {
        // Move ring steps in direction 4 (southwest: -1, +1)
        q += AXIAL_DIRECTIONS[4][0];
        r += AXIAL_DIRECTIONS[4][1];

        // Walk 6 sides, each with 'ring' steps
        for (int side = 0; side < 6; side++) {
            for (int step = 0; step < ring; step++) {
                result[idx++] = encodeCellId(resolution, q, r);
                q += AXIAL_DIRECTIONS[side][0];
                r += AXIAL_DIRECTIONS[side][1];
            }
        }
    }

    return result;
}
```

For k=2 (center + 2 rings), this generates 19 cell IDs.

---

## 2. Spatial Index

### 2.1 Cell-Based Hash Index

The spatial index uses H3 cells as hash buckets. Instead of a tree-based index (R-tree, k-d tree) which requires O(log n) lookups and rebalancing, the cell-based approach is **O(1) average case** and **O(k) worst case** (where k is the size of the k-ring).

```java
private final ConcurrentHashMap<Long, CopyOnWriteArrayList<DriverLocation>> cells
        = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, Long> driverCells = new ConcurrentHashMap<>();
```

**Structure**:
- `cells`: Maps cell ID → list of drivers in that cell.
- `driverCells`: Maps driver ID → their current cell (for efficient updates when they move).

### 2.2 Driver Update (Cell Migration)

When a driver's GPS updates and they move to a different H3 cell, the index must:
1. Remove them from the old cell.
2. Add them to the new cell.

```java
public void update(DriverLocation location) {
    long newCellId = location.h3CellId();
    Long oldCellId = driverCells.put(location.driverId(), newCellId);

    if (oldCellId != null && oldCellId != newCellId) {
        // Driver moved cells -- remove from old
        removeFromCell(oldCellId, location.driverId());
    }

    if (oldCellId != null && oldCellId == newCellId) {
        // Same cell -- update in place
        var cellDrivers = cells.get(newCellId);
        if (cellDrivers != null) {
            cellDrivers.removeIf(d -> d.driverId().equals(location.driverId()));
        }
    }

    // Add to new cell
    cells.computeIfAbsent(newCellId, k -> new CopyOnWriteArrayList<>())
            .add(location);
}
```

This approach is **concurrent-friendly**: `ConcurrentHashMap` and `CopyOnWriteArrayList` allow readers (nearest-driver queries) to proceed without locks while writers (GPS updates) modify the index.

### 2.3 Nearest-Driver Query: k-Ring Expansion

To find the nearest N drivers within a radius:

```
Algorithm:
1. Compute query point's H3 cell at resolution 9
2. Calculate k-ring radius: k = ceil(radiusMeters / cellEdgeLength)
3. Fetch all drivers from the k-ring cells
4. Compute Haversine distance to each driver
5. Filter by radius, sort by distance, return top N
```

```java
public List<DriverLocation> findNearest(double lat, double lng,
                                        double radiusMeters, int maxResults) {
    long queryCellId = H3Index.latLngToCell(lat, lng, RESOLUTION);
    double edgeLength = H3Index.cellEdgeLengthMeters(RESOLUTION);

    // Determine k-ring radius
    int k = Math.max(1, (int) Math.ceil(radiusMeters / edgeLength));

    // Collect candidates from all cells in the k-ring
    long[] ringCells = H3Index.kRing(queryCellId, k);
    List<DriverWithDistance> candidates = new ArrayList<>();

    for (long cellId : ringCells) {
        var cellDrivers = cells.get(cellId);
        if (cellDrivers == null) continue;

        for (DriverLocation driver : cellDrivers) {
            double distance = GeoUtils.haversineMeters(lat, lng, driver.lat(), driver.lng());
            if (distance <= radiusMeters) {
                candidates.add(new DriverWithDistance(driver, distance));
            }
        }
    }

    // Sort by distance and take top N
    candidates.sort(Comparator.comparingDouble(DriverWithDistance::distance));
    return candidates.stream()
            .limit(maxResults)
            .map(DriverWithDistance::driver)
            .toList();
}
```

**Haversine distance** (great-circle distance on a sphere):

```
a = sin²(Δlat/2) + cos(lat1) * cos(lat2) * sin²(Δlng/2)
c = 2 * atan2(√a, √(1-a))
distance = R * c
```

For resolution 9 (~174m cells) and a 5km search radius, k ≈ 30, fetching ~2,700 candidate drivers. With Haversine filtering, most queries return 10–100 results, making sort negligible.

---

## 3. GPS Packet Pipeline

### 3.1 End-to-End Flow

A driver's GPS update flows through:

```
Driver (mobile app)
    │
    ├─ GPS reading (lat, lng, heading, speed, timestamp)
    │
    ▼
LocationServer (ingestion)
    │
    ├─ UDP packet received
    ├─ CRC-32 verify
    ├─ H3 cell compute
    ├─ Spatial index update
    ├─ Memory-mapped IPC write
    ├─ Timeout manager heartbeat
    │
    ▼
Query Handler (reads from spatial index + shared buffer)
    │
    ├─ Receive /nearest-drivers request
    ├─ Spatial index k-ring query
    ├─ Haversine distance ranking
    ├─ Return top N drivers
    │
    ▼
Client (mobile app or web)
```

### 3.2 GPS Packet Structure

A GPS packet is serialized as:

```
Bytes 0-3:   driverId length (int)
Bytes 4-35:  driverId (UTF-8, zero-padded to 32 bytes)
Bytes 36-43: latitude (double)
Bytes 44-51: longitude (double)
Bytes 52-59: heading (double, degrees 0-360)
Bytes 60-67: speed (double, km/h)
Bytes 68-75: timestamp (long, milliseconds)
Bytes 76-83: H3 cell ID (long, pre-computed)
Bytes 84-87: CRC-32 checksum (int)
```

Total: 88 bytes per update.

### 3.3 Validation & Transformation

On receipt:
1. **CRC-32 verify**: Ensure the packet was not corrupted in transit.
2. **H3 cell compute**: Convert lat/lng to H3 cell at resolution 9.
3. **Spatial index update**: Insert or update the driver in the cell-based index.
4. **Shared buffer write**: Write the update to the memory-mapped IPC buffer for readers.
5. **Timeout heartbeat**: Reset the driver's inactivity timer.

---

## 4. CRC-32 Implementation

### 4.1 Cyclic Redundancy Check: Polynomial Division in GF(2)

CRC-32 treats a byte stream as the coefficients of a polynomial over **GF(2)** (the Galois Field with elements {0, 1}).

**Example**: The bytes `0xAB 0xCD` are interpreted as the polynomial:

```
Bit pattern: 10101011 11001101
Polynomial:  x^15 + x^13 + x^11 + x^9 + x^6 + x^3 + x^2 + x^0 + ...
            (exponent = bit position)
```

CRC divides this polynomial by the **generator polynomial** (for IEEE 802.3, `x^32 + x^26 + ... + 1`) and computes the remainder. In GF(2), addition is XOR, so subtraction is also XOR. The division is a sequence of XOR and shift operations (long division in binary).

### 4.2 Lookup-Table Optimization

Computing CRC bit-by-bit costs O(8n) operations. A lookup table reduces this to O(n):

**Precompute** (at construction time): For each byte value `b ∈ [0, 255]`, compute the CRC remainder of `b` shifted through all 8 bits.

**Use** (at runtime): For each input byte `b`:

```
crc = table[(crc ^ b) & 0xFF] ^ (crc >>> 8)
```

This processes 8 bits in a single table lookup + two XOR operations.

```java
static int[] buildTable(int polynomial) {
    int[] tbl = new int[256];
    for (int i = 0; i < 256; i++) {
        int crc = i;
        for (int bit = 0; bit < 8; bit++) {
            if ((crc & 1) != 0) {
                crc = (crc >>> 1) ^ polynomial;
            } else {
                crc = crc >>> 1;
            }
        }
        tbl[i] = crc;
    }
    return tbl;
}
```

### 4.3 Algorithm (IEEE 802.3 Reflected)

```java
public int compute(byte[] data) {
    int crc = 0xFFFFFFFF;  // Initial value (all-ones)
    for (byte b : data) {
        crc = table[(crc ^ b) & 0xFF] ^ (crc >>> 8);
    }
    return crc ^ 0xFFFFFFFF;  // Final XOR complement
}
```

**Why initial and final XOR?** These ensure:
1. Leading zeros in the data are distinguishable.
2. A data stream followed by its own CRC will always produce a known residue.

### 4.4 Verify

```java
public boolean verify(byte[] data, int expectedCrc) {
    return compute(data) == expectedCrc;
}
```

In GrabFlow, each GPS packet includes a CRC-32 checksum of the first 84 bytes (driverId through H3 cellId). Before processing:

```
byte[] payload = Arrays.copyOf(packet, 84);
int receivedCrc = packet.getCrc();
if (!crc32.verify(payload, receivedCrc)) {
    log.warn("GPS packet CRC mismatch, dropping");
    return;  // Discard corrupted packet
}
```

---

## 5. Memory-Mapped IPC

### 5.1 Zero-Copy Inter-Process Communication

Traditional IPC (pipes, sockets, message queues) incurs **two copies per message**:
1. User space → kernel space (copy into socket buffer).
2. Kernel space → user space (copy into receive buffer).

Memory-mapped files eliminate both copies: the OS maps the same physical memory pages into both processes' virtual address spaces. A write by one process is instantly visible to the other (once flushed to the page cache).

```
┌─────────────────────────────────────┐
│   Disk File (backing storage)        │
│  (persistent, survives crashes)      │
└─────────────────┬───────────────────┘
                  │
                  │ OS page cache
                  │
        ┌─────────┴─────────┐
        │                   │
   ┌────▼────┐         ┌────▼────┐
   │ Process │         │ Process │
   │ Writer  │         │ Reader  │
   │ (GPS    │         │ (Query  │
   │Ingest)  │         │Handler) │
   │         │         │         │
   │MappedBB │         │MappedBB │
   │READ_WRITE│        │READ_ONLY│
   └─────────┘         └─────────┘
```

GrabFlow uses a **ring buffer** layout:

```
Bytes 0-7:      Write position counter (64-bit long)
Bytes 8-135:    Slot 0 (128 bytes per entry)
Bytes 136-263:  Slot 1
...
Bytes 8 + N*128 to 8 + (N+1)*128: Slot N-1
```

### 5.2 Ring Buffer Protocol

```java
public void write(DriverLocation location) {
    long writePos = getWritePosition();
    int slotIndex = (int) (writePos % capacity);
    int offset = HEADER_SIZE + slotIndex * SLOT_SIZE;

    // Write driver ID
    byte[] idBytes = location.driverId().getBytes(StandardCharsets.UTF_8);
    int idLen = Math.min(idBytes.length, MAX_DRIVER_ID_LENGTH);

    synchronized (buffer) {
        buffer.putInt(offset, idLen);
        for (int i = 0; i < MAX_DRIVER_ID_LENGTH; i++) {
            buffer.put(offset + 4 + i, i < idLen ? idBytes[i] : 0);
        }

        // Write geo data
        buffer.putDouble(offset + 36, location.lat());
        buffer.putDouble(offset + 44, location.lng());
        buffer.putDouble(offset + 52, location.heading());
        buffer.putDouble(offset + 60, location.speed());
        buffer.putLong(offset + 68, location.timestamp());
        buffer.putLong(offset + 76, location.h3CellId());

        // Advance write position
        buffer.putLong(0, writePos + 1);
    }
}
```

**Write position semantics**:
- `writePos` is the number of entries written total (wraps around via modulo).
- New entries are always written to `slots[writePos % capacity]`, overwriting old entries if the buffer is full (ring behavior).
- Readers track their own read position and catch up by comparing with `writePos`.

### 5.3 Comparison: Other IPC Mechanisms

| Mechanism | Copies | Latency | Cross-Machine | Setup |
|-----------|--------|---------|---------------|-------|
| Pipe/Socket | 2 (user→kernel→user) | ~1-10 μs | Yes (socket) | Simple |
| shmget (POSIX) | 0 | ~10-100 ns | No | Linux-specific |
| Memory-mapped file | 0 | ~10-100 ns (cached) | No | Portable |
| Disruptor (LMAX) | 0 | ~1-2 ns | No | Single-producer only |

GrabFlow uses memory-mapped files for **portability** across Linux, macOS, and Windows, while maintaining **nanosecond-scale latency** in the common case (hot page cache).

### 5.4 Cache-Line Alignment

Each slot is **128 bytes**, aligned to CPU cache-line size (typically 64 bytes on modern CPUs). This ensures:
1. Writer and reader threads don't contend on the same cache line.
2. Each slot fit in exactly 2 cache lines (128 / 64).
3. False sharing is eliminated.

---

## 6. Hierarchical Timing Wheel

### 6.1 The Problem: O(log n) Timers Are Expensive

Naively, timers could be stored in a `PriorityQueue`. But for 100K drivers with timeout timers:
- Insert timeout: O(log 100K) = O(17).
- Cancel timeout (on heartbeat): O(log 100K) = O(17).
- Tick processing: O(1).

At 100K heartbeats/second, this becomes 1.7M log ops/second — significant.

The timing wheel trades complexity for speed: **O(1) insert, O(1) cancel, O(1) amortized per-tick**.

### 6.2 Single-Wheel Mechanics

A timing wheel is a circular array (like a clock face):

```
┌──────────────────────┐
│ Wheel (512 slots)    │
├─┬─┬─┬─┬─┬─┬─┬─┬─┬──┤
│0│1│2│3│4│5│...│511│
├─┴─┴─┴─┴─┴─┴─┴─┴─┴──┤
  ▲
  └─ Clock hand (advances one slot per tick)
```

**Configuration**:
- **Tick duration**: 100 ms (each slot represents 100 ms).
- **Wheel size**: 512 slots.
- **Total coverage**: 100 ms × 512 = 51.2 seconds.

**Insertion** (O(1)):

```
Task deadline: 5,234 ms
Current clock: 1,000 ms
Interval: 5,234 - 1,000 = 4,234 ms
Slot index: (5,234 / 100) % 512 = 52 % 512 = 52

Place task in slots[52]
```

**Tick** (advancing the clock):

```
old clock: 1,000 ms
new clock: 1,100 ms
Hand moves from slot 10 to slot 11
Fire all tasks in slots[11] whose deadline <= 1,100 ms
```

### 6.3 Hierarchical (Overflow) Wheels

A single wheel covers only 51.2 seconds. For longer timeouts (e.g., 5-minute driver inactivity timeout = 300,000 ms), tasks must be placed in an **overflow wheel**.

The overflow wheel is another timing wheel, but with:
- **Tick duration**: 100 ms × 512 = 51.2 seconds (the entire range of the inner wheel).
- **Wheel size**: 512 slots.
- **Total coverage**: 51.2 seconds × 512 ≈ 6.8 hours.

When a task's deadline is beyond the inner wheel's range:

```
Task deadline: 300,000 ms (5 minutes)
Inner wheel range: 51,200 ms
Interval: 300,000 - 1,000 = 299,000 ms > 51,200 ms

Place task in overflow wheel
Overflow wheel slot: (300,000 / 5,120) % 512 ≈ slot 58
```

This is the pattern used by the Linux kernel (five-level timer wheel) and Kafka's TimingWheel.

### 6.4 Clock Advancement and Cascading

When the inner wheel's clock advances, overflow tasks must be **cascaded** back (requeued at finer granularity).

```java
public void advanceClock(long targetMs) {
    // Step 1: Advance inner wheel tick by tick
    while (currentTickMs + tickDurationMs <= targetMs) {
        currentTickMs += tickDurationMs;
        int slotIndex = (int) ((currentTickMs / tickDurationMs) % wheelSize);

        List<TimerTask> bucket;
        synchronized (slots[slotIndex]) {
            bucket = new ArrayList<>(slots[slotIndex]);
            slots[slotIndex].clear();
        }

        for (TimerTask task : bucket) {
            if (task.isCancelled()) continue;
            if (task.getDeadlineMs() <= targetMs) {
                task.getAction().run();  // Fire
            } else {
                placeTask(task);  // Re-place (may go to overflow)
            }
        }
    }

    // Step 2: Cascade from overflow wheels
    if (overflowWheel != null) {
        overflowWheel.drainOverflow(targetMs, this);
    }
}
```

When the inner wheel ticks forward (e.g., from slot 10 to slot 11):
1. Inner wheel advances: `currentTickMs` is updated.
2. Overflow wheels drain their "expired" slots into the inner wheel.
3. Tasks cascaded from overflow are re-placed using the now-updated `currentTickMs`, landing in the correct future slot.

### 6.5 Complexity Analysis

```
Insert:          O(1)
Cancel:          O(1)
Tick:            O(1) amortized
Per-task work:   O(1) when cascaded back
```

For 100K driver timeouts at 100K heartbeats/second:
- Cancels: 100K × O(1) = O(100K)
- Inserts: 100K × O(1) = O(100K)
- Ticks: 100 ticks/sec × O(1) = O(100)

Total: O(100K) operations for both heartbeats and timeout management, vs O(1.7M) with a priority queue.

---

## 7. Driver Timeout Manager

### 7.1 Watchdog Timer Pattern

The timeout manager implements the classic **watchdog timer** from embedded systems:

```
On heartbeat (GPS update):
    Cancel existing timeout (if any)
    Schedule new timeout at: now + timeoutMs

On timeout fire:
    Remove driver from spatial index
    Remove driver from active connections
    Log "Driver X timed out"
```

This is the same pattern used by:
- TCP keepalive probes (RFC 9293).
- ZooKeeper session expiry.
- Kafka consumer group heartbeats.
- Raft leader election timeouts.

### 7.2 Cancel-and-Reinsert Pattern

Each heartbeat cancels the driver's existing timer and creates a new one:

```java
public void heartbeat(String driverId) {
    // Cancel existing
    TimingWheel.TimerTask existing = activeTimers.get(driverId);
    if (existing != null) {
        existing.cancel();
    }

    // Create new
    long deadline = System.currentTimeMillis() + timeoutMs;
    TimingWheel.TimerTask task = timingWheel.createTask(
            driverId,
            deadline,
            () -> {
                activeTimers.remove(driverId);
                log.info("Driver timed out: {}", driverId);
                onTimeout.accept(driverId);  // User callback
            }
    );

    activeTimers.put(driverId, task);
    timingWheel.add(task);
}
```

**Why cancel-and-reinsert?**
- In a priority queue, updating a task's deadline requires O(log n) delete + O(log n) insert.
- In a timing wheel, both operations are O(1), so the pattern is **efficient**.
- The cancelled task remains in its slot but is skipped when it fires (due to the `cancelled` flag).

### 7.3 Background Ticker

A virtual thread ticks the timing wheel at regular intervals:

```java
public void start() {
    if (running.compareAndSet(false, true)) {
        tickerThread = Thread.ofVirtual()
                .name("driver-timeout-ticker")
                .start(() -> {
                    while (running.get()) {
                        try {
                            Thread.sleep(100);  // Tick every 100 ms
                            timingWheel.advanceClock(System.currentTimeMillis());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                });
    }
}
```

The virtual thread is lightweight; blocking in `Thread.sleep(100)` does not occupy a carrier OS thread. Multiple timeout managers can run concurrently without consuming platform threads.

### 7.4 Integration with Spatial Index

When a driver times out, the callback removes them from the spatial index:

```java
DriverTimeoutManager timeoutMgr = new DriverTimeoutManager(
    30_000,  // 30 second timeout
    driverId -> {
        spatialIndex.remove(driverId);
        connectionTracker.removeDriver(driverId);
    }
);
```

This ensures that if a driver's GPS update process crashes (or is disconnected), they disappear from the spatial index after 30 seconds, preventing stale driver locations from being returned in nearest-driver queries.

---

## 8. See Also

- [H3 by Uber: A Hexagonal Hierarchical Geospatial Indexing System](https://eng.uber.com/h3/)
- [Hexagonal Grids (Red Blob Games)](https://www.redblobgames.com/grids/hexagons/)
- [Haversine Formula (Great-Circle Distance)](https://en.wikipedia.org/wiki/Haversine_formula)
- [CRC-32: Cyclic Redundancy Check](https://en.wikipedia.org/wiki/Cyclic_redundancy_check)
- [CRC32 Polynomial Lookup Table](https://www.kernel.org/doc/html/latest/crypto/api.html)
- [Memory-Mapped I/O in Java](https://docs.oracle.com/en/java/javase/21/docs/api/java.nio/java/nio/MappedByteBuffer.html)
- [Linux Timing Wheel Implementation (kernel/time/timer.c)](https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/kernel/time/timer.c)
- [Kafka TimingWheel: O(1) Approximate Insertion and Deletion in a Timing Wheel](https://github.com/apache/kafka/blob/trunk/clients/src/main/java/org/apache/kafka/common/utils/TimingWheel.java)
- [LMAX Disruptor: High-Performance Inter-Thread Messaging](https://github.com/LMAX-Exchange/disruptor)
- [Project Loom: Virtual Threads](https://openjdk.org/projects/loom/)
