package grabflow.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * High-level payment orchestration service for GrabFlow.
 *
 * <p>Wires together three collaborators:
 * <ul>
 *   <li>{@link SagaOrchestrator} – executes the multi-step charge workflow
 *       with automatic compensation on failure.</li>
 *   <li>{@link IdempotencyStore}  – prevents duplicate charges when clients
 *       retry failed or timed-out requests.</li>
 *   <li>{@link PaymentLedger}     – maintains an append-only audit trail of
 *       every transaction.</li>
 * </ul>
 *
 * <h2>Charge Flow</h2>
 * <ol>
 *   <li>Check the {@link IdempotencyStore}; return the cached result immediately
 *       if the idempotency key has been seen before.</li>
 *   <li>Build a three-step saga: authorize → capture → pay-driver.</li>
 *   <li>Execute the saga via {@link SagaOrchestrator#execute}.</li>
 *   <li>Record the outcome in the {@link PaymentLedger}.</li>
 *   <li>Cache the result in the {@link IdempotencyStore} for future retries.</li>
 *   <li>Return a {@link ChargeResult} to the caller.</li>
 * </ol>
 *
 * <h2>Refund Flow</h2>
 * Looks up the original payment by ID and appends a {@code REFUNDED} record to
 * the ledger without re-running the saga.
 */
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Default TTL for idempotency keys (24 hours). */
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    // -------------------------------------------------------------------------
    // API types
    // -------------------------------------------------------------------------

    /**
     * Input to a charge operation.
     *
     * @param rideId         the completed ride being charged
     * @param riderId        the rider whose payment method is charged
     * @param driverId       the driver receiving their share
     * @param amount         amount to charge in the platform's base currency unit
     * @param idempotencyKey client-supplied key to deduplicate retries
     */
    public record ChargeRequest(
            String rideId,
            String riderId,
            String driverId,
            double amount,
            String idempotencyKey
    ) {}

    /**
     * Result of a charge or refund operation.
     *
     * @param paymentId unique identifier for the payment record created
     * @param status    final status of the payment
     * @param message   human-readable description of the outcome
     */
    public record ChargeResult(
            String paymentId,
            PaymentLedger.PaymentStatus status,
            String message
    ) {}

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    private final SagaOrchestrator orchestrator;
    private final IdempotencyStore idempotencyStore;
    private final PaymentLedger ledger;

    /**
     * Construct a {@code PaymentService} with its required collaborators.
     *
     * @param orchestrator    saga orchestrator for multi-step workflows
     * @param idempotencyStore store for deduplicating client retries
     * @param ledger          append-only payment ledger
     */
    public PaymentService(SagaOrchestrator orchestrator,
                          IdempotencyStore idempotencyStore,
                          PaymentLedger ledger) {
        this.orchestrator = orchestrator;
        this.idempotencyStore = idempotencyStore;
        this.ledger = ledger;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Process a charge request for a completed ride.
     *
     * <p>If the {@code idempotencyKey} in the request has been seen before and
     * the cached result has not expired, the cached {@link ChargeResult} is
     * returned immediately without running the saga again.
     *
     * @param request the charge request
     * @return the charge result (may be a cached response on duplicate requests)
     */
    public ChargeResult processCharge(ChargeRequest request) {
        // 1. Idempotency check
        Optional<String> cached = idempotencyStore.check(request.idempotencyKey());
        if (cached.isPresent()) {
            log.info("Idempotent duplicate for key '{}' — returning cached result", request.idempotencyKey());
            return deserializeResult(cached.get());
        }

        String paymentId = UUID.randomUUID().toString();
        String sagaId = "saga-" + paymentId;

        // 2. Build saga steps (authorize → capture → pay driver)
        List<SagaOrchestrator.SagaStep> steps = List.of(
                new AuthorizeStep(request),
                new CaptureStep(request),
                new PayDriverStep(request)
        );

        // 3. Execute saga
        SagaOrchestrator.SagaOutcome outcome = orchestrator.execute(sagaId, steps);

        // 4. Determine final status and record in ledger
        PaymentLedger.PaymentStatus status;
        String message;
        if (outcome.isSuccess()) {
            status = PaymentLedger.PaymentStatus.CAPTURED;
            message = "Payment captured successfully";
        } else {
            status = PaymentLedger.PaymentStatus.FAILED;
            message = "Payment failed: " + outcome.failureReason();
        }

        PaymentLedger.PaymentRecord ledgerRecord = new PaymentLedger.PaymentRecord(
                paymentId,
                request.rideId(),
                request.riderId(),
                request.driverId(),
                request.amount(),
                status,
                Instant.now()
        );
        ledger.record(ledgerRecord);

        // 5. Cache result
        ChargeResult result = new ChargeResult(paymentId, status, message);
        idempotencyStore.store(request.idempotencyKey(), serializeResult(result), IDEMPOTENCY_TTL);

        log.info("Charge {} for ride {} completed with status {}", paymentId, request.rideId(), status);
        return result;
    }

    /**
     * Refund a previously captured payment.
     *
     * <p>Looks up the most recent ledger record for {@code paymentId}, then
     * appends a new {@code REFUNDED} record. Only {@code CAPTURED} payments
     * can be refunded; any other status returns a failure result.
     *
     * @param paymentId the payment to refund
     * @return the refund result
     */
    public ChargeResult refund(String paymentId) {
        Optional<PaymentLedger.PaymentRecord> existing = ledger.findByPaymentId(paymentId);
        if (existing.isEmpty()) {
            log.warn("Refund requested for unknown payment '{}'", paymentId);
            return new ChargeResult(paymentId, PaymentLedger.PaymentStatus.FAILED,
                    "Payment not found: " + paymentId);
        }

        PaymentLedger.PaymentRecord original = existing.get();
        if (original.status() != PaymentLedger.PaymentStatus.CAPTURED) {
            log.warn("Refund rejected — payment '{}' is in status {}", paymentId, original.status());
            return new ChargeResult(paymentId, original.status(),
                    "Cannot refund payment in status: " + original.status());
        }

        PaymentLedger.PaymentRecord refunded = new PaymentLedger.PaymentRecord(
                paymentId,
                original.rideId(),
                original.riderId(),
                original.driverId(),
                original.amount(),
                PaymentLedger.PaymentStatus.REFUNDED,
                Instant.now()
        );
        ledger.record(refunded);
        log.info("Payment '{}' refunded successfully", paymentId);
        return new ChargeResult(paymentId, PaymentLedger.PaymentStatus.REFUNDED, "Refund processed successfully");
    }

    // -------------------------------------------------------------------------
    // Saga step implementations (package-private for testability)
    // -------------------------------------------------------------------------

    /**
     * Saga step 1: authorize (reserve) funds on the rider's payment method.
     */
    static class AuthorizeStep implements SagaOrchestrator.SagaStep {
        private final ChargeRequest request;

        AuthorizeStep(ChargeRequest request) {
            this.request = request;
        }

        @Override
        public SagaOrchestrator.SagaResult execute(SagaOrchestrator.SagaContext ctx) {
            // Simulate authorization: store auth code in context for downstream steps
            String authCode = "AUTH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            ctx.data().put("authCode", authCode);
            ctx.data().put("amount", request.amount());
            LoggerFactory.getLogger(AuthorizeStep.class)
                    .info("Authorized {} for rider {} (authCode={})", request.amount(), request.riderId(), authCode);
            return SagaOrchestrator.SagaResult.SUCCESS;
        }

        @Override
        public void compensate(SagaOrchestrator.SagaContext ctx) {
            String authCode = (String) ctx.data().get("authCode");
            LoggerFactory.getLogger(AuthorizeStep.class)
                    .info("Voiding authorization {} for rider {}", authCode, request.riderId());
        }

        @Override
        public String name() {
            return "authorize_payment";
        }
    }

    /**
     * Saga step 2: capture (settle) the authorized amount.
     */
    static class CaptureStep implements SagaOrchestrator.SagaStep {
        private final ChargeRequest request;

        CaptureStep(ChargeRequest request) {
            this.request = request;
        }

        @Override
        public SagaOrchestrator.SagaResult execute(SagaOrchestrator.SagaContext ctx) {
            String authCode = (String) ctx.data().get("authCode");
            String captureId = "CAP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            ctx.data().put("captureId", captureId);
            LoggerFactory.getLogger(CaptureStep.class)
                    .info("Captured {} against auth {} (captureId={})", request.amount(), authCode, captureId);
            return SagaOrchestrator.SagaResult.SUCCESS;
        }

        @Override
        public void compensate(SagaOrchestrator.SagaContext ctx) {
            String captureId = (String) ctx.data().get("captureId");
            LoggerFactory.getLogger(CaptureStep.class)
                    .info("Refunding capture {} for rider {}", captureId, request.riderId());
        }

        @Override
        public String name() {
            return "capture_payment";
        }
    }

    /**
     * Saga step 3: transfer the driver's share to the driver's account.
     */
    static class PayDriverStep implements SagaOrchestrator.SagaStep {
        private final ChargeRequest request;

        PayDriverStep(ChargeRequest request) {
            this.request = request;
        }

        @Override
        public SagaOrchestrator.SagaResult execute(SagaOrchestrator.SagaContext ctx) {
            String transferId = "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            ctx.data().put("transferId", transferId);
            LoggerFactory.getLogger(PayDriverStep.class)
                    .info("Paid driver {} amount {} (transferId={})", request.driverId(), request.amount(), transferId);
            return SagaOrchestrator.SagaResult.SUCCESS;
        }

        @Override
        public void compensate(SagaOrchestrator.SagaContext ctx) {
            String transferId = (String) ctx.data().get("transferId");
            LoggerFactory.getLogger(PayDriverStep.class)
                    .info("Reversing driver transfer {} for driver {}", transferId, request.driverId());
        }

        @Override
        public String name() {
            return "pay_driver";
        }
    }

    // -------------------------------------------------------------------------
    // Serialization helpers (simple delimited format to avoid external deps)
    // -------------------------------------------------------------------------

    private static String serializeResult(ChargeResult result) {
        return result.paymentId() + "|" + result.status().name() + "|" + result.message();
    }

    private static ChargeResult deserializeResult(String serialized) {
        String[] parts = serialized.split("\\|", 3);
        return new ChargeResult(
                parts[0],
                PaymentLedger.PaymentStatus.valueOf(parts[1]),
                parts.length > 2 ? parts[2] : ""
        );
    }
}
