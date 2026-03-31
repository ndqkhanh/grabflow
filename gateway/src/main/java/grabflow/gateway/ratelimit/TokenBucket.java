package grabflow.gateway.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-client token bucket rate limiter.
 *
 * <p>The token bucket algorithm allows bursts up to the bucket capacity while
 * maintaining a sustained rate over time. Each client (identified by IP) gets
 * their own bucket. Tokens refill at a steady rate; each request consumes one token.
 * If the bucket is empty, the request is rejected.</p>
 *
 * <h3>Algorithm</h3>
 * <pre>
 *   tokens = min(capacity, tokens + (now - lastRefill) * refillRate)
 *   if tokens >= 1:
 *       tokens -= 1
 *       ALLOW
 *   else:
 *       REJECT (429 Too Many Requests)
 * </pre>
 *
 * <p>This implementation uses lazy refill: tokens are not added on a timer,
 * but calculated on each request based on elapsed time. This avoids background
 * threads and scales to millions of clients.</p>
 */
public class TokenBucket {

    private final int capacity;
    private final double refillRatePerSecond;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param capacity             max tokens per client (burst size)
     * @param refillRatePerSecond  tokens added per second (sustained rate)
     */
    public TokenBucket(int capacity, double refillRatePerSecond) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        if (refillRatePerSecond <= 0) throw new IllegalArgumentException("Refill rate must be positive");
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
    }

    /**
     * Attempts to consume one token for the given client.
     *
     * @param clientId client identifier (typically IP address)
     * @return true if the request is allowed, false if rate-limited
     */
    public boolean tryAcquire(String clientId) {
        Bucket bucket = buckets.computeIfAbsent(clientId,
                k -> new Bucket(capacity, System.nanoTime()));
        return bucket.tryConsume(capacity, refillRatePerSecond);
    }

    /**
     * Returns the number of tokens currently available for a client.
     * Returns the full capacity if the client has not been seen.
     */
    public double availableTokens(String clientId) {
        Bucket bucket = buckets.get(clientId);
        if (bucket == null) return capacity;
        return bucket.currentTokens(capacity, refillRatePerSecond);
    }

    /**
     * Removes expired buckets that haven't been used recently.
     * Should be called periodically to prevent memory leaks from departed clients.
     *
     * @param maxIdleNanos remove buckets idle for longer than this duration
     * @return number of buckets evicted
     */
    public int evictIdle(long maxIdleNanos) {
        long now = System.nanoTime();
        int evicted = 0;
        var it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (now - entry.getValue().lastAccessNanos() > maxIdleNanos) {
                it.remove();
                evicted++;
            }
        }
        return evicted;
    }

    /**
     * Returns the number of tracked clients.
     */
    public int clientCount() {
        return buckets.size();
    }

    /**
     * Internal bucket state for a single client. Thread-safe via synchronized.
     * Uses lazy refill: tokens are computed on access, not on a timer.
     */
    static final class Bucket {
        private double tokens;
        private long lastRefillNanos;
        private long lastAccessNanos;

        Bucket(int initialTokens, long nowNanos) {
            this.tokens = initialTokens;
            this.lastRefillNanos = nowNanos;
            this.lastAccessNanos = nowNanos;
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

        synchronized double currentTokens(int capacity, double refillRate) {
            refill(System.nanoTime(), capacity, refillRate);
            return tokens;
        }

        long lastAccessNanos() {
            return lastAccessNanos;
        }

        private void refill(long nowNanos, int capacity, double refillRate) {
            long elapsedNanos = nowNanos - lastRefillNanos;
            if (elapsedNanos <= 0) return;

            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
            double newTokens = elapsedSeconds * refillRate;
            tokens = Math.min(capacity, tokens + newTokens);
            lastRefillNanos = nowNanos;
        }
    }
}
