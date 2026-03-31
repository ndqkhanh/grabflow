package grabflow.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyStoreTest {

    private IdempotencyStore store;

    @BeforeEach
    void setUp() {
        store = new IdempotencyStore();
    }

    // -------------------------------------------------------------------------
    // 1. Empty store returns empty for any key
    // -------------------------------------------------------------------------

    @Test
    void emptyStoreReturnsEmptyForAnyKey() {
        assertThat(store.check("nonexistent-key")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // 2. Store and retrieve a result
    // -------------------------------------------------------------------------

    @Test
    void storeAndRetrieveResult() {
        store.store("key-1", "result-1", Duration.ofMinutes(5));

        Optional<String> result = store.check("key-1");

        assertThat(result).isPresent().contains("result-1");
    }

    // -------------------------------------------------------------------------
    // 3. Different keys do not interfere
    // -------------------------------------------------------------------------

    @Test
    void differentKeysAreIndependent() {
        store.store("key-A", "result-A", Duration.ofMinutes(5));
        store.store("key-B", "result-B", Duration.ofMinutes(5));

        assertThat(store.check("key-A")).contains("result-A");
        assertThat(store.check("key-B")).contains("result-B");
    }

    // -------------------------------------------------------------------------
    // 4. Expired entries return empty
    // -------------------------------------------------------------------------

    @Test
    void expiredEntryReturnsEmpty() throws InterruptedException {
        store.store("key-expired", "some-result", Duration.ofMillis(50));

        Thread.sleep(100); // wait for expiry

        assertThat(store.check("key-expired")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // 5. Non-expired entry is returned even close to its TTL
    // -------------------------------------------------------------------------

    @Test
    void nonExpiredEntryIsReturned() {
        store.store("key-valid", "value", Duration.ofHours(1));

        assertThat(store.check("key-valid")).isPresent();
    }

    // -------------------------------------------------------------------------
    // 6. Duplicate key — second write overwrites first
    // -------------------------------------------------------------------------

    @Test
    void duplicateKeyOverwritesPreviousResult() {
        store.store("key-dup", "first-result",  Duration.ofMinutes(5));
        store.store("key-dup", "second-result", Duration.ofMinutes(5));

        assertThat(store.check("key-dup")).contains("second-result");
    }

    // -------------------------------------------------------------------------
    // 7. evictExpired removes only expired records
    // -------------------------------------------------------------------------

    @Test
    void evictExpiredRemovesOnlyExpiredRecords() throws InterruptedException {
        store.store("live-key",    "live-value",    Duration.ofHours(1));
        store.store("expired-key", "expired-value", Duration.ofMillis(50));

        Thread.sleep(100); // let the second entry expire

        store.evictExpired();

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.check("live-key")).isPresent();
        assertThat(store.check("expired-key")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // 8. evictExpired on an empty store is a no-op
    // -------------------------------------------------------------------------

    @Test
    void evictExpiredOnEmptyStoreIsNoOp() {
        store.evictExpired(); // must not throw
        assertThat(store.size()).isZero();
    }

    // -------------------------------------------------------------------------
    // 9. size reflects stored entries
    // -------------------------------------------------------------------------

    @Test
    void sizeReflectsStoredEntries() {
        assertThat(store.size()).isZero();

        store.store("k1", "v1", Duration.ofMinutes(5));
        assertThat(store.size()).isEqualTo(1);

        store.store("k2", "v2", Duration.ofMinutes(5));
        assertThat(store.size()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // 10. TTL boundary — zero-duration TTL is immediately expired
    // -------------------------------------------------------------------------

    @Test
    void zeroDurationTtlIsImmediatelyExpired() throws InterruptedException {
        store.store("key-zero", "val", Duration.ZERO);

        Thread.sleep(10); // give clock a moment to advance

        assertThat(store.check("key-zero")).isEmpty();
    }
}
