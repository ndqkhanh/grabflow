package grabflow.gateway.dns;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DnsCache}.
 */
class DnsCacheTest {

    private DnsCache cache;

    @BeforeEach
    void setUp() {
        cache = new DnsCache();
    }

    @AfterEach
    void tearDown() {
        cache.shutdown();
    }

    // -------------------------------------------------------------------------
    // Helper factory
    // -------------------------------------------------------------------------

    /** Create an A record with given TTL, using now as createdAt. */
    private static DnsRecord aRecord(String domain, String ip, int ttlSeconds) {
        return new DnsRecord(domain, DnsRecord.RecordType.A, ip, ttlSeconds, Instant.now());
    }

    /** Create an A record that is already expired (createdAt in the past by ttl+1 seconds). */
    private static DnsRecord expiredARecord(String domain, String ip, int ttlSeconds) {
        Instant past = Instant.now().minusSeconds(ttlSeconds + 1);
        return new DnsRecord(domain, DnsRecord.RecordType.A, ip, ttlSeconds, past);
    }

    // -------------------------------------------------------------------------
    // Test 1: putAndGetRecords
    // -------------------------------------------------------------------------

    @Test
    void putAndGetRecords() {
        List<DnsRecord> records = List.of(
                aRecord("example.com", "93.184.216.34", 300),
                aRecord("example.com", "93.184.216.35", 300)
        );

        cache.put("example.com", records);

        Optional<List<DnsRecord>> result = cache.get("example.com");
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(2);
        assertThat(result.get().stream().map(DnsRecord::value))
                .containsExactlyInAnyOrder("93.184.216.34", "93.184.216.35");
    }

    @Test
    void putAndGetIsCaseInsensitive() {
        cache.put("Example.COM", List.of(aRecord("Example.COM", "1.2.3.4", 300)));

        // Should be retrievable regardless of case.
        assertThat(cache.get("example.com")).isPresent();
        assertThat(cache.get("EXAMPLE.COM")).isPresent();
    }

    @Test
    void getMissingDomainReturnsEmpty() {
        assertThat(cache.get("nonexistent.example.com")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Test 2: expiredRecordsAreEvicted
    // -------------------------------------------------------------------------

    @Test
    void expiredRecordsAreEvicted() {
        // All records have ttl=1 and createdAt in the past, so they are already expired.
        List<DnsRecord> expired = List.of(
                expiredARecord("old.example.com", "1.1.1.1", 1)
        );
        cache.put("old.example.com", expired);

        // get() should detect expiry, remove the entry, and return empty.
        Optional<List<DnsRecord>> result = cache.get("old.example.com");
        assertThat(result).isEmpty();

        // The entry must have been removed from the cache.
        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    void liveRecordsAreReturnedEvenIfSomeExpired() {
        // Mix of live and expired records for the same domain.
        List<DnsRecord> mixed = List.of(
                aRecord("mixed.example.com", "1.1.1.1", 300),        // live
                expiredARecord("mixed.example.com", "2.2.2.2", 1)    // expired
        );
        cache.put("mixed.example.com", mixed);

        Optional<List<DnsRecord>> result = cache.get("mixed.example.com");
        // At least one live record exists, so we should get back the live ones.
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().getFirst().value()).isEqualTo("1.1.1.1");
    }

    // -------------------------------------------------------------------------
    // Test 3: evictExpiredRemovesStaleEntries
    // -------------------------------------------------------------------------

    @Test
    void evictExpiredRemovesStaleEntries() {
        // Insert one live and one expired entry.
        cache.put("live.example.com", List.of(aRecord("live.example.com", "1.1.1.1", 300)));
        cache.put("dead.example.com", List.of(expiredARecord("dead.example.com", "2.2.2.2", 1)));

        assertThat(cache.size()).isEqualTo(2);

        cache.evictExpired();

        // Only the live entry should remain.
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get("live.example.com")).isPresent();
        assertThat(cache.get("dead.example.com")).isEmpty();
    }

    @Test
    void evictExpiredOnEmptyCacheIsNoop() {
        // Should not throw.
        cache.evictExpired();
        assertThat(cache.size()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Test 4: maxSizeEvictsOldest
    // -------------------------------------------------------------------------

    @Test
    void maxSizeEvictsOldest() throws Exception {
        // Create a cache that holds at most 2 domains.
        DnsCache bounded = new DnsCache(2);
        try {
            // Records with a short TTL (expire sooner = evicted first).
            DnsRecord shortLived = new DnsRecord("a.example.com", DnsRecord.RecordType.A,
                    "1.1.1.1", 10, Instant.now());
            DnsRecord longLived  = new DnsRecord("b.example.com", DnsRecord.RecordType.A,
                    "2.2.2.2", 3600, Instant.now());

            bounded.put("a.example.com", List.of(shortLived));
            bounded.put("b.example.com", List.of(longLived));

            assertThat(bounded.size()).isEqualTo(2);

            // Adding a third entry must evict one (the short-lived one has earliest expiry).
            bounded.put("c.example.com", List.of(aRecord("c.example.com", "3.3.3.3", 3600)));

            assertThat(bounded.size()).isEqualTo(2);
            // "c.example.com" must be present.
            assertThat(bounded.get("c.example.com")).isPresent();
        } finally {
            bounded.shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // Test 5: threadSafeConcurrentAccess
    // -------------------------------------------------------------------------

    @Test
    void threadSafeConcurrentAccess() throws InterruptedException {
        int threadCount = 20;
        int opsPerThread = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int t = 0; t < threadCount; t++) {
                int tid = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int op = 0; op < opsPerThread; op++) {
                            String domain = "domain" + (tid % 5) + ".example.com";
                            if (op % 3 == 0) {
                                cache.put(domain, List.of(aRecord(domain, "1.2.3." + tid, 300)));
                            } else if (op % 3 == 1) {
                                cache.get(domain);
                            } else {
                                cache.evictExpired();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(errors.get()).isEqualTo(0);
        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------------------------------------------------------------
    // Test 6: clearRemovesAllEntries
    // -------------------------------------------------------------------------

    @Test
    void clearRemovesAllEntries() {
        cache.put("a.example.com", List.of(aRecord("a.example.com", "1.1.1.1", 300)));
        cache.put("b.example.com", List.of(aRecord("b.example.com", "2.2.2.2", 300)));
        cache.put("c.example.com", List.of(aRecord("c.example.com", "3.3.3.3", 300)));

        assertThat(cache.size()).isEqualTo(3);

        cache.clear();

        assertThat(cache.size()).isEqualTo(0);
        assertThat(cache.get("a.example.com")).isEmpty();
        assertThat(cache.get("b.example.com")).isEmpty();
        assertThat(cache.get("c.example.com")).isEmpty();
    }
}
