package grabflow.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentLedgerTest {

    private PaymentLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new PaymentLedger();
    }

    // -------------------------------------------------------------------------
    // Helper factory
    // -------------------------------------------------------------------------

    private PaymentLedger.PaymentRecord record(String paymentId, String rideId,
                                               PaymentLedger.PaymentStatus status) {
        return new PaymentLedger.PaymentRecord(
                paymentId, rideId, "rider-1", "driver-1", 15.50, status, Instant.now());
    }

    // -------------------------------------------------------------------------
    // 1. Empty ledger
    // -------------------------------------------------------------------------

    @Test
    void emptyLedgerReturnsEmptyForAllQueries() {
        assertThat(ledger.findByPaymentId("pid")).isEmpty();
        assertThat(ledger.findByRideId("rid")).isEmpty();
        assertThat(ledger.findByStatus(PaymentLedger.PaymentStatus.CAPTURED)).isEmpty();
        assertThat(ledger.size()).isZero();
    }

    // -------------------------------------------------------------------------
    // 2. Record and find by payment ID
    // -------------------------------------------------------------------------

    @Test
    void recordAndFindByPaymentId() {
        PaymentLedger.PaymentRecord r = record("p1", "r1", PaymentLedger.PaymentStatus.CAPTURED);
        ledger.record(r);

        Optional<PaymentLedger.PaymentRecord> found = ledger.findByPaymentId("p1");

        assertThat(found).isPresent();
        assertThat(found.get().paymentId()).isEqualTo("p1");
        assertThat(found.get().status()).isEqualTo(PaymentLedger.PaymentStatus.CAPTURED);
    }

    // -------------------------------------------------------------------------
    // 3. findByPaymentId returns the LATEST record for that ID
    // -------------------------------------------------------------------------

    @Test
    void findByPaymentIdReturnsLatestRecord() {
        ledger.record(record("p1", "r1", PaymentLedger.PaymentStatus.AUTHORIZED));
        ledger.record(record("p1", "r1", PaymentLedger.PaymentStatus.CAPTURED));

        Optional<PaymentLedger.PaymentRecord> found = ledger.findByPaymentId("p1");

        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(PaymentLedger.PaymentStatus.CAPTURED);
    }

    // -------------------------------------------------------------------------
    // 4. findByPaymentId returns empty for unknown ID
    // -------------------------------------------------------------------------

    @Test
    void findByPaymentIdReturnsEmptyForUnknownId() {
        ledger.record(record("p1", "r1", PaymentLedger.PaymentStatus.CAPTURED));

        assertThat(ledger.findByPaymentId("unknown")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // 5. findByRideId returns all records for a ride
    // -------------------------------------------------------------------------

    @Test
    void findByRideIdReturnsAllMatchingRecords() {
        ledger.record(record("p1", "ride-A", PaymentLedger.PaymentStatus.AUTHORIZED));
        ledger.record(record("p1", "ride-A", PaymentLedger.PaymentStatus.CAPTURED));
        ledger.record(record("p2", "ride-B", PaymentLedger.PaymentStatus.CAPTURED));

        List<PaymentLedger.PaymentRecord> results = ledger.findByRideId("ride-A");

        assertThat(results).hasSize(2);
        assertThat(results).extracting(PaymentLedger.PaymentRecord::paymentId)
                .containsOnly("p1");
    }

    // -------------------------------------------------------------------------
    // 6. findByRideId returns empty for unknown ride
    // -------------------------------------------------------------------------

    @Test
    void findByRideIdReturnsEmptyForUnknownRide() {
        ledger.record(record("p1", "ride-A", PaymentLedger.PaymentStatus.CAPTURED));

        assertThat(ledger.findByRideId("ride-Z")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // 7. findByStatus filters correctly
    // -------------------------------------------------------------------------

    @Test
    void findByStatusFiltersCorrectly() {
        ledger.record(record("p1", "r1", PaymentLedger.PaymentStatus.CAPTURED));
        ledger.record(record("p2", "r2", PaymentLedger.PaymentStatus.FAILED));
        ledger.record(record("p3", "r3", PaymentLedger.PaymentStatus.CAPTURED));

        List<PaymentLedger.PaymentRecord> captured = ledger.findByStatus(PaymentLedger.PaymentStatus.CAPTURED);
        List<PaymentLedger.PaymentRecord> failed   = ledger.findByStatus(PaymentLedger.PaymentStatus.FAILED);
        List<PaymentLedger.PaymentRecord> refunded = ledger.findByStatus(PaymentLedger.PaymentStatus.REFUNDED);

        assertThat(captured).hasSize(2);
        assertThat(failed).hasSize(1);
        assertThat(refunded).isEmpty();
    }

    // -------------------------------------------------------------------------
    // 8. size reflects total entries (including duplicates for same payment ID)
    // -------------------------------------------------------------------------

    @Test
    void sizeReflectsTotalEntries() {
        assertThat(ledger.size()).isZero();

        ledger.record(record("p1", "r1", PaymentLedger.PaymentStatus.AUTHORIZED));
        ledger.record(record("p1", "r1", PaymentLedger.PaymentStatus.CAPTURED));

        assertThat(ledger.size()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // 9. Records from findByRideId are in insertion order
    // -------------------------------------------------------------------------

    @Test
    void findByRideIdPreservesInsertionOrder() {
        ledger.record(record("p1", "ride-X", PaymentLedger.PaymentStatus.AUTHORIZED));
        ledger.record(record("p2", "ride-X", PaymentLedger.PaymentStatus.CAPTURED));
        ledger.record(record("p3", "ride-X", PaymentLedger.PaymentStatus.REFUNDED));

        List<PaymentLedger.PaymentRecord> results = ledger.findByRideId("ride-X");

        assertThat(results).extracting(PaymentLedger.PaymentRecord::paymentId)
                .containsExactly("p1", "p2", "p3");
    }
}
