package grabflow.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates multi-step payment workflows using the <em>Saga pattern</em>
 * (Garcia-Molina &amp; Salem, 1987).
 *
 * <h2>What is a Saga?</h2>
 * A Saga breaks a distributed transaction into a sequence of local transactions,
 * each paired with a <em>compensating action</em> that undoes the transaction's
 * effect if a later step fails. Because no two-phase locking is used, each step
 * commits immediately, making the system resilient and avoiding distributed
 * deadlocks.
 *
 * <h2>GrabFlow Payment Saga Example</h2>
 * When a ride completes, the payment saga runs these steps in order:
 * <ol>
 *   <li>{@code authorize_payment} – reserves funds on the rider's card.</li>
 *   <li>{@code capture_payment}   – settles the reserved amount.</li>
 *   <li>{@code pay_driver}        – transfers the driver's share.</li>
 * </ol>
 *
 * <h3>Compensation on failure</h3>
 * <ul>
 *   <li>If {@code capture_payment} fails: compensate {@code authorize_payment}
 *       (void / release the hold).</li>
 *   <li>If {@code pay_driver} fails: compensate {@code capture_payment}
 *       (refund the rider), then compensate {@code authorize_payment}.</li>
 * </ul>
 * Compensations are always run in <em>reverse</em> order of the steps that
 * succeeded.
 *
 * <h2>Thread Safety</h2>
 * Each call to {@link #execute} creates its own {@link SagaContext}, so the
 * orchestrator itself is stateless and thread-safe.
 */
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    // -------------------------------------------------------------------------
    // Public API types
    // -------------------------------------------------------------------------

    /**
     * The result of a single saga step execution.
     */
    public enum SagaResult {
        /** The step completed successfully; the saga may continue. */
        SUCCESS,
        /** The step failed; the saga must stop and compensate. */
        FAILURE
    }

    /**
     * Mutable context shared across all steps within a single saga execution.
     * Steps may read and write arbitrary key-value data to coordinate state
     * (e.g., storing an authorization code for the capture step to consume).
     *
     * @param sagaId unique identifier for this saga run
     * @param data   mutable map for inter-step data sharing
     */
    public record SagaContext(String sagaId, Map<String, Object> data) {

        /**
         * Convenience factory that creates a context with an empty, mutable map.
         *
         * @param sagaId unique saga identifier
         * @return a fresh {@link SagaContext}
         */
        public static SagaContext of(String sagaId) {
            return new SagaContext(sagaId, new HashMap<>());
        }
    }

    /**
     * A single step in a saga. Implementations must provide both a forward
     * action ({@link #execute}) and its compensating action ({@link #compensate}).
     */
    public interface SagaStep {

        /**
         * Execute the forward action of this step.
         *
         * @param ctx shared saga context
         * @return {@link SagaResult#SUCCESS} to continue, {@link SagaResult#FAILURE} to abort
         */
        SagaResult execute(SagaContext ctx);

        /**
         * Undo or compensate for the effects of a previously successful
         * {@link #execute}. Compensation failures are logged but do not
         * prevent other compensations from running.
         *
         * @param ctx shared saga context (same instance passed to {@link #execute})
         */
        void compensate(SagaContext ctx);

        /**
         * Human-readable name used in log messages and outcome reports.
         *
         * @return step name
         */
        default String name() {
            return getClass().getSimpleName();
        }
    }

    /**
     * The final outcome of a saga execution.
     *
     * @param sagaId         the saga's unique identifier
     * @param result         overall result ({@link SagaResult#SUCCESS} or {@link SagaResult#FAILURE})
     * @param stepsCompleted number of steps that executed successfully before failure (or total on success)
     * @param failureReason  human-readable failure description, or {@code null} on success
     */
    public record SagaOutcome(
            String sagaId,
            SagaResult result,
            int stepsCompleted,
            String failureReason
    ) {

        /** Convenience predicate. */
        public boolean isSuccess() {
            return result == SagaResult.SUCCESS;
        }
    }

    // -------------------------------------------------------------------------
    // Core execution logic
    // -------------------------------------------------------------------------

    /**
     * Execute a saga composed of the given steps against a fresh context.
     *
     * <p>Steps are executed in order. On the first failure, all previously
     * completed steps are compensated in reverse order. The method always
     * returns an outcome — it never throws.
     *
     * @param sagaId unique identifier for this run (used for logging and the outcome)
     * @param steps  ordered list of saga steps; may be empty
     * @return outcome describing overall success/failure and progress
     */
    public SagaOutcome execute(String sagaId, List<SagaStep> steps) {
        SagaContext ctx = SagaContext.of(sagaId);
        log.info("Saga [{}] starting with {} step(s)", sagaId, steps.size());

        List<SagaStep> completed = new ArrayList<>();

        for (SagaStep step : steps) {
            log.debug("Saga [{}] executing step '{}'", sagaId, step.name());
            SagaResult result;
            try {
                result = step.execute(ctx);
            } catch (Exception e) {
                log.error("Saga [{}] step '{}' threw unexpectedly: {}", sagaId, step.name(), e.getMessage(), e);
                result = SagaResult.FAILURE;
            }

            if (result == SagaResult.SUCCESS) {
                completed.add(step);
                log.debug("Saga [{}] step '{}' succeeded", sagaId, step.name());
            } else {
                String reason = "Step '" + step.name() + "' failed";
                log.warn("Saga [{}] {} — compensating {} completed step(s)", sagaId, reason, completed.size());
                compensateAll(sagaId, completed, ctx);
                return new SagaOutcome(sagaId, SagaResult.FAILURE, completed.size(), reason);
            }
        }

        log.info("Saga [{}] completed successfully ({} step(s))", sagaId, completed.size());
        return new SagaOutcome(sagaId, SagaResult.SUCCESS, completed.size(), null);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void compensateAll(String sagaId, List<SagaStep> completed, SagaContext ctx) {
        for (int i = completed.size() - 1; i >= 0; i--) {
            SagaStep step = completed.get(i);
            log.debug("Saga [{}] compensating step '{}'", sagaId, step.name());
            try {
                step.compensate(ctx);
            } catch (Exception e) {
                // Compensation failures are logged but must not block other compensations.
                log.error("Saga [{}] compensation of '{}' threw: {}", sagaId, step.name(), e.getMessage(), e);
            }
        }
    }
}
