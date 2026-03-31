package grabflow.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Two-tier rate limiting filter combining a Bloom filter (fast path)
 * with a token bucket (precise path).
 *
 * <h3>Design</h3>
 * <pre>
 *   Request arrives with client IP
 *       │
 *       ▼
 *   ┌─────────────────────┐
 *   │ Bloom Filter Check   │  O(1), probabilistic
 *   │ "Is this a known-bad │
 *   │  IP?"                │
 *   └──────┬──────────────┘
 *          │
 *     ┌────┴────┐
 *     │ YES     │ NO
 *     │ (maybe) │ (definitely not)
 *     ▼         ▼
 *   REJECT   ┌─────────────────┐
 *   (403)    │ Token Bucket     │  Per-client, precise
 *            │ "Has this client │
 *            │  exceeded rate?" │
 *            └──────┬──────────┘
 *                   │
 *              ┌────┴────┐
 *              │ YES     │ NO
 *              ▼         ▼
 *            REJECT    ALLOW
 *            (429)
 * </pre>
 *
 * <p>The Bloom filter acts as a fast-path DDoS filter for known-bad IPs.
 * It runs in O(k) time (k hash functions) and uses minimal memory.
 * False positives are acceptable here: a legitimate IP occasionally blocked
 * is less harmful than letting a DDoS through. The token bucket handles
 * precise per-client rate limiting for all IPs that pass the Bloom filter.</p>
 */
public class RateLimitFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final BloomFilter blocklist;
    private final TokenBucket tokenBucket;

    /**
     * @param blocklist   Bloom filter containing known-bad IPs
     * @param tokenBucket per-client rate limiter
     */
    public RateLimitFilter(BloomFilter blocklist, TokenBucket tokenBucket) {
        this.blocklist = blocklist;
        this.tokenBucket = tokenBucket;
    }

    /**
     * Checks whether a request from the given IP should be allowed.
     *
     * @param clientIp the client's IP address string
     * @return the rate limit decision
     */
    public Decision check(String clientIp) {
        // Tier 1: Bloom filter blocklist (O(1), probabilistic)
        if (blocklist.mightContain(clientIp)) {
            log.debug("Blocked by Bloom filter: {}", clientIp);
            return Decision.BLOCKED;
        }

        // Tier 2: Token bucket (precise per-client rate)
        if (!tokenBucket.tryAcquire(clientIp)) {
            log.debug("Rate limited: {}", clientIp);
            return Decision.RATE_LIMITED;
        }

        return Decision.ALLOWED;
    }

    /**
     * Adds an IP to the blocklist. Once added, it cannot be removed
     * (Bloom filter limitation -- use a time-rotated filter for expiry).
     */
    public void blockIp(String ip) {
        blocklist.add(ip);
        log.info("Added to blocklist: {}", ip);
    }

    /**
     * Returns the number of clients currently tracked by the token bucket.
     */
    public int trackedClients() {
        return tokenBucket.clientCount();
    }

    public enum Decision {
        /** Request is allowed */
        ALLOWED,
        /** IP is in the Bloom filter blocklist (403 Forbidden) */
        BLOCKED,
        /** Client exceeded rate limit (429 Too Many Requests) */
        RATE_LIMITED
    }
}
