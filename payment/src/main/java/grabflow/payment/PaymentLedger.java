package grabflow.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory ledger that records every payment transaction in the
 * GrabFlow system.
 *
 * <h2>Ledger Semantics</h2>
 * The ledger is <em>append-only</em>: once a {@link PaymentRecord} is written,
 * it is never mutated or removed. Status changes (e.g., AUTHORIZED → CAPTURED)
 * are represented by adding a new record with the updated status. This mirrors
 * the immutable-log design of real financial ledgers and makes the audit trail
 * trivially reconstructible.
 *
 * <h2>Thread Safety</h2>
 * Backed by a {@link CopyOnWriteArrayList}, which provides safe concurrent
 * reads without locking. Writes are serialised by the list's own internal lock.
 * This is appropriate for payment workloads where reads heavily outnumber writes.
 */
public class PaymentLedger {

    private static final Logger log = LoggerFactory.getLogger(PaymentLedger.class);

    // -------------------------------------------------------------------------
    // Domain types
    // -------------------------------------------------------------------------

    /**
     * The lifecycle state of a payment transaction.
     */
    public enum PaymentStatus {
        /** Funds have been reserved on the rider's payment method. */
        AUTHORIZED,
        /** Reserved funds have been settled; money has left the rider's account. */
        CAPTURED,
        /** A captured payment has been reversed and funds returned to the rider. */
        REFUNDED,
        /** The payment attempt failed and no funds were moved. */
        FAILED
    }

    /**
     * An immutable snapshot of a payment transaction at a point in time.
     *
     * @param paymentId unique identifier for this payment record
     * @param rideId    the ride this payment is associated with
     * @param riderId   the rider being charged
     * @param driverId  the driver receiving the fare
     * @param amount    the transaction amount in the platform's base currency unit
     * @param status    the status of this payment record
     * @param timestamp when this record was created
     */
    public record PaymentRecord(
            String paymentId,
            String rideId,
            String riderId,
            String driverId,
            double amount,
            PaymentStatus status,
            Instant timestamp
    ) {}

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------

    private final CopyOnWriteArrayList<PaymentRecord> records = new CopyOnWriteArrayList<>();

    // -------------------------------------------------------------------------
    // Write API
    // -------------------------------------------------------------------------

    /**
     * Append a payment record to the ledger.
     *
     * @param payment the record to store; must not be {@code null}
     */
    public void record(PaymentRecord payment) {
        records.add(payment);
        log.info("Ledger: recorded payment {} (ride={}, status={}, amount={})",
                payment.paymentId(), payment.rideId(), payment.status(), payment.amount());
    }

    // -------------------------------------------------------------------------
    // Read API
    // -------------------------------------------------------------------------

    /**
     * Find the most recently recorded entry for a given payment ID.
     *
     * <p>Because the ledger is append-only, multiple records may share the same
     * {@code paymentId} (each representing a status transition). This method
     * returns the <em>last</em> recorded entry, which reflects the current state.
     *
     * @param paymentId the payment identifier to look up
     * @return the latest record for that ID, or empty if none exists
     */
    public Optional<PaymentRecord> findByPaymentId(String paymentId) {
        PaymentRecord latest = null;
        for (PaymentRecord r : records) {
            if (paymentId.equals(r.paymentId())) {
                latest = r;
            }
        }
        return Optional.ofNullable(latest);
    }

    /**
     * Return all payment records associated with a ride, in insertion order.
     *
     * @param rideId the ride identifier
     * @return unmodifiable list of matching records (may be empty)
     */
    public List<PaymentRecord> findByRideId(String rideId) {
        List<PaymentRecord> result = new ArrayList<>();
        for (PaymentRecord r : records) {
            if (rideId.equals(r.rideId())) {
                result.add(r);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Return all payment records currently in the given status, in insertion order.
     *
     * @param status the status to filter by
     * @return unmodifiable list of matching records (may be empty)
     */
    public List<PaymentRecord> findByStatus(PaymentStatus status) {
        List<PaymentRecord> result = new ArrayList<>();
        for (PaymentRecord r : records) {
            if (status == r.status()) {
                result.add(r);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns the total number of records in the ledger (including multiple
     * entries for the same payment ID).
     *
     * @return record count
     */
    public int size() {
        return records.size();
    }
}
