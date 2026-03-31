package grabflow.location.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Manages driver inactivity timeouts using a hierarchical timing wheel.
 *
 * <h3>CS Fundamental: Watchdog Timer / Heartbeat Pattern</h3>
 * <p>This implements the classic "watchdog timer" pattern from embedded systems
 * and distributed systems heartbeat protocols. Each driver has an associated timer.
 * On every GPS update (heartbeat), the timer is reset. If no heartbeat arrives
 * within the timeout period, the driver is considered inactive and removed.</p>
 *
 * <p>This is analogous to:</p>
 * <ul>
 *   <li>Linux's {@code ITIMER_REAL} watchdog timer</li>
 *   <li>TCP keepalive probes</li>
 *   <li>ZooKeeper session expiry</li>
 *   <li>Kafka consumer group heartbeat timeout</li>
 * </ul>
 *
 * <p>The timing wheel provides O(1) timer insertion and cancellation,
 * making it efficient even with 100K+ active drivers.</p>
 */
public class DriverTimeoutManager {

    private static final Logger log = LoggerFactory.getLogger(DriverTimeoutManager.class);

    private final long timeoutMs;
    private final Consumer<String> onTimeout;
    private final TimingWheel timingWheel;
    private final ConcurrentHashMap<String, TimingWheel.TimerTask> activeTimers =
            new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread tickerThread;

    /**
     * @param timeoutMs  driver inactivity timeout in milliseconds
     * @param onTimeout  callback invoked with driverId when a driver times out
     */
    public DriverTimeoutManager(long timeoutMs, Consumer<String> onTimeout) {
        this.timeoutMs = timeoutMs;
        this.onTimeout = onTimeout;
        // Timing wheel: 100ms tick, 512 slots = 51.2s range per level
        this.timingWheel = new TimingWheel(100, 512, System.currentTimeMillis());
    }

    /**
     * Records a heartbeat from a driver, resetting their timeout timer.
     *
     * <p>Cancel-and-reinsert pattern: cancel the existing timer (if any),
     * then create a new timer at {@code now + timeoutMs}. This is the standard
     * approach for resettable timers in O(1) amortized time.</p>
     *
     * @param driverId the driver sending the heartbeat
     */
    public void heartbeat(String driverId) {
        // Cancel existing timer
        TimingWheel.TimerTask existing = activeTimers.get(driverId);
        if (existing != null) {
            existing.cancel();
        }

        // Create new timer
        long deadline = System.currentTimeMillis() + timeoutMs;
        TimingWheel.TimerTask task = timingWheel.createTask(
                driverId,
                deadline,
                () -> {
                    activeTimers.remove(driverId);
                    log.info("Driver timed out: {}", driverId);
                    onTimeout.accept(driverId);
                }
        );

        activeTimers.put(driverId, task);
        timingWheel.add(task);
    }

    /**
     * Removes a driver's timer (e.g., when they explicitly go offline).
     */
    public void removeDriver(String driverId) {
        TimingWheel.TimerTask task = activeTimers.remove(driverId);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * Returns the number of drivers with active heartbeat timers.
     */
    public int activeDrivers() {
        return activeTimers.size();
    }

    /**
     * Starts the background ticker thread that advances the timing wheel clock.
     * Uses a virtual thread (Project Loom) for lightweight scheduling.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            tickerThread = Thread.ofVirtual()
                    .name("driver-timeout-ticker")
                    .start(() -> {
                        while (running.get()) {
                            try {
                                Thread.sleep(100); // tick every 100ms
                                timingWheel.advanceClock(System.currentTimeMillis());
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    });
            log.info("Driver timeout manager started (timeout={}ms)", timeoutMs);
        }
    }

    /**
     * Stops the ticker thread.
     */
    public void stop() {
        running.set(false);
        if (tickerThread != null) {
            tickerThread.interrupt();
        }
    }

    /**
     * Manually advances the clock (for testing without the background thread).
     */
    public void tick(long currentTimeMs) {
        timingWheel.advanceClock(currentTimeMs);
    }
}
