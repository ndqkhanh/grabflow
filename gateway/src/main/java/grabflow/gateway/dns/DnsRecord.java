package grabflow.gateway.dns;

import java.time.Instant;

/**
 * Immutable DNS resource record with TTL tracking.
 *
 * <p>DNS resource records (RRs) are the fundamental data unit in DNS (RFC 1035 §3.2).
 * Each record carries: owner name, type, class, TTL, and RDATA (record data).
 * This class models the subset used by the gateway resolver: A, AAAA, and CNAME.
 *
 * <p>TTL (Time To Live) is the number of seconds a record may be cached before it
 * must be re-fetched from an authoritative server. Expiry is computed relative to
 * {@code createdAt}, which is set when the record is first stored in the cache.
 *
 * @param name      Fully-qualified domain name this record belongs to (e.g. "www.example.com")
 * @param type      DNS record type (A, AAAA, or CNAME)
 * @param value     Resolved value: IPv4 string for A, IPv6 string for AAAA, target FQDN for CNAME
 * @param ttl       Time-to-live in seconds as received from the upstream DNS server
 * @param createdAt Timestamp at which this record was inserted into the local cache
 */
public record DnsRecord(
        String name,
        RecordType type,
        String value,
        int ttl,
        Instant createdAt
) {

    /**
     * DNS record types supported by the gateway resolver.
     *
     * <p>Values correspond to the QTYPE/TYPE codes defined in RFC 1035 §3.2.2 and RFC 3596.
     */
    public enum RecordType {
        /** IPv4 host address (RFC 1035 §3.4.1). Wire type code: 1 */
        A(1),
        /** IPv6 host address (RFC 3596). Wire type code: 28 */
        AAAA(28),
        /** Canonical name alias (RFC 1035 §3.3.1). Wire type code: 5 */
        CNAME(5);

        /** The numeric TYPE value used in DNS wire-format packets. */
        public final int wireCode;

        RecordType(int wireCode) {
            this.wireCode = wireCode;
        }

        /**
         * Resolve a wire-format type code to a {@link RecordType}.
         *
         * @param code numeric DNS TYPE value
         * @return matching enum constant, or {@code null} if not recognised
         */
        public static RecordType fromWireCode(int code) {
            for (RecordType rt : values()) {
                if (rt.wireCode == code) return rt;
            }
            return null;
        }
    }

    /**
     * Returns {@code true} if this record's TTL has elapsed since it was cached.
     *
     * <p>A record is expired when {@code now >= createdAt + ttl}.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(createdAt.plusSeconds(ttl));
    }
}
