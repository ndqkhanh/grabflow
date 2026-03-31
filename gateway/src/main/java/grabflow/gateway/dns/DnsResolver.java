package grabflow.gateway.dns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DNS resolver that speaks the DNS wire protocol (RFC 1035) over UDP.
 *
 * <h2>DNS Wire Protocol Overview (RFC 1035 §4)</h2>
 * <p>A DNS message consists of:
 * <ol>
 *   <li><b>Header</b> (12 bytes): ID, flags, and four 2-byte counts (QDCOUNT, ANCOUNT, NSCOUNT, ARCOUNT)</li>
 *   <li><b>Question section</b>: one or more QNAME/QTYPE/QCLASS tuples</li>
 *   <li><b>Answer section</b>: resource records matching the query</li>
 *   <li><b>Authority section</b>: name-server RRs (not used here)</li>
 *   <li><b>Additional section</b>: "glue" RRs (not used here)</li>
 * </ol>
 *
 * <h2>Name Encoding (§4.1.2 / §4.1.4)</h2>
 * <p>Domain names are encoded as a sequence of length-prefixed <em>labels</em>.
 * Each label is: 1-byte length followed by that many ASCII bytes. The name is
 * terminated by a zero-length label (0x00). For example, "www.example.com" encodes as:
 * {@code 03 77 77 77 07 65 78 61 6d 70 6c 65 03 63 6f 6d 00}.
 *
 * <h2>Pointer Compression (§4.1.4)</h2>
 * <p>To save space, a name in the response may use a <em>pointer</em> instead of
 * repeating labels. A pointer is two bytes where the top two bits are both 1:
 * {@code 0xC0xx yy} — the offset into the message where the remainder of the name
 * can be found. This resolver handles pointers recursively.
 *
 * <h2>CNAME Chaining</h2>
 * <p>When the answer section of a response contains a CNAME record, the canonical
 * target must itself be resolved. This resolver follows CNAME chains up to
 * {@value #MAX_CNAME_HOPS} hops to prevent infinite loops.
 */
public class DnsResolver {

    private static final Logger log = LoggerFactory.getLogger(DnsResolver.class);

    /** DNS standard port (UDP). */
    private static final int DNS_PORT = 53;

    /** Socket read timeout in milliseconds per attempt. */
    private static final int TIMEOUT_MS = 2_000;

    /** Number of send attempts before giving up on a query. */
    private static final int MAX_RETRIES = 2;

    /** Maximum UDP DNS message size (RFC 1035 §2.3.4 limits to 512 without EDNS). */
    private static final int MAX_DNS_MSG = 512;

    /** Maximum CNAME chain length before loop detection kicks in. */
    private static final int MAX_CNAME_HOPS = 10;

    /** DNS header flag: Recursion Desired (bit 8 of the flags word). */
    private static final int FLAG_RD = 0x0100;

    /** Response code mask (lower 4 bits of the flags word). */
    private static final int RCODE_MASK = 0x000F;

    private final String upstreamDns;
    private final DnsCache cache;

    /**
     * Create a resolver using Google's public DNS (8.8.8.8) and a new shared cache.
     */
    public DnsResolver() {
        this("8.8.8.8", new DnsCache());
    }

    /**
     * Create a resolver with an explicit upstream DNS server and cache.
     *
     * @param upstreamDns IPv4 address of the upstream recursive resolver (e.g. "8.8.8.8")
     * @param cache       shared {@link DnsCache} instance; records are read from and written to this cache
     */
    public DnsResolver(String upstreamDns, DnsCache cache) {
        this.upstreamDns = upstreamDns;
        this.cache = cache;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Resolve a domain to its A records (IPv4), checking the cache first.
     *
     * @param domain fully-qualified domain name
     * @return list of matching {@link DnsRecord}s (never null, may be empty)
     * @throws IOException if a network error occurs
     */
    public List<DnsRecord> resolve(String domain) throws IOException {
        return resolve(domain, DnsRecord.RecordType.A);
    }

    /**
     * Resolve a domain for a specific record type, checking the cache first.
     *
     * @param domain fully-qualified domain name
     * @param type   desired record type (A, AAAA, or CNAME)
     * @return list of matching {@link DnsRecord}s (never null, may be empty)
     * @throws IOException if a network error occurs
     */
    public List<DnsRecord> resolve(String domain, DnsRecord.RecordType type) throws IOException {
        var cached = cache.get(domain);
        if (cached.isPresent()) {
            List<DnsRecord> filtered = cached.get().stream()
                    .filter(r -> r.type() == type)
                    .toList();
            if (!filtered.isEmpty()) {
                log.debug("resolve cache hit: {} {}", domain, type);
                return filtered;
            }
        }
        return resolveWithoutCache(domain, type);
    }

    /**
     * Resolve a domain, bypassing the cache and always querying the upstream DNS server.
     *
     * <p>On success, results are stored in the cache for future calls.
     *
     * @param domain fully-qualified domain name
     * @param type   desired record type
     * @return list of {@link DnsRecord}s returned by the upstream server
     * @throws IOException if a network or protocol error occurs
     */
    public List<DnsRecord> resolveWithoutCache(String domain, DnsRecord.RecordType type)
            throws IOException {
        log.debug("Querying upstream {} for {} {}", upstreamDns, domain, type);
        return resolveRecursive(domain, type, new HashSet<>());
    }

    // =========================================================================
    // Core resolution with CNAME chain following
    // =========================================================================

    private List<DnsRecord> resolveRecursive(
            String domain,
            DnsRecord.RecordType type,
            Set<String> visited) throws IOException {

        if (visited.size() >= MAX_CNAME_HOPS) {
            log.warn("CNAME chain too long for {}, aborting after {} hops", domain, MAX_CNAME_HOPS);
            return List.of();
        }
        if (!visited.add(domain.toLowerCase())) {
            log.warn("CNAME loop detected at {}", domain);
            return List.of();
        }

        byte[] query = buildQuery(domain, type);
        byte[] response = sendQuery(query);

        List<DnsRecord> records = parseResponse(response, domain);
        if (!records.isEmpty()) {
            cache.put(domain, records);
        }

        // If we got CNAME records but wanted A/AAAA, follow the chain.
        List<DnsRecord> direct = records.stream().filter(r -> r.type() == type).toList();
        if (direct.isEmpty() && type != DnsRecord.RecordType.CNAME) {
            List<DnsRecord> cnames = records.stream()
                    .filter(r -> r.type() == DnsRecord.RecordType.CNAME)
                    .toList();
            if (!cnames.isEmpty()) {
                String target = cnames.getFirst().value();
                log.debug("Following CNAME {} -> {}", domain, target);
                return resolveRecursive(target, type, visited);
            }
        }
        return direct.isEmpty() ? records : direct;
    }

    // =========================================================================
    // DNS Wire Protocol: Query Building
    // =========================================================================

    /**
     * Build a DNS query packet in wire format.
     *
     * <pre>
     * DNS Header (12 bytes, RFC 1035 §4.1.1):
     *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
     *   |                      ID                         |  (2 bytes) random transaction ID
     *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
     *   |QR|   Opcode  |AA|TC|RD|RA|   Z    |   RCODE    |  (2 bytes) flags: QR=0 (query), RD=1
     *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
     *   |                    QDCOUNT                      |  (2 bytes) = 1
     *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
     *   |                    ANCOUNT                      |  (2 bytes) = 0
     *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
     *   |                    NSCOUNT                      |  (2 bytes) = 0
     *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
     *   |                    ARCOUNT                      |  (2 bytes) = 0
     *   +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
     *
     * Question section (RFC 1035 §4.1.2):
     *   QNAME  - domain name as length-prefixed labels, terminated by 0x00
     *   QTYPE  - 2 bytes (1=A, 28=AAAA, 5=CNAME)
     *   QCLASS - 2 bytes = 1 (IN = Internet)
     * </pre>
     *
     * @param domain fully-qualified domain name to query
     * @param type   requested record type
     * @return raw DNS query packet bytes
     */
    public byte[] buildQuery(String domain, DnsRecord.RecordType type) {
        byte[] encodedName = encodeDomainName(domain);

        // Header (12) + encoded name + QTYPE (2) + QCLASS (2)
        ByteBuffer buf = ByteBuffer.allocate(12 + encodedName.length + 4);

        // --- Header ---
        int id = ThreadLocalRandom.current().nextInt(0xFFFF);
        buf.putShort((short) id);          // Transaction ID
        buf.putShort((short) FLAG_RD);     // Flags: QR=0 (query), RD=1
        buf.putShort((short) 1);           // QDCOUNT = 1
        buf.putShort((short) 0);           // ANCOUNT = 0
        buf.putShort((short) 0);           // NSCOUNT = 0
        buf.putShort((short) 0);           // ARCOUNT = 0

        // --- Question ---
        buf.put(encodedName);              // QNAME: length-labelled domain
        buf.putShort((short) type.wireCode); // QTYPE
        buf.putShort((short) 1);           // QCLASS = IN (Internet)

        return buf.array();
    }

    /**
     * Encode a domain name into DNS wire format label sequence.
     *
     * <p>Example: "www.example.com" becomes:
     * {@code 03 'w' 'w' 'w'  07 'e' 'x' 'a' 'm' 'p' 'l' 'e'  03 'c' 'o' 'm'  00}
     *
     * @param domain fully-qualified domain name (trailing dot is optional)
     * @return byte array in DNS name wire format
     */
    public static byte[] encodeDomainName(String domain) {
        // Strip trailing dot if present (root label).
        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        String[] labels = domain.split("\\.");
        // Each label contributes 1 length byte + label bytes; plus 1 terminal zero byte.
        int totalLen = 1; // for terminal 0x00
        for (String label : labels) {
            totalLen += 1 + label.length();
        }
        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        for (String label : labels) {
            buf.put((byte) label.length());
            buf.put(label.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        buf.put((byte) 0); // terminal zero-length label
        return buf.array();
    }

    // =========================================================================
    // DNS Wire Protocol: Response Parsing
    // =========================================================================

    /**
     * Parse a DNS response packet and extract resource records from the answer section.
     *
     * <pre>
     * Response structure (RFC 1035 §4.1):
     *   Header (12 bytes)
     *     ID       - matches query ID
     *     Flags    - QR=1, RCODE in lower 4 bits (0=NOERROR)
     *     QDCOUNT  - number of questions (echo of request)
     *     ANCOUNT  - number of answer RRs
     *     NSCOUNT  - number of authority RRs (skipped)
     *     ARCOUNT  - number of additional RRs (skipped)
     *   Question section (repeated QDCOUNT times, skipped)
     *   Answer section (repeated ANCOUNT times):
     *     NAME      - compressed or uncompressed domain name
     *     TYPE      - 2 bytes record type
     *     CLASS     - 2 bytes (1 = IN)
     *     TTL       - 4 bytes signed integer
     *     RDLENGTH  - 2 bytes: length of RDATA
     *     RDATA     - record data:
     *                 A    : 4 bytes IPv4
     *                 AAAA : 16 bytes IPv6
     *                 CNAME: compressed domain name
     * </pre>
     *
     * @param response raw UDP response bytes
     * @param queryDomain the original query domain (used as fallback for name)
     * @return list of parsed resource records
     */
    public List<DnsRecord> parseResponse(byte[] response, String queryDomain) {
        ByteBuffer buf = ByteBuffer.wrap(response);
        List<DnsRecord> records = new ArrayList<>();

        // --- Header ---
        buf.getShort();                             // ID (ignore for now)
        int flags  = buf.getShort() & 0xFFFF;
        int rcode  = flags & RCODE_MASK;
        int qdcount = buf.getShort() & 0xFFFF;
        int ancount = buf.getShort() & 0xFFFF;
        buf.getShort();                             // NSCOUNT (skip)
        buf.getShort();                             // ARCOUNT (skip)

        if (rcode != 0) {
            log.warn("DNS response RCODE={} for {}", rcode, queryDomain);
            return records;
        }

        // --- Skip Question Section ---
        for (int i = 0; i < qdcount; i++) {
            readName(buf, response);   // QNAME
            buf.getShort();            // QTYPE
            buf.getShort();            // QCLASS
        }

        // --- Parse Answer Section ---
        for (int i = 0; i < ancount; i++) {
            String name = readName(buf, response);   // owner name
            int type    = buf.getShort() & 0xFFFF;
            buf.getShort();                          // CLASS (skip)
            int ttl     = buf.getInt();              // TTL (signed 32-bit)
            int rdlen   = buf.getShort() & 0xFFFF;  // RDLENGTH

            DnsRecord.RecordType recordType = DnsRecord.RecordType.fromWireCode(type);
            if (recordType == null) {
                // Unknown type: skip RDATA bytes and continue.
                buf.position(buf.position() + rdlen);
                continue;
            }

            String value = switch (recordType) {
                case A    -> parseIPv4(buf);
                case AAAA -> parseIPv6(buf);
                case CNAME -> readName(buf, response);
            };

            records.add(new DnsRecord(
                    name.isEmpty() ? queryDomain : name,
                    recordType,
                    value,
                    ttl,
                    Instant.now()
            ));
        }

        return records;
    }

    // =========================================================================
    // Name reading with pointer-compression support
    // =========================================================================

    /**
     * Read a DNS domain name from the buffer, following pointer compression as per RFC 1035 §4.1.4.
     *
     * <p>Two high bits set ({@code 0xC0}) on the first byte of a label indicate a
     * <em>pointer</em>: the lower 6 bits of the first byte and all 8 bits of the
     * second byte form a 14-bit offset from the start of the message. The name
     * continues at that offset. The original buffer position advances past the 2-byte
     * pointer; pointer following does NOT advance further in the original stream.
     *
     * @param buf     buffer with its position at the start of a name field
     * @param message the entire raw DNS message (needed for pointer offsets)
     * @return decoded fully-qualified domain name (without trailing dot)
     */
    public static String readName(ByteBuffer buf, byte[] message) {
        StringBuilder sb = new StringBuilder();
        readNameInto(buf, message, sb, 0);
        // Remove trailing dot if present.
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '.') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * Recursive helper for {@link #readName}; uses {@code depth} to guard against loops.
     */
    private static void readNameInto(ByteBuffer buf, byte[] message, StringBuilder sb, int depth) {
        if (depth > 20) {
            // Safety valve against malformed packets with circular pointer chains.
            return;
        }
        while (buf.hasRemaining()) {
            int labelLen = buf.get() & 0xFF;

            if (labelLen == 0) {
                // Terminal zero-length label: end of name.
                return;
            }

            if ((labelLen & 0xC0) == 0xC0) {
                // Pointer: next byte completes the 14-bit offset.
                int lo     = buf.get() & 0xFF;
                int offset = ((labelLen & 0x3F) << 8) | lo;

                // Follow pointer using a separate view of the message.
                ByteBuffer ptr = ByteBuffer.wrap(message);
                ptr.position(offset);
                readNameInto(ptr, message, sb, depth + 1);
                // After a pointer we do NOT continue reading from buf.
                return;
            }

            // Normal label: read labelLen bytes.
            byte[] labelBytes = new byte[labelLen];
            buf.get(labelBytes);
            sb.append(new String(labelBytes, java.nio.charset.StandardCharsets.US_ASCII));
            sb.append('.');
        }
    }

    // =========================================================================
    // RDATA parsers
    // =========================================================================

    /**
     * Parse a 4-byte IPv4 address from RDATA.
     *
     * <p>RFC 1035 §3.4.1: A RDATA is a 32-bit Internet address in network byte order.
     */
    private static String parseIPv4(ByteBuffer buf) {
        int a = buf.get() & 0xFF;
        int b = buf.get() & 0xFF;
        int c = buf.get() & 0xFF;
        int d = buf.get() & 0xFF;
        return a + "." + b + "." + c + "." + d;
    }

    /**
     * Parse a 16-byte IPv6 address from RDATA.
     *
     * <p>RFC 3596 §2.2: AAAA RDATA is 128 bits in network byte order, formatted as
     * 8 groups of 4 hex digits separated by colons.
     */
    private static String parseIPv6(ByteBuffer buf) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) sb.append(':');
            int hi = buf.get() & 0xFF;
            int lo = buf.get() & 0xFF;
            sb.append(String.format("%04x", (hi << 8) | lo));
        }
        return sb.toString();
    }

    // =========================================================================
    // UDP transport
    // =========================================================================

    /**
     * Send a DNS query over UDP, retrying up to {@value #MAX_RETRIES} times.
     *
     * @param query raw DNS query bytes
     * @return raw DNS response bytes
     * @throws IOException on network failure or timeout after all retries
     */
    private byte[] sendQuery(byte[] query) throws IOException {
        IOException lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return sendOnce(query);
            } catch (SocketTimeoutException e) {
                log.warn("DNS query attempt {} timed out ({}ms)", attempt, TIMEOUT_MS);
                lastException = e;
            }
        }
        throw new IOException("DNS query failed after " + MAX_RETRIES + " attempts", lastException);
    }

    /**
     * Perform a single UDP send/receive cycle.
     */
    private byte[] sendOnce(byte[] query) throws IOException {
        InetAddress server = InetAddress.getByName(upstreamDns);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);

            DatagramPacket request = new DatagramPacket(query, query.length, server, DNS_PORT);
            socket.send(request);

            byte[] buf = new byte[MAX_DNS_MSG];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            socket.receive(response);

            // Trim to actual received length.
            byte[] data = new byte[response.getLength()];
            System.arraycopy(buf, 0, data, 0, data.length);
            return data;
        }
    }
}
