package grabflow.location.scheduler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TimingWheel}.
 *
 * Each test controls time explicitly — no real sleeping — making the suite
 * deterministic and fast.
 */
class TimingWheelTest {

    // Convenience factory: 100ms tick, 10 slots → covers 1000ms
    private TimingWheel wheel(long startMs) {
        return new TimingWheel(100, 10, startMs);
    }

    // -------------------------------------------------------------------------
    // 1. taskFiresAtDeadline
    // -------------------------------------------------------------------------
    @Test
    void taskFiresAtDeadline() {
        long now = 1000L;
        TimingWheel tw = wheel(now);
        AtomicBoolean fired = new AtomicBoolean(false);

        TimingWheel.TimerTask task = tw.createTask("t1", now + 500, () -> fired.set(true));
        assertTrue(tw.add(task));

        tw.advanceClock(now + 500);
        assertTrue(fired.get(), "Task should fire exactly at its deadline");
    }

    // -------------------------------------------------------------------------
    // 2. taskDoesNotFireBeforeDeadline
    // -------------------------------------------------------------------------
    @Test
    void taskDoesNotFireBeforeDeadline() {
        long now = 1000L;
        TimingWheel tw = wheel(now);
        AtomicBoolean fired = new AtomicBoolean(false);

        TimingWheel.TimerTask task = tw.createTask("t1", now + 500, () -> fired.set(true));
        tw.add(task);

        tw.advanceClock(now + 499);
        assertFalse(fired.get(), "Task must not fire before its deadline");
    }

    // -------------------------------------------------------------------------
    // 3. cancelledTaskDoesNotFire
    // -------------------------------------------------------------------------
    @Test
    void cancelledTaskDoesNotFire() {
        long now = 1000L;
        TimingWheel tw = wheel(now);
        AtomicBoolean fired = new AtomicBoolean(false);

        TimingWheel.TimerTask task = tw.createTask("t1", now + 500, () -> fired.set(true));
        tw.add(task);
        task.cancel();

        tw.advanceClock(now + 600);
        assertFalse(fired.get(), "Cancelled task must not fire");
    }

    // -------------------------------------------------------------------------
    // 4. multipleTasksSameSlot
    // -------------------------------------------------------------------------
    @Test
    void multipleTasksSameSlot() {
        long now = 1000L;
        TimingWheel tw = wheel(now);
        AtomicInteger count = new AtomicInteger(0);

        // Both map to the same slot: deadline 1500 → slot (1500/100) % 10 = 5
        tw.add(tw.createTask("t1", now + 500, count::incrementAndGet));
        tw.add(tw.createTask("t2", now + 500, count::incrementAndGet));

        tw.advanceClock(now + 500);
        assertEquals(2, count.get(), "Both tasks sharing a slot must fire");
    }

    // -------------------------------------------------------------------------
    // 5. overflowWheelHandlesLargeDeadlines
    // -------------------------------------------------------------------------
    @Test
    void overflowWheelHandlesLargeDeadlines() {
        // tickDuration=1ms, wheelSize=10 → covers only 10ms
        // deadline of now+100ms requires the overflow wheel
        long now = 0L;
        TimingWheel tw = new TimingWheel(1, 10, now);
        AtomicBoolean fired = new AtomicBoolean(false);

        TimingWheel.TimerTask task = tw.createTask("big", now + 100, () -> fired.set(true));
        assertTrue(tw.add(task), "Overflow task should be accepted");

        tw.advanceClock(now + 100);
        assertTrue(fired.get(), "Task with large deadline must fire after overflow cascade");
    }

    // -------------------------------------------------------------------------
    // 6. pendingCountAccurate
    // -------------------------------------------------------------------------
    @Test
    void pendingCountAccurate() {
        long now = 0L;
        TimingWheel tw = wheel(now);
        AtomicInteger fired = new AtomicInteger(0);

        // Add 5 tasks at different deadlines
        List<TimingWheel.TimerTask> tasks = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            TimingWheel.TimerTask t = tw.createTask("t" + i, now + i * 100L, fired::incrementAndGet);
            tw.add(t);
            tasks.add(t);
        }
        assertEquals(5, tw.pendingTasks(), "Should have 5 pending tasks");

        // Advance past 2 deadlines (100ms and 200ms)
        tw.advanceClock(now + 200);
        assertEquals(2, fired.get());
        assertEquals(3, tw.pendingTasks(), "3 tasks still pending");

        // Cancel one more
        tasks.get(2).cancel(); // deadline=300ms
        // Advance to fire its slot; it was cancelled so fired count stays 2
        tw.advanceClock(now + 300);
        assertEquals(2, fired.get(), "Cancelled task must not increment fired count");
        assertEquals(2, tw.pendingTasks(), "2 tasks still pending after cancel");
    }

    // -------------------------------------------------------------------------
    // 7. expiredTaskRejected
    // -------------------------------------------------------------------------
    @Test
    void expiredTaskRejected() {
        long now = 1000L;
        TimingWheel tw = wheel(now);

        // Deadline in the past
        TimingWheel.TimerTask task = tw.createTask("old", now - 1, () -> {});
        assertFalse(tw.add(task), "Past-deadline task must be rejected");
        assertEquals(0, tw.pendingTasks());
    }

    // -------------------------------------------------------------------------
    // 8. cancelledTaskRejected
    // -------------------------------------------------------------------------
    @Test
    void cancelledTaskRejected() {
        long now = 1000L;
        TimingWheel tw = wheel(now);

        TimingWheel.TimerTask task = tw.createTask("pre-cancelled", now + 500, () -> {});
        task.cancel();
        assertFalse(tw.add(task), "Pre-cancelled task must be rejected by add()");
        assertEquals(0, tw.pendingTasks());
    }

    // -------------------------------------------------------------------------
    // 9. hierarchicalCascade
    // -------------------------------------------------------------------------
    @Test
    void hierarchicalCascade() {
        // tickDuration=10ms, wheelSize=5 → inner covers 50ms
        // Overflow tick = 10*5 = 50ms, wheelSize=5 → overflow covers 250ms
        // Task at now+120ms goes to overflow; cascades into inner when overflow tick fires.
        long now = 0L;
        TimingWheel tw = new TimingWheel(10, 5, now);
        AtomicBoolean fired = new AtomicBoolean(false);

        TimingWheel.TimerTask task = tw.createTask("cascade", now + 120, () -> fired.set(true));
        assertTrue(tw.add(task));

        // Advance step by step; task must fire at or after 120ms but not before.
        tw.advanceClock(now + 100);
        assertFalse(fired.get(), "Task must not fire at 100ms");

        tw.advanceClock(now + 120);
        assertTrue(fired.get(), "Task must fire at 120ms after cascade");
    }

    // -------------------------------------------------------------------------
    // 10. performanceO1Insert
    // -------------------------------------------------------------------------
    @Test
    void performanceO1Insert() {
        // 100K tasks spread across a large wheel should insert in well under 200ms.
        long now = System.currentTimeMillis();
        TimingWheel tw = new TimingWheel(1, 512, now);

        int count = 100_000;
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            tw.add(tw.createTask("t" + i, now + 1 + (i % 511), () -> {}));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 200,
                "100K inserts should complete in < 200ms (O(1) per insert); took " + elapsedMs + "ms");
        assertEquals(count, tw.pendingTasks());
    }

    // -------------------------------------------------------------------------
    // 11. taskExecutionOrder
    // -------------------------------------------------------------------------
    @Test
    void taskExecutionOrder() {
        long now = 0L;
        TimingWheel tw = wheel(now);
        List<String> order = new ArrayList<>();

        tw.add(tw.createTask("a", now + 300, () -> order.add("a")));
        tw.add(tw.createTask("b", now + 100, () -> order.add("b")));
        tw.add(tw.createTask("c", now + 200, () -> order.add("c")));

        tw.advanceClock(now + 300);

        assertEquals(List.of("b", "c", "a"), order,
                "Tasks must fire in chronological deadline order");
    }
}
