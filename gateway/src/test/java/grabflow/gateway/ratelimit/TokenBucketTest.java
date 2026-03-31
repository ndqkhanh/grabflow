package grabflow.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class TokenBucketTest {

    @Test
    void allowsRequestsWithinCapacity() {
        var bucket = new TokenBucket(5, 1.0);
        for (int i = 0; i < 5; i++) {
            assertThat(bucket.tryAcquire("client-1")).isTrue();
        }
    }

    @Test
    void rejectsWhenBucketExhausted() {
        var bucket = new TokenBucket(3, 1.0);
        // Exhaust all tokens
        for (int i = 0; i < 3; i++) {
            bucket.tryAcquire("client-1");
        }
        // Next request should be rejected
        assertThat(bucket.tryAcquire("client-1")).isFalse();
    }

    @Test
    void separateBucketsPerClient() {
        var bucket = new TokenBucket(2, 1.0);
        // Exhaust client-1
        bucket.tryAcquire("client-1");
        bucket.tryAcquire("client-1");
        assertThat(bucket.tryAcquire("client-1")).isFalse();

        // client-2 should still have tokens
        assertThat(bucket.tryAcquire("client-2")).isTrue();
    }

    @Test
    void tokensRefillOverTime() throws Exception {
        var bucket = new TokenBucket(2, 100.0); // 100 tokens/sec
        // Exhaust
        bucket.tryAcquire("c");
        bucket.tryAcquire("c");
        assertThat(bucket.tryAcquire("c")).isFalse();

        // Wait for refill (at 100/sec, 50ms should give ~5 tokens)
        Thread.sleep(50);
        assertThat(bucket.tryAcquire("c")).isTrue();
    }

    @Test
    void tokensDoNotExceedCapacity() throws Exception {
        var bucket = new TokenBucket(3, 1000.0); // very fast refill
        Thread.sleep(100); // way more than needed to fill
        // Should only have capacity tokens, not more
        assertThat(bucket.availableTokens("new-client")).isEqualTo(3.0);
    }

    @Test
    void availableTokensReportsCorrectly() {
        var bucket = new TokenBucket(10, 1.0);
        assertThat(bucket.availableTokens("unknown")).isEqualTo(10.0);

        bucket.tryAcquire("x");
        bucket.tryAcquire("x");
        assertThat(bucket.availableTokens("x")).isLessThanOrEqualTo(8.1);
        assertThat(bucket.availableTokens("x")).isGreaterThanOrEqualTo(7.9);
    }

    @Test
    void clientCountTracksUniquClients() {
        var bucket = new TokenBucket(5, 1.0);
        assertThat(bucket.clientCount()).isZero();

        bucket.tryAcquire("a");
        bucket.tryAcquire("b");
        bucket.tryAcquire("a"); // same client again
        assertThat(bucket.clientCount()).isEqualTo(2);
    }

    @Test
    void evictIdleRemovesStaleClients() throws Exception {
        var bucket = new TokenBucket(5, 1.0);
        bucket.tryAcquire("active");
        bucket.tryAcquire("idle");

        // Wait a bit then access only "active"
        Thread.sleep(50);
        bucket.tryAcquire("active");

        // Evict anything idle for more than 30ms
        int evicted = bucket.evictIdle(30_000_000L); // 30ms in nanos
        assertThat(evicted).isEqualTo(1);
        assertThat(bucket.clientCount()).isEqualTo(1);
    }

    @Test
    void threadSafetyConcurrentAccess() throws Exception {
        // Use a negligible refill rate so tokens effectively don't regenerate during the test
        var bucket = new TokenBucket(1000, 0.001);
        int threads = 8;
        int requestsPerThread = 200;
        var allowed = new AtomicInteger();
        var latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Thread.ofVirtual().start(() -> {
                for (int i = 0; i < requestsPerThread; i++) {
                    if (bucket.tryAcquire("shared-client")) {
                        allowed.incrementAndGet();
                    }
                }
                latch.countDown();
            });
        }

        latch.await();
        // Total allowed should be close to capacity (1000) -- allow tiny margin for refill
        assertThat(allowed.get()).isBetween(1000, 1002);
    }

    @Test
    void invalidParametersThrow() {
        assertThatThrownBy(() -> new TokenBucket(0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(-1, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
