package grabflow.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentServiceTest {

    private SagaOrchestrator orchestrator;
    private IdempotencyStore idempotencyStore;
    private PaymentLedger ledger;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        orchestrator = new SagaOrchestrator();
        idempotencyStore = new IdempotencyStore();
        ledger = new PaymentLedger();
        service = new PaymentService(orchestrator, idempotencyStore, ledger);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PaymentService.ChargeRequest request(String rideId, String idempotencyKey) {
        return new PaymentService.ChargeRequest(rideId, "rider-1", "driver-1", 20.00, idempotencyKey);
    }

    // -------------------------------------------------------------------------
    // 1. Successful charge end-to-end
    // -------------------------------------------------------------------------

    @Test
    void successfulChargeReturnsCapuredStatus() {
        PaymentService.ChargeResult result = service.processCharge(request("ride-1", "idem-1"));

        assertThat(result.status()).isEqualTo(PaymentLedger.PaymentStatus.CAPTURED);
        assertThat(result.paymentId()).isNotBlank();
        assertThat(result.message()).containsIgnoringCase("captured");
    }

    // -------------------------------------------------------------------------
    // 2. Successful charge is recorded in ledger
    // -------------------------------------------------------------------------

    @Test
    void successfulChargeIsRecordedInLedger() {
        PaymentService.ChargeResult result = service.processCharge(request("ride-2", "idem-2"));

        Optional<PaymentLedger.PaymentRecord> record = ledger.findByPaymentId(result.paymentId());

        assertThat(record).isPresent();
        assertThat(record.get().rideId()).isEqualTo("ride-2");
        assertThat(record.get().status()).isEqualTo(PaymentLedger.PaymentStatus.CAPTURED);
        assertThat(record.get().amount()).isEqualTo(20.00);
    }

    // -------------------------------------------------------------------------
    // 3. Idempotent duplicate returns same result without processing again
    // -------------------------------------------------------------------------

    @Test
    void duplicateRequestWithSameKeyReturnsCachedResult() {
        PaymentService.ChargeResult first  = service.processCharge(request("ride-3", "idem-3"));
        PaymentService.ChargeResult second = service.processCharge(request("ride-3", "idem-3"));

        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(second.status()).isEqualTo(first.status());

        // Ledger must have exactly one record — second call must not double-charge
        assertThat(ledger.findByRideId("ride-3")).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // 4. Different idempotency keys produce independent charges
    // -------------------------------------------------------------------------

    @Test
    void differentKeysProduceIndependentCharges() {
        PaymentService.ChargeResult r1 = service.processCharge(request("ride-4a", "idem-4a"));
        PaymentService.ChargeResult r2 = service.processCharge(request("ride-4b", "idem-4b"));

        assertThat(r1.paymentId()).isNotEqualTo(r2.paymentId());
        assertThat(ledger.size()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // 5. Failed saga records FAILED status in ledger
    // -------------------------------------------------------------------------

    @Test
    void failedSagaRecordsFailedStatusInLedger() {
        // Inject a custom orchestrator that always fails at step 2
        SagaOrchestrator failingOrchestrator = new SagaOrchestrator() {
            @Override
            public SagaOutcome execute(String sagaId, List<SagaStep> steps) {
                // Execute step1 (authorize), then fail step2 (capture)
                SagaContext ctx = SagaContext.of(sagaId);
                steps.get(0).execute(ctx);
                steps.get(0).compensate(ctx); // compensate authorize
                return new SagaOutcome(sagaId, SagaResult.FAILURE, 1, "Step 'capture_payment' failed");
            }
        };

        PaymentService failingService = new PaymentService(failingOrchestrator, idempotencyStore, ledger);
        PaymentService.ChargeResult result = failingService.processCharge(request("ride-5", "idem-5"));

        assertThat(result.status()).isEqualTo(PaymentLedger.PaymentStatus.FAILED);
        assertThat(result.message()).containsIgnoringCase("failed");

        Optional<PaymentLedger.PaymentRecord> record = ledger.findByPaymentId(result.paymentId());
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo(PaymentLedger.PaymentStatus.FAILED);
    }

    // -------------------------------------------------------------------------
    // 6. Refund a captured payment — changes status to REFUNDED
    // -------------------------------------------------------------------------

    @Test
    void refundCapturedPaymentChangesStatusToRefunded() {
        PaymentService.ChargeResult charged = service.processCharge(request("ride-6", "idem-6"));

        PaymentService.ChargeResult refunded = service.refund(charged.paymentId());

        assertThat(refunded.status()).isEqualTo(PaymentLedger.PaymentStatus.REFUNDED);
        assertThat(refunded.paymentId()).isEqualTo(charged.paymentId());

        // Latest ledger entry reflects REFUNDED
        Optional<PaymentLedger.PaymentRecord> record = ledger.findByPaymentId(charged.paymentId());
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo(PaymentLedger.PaymentStatus.REFUNDED);
    }

    // -------------------------------------------------------------------------
    // 7. Refund of unknown payment returns FAILED
    // -------------------------------------------------------------------------

    @Test
    void refundUnknownPaymentReturnsFailed() {
        PaymentService.ChargeResult result = service.refund("nonexistent-payment-id");

        assertThat(result.status()).isEqualTo(PaymentLedger.PaymentStatus.FAILED);
        assertThat(result.message()).containsIgnoringCase("not found");
    }

    // -------------------------------------------------------------------------
    // 8. Refund of already-refunded payment is rejected
    // -------------------------------------------------------------------------

    @Test
    void refundAlreadyRefundedPaymentIsRejected() {
        PaymentService.ChargeResult charged  = service.processCharge(request("ride-8", "idem-8"));
        service.refund(charged.paymentId()); // first refund

        PaymentService.ChargeResult second = service.refund(charged.paymentId());

        assertThat(second.status()).isEqualTo(PaymentLedger.PaymentStatus.REFUNDED);
        assertThat(second.message()).containsIgnoringCase("refunded");
    }

    // -------------------------------------------------------------------------
    // 9. processCharge stores idempotency result for future checks
    // -------------------------------------------------------------------------

    @Test
    void processChargeStoresResultInIdempotencyStore() {
        service.processCharge(request("ride-9", "idem-9"));

        assertThat(idempotencyStore.check("idem-9")).isPresent();
    }

    // -------------------------------------------------------------------------
    // 10. Ledger find by status works after multiple charges
    // -------------------------------------------------------------------------

    @Test
    void ledgerFindByStatusWorksAcrossMultipleCharges() {
        service.processCharge(request("ride-10a", "idem-10a"));
        service.processCharge(request("ride-10b", "idem-10b"));

        List<PaymentLedger.PaymentRecord> captured = ledger.findByStatus(PaymentLedger.PaymentStatus.CAPTURED);

        assertThat(captured).hasSize(2);
    }
}
