package grabflow.gateway.dns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Thread-safe, TTL-aware DNS record cache with background eviction.
 *
 * <p>Stores resolved DNS records keyed by domain name. Each entry is a list of
 * {@link DnsRecord}s (a domain can have multiple A/AAAA/CNAME records). Entries
 * are evicted either lazily on read (when TTL has elapsed) or eagerly by a
 * background scheduler that runs {@link #evictExpired()} every 30 seconds.
 *
 * <p>An optional maximum cache size triggers LRU-style eviction: when a {@link #put}
 * would exceed {@code maxSize}, the entries whose records have the nearest expiry
 * (oldest {@code createdAt + ttl}) are removed first to make room.
 *
 * <p>All public methods are safe for concurrent use by multiple threads.
 */
public class DnsCache {

    private static final Logger log = LoggerFactory.getLogger(DnsCache.class);

    /** Sentinel value meaning "no size limit". */
    private static final int UNLIMITED = Integer.MAX_VALUE;

    /** Background eviction interval in seconds. */
    private static final long EVICTION_INTERVAL_SECONDS = 30;

    /**
     * Primary storage. Key = domain name (lower-cased).
     * Value = insertion-timestamped list of records for that domain.
     */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private final int maxSize;
    private final ScheduledExecutorService scheduler;

    /**
     * Create an unbounded cache with background TTL eviction.
     */
    public DnsCache() {
        this(UNLIMITED);
    }

    /**
     * Create a cache with an upper bound on the number of cached domains.
     *
     * @param maxSize maximum number of domain entries to hold; entries beyond
     *                this limit are evicted using an LRU-like strategy based on TTL expiry time
     */
    public DnsCache(int maxSize) {
        this.maxSize = maxSize;

        // Use a virtual-thread-backed scheduler for lightweight background work (Java 21).
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("dns-cache-eviction").factory()
        );
        scheduler.scheduleAtFixedRate(
                this::evictExpired,
                EVICTION_INTERVAL_SECONDS,
                EVICTION_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    /**
     * Store a list of DNS records for the given domain, overwriting any previous entry.
     *
     * <p>If the cache is at capacity ({@code maxSize}), the entry whose combined
     * records will expire soonest is removed first to make room.
     *
     * @param domain  fully-qualified domain name (case-insensitive)
     * @param records list of resolved records; must not be null or empty
     */
    public void put(String domain, List<DnsRecord> records) {
        if (domain == null || records == null || records.isEmpty()) return;
        String key = domain.toLowerCase();

        // Enforce max size before inserting a new key.
        if (!cache.containsKey(key) && cache.size() >= maxSize) {
            evictOldestEntry();
        }

        cache.put(key, new CacheEntry(new ArrayList<>(records), Instant.now()));
        log.debug("DNS cache put: {} ({} record(s))", key, records.size());
    }

    /**
     * Retrieve cached DNS records for a domain if they are still valid.
     *
     * <p>If the stored records have expired (all records past their TTL), the entry
     * is removed from the cache and {@link Optional#empty()} is returned.
     *
     * @param domain fully-qualified domain name (case-insensitive)
     * @return an {@link Optional} containing the live record list, or empty if absent/expired
     */
    public Optional<List<DnsRecord>> get(String domain) {
        if (domain == null) return Optional.empty();
        String key = domain.toLowerCase();

        CacheEntry entry = cache.get(key);
        if (entry == null) {
            log.debug("DNS cache miss: {}", key);
            return Optional.empty();
        }

        // Consider entry expired if ALL records have exceeded their TTL.
        boolean allExpired = entry.records().stream().allMatch(DnsRecord::isExpired);
        if (allExpired) {
            cache.remove(key);
            log.debug("DNS cache expired (removed): {}", key);
            return Optional.empty();
        }

        // Filter out any individually expired records before returning.
        List<DnsRecord> live = entry.records().stream()
                .filter(r -> !r.isExpired())
                .collect(Collectors.toList());

        log.debug("DNS cache hit: {} ({} live record(s))", key, live.size());
        return Optional.of(live);
    }

    /**
     * Scan the entire cache and remove all entries whose records have all expired.
     *
     * <p>Called periodically by the background scheduler; may also be invoked directly.
     */
    public void evictExpired() {
        int before = cache.size();
        cache.entrySet().removeIf(e ->
                e.getValue().records().stream().allMatch(DnsRecord::isExpired)
        );
        int removed = before - cache.size();
        if (removed > 0) {
            log.debug("DNS cache evicted {} expired entries ({} remaining)", removed, cache.size());
        }
    }

    /**
     * Returns the number of domain entries currently in the cache (including entries
     * that may have expired but not yet been evicted).
     */
    public int size() {
        return cache.size();
    }

    /**
     * Remove all entries from the cache immediately.
     */
    public void clear() {
        cache.clear();
        log.debug("DNS cache cleared");
    }

    /**
     * Shut down the background eviction scheduler gracefully.
     * Call this when the cache is no longer needed to avoid thread leaks.
     */
    public void shutdown() {
        scheduler.shutdown();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Remove the single entry that is "oldest" in the sense that its earliest-expiring
     * record expires soonest (i.e. was created longest ago relative to its TTL).
     * This gives an LRU-like behaviour favouring records closer to expiry.
     */
    private void evictOldestEntry() {
        cache.entrySet().stream()
                .min(Comparator.comparing(e -> earliestExpiry(e.getValue())))
                .ifPresent(e -> {
                    cache.remove(e.getKey());
                    log.debug("DNS cache LRU eviction: {}", e.getKey());
                });
    }

    /** Returns the earliest absolute expiry instant among all records in an entry. */
    private static Instant earliestExpiry(CacheEntry entry) {
        return entry.records().stream()
                .map(r -> r.createdAt().plusSeconds(r.ttl()))
                .min(Comparator.naturalOrder())
                .orElse(Instant.MIN);
    }

    /**
     * Internal cache entry pairing records with the time they were inserted.
     *
     * @param records  DNS records for a given domain
     * @param insertedAt wall-clock time when this entry was placed into the cache
     */
    private record CacheEntry(List<DnsRecord> records, Instant insertedAt) {}
}
