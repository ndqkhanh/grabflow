# GrabFlow — API Gateway: From-Scratch Protocol Stack

> **Deep dive** into the gateway's networking layer.
> Platform: Java 21 NIO — no Netty, no HTTP framework.
> Protocols: IPv4/IPv6, TLS 1.3, HTTP/1.1, HTTP/2, DNS (RFC 1035).

---

## Table of Contents

1. [Protocol Stack Overview](#1-protocol-stack-overview)
2. [IPv4/IPv6 Dual-Stack](#2-ipv4ipv6-dual-stack)
3. [TLS 1.3 Handshake](#3-tls-13-handshake)
4. [DNS Resolver](#4-dns-resolver)
5. [Rate Limiting Pipeline](#5-rate-limiting-pipeline)
6. [NIO Reactor Event Loop](#6-nio-reactor-event-loop)
7. [Request Routing](#7-request-routing)
8. [See Also](#8-see-also)

---

## 1. Protocol Stack Overview

The API gateway is a layered protocol stack built on Java 21 NIO. No frameworks abstract the networking; every layer is explicit and testable.

```
┌────────────────────────────────────────────────────┐
│                   Application                      │
│          Request Router (longest-prefix match)     │
├────────────────────────────────────────────────────┤
│                  Rate Limiting                     │
│       (BloomFilter + TokenBucket two-tier)        │
├────────────────────────────────────────────────────┤
│              HTTP Protocol Detection               │
│        (HTTP/1.1, HTTP/2 via ALPN negotiation)    │
├────────────────────────────────────────────────────┤
│               TLS 1.3 Terminator                   │
│       (ECDHE, AEAD ciphers, hot certificate reload)│
├────────────────────────────────────────────────────┤
│            DNS Resolver (RFC 1035)                 │
│          (UDP wire protocol, pointer compression)  │
├────────────────────────────────────────────────────┤
│         NIO Selector Event Loop (Reactor)          │
│      OP_ACCEPT → OP_READ → OP_WRITE state machine │
├────────────────────────────────────────────────────┤
│         IPv4/IPv6 Dual-Stack Listener              │
│       (Two ServerSocketChannels, CIDR matching)    │
├────────────────────────────────────────────────────┤
│              Java NIO (java.nio.channels)          │
│           ServerSocketChannel, SocketChannel       │
├────────────────────────────────────────────────────┤
│              OS Kernel (epoll / kqueue)            │
│          Non-blocking socket multiplexing          │
└────────────────────────────────────────────────────┘
```

The stack is **synchronous** at every layer (no callbacks, no futures on the critical path). Virtual threads eliminate the false choice between simple blocking I/O and reactive callback chains. A single virtual thread drives each client connection from accept through response; the Selector parks threads on I/O without consuming OS kernel resources.

---

## 2. IPv4/IPv6 Dual-Stack

### 2.1 Why Separate Channels?

The `DualStackListener` opens two independent `ServerSocketChannel` instances: one bound to `0.0.0.0:port` (IPv4) and one to `[::]​:port` (IPv6). Both are registered with the same NIO `Selector`.

Modern operating systems (Linux, macOS, Windows) support binding a single IPv6 socket to `::` with `IPV6_V6ONLY=false`, which automatically accepts IPv4 connections as IPv4-mapped IPv6 addresses (`::ffff:x.x.x.x`). However, this behavior varies across OS kernels. For **deterministic, testable, and portable** dual-stack behavior, separate channels are explicit:

- **IPv4 channel**: Receives all IPv4 connections in native format.
- **IPv6 channel**: Receives all IPv6 connections (including IPv4-mapped addresses if the OS passes them).

### 2.2 CIDR Matching Algorithm

The `matchesCidr()` method implements bit-level prefix matching for both IPv4 and IPv6 addresses.

**Algorithm**:

```java
// Example: does 192.168.1.100 match CIDR 192.168.0.0/24?
// Prefix length 24 means compare first 24 bits (3 full bytes).
// If prefix is partial (e.g. /25), apply a bitmask to the 4th byte.

public static boolean matchBits(byte[] a, byte[] b, int prefixLength) {
    int fullBytes = prefixLength / 8;                  // e.g. 24 / 8 = 3
    for (int i = 0; i < fullBytes; i++) {
        if (a[i] != b[i]) return false;                // Compare 3 full bytes
    }

    int remainingBits = prefixLength % 8;              // e.g. 24 % 8 = 0
    if (remainingBits > 0) {
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (a[fullBytes] & mask) == (b[fullBytes] & mask);
    }
    return true;
}
```

**Bit mask example**: For 3 remaining bits, the mask is `0b11100000 = 0xE0`. The client's address and CIDR network address are masked to the same 3 bits before comparison.

### 2.3 IPv4-Mapped IPv6 Addresses

When an address arrives in IPv6 format but the CIDR block is IPv4 (or vice versa), the `extractIpv4FromMapped()` method attempts to unwrap IPv4-mapped IPv6 addresses.

**IPv4-mapped IPv6 format**: `::ffff:x.x.x.x` encodes as:
- Bytes 0–9: all zeros
- Bytes 10–11: `0xFF 0xFF` (mapping marker)
- Bytes 12–15: four bytes of IPv4 address in network byte order

```java
static byte[] extractIpv4FromMapped(byte[] ipv6Bytes) {
    if (ipv6Bytes.length != 16) return null;

    for (int i = 0; i < 10; i++) {
        if (ipv6Bytes[i] != 0) return null;            // First 10 bytes must be 0
    }
    if (ipv6Bytes[10] != (byte) 0xFF || ipv6Bytes[11] != (byte) 0xFF) {
        return null;                                    // Bytes 10-11 must be 0xFF
    }

    byte[] ipv4 = new byte[4];
    System.arraycopy(ipv6Bytes, 12, ipv4, 0, 4);      // Extract last 4 bytes
    return ipv4;
}
```

This allows rules like "block CIDR 10.0.0.0/8" to match both IPv4 connections and IPv4-mapped IPv6 connections.

---

## 3. TLS 1.3 Handshake

### 3.1 1-RTT vs 2-RTT: The RFC 8446 Advantage

TLS 1.2 required 2-RTT (round trips):
1. ClientHello, ServerHello, Certificate, CertificateVerify, Finished.
2. Client Finished.

TLS 1.3 (RFC 8446) reduces this to 1-RTT by moving the ServerHello and encrypted extensions into the first response:

```
Client                                    Server
  │─ ClientHello ──────────────────────────────▶│
  │   (supported_versions, key_share, ALPN)     │
  │                                               │
  │◀─ ServerHello ──────────────────────────────│
  │   (selected key_share, version)              │
  │◀─ EncryptedExtensions ──────────────────────│
  │   (ALPN selection: "h2" or "http/1.1")      │
  │◀─ Certificate ──────────────────────────────│
  │   (server certificate chain)                 │
  │◀─ CertificateVerify ────────────────────────│
  │   (signature over handshake transcript)      │
  │◀─ Finished ─────────────────────────────────│
  │   (HMAC-verified handshake finalization)     │
  │                                               │
  │─ Finished ──────────────────────────────────▶│
  │                                               │
  │◀── Application Data (encrypted) ────────────│
```

The key shift: the **client includes key material in the initial ClientHello** (`key_share` extension), so the server can start encryption immediately without waiting for a second round trip.

### 3.2 ECDHE Key Exchange & HKDF Derivation

TLS 1.3 mandates forward secrecy: only ephemeral (temporary) keys, never static RSA keys.

**ECDHE (Elliptic Curve Diffie-Hellman Ephemeral)**:
- Client generates a random ephemeral ECDH private key and sends the public key in ClientHello.
- Server generates its own ephemeral ECDH private key and responds with its public key.
- Both compute the shared secret: `shared = ECDH(client_private, server_public)`.

**HKDF (HMAC-based Key Derivation Function, RFC 5869)**:
The shared secret is not used directly. Instead, HKDF derives encryption keys, authentication keys, and IVs:

```
early_exporter_master_secret = HKDF-Expand-Label(
    Derive-Secret(client_early_traffic_secret), "exporter", ...
)

client_handshake_traffic_secret = HKDF-Expand-Label(
    Derive-Secret(shared_secret), "c hs traffic", ClientHello...ServerHello
)

server_handshake_traffic_secret = HKDF-Expand-Label(
    Derive-Secret(shared_secret), "s hs traffic", ClientHello...ServerHello
)

client_application_traffic_secret = HKDF-Expand-Label(
    Derive-Secret(shared_secret), "c ap traffic", all_handshake_messages
)

server_application_traffic_secret = HKDF-Expand-Label(
    Derive-Secret(shared_secret), "s ap traffic", all_handshake_messages
)
```

Each secret is context-dependent (includes parts of the ClientHello, ServerHello, and all prior handshake messages), preventing downgrade attacks.

### 3.3 ALPN for HTTP/2 vs HTTP/1.1 Negotiation

ALPN (Application-Layer Protocol Negotiation, RFC 7301) extends the TLS handshake to negotiate the application protocol without a separate round trip.

**Client advertises**:
```
supported_protocols = ["h2", "http/1.1"]
```

**Server responds with**:
```
selected_protocol = "h2"   // HTTP/2 preferred
// or
selected_protocol = "http/1.1"
```

In `TlsTerminator`, this is configured as:

```java
private static final String[] ALPN_PROTOCOLS = {"h2", "http/1.1"};

SSLParameters params = engine.getSSLParameters();
params.setApplicationProtocols(ALPN_PROTOCOLS);
engine.setSSLParameters(params);
```

The selected protocol is retrieved post-handshake via `engine.getApplicationProtocol()`, allowing the gateway to route the connection to the appropriate HTTP parser.

### 3.4 AEAD-Only Ciphers

TLS 1.3 permits only AEAD (Authenticated Encryption with Associated Data) ciphers:

```java
private static final String[] TLS13_CIPHER_SUITES = {
    "TLS_AES_256_GCM_SHA384",        // AES-256-GCM, SHA-384 for HKDF
    "TLS_AES_128_GCM_SHA256",        // AES-128-GCM, SHA-256 for HKDF
    "TLS_CHACHA20_POLY1305_SHA256"   // ChaCha20-Poly1305, good for non-AES hardware
};
```

No CBC (Cipher Block Chaining) modes, no RC4, no stream ciphers that leak plaintext lengths. AEAD combines encryption and authentication: a single primitive provides both confidentiality (ciphertext does not leak plaintext) and authenticity (attacker cannot forge valid ciphertext).

### 3.5 Certificate Hot-Rotation via WatchService

Certificates expire or are revoked. The `TlsTerminator` monitors the keystore file for changes using Java's `WatchService`, backed by OS-native file events (inotify on Linux, kqueue on macOS).

```java
private void startCertificateWatcher() {
    watcherRunning = true;
    watcherThread = Thread.ofVirtual()
            .name("tls-cert-watcher")
            .start(() -> {
                Path certDir = Path.of(keystorePath).getParent();
                try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                    certDir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY);

                    while (watcherRunning) {
                        WatchKey key = watcher.take();  // Blocks until event
                        for (WatchEvent<?> event : key.pollEvents()) {
                            Path changed = (Path) event.context();
                            if (changed.toString().equals(certFileName)) {
                                reloadCertificate();
                            }
                        }
                        if (!key.reset()) break;
                    }
                }
            });
}
```

When a certificate file is modified, `reloadCertificate()` atomically replaces the `SSLContext` via `AtomicReference.getAndSet()`:

```java
private void reloadCertificate() {
    try {
        Thread.sleep(500);  // Wait for file write to complete
        SSLContext newCtx = buildSslContext();
        SSLContext oldCtx = sslContextRef.getAndSet(newCtx);
        log.info("Certificate hot-reloaded");
    } catch (Exception e) {
        log.error("Failed to reload certificate", e);
    }
}
```

**Impact on connections**: Existing connections continue with their original `SSLEngine` (which holds a reference to the old context). Only new connections use the updated certificate. No disruption.

---

## 4. DNS Resolver

### 4.1 RFC 1035 Wire Protocol

The DNS resolver implements the DNS protocol from scratch over UDP. Every request and response is a binary packet following RFC 1035 §4.

**DNS Header (12 bytes)**:

```
+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
|                      ID                         |  (2 bytes)
+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
|QR|   Opcode  |AA|TC|RD|RA|   Z    |   RCODE   |  (2 bytes)
+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
|                    QDCOUNT                      |  (2 bytes)
+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
|                    ANCOUNT                      |  (2 bytes)
+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
|                    NSCOUNT                      |  (2 bytes)
+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
|                    ARCOUNT                      |  (2 bytes)
+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
```

- **ID**: Random transaction ID to match responses.
- **QR**: Query (0) or Response (1).
- **RD (Recursion Desired)**: Set in queries to request recursive resolution.
- **RA (Recursion Available)**: Set in responses if the resolver supports recursion.
- **RCODE (Response Code)**: 0 = NOERROR, 3 = NXDOMAIN, 2 = SERVFAIL, etc.
- **QDCOUNT**, **ANCOUNT**, **NSCOUNT**, **ARCOUNT**: Counts of sections.

### 4.2 Domain Name Encoding: Length-Prefixed Labels

Domain names are not stored as ASCII strings. Instead, they are encoded as a sequence of **labels**, each preceded by its length.

**Example**: `www.example.com` encodes as:

```
03 77 77 77    (length 3, "www")
07 65 78 61 6d 70 6c 65    (length 7, "example")
03 63 6f 6d    (length 3, "com")
00    (zero-length label: end of name)
```

Hex dump: `03 77 77 77 07 65 78 61 6d 70 6c 65 03 63 6f 6d 00`

```java
public static byte[] encodeDomainName(String domain) {
    if (domain.endsWith(".")) {
        domain = domain.substring(0, domain.length() - 1);
    }
    String[] labels = domain.split("\\.");
    int totalLen = 1;  // Terminal zero byte
    for (String label : labels) {
        totalLen += 1 + label.length();
    }
    ByteBuffer buf = ByteBuffer.allocate(totalLen);
    for (String label : labels) {
        buf.put((byte) label.length());
        buf.put(label.getBytes(StandardCharsets.US_ASCII));
    }
    buf.put((byte) 0);  // Terminal zero
    return buf.array();
}
```

### 4.3 Pointer Compression (0xC0xx)

To save space, DNS responses reuse domain name suffixes via pointers. A pointer is a 2-byte value where the **top 2 bits are set** (`0xC0xx`):

```
0xC0xx yy    ← pointer to offset (((0xC0 & 0x3F) << 8) | yy) in the message
```

Example: In the answer section, instead of repeating `07 65 78 61 6d 70 6c 65 03 63 6f 6d 00`, the server can use `C0 0C` (pointer to byte offset 12), which was already in the query section.

```java
if ((labelLen & 0xC0) == 0xC0) {
    // Pointer detected
    int lo = buf.get() & 0xFF;
    int offset = ((labelLen & 0x3F) << 8) | lo;

    ByteBuffer ptr = ByteBuffer.wrap(message);
    ptr.position(offset);
    readNameInto(ptr, message, sb, depth + 1);  // Recursive follow-pointer
    return;  // Do NOT continue reading from original buffer after pointer
}
```

This recursion is **depth-limited** (max 20 levels) to prevent loops in malformed packets.

### 4.4 CNAME Chain Following

When resolving an A record (IPv4 address), the server may return a CNAME (Canonical Name) record instead, pointing to another name. The resolver must follow the chain:

```java
List<DnsRecord> direct = records.stream().filter(r -> r.type() == type).toList();
if (direct.isEmpty() && type != DnsRecord.RecordType.CNAME) {
    List<DnsRecord> cnames = records.stream()
            .filter(r -> r.type() == DnsRecord.RecordType.CNAME)
            .toList();
    if (!cnames.isEmpty()) {
        String target = cnames.getFirst().value();
        log.debug("Following CNAME {} -> {}", domain, target);
        return resolveRecursive(target, type, visited);  // Recurse with new domain
    }
}
```

To prevent infinite loops, the `visited` set tracks domains already queried. After `MAX_CNAME_HOPS=10` hops, the resolver gives up.

### 4.5 TTL Caching

Results are cached with their TTL (Time-To-Live) in `DnsCache`. Subsequent queries for the same domain check the cache before hitting the upstream DNS server.

---

## 5. Rate Limiting Pipeline

### 5.1 Two-Tier Architecture

The gateway implements a **two-tier rate limiting scheme**:

1. **Bloom Filter (fast path)**: Probabilistic data structure identifying "definitely bad" IPs in O(1) space and time.
2. **Token Bucket (precise path)**: Per-client bucket allowing bursts while enforcing a sustained rate.

```
Incoming Request
    │
    ├──▶ BloomFilter.mightContain(clientIP)?
    │    │
    │    ├─ YES (or unknown) ──▶ TokenBucket.tryAcquire(clientIP)?
    │    │                       │
    │    │                       ├─ YES ──▶ ALLOW
    │    │                       └─ NO ──▶ REJECT (429)
    │    │
    │    └─ NO (definite not bad) ──▶ ALLOW
    │
    └── (Continue to HTTP parsing)
```

**Rationale**: The Bloom filter is compact (~1 byte per expected bad IP) and answers "definitely not" with certainty. The token bucket is more expensive (per-client state) but precise. By checking the filter first, we avoid token bucket overhead for IPs that are definitely clean.

### 5.2 Bloom Filter: Probabilistic Membership Testing

A Bloom filter is a space-efficient data structure that answers: "Has this element been added?"

- **False negatives**: Never. If the filter says "NO", the element was definitely not added.
- **False positives**: Possible, with a bounded probability. If the filter says "YES", the element was probably added.

**Optimal parameters** (given expected insertions `n` and desired false-positive rate `p`):

```
m = -n * ln(p) / (ln 2)²    (number of bits needed)
k = (m / n) * ln 2          (number of hash functions)
```

For 10,000 bad IPs and 1% FPR:

```
m = -10,000 * ln(0.01) / (ln 2)² = -10,000 * (-4.605) / 0.481 ≈ 95,850 bits
k = (95,850 / 10,000) * 0.693 ≈ 6.6 ≈ 7 hash functions
```

This is stored as 95,850 / 64 ≈ 1,498 `long` values (12 KB).

### 5.3 Double Hashing (Kirsch-Mitzenmacher)

Rather than implementing `k` independent hash functions, the filter uses two hash functions (`h1` and `h2`) and computes:

```
hash_i(x) = (h1(x) + i * h2(x)) mod m
```

This is the Kirsch-Mitzenmacher technique, which gives effectively independent positions with only two hash computations.

```java
public void add(String element) {
    long combined = murmur3Hash64(element);
    int h1 = (int) (combined >>> 32);
    int h2 = (int) combined;

    for (int i = 0; i < k; i++) {
        int position = Math.floorMod(h1 + i * h2, m);
        setBit(position);
    }
    count.incrementAndGet();
}
```

### 5.4 MurmurHash3 from Scratch

The filter uses MurmurHash3 (32-bit variant) invoked twice with different seeds to produce a 64-bit hash.

**MurmurHash3 algorithm**:
1. Process input in 4-byte blocks, multiplying by constants `c1=0xcc9e2d51` and `c2=0x1b873593`.
2. Rotate and XOR the result to mix bits.
3. Handle the remaining 1–3 byte "tail" with a partial block.
4. Apply a finalisation mix (`fmix32`) to eliminate bias.

```java
static int murmur3_32(byte[] data, int seed) {
    final int c1 = 0xcc9e2d51;
    final int c2 = 0x1b873593;

    int h = seed;
    int numBlocks = data.length / 4;

    // Process 4-byte blocks
    for (int i = 0; i < numBlocks; i++) {
        int k = readLittleEndianInt(data, i * 4);
        k *= c1;
        k = Integer.rotateLeft(k, 15);
        k *= c2;
        h ^= k;
        h = Integer.rotateLeft(h, 13);
        h = h * 5 + 0xe6546b64;
    }

    // Handle remaining 1-3 bytes
    int tail = numBlocks * 4;
    int k1 = 0;
    switch (data.length & 3) {
        case 3: k1 ^= (data[tail + 2] & 0xFF) << 16;
        case 2: k1 ^= (data[tail + 1] & 0xFF) << 8;
        case 1: k1 ^= (data[tail] & 0xFF);
                k1 *= c1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= c2;
                h ^= k1;
    }

    h ^= data.length;
    h = fmix32(h);
    return h;
}
```

The constants `c1` and `c2` were carefully chosen through empirical avalanche testing to ensure that small changes in input produce large, unpredictable changes in output.

### 5.5 Token Bucket: Lazy Refill Algorithm

The token bucket allows up to `capacity` tokens per client. Tokens refill at `refillRatePerSecond` tokens/second. Each request consumes one token.

**Naive approach**: Background thread increments token count every millisecond. Cost: one timer per active client.

**This implementation**: Lazy refill. Tokens are calculated on demand based on elapsed time since last refill:

```
elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0
newTokens = elapsedSeconds * refillRate
tokens = min(capacity, tokens + newTokens)
lastRefillNanos = now
```

```java
private void refill(long nowNanos, int capacity, double refillRate) {
    long elapsedNanos = nowNanos - lastRefillNanos;
    if (elapsedNanos <= 0) return;

    double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
    double newTokens = elapsedSeconds * refillRate;
    tokens = Math.min(capacity, tokens + newTokens);
    lastRefillNanos = nowNanos;
}

synchronized boolean tryConsume(int capacity, double refillRate) {
    long now = System.nanoTime();
    refill(now, capacity, refillRate);
    lastAccessNanos = now;

    if (tokens >= 1.0) {
        tokens -= 1.0;
        return true;
    }
    return false;
}
```

**Cost**: O(1) space per active client, no background threads, O(1) per request.

---

## 6. NIO Reactor Event Loop

### 6.1 The Reactor Pattern

The gateway uses the **Reactor** pattern (also called the **proactor** pattern in async I/O literature). A single thread (or a pool of threads) multiplexes thousands of connections via a **Selector**.

```java
while (running.get()) {
    int readyCount = selector.select(1000);  // Block until events, timeout 1s
    if (readyCount == 0) continue;

    var selectedKeys = selector.selectedKeys().iterator();
    while (selectedKeys.hasNext()) {
        SelectionKey key = selectedKeys.next();
        selectedKeys.remove();

        if (!key.isValid()) continue;

        try {
            if (key.isAcceptable()) {
                handleAccept(key);
            } else if (key.isReadable()) {
                handleRead(key);
            } else if (key.isWritable()) {
                handleWrite(key);
            }
        } catch (CancelledKeyException e) {
            closeConnection(key);
        }
    }
}
```

This is the same pattern used by Nginx, Redis, and Node.js. It avoids the thread-per-connection model (which scales poorly beyond a few thousand connections) by multiplexing all I/O through a single kernel call (`epoll` on Linux, `kqueue` on macOS).

### 6.2 Connection State Machine

Each client connection transitions through states:

```
┌─────────────┐
│  ACCEPT     │
│  (new conn) │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  READ       │  OP_READ registered
│  (wait for  │  Selector blocks waiting for data
│   data)     │
└──────┬──────┘
       │
       │ Data arrives
       ▼
┌─────────────┐
│  PARSE      │  parseHttpRequest()
│  (HTTP)     │  Extract method, path, headers, body
└──────┬──────┘
       │
       │ Complete request
       ▼
┌─────────────┐
│  PROCESS    │  Handler applies business logic
│  (invoke    │  (rate limiting, routing, etc.)
│   handler)  │
└──────┬──────┘
       │
       │ Response ready
       ▼
┌─────────────┐
│  WRITE      │  OP_WRITE registered
│  (send      │  Selector wakes when socket buffer has space
│   response) │
└──────┬──────┘
       │
       │ All bytes flushed
       ▼
┌─────────────┐
│  KEEP_ALIVE │  Switch back to OP_READ
│  (ready for │  (or close if Connection: close)
│   next req) │
└─────────────┘
```

### 6.3 Per-Connection Buffers

Each connection maintains its own `ConnectionState`:

```java
static final class ConnectionState {
    private final ByteBuffer readBuffer;      // 16 KB for incoming data
    private final ByteBuffer writeBuffer;     // 16 KB for outgoing data
    private ByteBuffer overflowWrite;         // For responses larger than 16 KB

    ByteBuffer readBuffer() { return readBuffer; }
    ByteBuffer writeBuffer() { return writeBuffer; }
}
```

When `handleRead()` receives data:
1. Read from socket into `readBuffer`.
2. Flip the buffer (`buf.flip()`) to switch from write to read mode.
3. Try to parse an HTTP request.
4. If complete, clear the buffer and process.
5. If incomplete, `buf.compact()` to preserve unread bytes for the next read event.

### 6.4 Non-Blocking I/O and Virtual Threads

The event loop itself runs on a **virtual thread**:

```java
eventLoopThread = Thread.ofVirtual()
        .name("gateway-event-loop")
        .start(this::eventLoop);
```

Virtual threads (Java 21+) are lightweight, and the `Selector.select()` call parks the virtual thread without occupying a carrier thread. This allows the OS scheduler to run other work while this virtual thread waits for I/O.

---

## 7. Request Routing

### 7.1 Longest-Prefix Matching via Reverse-Sorted TreeMap

The router uses a `TreeMap` with a reverse-order comparator to implement longest-prefix matching in O(log n) time.

```java
private final TreeMap<String, String> routes = new TreeMap<>(Comparator.reverseOrder());

public void addRoute(String pathPrefix, String serviceName) {
    routes.put(pathPrefix, serviceName);
}

public Optional<RoutingResult> route(String path) {
    for (var entry : routes.entrySet()) {
        if (path.startsWith(entry.getKey())) {
            String serviceName = entry.getValue();
            // Match found -- get the endpoint
            var endpoints = registry.getEndpoints(serviceName);
            ServiceEndpoint selected = selectEndpoint(serviceName, endpoints.get());
            String strippedPath = stripPrefix(path, entry.getKey());
            return Optional.of(new RoutingResult(serviceName, selected, strippedPath));
        }
    }
    return Optional.empty();
}
```

**Example routes** (in reverse-sorted order):

```
/api/v1/rides/schedule
/api/v1/rides
/api/v1/users
/api/v1
/api
/
```

For a request path `/api/v1/rides/123/cancel`:
1. Check `/api/v1/rides/schedule` → no match.
2. Check `/api/v1/rides` → **MATCH**. Strip prefix → `/123/cancel`. Route to rides service.

The reverse sort ensures longer prefixes are checked before shorter ones.

### 7.2 Load Balancing: Weighted Round-Robin

When a service has multiple backend endpoints, the router selects one using round-robin:

```java
private ServiceEndpoint selectEndpoint(String serviceName, List<ServiceEndpoint> endpoints) {
    if (endpoints.size() == 1) return endpoints.getFirst();

    AtomicInteger counter = roundRobinCounters.get(serviceName);
    int index = Math.abs(counter.getAndIncrement() % endpoints.size());
    return endpoints.get(index);
}
```

Each service has its own atomic counter. On each route, the counter increments and selects `endpoints[counter % endpoints.size()]`, cycling through all endpoints.

### 7.3 Path Prefix Stripping

After routing, the matched prefix is stripped from the path. This allows backend services to be agnostic of the gateway's routing topology.

```java
private String stripPrefix(String path, String prefix) {
    if (prefix.equals("/")) return path;
    String remainder = path.substring(prefix.length());
    return remainder.isEmpty() ? "/" : remainder;
}
```

Example:
- Incoming: `/api/v1/rides/123`
- Matched prefix: `/api/v1/rides`
- Stripped path: `/123`
- Forwarded to rides service as: `/123`

---

## 8. See Also

- [RFC 8446 — TLS 1.3 Specification](https://tools.ietf.org/html/rfc8446)
- [RFC 1035 — Domain Names: Implementation and Specification](https://tools.ietf.org/html/rfc1035)
- [RFC 7301 — Transport Layer Security (TLS) Application-Layer Protocol Negotiation Extension](https://tools.ietf.org/html/rfc7301)
- [Bloom Filters by Example](https://brilliant.org/wiki/bloom-filter/)
- [Scalable Network I/O with the Reactor Pattern](http://www.dre.vanderbilt.edu/~schmidt/PDF/reactor-sicp.pdf)
- [The Linux Kernel's epoll API](https://man7.org/linux/man-pages/man7/epoll.7.html)
- [Java NIO Tutorial](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/package-summary.html)
- [Project Loom: Virtual Threads](https://openjdk.org/projects/loom/)
