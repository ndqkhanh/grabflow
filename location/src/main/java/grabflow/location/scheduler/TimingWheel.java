package grabflow.location.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hierarchical Timing Wheel — an O(1) timer data structure.
 *
 * <h2>OS Scheduling Background</h2>
 * Operating system kernels must manage thousands of concurrent timers (TCP retransmit,
 * process scheduling quanta, timeout syscalls). A naïve sorted priority-queue costs
 * O(log n) per insert and O(log n) per delete. At 100k timers per second that overhead
 * becomes non-trivial inside the kernel's interrupt handler.
 *
 * <p>The timing wheel, first described by Varghese &amp; Lauck (1987) and adopted by the
 * Linux kernel ({@code timer_list}, later {@code hrtimer}), achieves <em>O(1) insert,
 * O(1) delete, and amortised O(1) per-tick processing</em> by exploiting the fact that
 * deadlines can be reduced modulo the wheel period.
 *
 * <h2>How This Implementation Works</h2>
 * <pre>
 *   Wheel period = tickDurationMs * wheelSize
 *   Slot index   = (deadlineMs / tickDurationMs) % wheelSize
 * </pre>
 * Each "tick" the clock hand advances one slot and all tasks in that slot whose
 * {@code deadline &le; currentTime} are executed immediately.
 *
 * <h2>Hierarchical (Overflow) Wheel</h2>
 * A single wheel only covers {@code tickDuration * wheelSize} milliseconds. Deadlines
 * beyond that range are placed in an <em>overflow wheel</em> whose tick duration equals
 * the current wheel's full period ({@code tickDuration * wheelSize}). This mirrors
 * Linux's five-level timer wheel and Kafka's hierarchical timing wheel, forming a
 * multi-precision cascade:
 * <ul>
 *   <li>Inner wheel — fine granularity, small range</li>
 *   <li>Overflow wheel — coarse granularity, large range</li>
 * </ul>
 * When the overflow wheel's clock hand advances past a slot, tasks are
 * <em>cascaded</em> back to the root wheel: expired tasks fire immediately;
 * live tasks are re-inserted at the finest granularity that fits.
 *
 * <h2>Complexity Comparison</h2>
 * <table border="1">
 *   <caption>Timer data structure complexities</caption>
 *   <tr><th>Implementation</th><th>Insert</th><th>Tick</th></tr>
 *   <tr><td>java.util.PriorityQueue</td><td>O(log n)</td><td>O(log n)</td></tr>
 *   <tr><td>Linux kernel hrtimer</td><td>O(1)</td><td>O(1) amortised</td></tr>
 *   <tr><td>Kafka TimingWheel</td><td>O(1)</td><td>O(1) amortised</td></tr>
 *   <tr><td>This class</td><td>O(1)</td><td>O(1) amortised</td></tr>
 * </table>
 *
 * <h2>Thread Safety</h2>
 * Slot lists are guarded by {@code synchronized} on the list object itself.
 * The overflow wheel is lazily initialised with double-checked locking on the
 * {@code volatile overflowWheel} field.
 */
public class TimingWheel {

    // -------------------------------------------------------------------------
    // Inner class
    // -------------------------------------------------------------------------

    /**
     * A unit of work scheduled at a specific wall-clock deadline.
     */
    public static class TimerTask {
        private final String id;
        private final long deadlineMs;
        private final Runnable action;
        private volatile boolean cancelled;

        public TimerTask(String id, long deadlineMs, Runnable action) {
            this.id = id;
            this.deadlineMs = deadlineMs;
            this.action = action;
            this.cancelled = false;
        }

        /** Cancels this task. A cancelled task will not be executed even if its slot fires. */
        public void cancel() {
            this.cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public String getId() {
            return id;
        }

        public long getDeadlineMs() {
            return deadlineMs;
        }

        public Runnable getAction() {
            return action;
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final long tickDurationMs;
    private final int wheelSize;

    @SuppressWarnings("unchecked")
    private final List<TimerTask>[] slots;

    /**
     * The tick-aligned clock position of this wheel.
     * Updated by one tickDurationMs per tick in advanceClock / drainOverflow.
     */
    private long currentTickMs;

    /** Lazily created overflow wheel; volatile for double-checked locking. */
    private volatile TimingWheel overflowWheel;

    /**
     * Tasks stored in this wheel's own slots (not in any overflow level).
     * {@link #pendingTasks()} sums across all levels.
     */
    private final AtomicInteger taskCount = new AtomicInteger();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates a timing wheel.
     *
     * @param tickDurationMs duration of each slot in milliseconds (e.g. 100)
     * @param wheelSize      number of slots (e.g. 512); total coverage = tickDurationMs * wheelSize
     * @param startMs        initial clock value (typically {@code System.currentTimeMillis()})
     */
    @SuppressWarnings("unchecked")
    public TimingWheel(long tickDurationMs, int wheelSize, long startMs) {
        this.tickDurationMs = tickDurationMs;
        this.wheelSize = wheelSize;
        // Align start to tick boundary
        this.currentTickMs = (startMs / tickDurationMs) * tickDurationMs;
        this.slots = (List<TimerTask>[]) new List[wheelSize];
        for (int i = 0; i < wheelSize; i++) {
            slots[i] = new ArrayList<>();
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Factory method — preferred way to create tasks.
     */
    public TimerTask createTask(String id, long deadlineMs, Runnable action) {
        return new TimerTask(id, deadlineMs, action);
    }

    /**
     * Inserts a task into the wheel in O(1) time.
     *
     * <p>The slot is computed as {@code (deadline / tickDuration) % wheelSize}.
     * If the deadline exceeds this wheel's range the task is delegated to the
     * overflow wheel (created lazily).
     *
     * @param task the task to schedule
     * @return {@code true} if the task was accepted, {@code false} if it was
     *         already cancelled or its deadline has already passed
     */
    public boolean add(TimerTask task) {
        if (task.isCancelled()) {
            return false;
        }
        if (task.getDeadlineMs() <= currentTickMs) {
            return false;
        }
        return placeTask(task);
    }

    /**
     * Advances the wheel clock to {@code targetMs} and fires all tasks whose
     * deadline has been reached.
     *
     * <p>Algorithm (mirrors Kafka's TimingWheel):
     * <ol>
     *   <li>Advance all overflow wheels, cascading expired tasks back to this root
     *       wheel.  Uses {@code targetMs} as the "now" so expiry is decided with the
     *       final clock value, not an intermediate one.</li>
     *   <li>Tick the inner wheel slot by slot; execute tasks whose deadline
     *       &le; {@code targetMs}.</li>
     * </ol>
     *
     * @param targetMs the new "now" value
     */
    public void advanceClock(long targetMs) {
        // Step 1: Advance the inner wheel tick by tick.
        // This updates currentTickMs so that when overflow tasks cascade (step 2),
        // placeTask() uses the correct reference time and assigns fine-grained slots.
        while (currentTickMs + tickDurationMs <= targetMs) {
            currentTickMs += tickDurationMs;
            int slotIndex = (int) ((currentTickMs / tickDurationMs) % wheelSize);

            List<TimerTask> bucket;
            synchronized (slots[slotIndex]) {
                bucket = new ArrayList<>(slots[slotIndex]);
                slots[slotIndex].clear();
            }
            taskCount.addAndGet(-bucket.size());

            for (TimerTask task : bucket) {
                if (task.isCancelled()) {
                    continue;
                }
                if (task.getDeadlineMs() <= targetMs) {
                    task.getAction().run();
                } else {
                    // Still in the future — re-place with the now-updated clock.
                    placeTask(task);
                }
            }
        }

        // Step 2: Cascade tasks from all overflow levels into this wheel.
        // currentTickMs is now up to date, so cascaded tasks that fit in the inner
        // wheel land in the correct future slot (to be fired in a later advanceClock).
        // Tasks already expired (deadline <= targetMs) fire immediately in requeueToRoot.
        if (overflowWheel != null) {
            overflowWheel.drainOverflow(targetMs, this);
        }
    }

    /**
     * Returns the total number of tasks currently pending in this wheel and all
     * overflow wheels combined.
     */
    public int pendingTasks() {
        int count = taskCount.get();
        if (overflowWheel != null) {
            count += overflowWheel.pendingTasks();
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Places a task into this wheel's slots without checking whether it is expired.
     * Escalates to the overflow wheel if the deadline is beyond this wheel's range.
     *
     * <p>Boundary: a task fits if {@code deadline - currentTickMs < tickDuration * wheelSize}.
     * A task whose interval equals the full wheel period maps to slot 0, which has
     * already been ticked past in this revolution — so it is pushed to overflow.
     */
    private boolean placeTask(TimerTask task) {
        if (task.isCancelled()) {
            return false;
        }
        long interval = task.getDeadlineMs() - currentTickMs;
        long wheelRange = tickDurationMs * (long) wheelSize;

        if (interval < wheelRange) {
            int slotIndex = (int) ((task.getDeadlineMs() / tickDurationMs) % wheelSize);
            synchronized (slots[slotIndex]) {
                slots[slotIndex].add(task);
            }
            taskCount.incrementAndGet();
            return true;
        } else {
            return getOrCreateOverflowWheel().placeTask(task);
        }
    }

    /**
     * Advances this overflow wheel's clock toward {@code targetMs}, draining each
     * elapsed slot's tasks back into {@code root} (the original innermost wheel).
     *
     * <p>All levels of overflow drain into the same {@code root} so that when a
     * cascaded task is re-queued, {@code root.currentTickMs} is the authoritative
     * "already-advanced" reference time for slot placement decisions.
     *
     * <p>A task cascaded from overflow is re-queued via {@link #requeueToRoot}:
     * if its deadline &le; {@code targetMs} it fires immediately; otherwise it is
     * placed into root (which may re-escalate to overflow if the interval is still
     * large, but since {@code root.currentTickMs} has been updated by the inner
     * loop, this is correct).
     *
     * @param targetMs target time to advance to
     * @param root     the root (innermost) wheel that owns the main clock
     */
    private void drainOverflow(long targetMs, TimingWheel root) {
        // Drain deeper overflow levels first (outermost → root direction of cascade).
        if (overflowWheel != null) {
            overflowWheel.drainOverflow(targetMs, root);
        }

        while (currentTickMs + tickDurationMs <= targetMs) {
            currentTickMs += tickDurationMs;
            int slotIndex = (int) ((currentTickMs / tickDurationMs) % wheelSize);

            List<TimerTask> bucket;
            synchronized (slots[slotIndex]) {
                bucket = new ArrayList<>(slots[slotIndex]);
                slots[slotIndex].clear();
            }
            taskCount.addAndGet(-bucket.size());

            for (TimerTask task : bucket) {
                requeueToRoot(task, targetMs, root);
            }
        }
    }

    /**
     * Re-queues a single cascaded task to the root wheel.
     *
     * <ul>
     *   <li>Cancelled tasks are silently dropped.</li>
     *   <li>Tasks whose deadline &le; {@code targetMs} are executed immediately.</li>
     *   <li>Live tasks are placed into {@code root} via {@link #placeTask}, which
     *       uses {@code root.currentTickMs} (updated by the inner loop that ran
     *       before this overflow drain) for interval calculation.</li>
     * </ul>
     */
    private static void requeueToRoot(TimerTask task, long targetMs, TimingWheel root) {
        if (task.isCancelled()) {
            return;
        }
        if (task.getDeadlineMs() <= targetMs) {
            task.getAction().run();
        } else {
            root.placeTask(task);
        }
    }

    /**
     * Returns the overflow wheel, creating it lazily with double-checked locking.
     * The overflow wheel's tick duration equals the full period of this wheel,
     * giving it a much larger time range at coarser granularity.
     */
    private TimingWheel getOrCreateOverflowWheel() {
        if (overflowWheel == null) {
            synchronized (this) {
                if (overflowWheel == null) {
                    overflowWheel = new TimingWheel(tickDurationMs * wheelSize, wheelSize, currentTickMs);
                }
            }
        }
        return overflowWheel;
    }
}
