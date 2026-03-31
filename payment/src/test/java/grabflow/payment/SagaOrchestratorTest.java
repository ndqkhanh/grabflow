package grabflow.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SagaOrchestratorTest {

    private SagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new SagaOrchestrator();
    }

    // -------------------------------------------------------------------------
    // 1. Empty saga — succeeds with zero steps
    // -------------------------------------------------------------------------

    @Test
    void emptySagaSucceedsImmediately() {
        SagaOrchestrator.SagaOutcome outcome = orchestrator.execute("saga-empty", List.of());

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.stepsCompleted()).isZero();
        assertThat(outcome.failureReason()).isNull();
        assertThat(outcome.sagaId()).isEqualTo("saga-empty");
    }

    // -------------------------------------------------------------------------
    // 2. Single successful step
    // -------------------------------------------------------------------------

    @Test
    void singleSuccessfulStep() {
        TrackingStep step = new TrackingStep("step1", SagaOrchestrator.SagaResult.SUCCESS);

        SagaOrchestrator.SagaOutcome outcome = orchestrator.execute("saga-single", List.of(step));

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.stepsCompleted()).isEqualTo(1);
        assertThat(step.executionCount()).isEqualTo(1);
        assertThat(step.compensationCount()).isZero();
    }

    // -------------------------------------------------------------------------
    // 3. All steps succeed — saga completes fully
    // -------------------------------------------------------------------------

    @Test
    void allStepsSucceedCompletesFullSaga() {
        TrackingStep step1 = new TrackingStep("authorize", SagaOrchestrator.SagaResult.SUCCESS);
        TrackingStep step2 = new TrackingStep("capture",   SagaOrchestrator.SagaResult.SUCCESS);
        TrackingStep step3 = new TrackingStep("pay_driver", SagaOrchestrator.SagaResult.SUCCESS);

        SagaOrchestrator.SagaOutcome outcome = orchestrator.execute("saga-full", List.of(step1, step2, step3));

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.stepsCompleted()).isEqualTo(3);
        assertThat(outcome.failureReason()).isNull();

        // All executed, none compensated
        assertThat(step1.executionCount()).isEqualTo(1);
        assertThat(step2.executionCount()).isEqualTo(1);
        assertThat(step3.executionCount()).isEqualTo(1);
        assertThat(step1.compensationCount()).isZero();
        assertThat(step2.compensationCount()).isZero();
        assertThat(step3.compensationCount()).isZero();
    }

    // -------------------------------------------------------------------------
    // 4. First step fails — no compensations needed (nothing succeeded yet)
    // -------------------------------------------------------------------------

    @Test
    void firstStepFailureTriggersNoCompensations() {
        TrackingStep step1 = new TrackingStep("authorize", SagaOrchestrator.SagaResult.FAILURE);
        TrackingStep step2 = new TrackingStep("capture",   SagaOrchestrator.SagaResult.SUCCESS);

        SagaOrchestrator.SagaOutcome outcome = orchestrator.execute("saga-fail-first", List.of(step1, step2));

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.stepsCompleted()).isZero();
        assertThat(outcome.failureReason()).contains("authorize");

        assertThat(step1.executionCount()).isEqualTo(1);
        assertThat(step1.compensationCount()).isZero();   // failed, not compensated
        assertThat(step2.executionCount()).isZero();      // never reached
    }

    // -------------------------------------------------------------------------
    // 5. Second step fails — first step is compensated
    // -------------------------------------------------------------------------

    @Test
    void secondStepFailureCompensatesFirstStep() {
        TrackingStep step1 = new TrackingStep("authorize", SagaOrchestrator.SagaResult.SUCCESS);
        TrackingStep step2 = new TrackingStep("capture",   SagaOrchestrator.SagaResult.FAILURE);
        TrackingStep step3 = new TrackingStep("pay_driver", SagaOrchestrator.SagaResult.SUCCESS);

        SagaOrchestrator.SagaOutcome outcome = orchestrator.execute("saga-fail-second", List.of(step1, step2, step3));

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.stepsCompleted()).isEqualTo(1);

        assertThat(step1.compensationCount()).isEqualTo(1);  // compensated
        assertThat(step2.compensationCount()).isZero();      // failed, not compensated
        assertThat(step3.executionCount()).isZero();         // never reached
    }

    // -------------------------------------------------------------------------
    // 6. Last step fails — all previous steps compensated in reverse order
    // -------------------------------------------------------------------------

    @Test
    void lastStepFailureCompensatesAllPreviousInReverseOrder() {
        List<String> compensationOrder = new ArrayList<>();

        SagaOrchestrator.SagaStep step1 = recordingStep("authorize", SagaOrchestrator.SagaResult.SUCCESS, compensationOrder);
        SagaOrchestrator.SagaStep step2 = recordingStep("capture",   SagaOrchestrator.SagaResult.SUCCESS, compensationOrder);
        SagaOrchestrator.SagaStep step3 = recordingStep("pay_driver", SagaOrchestrator.SagaResult.FAILURE, compensationOrder);

        SagaOrchestrator.SagaOutcome outcome = orchestrator.execute("saga-fail-last", List.of(step1, step2, step3));

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.stepsCompleted()).isEqualTo(2);

        // Compensations must fire in reverse: capture first, then authorize
        assertThat(compensationOrder).containsExactly("capture", "authorize");
    }

    // -------------------------------------------------------------------------
    // 7. Compensation itself throws — remaining compensations still run
    // -------------------------------------------------------------------------

    @Test
    void compensationFailureDoesNotPreventOtherCompensations() {
        List<String> compensationOrder = new ArrayList<>();

        SagaOrchestrator.SagaStep step1 = new SagaOrchestrator.SagaStep() {
            @Override
            public SagaOrchestrator.SagaResult execute(SagaOrchestrator.SagaContext ctx) {
                return SagaOrchestrator.SagaResult.SUCCESS;
            }
            @Override
            public void compensate(SagaOrchestrator.SagaContext ctx) {
                compensationOrder.add(name());
                // step1 compensation runs last (reverse order) — no throw here
            }
            @Override
            public String name() { return "step1"; }
        };

        SagaOrchestrator.SagaStep step2 = new SagaOrchestrator.SagaStep() {
            @Override
            public SagaOrchestrator.SagaResult execute(SagaOrchestrator.SagaContext ctx) {
                return SagaOrchestrator.SagaResult.SUCCESS;
            }
            @Override
            public void compensate(SagaOrchestrator.SagaContext ctx) {
                compensationOrder.add(name());
                throw new RuntimeException("Compensation exploded!");
            }
            @Override
            public String name() { return "step2"; }
        };

        SagaOrchestrator.SagaStep step3 = new SagaOrchestrator.SagaStep() {
            @Override
            public SagaOrchestrator.SagaResult execute(SagaOrchestrator.SagaContext ctx) {
                return SagaOrchestrator.SagaResult.FAILURE;
            }
            @Override
            public void compensate(SagaOrchestrator.SagaContext ctx) {}
            @Override
            public String name() { return "step3"; }
        };

        // Must not throw even though step2.compensate() throws
        SagaOrchestrator.SagaOutcome outcome = orchestrator.execute("saga-comp-fail", List.of(step1, step2, step3));

        assertThat(outcome.isSuccess()).isFalse();
        // Both compensations were attempted (step2 threw, step1 still ran)
        assertThat(compensationOrder).containsExactly("step2", "step1");
    }

    // -------------------------------------------------------------------------
    // 8. Context is shared across steps
    // -------------------------------------------------------------------------

    @Test
    void contextIsSharedAcrossSteps() {
        SagaOrchestrator.SagaStep writer = new SagaOrchestrator.SagaStep() {
            @Override
            public SagaOrchestrator.SagaResult execute(SagaOrchestrator.SagaContext ctx) {
                ctx.data().put("token", "abc123");
                return SagaOrchestrator.SagaResult.SUCCESS;
            }
            @Override
            public void compensate(SagaOrchestrator.SagaContext ctx) {}
        };

        List<Object> observed = new ArrayList<>();
        SagaOrchestrator.SagaStep reader = new SagaOrchestrator.SagaStep() {
            @Override
            public SagaOrchestrator.SagaResult execute(SagaOrchestrator.SagaContext ctx) {
                observed.add(ctx.data().get("token"));
                return SagaOrchestrator.SagaResult.SUCCESS;
            }
            @Override
            public void compensate(SagaOrchestrator.SagaContext ctx) {}
        };

        orchestrator.execute("saga-ctx", List.of(writer, reader));

        assertThat(observed).containsExactly("abc123");
    }

    // -------------------------------------------------------------------------
    // 9. Step that throws is treated as FAILURE
    // -------------------------------------------------------------------------

    @Test
    void stepThatThrowsIsHandledAsFailure() {
        SagaOrchestrator.SagaStep explosive = new SagaOrchestrator.SagaStep() {
            @Override
            public SagaOrchestrator.SagaResult execute(SagaOrchestrator.SagaContext ctx) {
                throw new RuntimeException("Network timeout");
            }
            @Override
            public void compensate(SagaOrchestrator.SagaContext ctx) {}
            @Override
            public String name() { return "explosive_step"; }
        };

        SagaOrchestrator.SagaOutcome outcome = orchestrator.execute("saga-throw", List.of(explosive));

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.stepsCompleted()).isZero();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** A saga step that records how many times it was executed and compensated. */
    private static class TrackingStep implements SagaOrchestrator.SagaStep {
        private final String stepName;
        private final SagaOrchestrator.SagaResult resultToReturn;
        private int executionCount;
        private int compensationCount;

        TrackingStep(String stepName, SagaOrchestrator.SagaResult resultToReturn) {
            this.stepName = stepName;
            this.resultToReturn = resultToReturn;
        }

        @Override
        public SagaOrchestrator.SagaResult execute(SagaOrchestrator.SagaContext ctx) {
            executionCount++;
            return resultToReturn;
        }

        @Override
        public void compensate(SagaOrchestrator.SagaContext ctx) {
            compensationCount++;
        }

        @Override
        public String name() { return stepName; }

        int executionCount()    { return executionCount; }
        int compensationCount() { return compensationCount; }
    }

    /** Creates a step that appends its name to {@code order} on compensation. */
    private static SagaOrchestrator.SagaStep recordingStep(
            String stepName,
            SagaOrchestrator.SagaResult result,
            List<String> order) {
        return new SagaOrchestrator.SagaStep() {
            @Override
            public SagaOrchestrator.SagaResult execute(SagaOrchestrator.SagaContext ctx) {
                return result;
            }
            @Override
            public void compensate(SagaOrchestrator.SagaContext ctx) {
                order.add(stepName);
            }
            @Override
            public String name() { return stepName; }
        };
    }
}
