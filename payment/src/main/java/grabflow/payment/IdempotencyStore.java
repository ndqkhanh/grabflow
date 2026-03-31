package grabflow.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store that prevents duplicate payment processing by
 * caching results keyed on an <em>idempotency key</em>.
 *
 * <h2>Why Idempotency?</h2>
 * In distributed systems, network retries can cause a client to send the same
 * payment request multiple times. Without idempotency, each retry would create
 * a new charge. The {@code IdempotencyStore} solves this by returning the
 * <em>same cached result</em> for any request that carries a key already seen
 * within its TTL window.
 *
 * <h2>Usage Pattern</h2>
 * <ol>
 *   <li>Before processing: call {@link #check(String)}. If present, return it.</li>
 *   <li>After processing: call {@link #store(String, String, Duration)}.</li>
 * </ol>
 *
 * <h2>Eviction</h2>
 * Expired entries are not removed automatically. Call {@link #evictExpired()}
 * periodically (e.g., via a scheduled task) to reclaim memory.
 */
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    /**
     * A stored idempotency record.
     *
     * @param key       the idempotency key supplied by the client
     * @param result    the serialized result that was returned to the client
     * @param createdAt when this record was first stored
     * @param expiresAt when this record ceases to be valid
     */
    public record IdempotencyRecord(
            String key,
            String result,
            Instant createdAt,
            Instant expiresAt
    ) {
        /** Returns {@code true} if this record has passed its expiry time. */
        public boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    private final ConcurrentHashMap<String, IdempotencyRecord> store = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Look up a previously stored result for the given idempotency key.
     *
     * @param key the idempotency key to look up
     * @return the cached result string if present and not expired, otherwise empty
     */
    public Optional<String> check(String key) {
        IdempotencyRecord record = store.get(key);
        if (record == null) {
            return Optional.empty();
        }
        if (record.isExpired(Instant.now())) {
            log.debug("Idempotency key '{}' found but expired — treating as absent", key);
            return Optional.empty();
        }
        log.debug("Idempotency hit for key '{}'", key);
        return Optional.of(record.result());
    }

    /**
     * Store the result of a successfully processed request.
     *
     * <p>If a non-expired record already exists for {@code key}, it is
     * silently overwritten (last write wins within a single JVM).
     *
     * @param key    the idempotency key supplied by the client
     * @param result the result to cache (e.g., a serialized {@code ChargeResult})
     * @param ttl    how long the result should be considered valid
     */
    public void store(String key, String result, Duration ttl) {
        Instant now = Instant.now();
        IdempotencyRecord record = new IdempotencyRecord(key, result, now, now.plus(ttl));
        store.put(key, record);
        log.debug("Stored idempotency key '{}' expiring at {}", key, record.expiresAt());
    }

    /**
     * Remove all records whose expiry time has passed.
     *
     * <p>This method is safe to call concurrently with {@link #check} and
     * {@link #store}; it uses the atomic {@link ConcurrentHashMap#entrySet()}
     * iterator and removes only entries that are unambiguously expired.
     */
    public void evictExpired() {
        Instant now = Instant.now();
        int before = store.size();
        store.entrySet().removeIf(e -> e.getValue().isExpired(now));
        int removed = before - store.size();
        if (removed > 0) {
            log.debug("Evicted {} expired idempotency record(s)", removed);
        }
    }

    /**
     * Returns the current number of records in the store, including any
     * that may be expired but not yet evicted.
     *
     * @return current store size
     */
    public int size() {
        return store.size();
    }
}
