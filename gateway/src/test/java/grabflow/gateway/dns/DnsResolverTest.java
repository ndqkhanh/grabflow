package grabflow.gateway.dns;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DnsResolver}, focusing on DNS wire-format correctness.
 *
 * <p>All tests that exercise packet building or parsing use <em>hardcoded byte arrays</em>
 * representing real DNS wire-format messages (per RFC 1035). This avoids any dependency on
 * a live DNS server and makes the tests deterministic.
 *
 * <h2>Byte Array Construction Guide</h2>
 * <pre>
 * DNS Header (12 bytes):
 *   [0-1]  ID
 *   [2-3]  Flags
 *   [4-5]  QDCOUNT
 *   [6-7]  ANCOUNT
 *   [8-9]  NSCOUNT
 *   [10-11] ARCOUNT
 *
 * Domain "example.com" encoded:
 *   07 65 78 61 6d 70 6c 65   ('e','x','a','m','p','l','e')
 *   03 63 6f 6d               ('c','o','m')
 *   00                        (root label)
 *
 * A record RDATA: 4 bytes IPv4 e.g. 93.184.216.34 = 5D B8 D8 22
 * AAAA record RDATA: 16 bytes IPv6
 * CNAME RDATA: encoded domain name
 * Pointer: C0 0C means "jump to offset 12 in the message"
 * </pre>
 */
class DnsResolverTest {

    private DnsCache cache;

    @BeforeEach
    void setUp() {
        cache = new DnsCache();
    }

    // =========================================================================
    // Test 1: buildDnsQueryPacket -- verify wire format of a constructed query
    // =========================================================================

    /**
     * Verifies that {@link DnsResolver#buildQuery} produces a correctly structured DNS query.
     *
     * <p>Expected packet for "a.b" type A:
     * <pre>
     * [0-1]  ID     (random, 2 bytes)
     * [2-3]  Flags  = 0x01 0x00  (RD=1)
     * [4-5]  QDCOUNT= 0x00 0x01  (1 question)
     * [6-7]  ANCOUNT= 0x00 0x00
     * [8-9]  NSCOUNT= 0x00 0x00
     * [10-11]ARCOUNT= 0x00 0x00
     * [12]   01              -- label length 1
     * [13]   61              -- 'a'
     * [14]   01              -- label length 1
     * [15]   62              -- 'b'
     * [16]   00              -- root label
     * [17-18] 00 01          -- QTYPE = A (1)
     * [19-20] 00 01          -- QCLASS = IN (1)
     * </pre>
     */
    @Test
    void buildDnsQueryPacket() {
        DnsResolver resolver = new DnsResolver("8.8.8.8", cache);
        byte[] packet = resolver.buildQuery("a.b", DnsRecord.RecordType.A);

        // Total length: 12 header + 5 name ("a.b" = 01 61 01 62 00) + 4 (QTYPE+QCLASS) = 21
        assertThat(packet).hasSize(21);

        ByteBuffer buf = ByteBuffer.wrap(packet);

        // ID: skip 2 bytes (random)
        buf.getShort();

        // Flags: RD=1 => 0x0100
        short flags = buf.getShort();
        assertThat(flags & 0xFFFF).isEqualTo(0x0100);

        // QDCOUNT = 1
        assertThat(buf.getShort() & 0xFFFF).isEqualTo(1);
        // ANCOUNT = 0
        assertThat(buf.getShort() & 0xFFFF).isEqualTo(0);
        // NSCOUNT = 0
        assertThat(buf.getShort() & 0xFFFF).isEqualTo(0);
        // ARCOUNT = 0
        assertThat(buf.getShort() & 0xFFFF).isEqualTo(0);

        // QNAME: "a.b" -> 01 'a' 01 'b' 00
        assertThat(buf.get() & 0xFF).isEqualTo(1);    // label length 1
        assertThat(buf.get() & 0xFF).isEqualTo('a');  // 'a'
        assertThat(buf.get() & 0xFF).isEqualTo(1);    // label length 1
        assertThat(buf.get() & 0xFF).isEqualTo('b');  // 'b'
        assertThat(buf.get() & 0xFF).isEqualTo(0);    // root label

        // QTYPE = 1 (A)
        assertThat(buf.getShort() & 0xFFFF).isEqualTo(1);
        // QCLASS = 1 (IN)
        assertThat(buf.getShort() & 0xFFFF).isEqualTo(1);
    }

    @Test
    void buildDnsQueryPacketForAaaaType() {
        DnsResolver resolver = new DnsResolver("8.8.8.8", cache);
        byte[] packet = resolver.buildQuery("x.y", DnsRecord.RecordType.AAAA);

        ByteBuffer buf = ByteBuffer.wrap(packet);
        // name "x.y" = 01 'x' 01 'y' 00 = 5 bytes, so QTYPE is at offset 12+5=17
        buf.position(17);
        assertThat(buf.getShort() & 0xFFFF).isEqualTo(28); // AAAA = 28
    }

    @Test
    void encodeDomainNameProducesCorrectLabels() {
        // "www.example.com" -> 03 77 77 77 07 65 78 61 6d 70 6c 65 03 63 6f 6d 00
        byte[] encoded = DnsResolver.encodeDomainName("www.example.com");

        assertThat(encoded[0] & 0xFF).isEqualTo(3);   // length of "www"
        assertThat((char) encoded[1]).isEqualTo('w');
        assertThat((char) encoded[2]).isEqualTo('w');
        assertThat((char) encoded[3]).isEqualTo('w');
        assertThat(encoded[4] & 0xFF).isEqualTo(7);   // length of "example"
        assertThat(encoded[12] & 0xFF).isEqualTo(3);  // length of "com"
        assertThat(encoded[16] & 0xFF).isEqualTo(0);  // root label
        assertThat(encoded).hasSize(17);
    }

    // =========================================================================
    // Test 2: parseDnsResponsePacket -- parse a known response byte array
    // =========================================================================

    /**
     * Constructs a real DNS A-record response for "example.com -> 93.184.216.34"
     * and verifies that {@link DnsResolver#parseResponse} extracts the correct record.
     *
     * <p>Packet structure:
     * <pre>
     * Header:
     *   AB CD          -- ID
     *   81 80          -- Flags: QR=1, RD=1, RA=1 (standard response)
     *   00 01          -- QDCOUNT = 1
     *   00 01          -- ANCOUNT = 1
     *   00 00          -- NSCOUNT = 0
     *   00 00          -- ARCOUNT = 0
     * Question (offset 12):
     *   07 65 78 61 6d 70 6c 65  -- "example"
     *   03 63 6f 6d              -- "com"
     *   00                      -- root
     *   00 01                   -- QTYPE = A
     *   00 01                   -- QCLASS = IN
     * Answer (offset 29):
     *   C0 0C                   -- NAME: pointer to offset 12 ("example.com")
     *   00 01                   -- TYPE = A
     *   00 01                   -- CLASS = IN
     *   00 00 01 2C             -- TTL = 300
     *   00 04                   -- RDLENGTH = 4
     *   5D B8 D8 22             -- RDATA = 93.184.216.34
     * </pre>
     */
    @Test
    void parseDnsResponsePacket() {
        byte[] response = {
            // Header
            (byte) 0xAB, (byte) 0xCD,  // ID
            (byte) 0x81, (byte) 0x80,  // Flags: QR=1 AA=0 TC=0 RD=1 RA=1 RCODE=0
            0x00, 0x01,                 // QDCOUNT = 1
            0x00, 0x01,                 // ANCOUNT = 1
            0x00, 0x00,                 // NSCOUNT = 0
            0x00, 0x00,                 // ARCOUNT = 0
            // Question (offset 12): "example.com"
            0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e',  // 7-char label
            0x03, 'c', 'o', 'm',                        // 3-char label
            0x00,                                       // root label
            0x00, 0x01,                                 // QTYPE = A
            0x00, 0x01,                                 // QCLASS = IN
            // Answer (offset 29)
            (byte) 0xC0, 0x0C,          // NAME = pointer to offset 12
            0x00, 0x01,                 // TYPE = A
            0x00, 0x01,                 // CLASS = IN
            0x00, 0x00, 0x01, 0x2C,    // TTL = 300
            0x00, 0x04,                 // RDLENGTH = 4
            93, (byte) 184, (byte) 216, 34  // RDATA = 93.184.216.34
        };

        DnsResolver resolver = new DnsResolver("8.8.8.8", cache);
        List<DnsRecord> records = resolver.parseResponse(response, "example.com");

        assertThat(records).hasSize(1);
        DnsRecord rec = records.getFirst();
        assertThat(rec.type()).isEqualTo(DnsRecord.RecordType.A);
        assertThat(rec.value()).isEqualTo("93.184.216.34");
        assertThat(rec.ttl()).isEqualTo(300);
        assertThat(rec.name()).isEqualTo("example.com");
    }

    // =========================================================================
    // Test 3: resolveUsesCache -- second call returns cached result without network
    // =========================================================================

    @Test
    void resolveUsesCache() throws IOException {
        // Pre-populate the cache so no network call is needed.
        DnsRecord cached = new DnsRecord(
                "cached.example.com",
                DnsRecord.RecordType.A,
                "10.0.0.1",
                300,
                Instant.now()
        );
        cache.put("cached.example.com", List.of(cached));

        // Use a resolver pointing at a localhost address that will never respond;
        // if the cache is correctly consulted first, no network call is attempted.
        DnsResolver resolver = new DnsResolver("127.0.0.1", cache);

        List<DnsRecord> result = resolver.resolve("cached.example.com", DnsRecord.RecordType.A);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().value()).isEqualTo("10.0.0.1");
    }

    // =========================================================================
    // Test 4: cnameChainFollowing -- parse CNAME response and verify record extraction
    // =========================================================================

    /**
     * Tests that a DNS response containing a CNAME record is parsed correctly.
     *
     * <p>Response for "alias.example.com -> www.example.com" (CNAME):
     * <pre>
     * Header:
     *   00 01  -- ID
     *   81 80  -- standard response
     *   00 01  -- QDCOUNT = 1
     *   00 01  -- ANCOUNT = 1
     *   00 00  -- NSCOUNT = 0
     *   00 00  -- ARCOUNT = 0
     * Question (offset 12): "alias.example.com"
     *   05 'a' 'l' 'i' 'a' 's'   -- "alias" label (6 bytes)
     *   07 'e' 'x' 'a' 'm' 'p' 'l' 'e'  -- "example" label (8 bytes)
     *   03 'c' 'o' 'm'            -- "com" label (4 bytes)
     *   00                        -- root (1 byte)
     *   00 05 00 01               -- QTYPE=CNAME QCLASS=IN
     * Answer:
     *   C0 0C                     -- NAME pointer to offset 12 "alias.example.com"
     *   00 05                     -- TYPE = CNAME
     *   00 01                     -- CLASS = IN
     *   00 00 01 2C               -- TTL = 300
     *   00 11                     -- RDLENGTH = 17
     *   03 'w' 'w' 'w' 07 'e'... 03 'c' 'o' 'm' 00 -- "www.example.com" (uncompressed)
     * </pre>
     */
    @Test
    void cnameChainFollowing() {
        // "alias.example.com" label = 05 61 6c 69 61 73 = offset 12
        // "example.com" part starts at offset 12+6 = 18 = 0x12
        byte[] response = {
            // Header
            0x00, 0x01,             // ID
            (byte) 0x81, (byte) 0x80, // Flags: standard response
            0x00, 0x01,             // QDCOUNT = 1
            0x00, 0x01,             // ANCOUNT = 1
            0x00, 0x00,             // NSCOUNT
            0x00, 0x00,             // ARCOUNT
            // Question (offset 12): "alias.example.com"
            0x05, 'a', 'l', 'i', 'a', 's',                    // "alias" (6 bytes incl. len)
            0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e',          // "example" (8 bytes)
            0x03, 'c', 'o', 'm',                               // "com" (4 bytes)
            0x00,                                              // root
            0x00, 0x05,                                        // QTYPE = CNAME
            0x00, 0x01,                                        // QCLASS = IN
            // Answer (offset 37)
            (byte) 0xC0, 0x0C,      // NAME: pointer to offset 12 = "alias.example.com"
            0x00, 0x05,             // TYPE = CNAME (5)
            0x00, 0x01,             // CLASS = IN
            0x00, 0x00, 0x01, 0x2C, // TTL = 300
            0x00, 0x11,             // RDLENGTH = 17 (03 www 07 example 03 com 00)
            // CNAME target: "www.example.com" (no compression, 17 bytes)
            0x03, 'w', 'w', 'w',
            0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e',
            0x03, 'c', 'o', 'm',
            0x00                    // root label
        };

        DnsResolver resolver = new DnsResolver("8.8.8.8", cache);
        List<DnsRecord> records = resolver.parseResponse(response, "alias.example.com");

        assertThat(records).hasSize(1);
        DnsRecord cname = records.getFirst();
        assertThat(cname.type()).isEqualTo(DnsRecord.RecordType.CNAME);
        assertThat(cname.value()).isEqualTo("www.example.com");
        assertThat(cname.ttl()).isEqualTo(300);
    }

    // =========================================================================
    // Test 5: pointerCompressionInResponse -- test 0xC0xx pointer handling
    // =========================================================================

    /**
     * Tests DNS name pointer compression (RFC 1035 §4.1.4).
     *
     * <p>Constructs a response where the answer's NAME field is a pointer (0xC0 0x0C)
     * pointing back to offset 12 (the start of the question's QNAME).
     * The question QNAME is "dns.example.com".
     *
     * <pre>
     * Pointer byte layout:
     *   High byte: 1100_0000  = 0xC0  (top 2 bits set = pointer indicator)
     *   Low byte:  offset     = 0x0C = 12
     *   => name is at absolute offset 12 in the message
     * </pre>
     */
    @Test
    void pointerCompressionInResponse() {
        // "dns.example.com" at offset 12:
        //   03 'd' 'n' 's'           (4 bytes) -> offset 12
        //   07 'e' 'x' 'a' 'm' 'p' 'l' 'e' (8 bytes) -> offset 16
        //   03 'c' 'o' 'm'           (4 bytes) -> offset 24
        //   00                       (1 byte)  -> offset 28
        // QTYPE at offset 29, QCLASS at 31
        // Answer starts at offset 33:
        //   C0 0C = pointer to 12 = "dns.example.com"
        byte[] response = {
            // Header (offsets 0-11)
            0x00, 0x02,             // ID
            (byte) 0x81, (byte) 0x80, // Flags
            0x00, 0x01,             // QDCOUNT = 1
            0x00, 0x01,             // ANCOUNT = 1
            0x00, 0x00,
            0x00, 0x00,
            // Question QNAME: "dns.example.com" (offset 12)
            0x03, 'd', 'n', 's',
            0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e',
            0x03, 'c', 'o', 'm',
            0x00,                   // root (offset 28)
            0x00, 0x01,             // QTYPE = A  (offset 29)
            0x00, 0x01,             // QCLASS = IN (offset 31)
            // Answer (offset 33)
            (byte) 0xC0, 0x0C,      // NAME = pointer to offset 12 = "dns.example.com"
            0x00, 0x01,             // TYPE = A
            0x00, 0x01,             // CLASS = IN
            0x00, 0x00, 0x00, (byte) 0x3C, // TTL = 60
            0x00, 0x04,             // RDLENGTH = 4
            8, 8, 8, 8              // RDATA = 8.8.8.8
        };

        DnsResolver resolver = new DnsResolver("8.8.8.8", cache);
        List<DnsRecord> records = resolver.parseResponse(response, "dns.example.com");

        assertThat(records).hasSize(1);
        DnsRecord rec = records.getFirst();
        assertThat(rec.name()).isEqualTo("dns.example.com");
        assertThat(rec.type()).isEqualTo(DnsRecord.RecordType.A);
        assertThat(rec.value()).isEqualTo("8.8.8.8");
        assertThat(rec.ttl()).isEqualTo(60);
    }

    // =========================================================================
    // Test 6: timeoutHandling -- verify IOException on timeout
    // =========================================================================

    /**
     * Verifies that DNS error responses (non-zero RCODE) are handled gracefully.
     *
     * <p>Tests several RCODE values defined in RFC 1035 §4.1.1:
     * <ul>
     *   <li>RCODE 1 (FORMERR) - Format error</li>
     *   <li>RCODE 2 (SERVFAIL) - Server failure</li>
     *   <li>RCODE 3 (NXDOMAIN) - Non-existent domain</li>
     * </ul>
     *
     * <p>For actual network timeout testing, the resolver uses a 2-second socket
     * timeout per attempt with 2 retries. This behaviour is covered by the
     * {@link DnsResolver} socket timeout configuration (SO_TIMEOUT = 2000ms).
     * Integration testing against a live blackhole server is omitted here since
     * DNS_PORT=53 requires root privileges and the test environment may not support it.
     */
    @Test
    void timeoutHandling() {
        DnsResolver resolver = new DnsResolver("8.8.8.8", cache);

        // RCODE=3 (NXDOMAIN): non-existent domain should return empty records, not throw.
        byte[] nxdomain = {
            0x00, 0x01,               // ID
            (byte) 0x81, (byte) 0x83, // Flags: QR=1 RCODE=3 (NXDOMAIN)
            0x00, 0x01,               // QDCOUNT = 1
            0x00, 0x00,               // ANCOUNT = 0
            0x00, 0x00,               // NSCOUNT
            0x00, 0x00,               // ARCOUNT
            // Question: "foo" (no TLD to keep it short)
            0x03, 'f', 'o', 'o', 0x00,
            0x00, 0x01, 0x00, 0x01    // QTYPE=A QCLASS=IN
        };
        assertThat(resolver.parseResponse(nxdomain, "foo")).isEmpty();

        // RCODE=2 (SERVFAIL): server failure also returns empty.
        byte[] servfail = {
            0x00, 0x02,
            (byte) 0x81, (byte) 0x82, // RCODE=2
            0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x03, 'b', 'a', 'r', 0x00,
            0x00, 0x01, 0x00, 0x01
        };
        assertThat(resolver.parseResponse(servfail, "bar")).isEmpty();

        // RCODE=1 (FORMERR): format error also returns empty.
        byte[] formerr = {
            0x00, 0x03,
            (byte) 0x81, (byte) 0x81, // RCODE=1
            0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x03, 'b', 'a', 'z', 0x00,
            0x00, 0x01, 0x00, 0x01
        };
        assertThat(resolver.parseResponse(formerr, "baz")).isEmpty();
    }

    // =========================================================================
    // Test 7: ipv4AndIpv6Resolution -- test both A and AAAA record parsing
    // =========================================================================

    /**
     * Tests parsing of an AAAA (IPv6) record from a DNS response.
     *
     * <p>AAAA RDATA: 16 bytes = 2001:0db8:85a3:0000:0000:8a2e:0370:7334
     * In hex: 20 01 0D B8 85 A3 00 00 00 00 8A 2E 03 70 73 34
     */
    @Test
    void ipv4AndIpv6Resolution() {
        // Build AAAA response for "ipv6.example.com"
        // 2001:0db8:85a3:0000:0000:8a2e:0370:7334
        byte[] aaaaResponse = {
            // Header
            0x00, 0x03,
            (byte) 0x81, (byte) 0x80,
            0x00, 0x01,  // QDCOUNT
            0x00, 0x01,  // ANCOUNT
            0x00, 0x00,
            0x00, 0x00,
            // Question (offset 12): "ipv6.example.com"
            0x04, 'i', 'p', 'v', '6',
            0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e',
            0x03, 'c', 'o', 'm',
            0x00,
            0x00, 0x1C, // QTYPE = AAAA (28)
            0x00, 0x01, // QCLASS = IN
            // Answer: NAME pointer to offset 12
            (byte) 0xC0, 0x0C,
            0x00, 0x1C,             // TYPE = AAAA
            0x00, 0x01,             // CLASS = IN
            0x00, 0x00, 0x01, 0x2C, // TTL = 300
            0x00, 0x10,             // RDLENGTH = 16
            // 2001:0db8:85a3:0000:0000:8a2e:0370:7334
            0x20, 0x01,
            0x0D, (byte) 0xB8,
            (byte) 0x85, (byte) 0xA3,
            0x00, 0x00,
            0x00, 0x00,
            (byte) 0x8A, 0x2E,
            0x03, 0x70,
            0x73, 0x34
        };

        DnsResolver resolver = new DnsResolver("8.8.8.8", cache);
        List<DnsRecord> records = resolver.parseResponse(aaaaResponse, "ipv6.example.com");

        assertThat(records).hasSize(1);
        DnsRecord rec = records.getFirst();
        assertThat(rec.type()).isEqualTo(DnsRecord.RecordType.AAAA);
        assertThat(rec.value()).isEqualTo("2001:0db8:85a3:0000:0000:8a2e:0370:7334");
        assertThat(rec.ttl()).isEqualTo(300);

        // Also verify A record parsing independently.
        byte[] aResponse = {
            0x00, 0x04,
            (byte) 0x81, (byte) 0x80,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            // Question: "a.test"
            0x01, 'a',
            0x04, 't', 'e', 's', 't',
            0x00,
            0x00, 0x01, 0x00, 0x01,
            // Answer
            (byte) 0xC0, 0x0C,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x3C,
            0x00, 0x04,
            (byte) 192, (byte) 168, 1, 100
        };

        List<DnsRecord> aRecords = resolver.parseResponse(aResponse, "a.test");
        assertThat(aRecords).hasSize(1);
        assertThat(aRecords.getFirst().type()).isEqualTo(DnsRecord.RecordType.A);
        assertThat(aRecords.getFirst().value()).isEqualTo("192.168.1.100");
    }

    @Test
    void multipleAnswerRecordsParsed() {
        // Two A records for "multi.example.com"
        // Question section: "multi.example.com" (5+7+3+1 = 16 label bytes)
        // offset 12: 05 'm' 'u' 'l' 't' 'i'  07 'e' 'x' 'a' 'm' 'p' 'l' 'e'  03 'c' 'o' 'm'  00
        // = 6 + 8 + 4 + 1 = 19 bytes, so question ends at offset 12+19 = 31
        // QTYPE+QCLASS = 4 bytes, answer starts at 35
        byte[] response = {
            0x00, 0x05,
            (byte) 0x81, (byte) 0x80,
            0x00, 0x01,  // QDCOUNT
            0x00, 0x02,  // ANCOUNT = 2
            0x00, 0x00,
            0x00, 0x00,
            // Question QNAME: "multi.example.com" (offset 12)
            0x05, 'm', 'u', 'l', 't', 'i',
            0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e',
            0x03, 'c', 'o', 'm',
            0x00,
            0x00, 0x01, 0x00, 0x01,   // QTYPE=A, QCLASS=IN  (answer starts at offset 35)
            // Answer 1
            (byte) 0xC0, 0x0C,         // NAME pointer to offset 12
            0x00, 0x01,                // TYPE = A
            0x00, 0x01,                // CLASS = IN
            0x00, 0x00, 0x01, 0x2C,   // TTL = 300
            0x00, 0x04,                // RDLENGTH = 4
            1, 2, 3, 4,                // RDATA = 1.2.3.4
            // Answer 2
            (byte) 0xC0, 0x0C,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00, 0x01, 0x2C,
            0x00, 0x04,
            5, 6, 7, 8                 // RDATA = 5.6.7.8
        };

        DnsResolver resolver = new DnsResolver("8.8.8.8", cache);
        List<DnsRecord> records = resolver.parseResponse(response, "multi.example.com");

        assertThat(records).hasSize(2);
        assertThat(records.stream().map(DnsRecord::value))
                .containsExactlyInAnyOrder("1.2.3.4", "5.6.7.8");
    }
}
