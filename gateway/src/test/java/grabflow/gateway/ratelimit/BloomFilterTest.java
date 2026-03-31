package grabflow.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BloomFilterTest {

    // -------------------------------------------------------------------------
    // 1. Basic add/contains
    // -------------------------------------------------------------------------

    @Test
    void addAndContains() {
        BloomFilter filter = new BloomFilter(1000, 0.01);

        filter.add("192.168.1.1");
        filter.add("10.0.0.1");
        filter.add("172.16.0.254");

        assertThat(filter.mightContain("192.168.1.1")).isTrue();
        assertThat(filter.mightContain("10.0.0.1")).isTrue();
        assertThat(filter.mightContain("172.16.0.254")).isTrue();
    }

    // -------------------------------------------------------------------------
    // 2. Definitely-absent element returns false (small filter, few elements)
    // -------------------------------------------------------------------------

    @Test
    void doesNotContainAbsentElement() {
        // Use a large filter with very few insertions so false positives are astronomically unlikely
        BloomFilter filter = new BloomFilter(100_000, 0.0001);

        filter.add("192.168.1.1");
        filter.add("10.0.0.1");

        // These IPs were never added; with 100K-capacity filter holding 2 elements
        // the probability of ANY of these being a false positive is effectively zero
        assertThat(filter.mightContain("1.2.3.4")).isFalse();
        assertThat(filter.mightContain("255.255.255.255")).isFalse();
        assertThat(filter.mightContain("0.0.0.0")).isFalse();
        assertThat(filter.mightContain("not-an-ip-at-all")).isFalse();
    }

    // -------------------------------------------------------------------------
    // 3. False positive rate stays within configured bounds
    // -------------------------------------------------------------------------

    @Test
    void falsePositiveRateIsWithinBounds() {
        double targetFpr = 0.01;        // 1%
        int insertions = 10_000;
        int probes = 100_000;           // test this many absent elements
        double margin = 0.005;          // allow 0.5% above target (measurement noise)

        BloomFilter filter = new BloomFilter(insertions, targetFpr);

        // Insert elements using a prefix that won't overlap probes
        for (int i = 0; i < insertions; i++) {
            filter.add("present-" + i);
        }

        // Probe with a distinct prefix that was never inserted
        int falsePositives = 0;
        for (int i = 0; i < probes; i++) {
            if (filter.mightContain("absent-" + i)) {
                falsePositives++;
            }
        }

        double measuredFpr = (double) falsePositives / probes;
        assertThat(measuredFpr)
                .as("measured FPR %.4f should be <= configured %.4f + margin %.4f",
                        measuredFpr, targetFpr, margin)
                .isLessThanOrEqualTo(targetFpr + margin);
    }

    // -------------------------------------------------------------------------
    // 4. Optimal parameter calculation
    // -------------------------------------------------------------------------

    @Test
    void optimalParameterCalculation() {
        // For n=1000, p=0.01:
        //   m = -1000 * ln(0.01) / (ln2)^2 = 9585.06... → 9586
        //   k = (9586/1000) * ln2 = 6.643... → 7
        int expectedM = (int) Math.ceil(-1000 * Math.log(0.01) / (Math.log(2) * Math.log(2)));
        int expectedK = (int) Math.round((double) expectedM / 1000 * Math.log(2));

        assertThat(BloomFilter.optimalBitCount(1000, 0.01)).isEqualTo(expectedM);
        assertThat(BloomFilter.optimalHashCount(expectedM, 1000)).isEqualTo(expectedK);

        // Cross-check concrete values (validates our formula is correct)
        assertThat(expectedM).isEqualTo(9586);
        assertThat(expectedK).isEqualTo(7);

        // A constructed filter should embed these values
        BloomFilter filter = new BloomFilter(1000, 0.01);
        assertThat(filter.bitCount()).isEqualTo(expectedM);
        assertThat(filter.hashCount()).isEqualTo(expectedK);
    }

    // -------------------------------------------------------------------------
    // 5. Thread safety — concurrent adds from multiple threads
    // -------------------------------------------------------------------------

    @Test
    void threadSafety() throws InterruptedException {
        int threads = 16;
        int insertsPerThread = 500;
        BloomFilter filter = new BloomFilter(threads * insertsPerThread * 2, 0.01);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < insertsPerThread; i++) {
                    filter.add("thread-" + threadId + "-ip-" + i);
                }
                done.countDown();
            });
        }

        ready.await();       // all threads are prepared
        start.countDown();   // release them simultaneously
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // Every element that was added must still be findable (no false negatives)
        int misses = 0;
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < insertsPerThread; i++) {
                if (!filter.mightContain("thread-" + t + "-ip-" + i)) {
                    misses++;
                }
            }
        }
        assertThat(misses)
                .as("concurrent adds must produce zero false negatives")
                .isEqualTo(0);

        // size() should reflect all insertions (no lost updates from data races)
        assertThat(filter.size()).isEqualTo(threads * insertsPerThread);
    }

    // -------------------------------------------------------------------------
    // 6. Empty filter contains nothing
    // -------------------------------------------------------------------------

    @Test
    void emptyFilterContainsNothing() {
        BloomFilter filter = new BloomFilter(1000, 0.01);

        assertThat(filter.mightContain("1.2.3.4")).isFalse();
        assertThat(filter.mightContain("192.168.0.1")).isFalse();
        assertThat(filter.mightContain("")).isFalse();
        assertThat(filter.size()).isEqualTo(0);
        assertThat(filter.expectedFalsePositiveRate()).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // 7. Factory method creates a correctly configured filter
    // -------------------------------------------------------------------------

    @Test
    void factoryMethodCreatesCorrectFilter() {
        int badIps = 5000;
        BloomFilter filter = BloomFilter.forRateLimiting(badIps);

        // Verify it works as a filter
        filter.add("1.2.3.4");
        assertThat(filter.mightContain("1.2.3.4")).isTrue();
        assertThat(filter.mightContain("9.9.9.9")).isFalse();

        // Verify optimal parameters are consistent with 1% FPR
        int expectedM = BloomFilter.optimalBitCount(badIps, 0.01);
        int expectedK = BloomFilter.optimalHashCount(expectedM, badIps);
        assertThat(filter.bitCount()).isEqualTo(expectedM);
        assertThat(filter.hashCount()).isEqualTo(expectedK);

        // FPR after inserting capacity worth of elements should be close to 1%
        for (int i = 0; i < badIps; i++) {
            filter.add("malicious-" + i);
        }
        double fpr = filter.expectedFalsePositiveRate();
        assertThat(fpr).isBetween(0.005, 0.02);  // within 2x of target
    }

    // -------------------------------------------------------------------------
    // 8. MurmurHash3 distributes bits uniformly (chi-squared test)
    // -------------------------------------------------------------------------

    @Test
    void hashDistributionIsUniform() {
        // We hash N strings and observe which of BUCKETS buckets (bit regions)
        // each hash falls into. A chi-squared goodness-of-fit test verifies
        // that the distribution does not deviate significantly from uniform.

        int n = 10_000;
        int buckets = 64;
        int[] observed = new int[buckets];

        for (int i = 0; i < n; i++) {
            long h = BloomFilter.murmur3Hash64("ip-" + i);
            // Use upper 32 bits to select bucket
            int bucket = (int) ((h >>> 32) & (buckets - 1));
            observed[bucket]++;
        }

        double expected = (double) n / buckets;  // expected count per bucket

        // Chi-squared statistic: sum((observed - expected)^2 / expected)
        double chiSquared = 0.0;
        for (int count : observed) {
            double diff = count - expected;
            chiSquared += (diff * diff) / expected;
        }

        // Critical value for chi-squared with df=63, alpha=0.001 is ~103.4
        // A good hash function should be well below this threshold
        assertThat(chiSquared)
                .as("chi-squared statistic %.2f should indicate uniform distribution (threshold 103.4)", chiSquared)
                .isLessThan(103.4);

        // Also verify no bucket is completely empty or has more than 3x the expected count
        for (int i = 0; i < buckets; i++) {
            assertThat(observed[i])
                    .as("bucket %d count %d should be within 3x expected %.0f", i, observed[i], expected)
                    .isBetween(1, (int) (expected * 3.5));
        }
    }

    // -------------------------------------------------------------------------
    // 9. Large-scale insertions: memory efficiency and FPR at capacity
    // -------------------------------------------------------------------------

    @Test
    void largeScaleInsertions() {
        int capacity = 100_000;
        double targetFpr = 0.01;
        BloomFilter filter = new BloomFilter(capacity, targetFpr);

        // Verify memory: a 1%-FPR Bloom filter needs ~9.6 bits per element
        // For 100K elements: ~960K bits = ~120KB. This is dramatically smaller
        // than a HashSet<String> storing 100K IPv4 strings (~6-8MB).
        int expectedBits = BloomFilter.optimalBitCount(capacity, targetFpr);
        int expectedBytes = (expectedBits + 7) / 8;
        assertThat(expectedBytes).isLessThan(200_000);  // under 200KB

        // Insert 100K unique IPs
        List<String> inserted = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) {
            String ip = String.format("%d.%d.%d.%d",
                    (i >> 24) & 0xFF, (i >> 16) & 0xFF, (i >> 8) & 0xFF, i & 0xFF);
            filter.add(ip);
            inserted.add(ip);
        }

        assertThat(filter.size()).isEqualTo(capacity);

        // Zero false negatives: every inserted element must be found
        for (String ip : inserted) {
            assertThat(filter.mightContain(ip))
                    .as("inserted element %s must be found (no false negatives)", ip)
                    .isTrue();
        }

        // FPR at capacity should be close to the configured 1%
        double fpr = filter.expectedFalsePositiveRate();
        assertThat(fpr)
                .as("FPR at capacity %.4f should be near configured %.4f", fpr, targetFpr)
                .isBetween(targetFpr * 0.5, targetFpr * 2.0);

        // Empirically verify FPR with a sample of absent elements
        int probes = 10_000;
        int fp = 0;
        for (int i = capacity; i < capacity + probes; i++) {
            if (filter.mightContain("absent-" + i)) fp++;
        }
        double empiricalFpr = (double) fp / probes;
        assertThat(empiricalFpr)
                .as("empirical FPR %.4f should be within 2x of target %.4f", empiricalFpr, targetFpr)
                .isLessThanOrEqualTo(targetFpr * 2.0);
    }
}
