package server.features.exam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The clock that ends exams whether or not anybody is watching (Logic tier, E10.5 ⚑ —
 * F6.4, ADR-010).
 *
 * <p>v1 shipped a client-side timer, and the team's first defence failed on it: the exam
 * stayed open. The fix is not a better countdown, it is moving the decision. This service
 * holds one scheduled task per live attempt, and when that task fires it force-submits
 * through {@link Expiry} — which is a database transaction, so it does its job with the
 * student's client closed, crashed, asleep or on a different network.
 *
 * <h2>What this class does and does not decide</h2>
 *
 * <p>It decides <b>when</b>. It does not decide what expiry means: {@link Expiry} owns the
 * transactional force-submit, the {@code TIMED_OUT} status, the recorded minutes and the
 * push. That split is why this class is testable without a database and why the expiry
 * path is testable without a scheduler.
 *
 * <h2>Four properties worth naming</h2>
 *
 * <ol>
 *   <li><b>Deadlines are given, not stored.</b> Callers pass the instant, because the
 *       deadline is derived from the attempt's start plus the execution's allotted minutes
 *       and there must be exactly one place that arithmetic lives (it is
 *       {@code AttemptRecord.deadline}). This service never computes one.</li>
 *   <li><b>Re-arming is the only way to move a deadline.</b> {@link #arm} replaces
 *       whatever was there, and the replaced task is both cancelled and fenced by a
 *       generation token, so a task that had already started running when the extension
 *       landed cannot expire an attempt that now has fifteen more minutes. Cancellation
 *       alone does not cover that window; the token does.</li>
 *   <li><b>Sweeping is idempotent and is the backstop.</b> {@link #sweep()} fires every
 *       overdue attempt it is holding. It exists because a scheduled task can be lost — a
 *       long stop-the-world pause, a rejected task on a saturated executor — and because
 *       {@link #rearmAll} at boot may hand it deadlines that passed while the process was
 *       down. Expiry itself is a compare-and-set, so firing twice is free.</li>
 *   <li><b>Nothing here throws at its caller.</b> An expiry that fails is logged and the
 *       attempt stays armed for the next sweep. A timer thread that dies on one bad row
 *       would silently stop ending every other exam in the building.</li>
 * </ol>
 */
public final class TimerService {

    private static final Logger log = LoggerFactory.getLogger(TimerService.class);

    /**
     * How often the backstop sweep runs (E10.5).
     *
     * <p>Thirty seconds: short enough that a lost scheduled task costs a student half a
     * minute of extra time rather than the rest of the exam, long enough that a server
     * with nothing running is not waking a thread constantly. It is a backstop — the
     * per-attempt tasks are what normally fire.
     */
    public static final Duration SWEEP_INTERVAL = Duration.ofSeconds(30);

    /** What actually happens when an attempt's time is up. */
    @FunctionalInterface
    public interface Expiry {

        /**
         * Force-submits one attempt, transactionally (F6.4).
         *
         * <p>Must be idempotent: the sweep and a scheduled task can both reach the same
         * attempt, and a student's own submit can beat both. The compare-and-set in the
         * attempt service is what makes that true.
         *
         * @param attemptId the attempt whose deadline has passed
         */
        void expire(long attemptId);
    }

    /**
     * The scheduling seam.
     *
     * <p>Production is a daemon {@link ScheduledExecutorService} wired in
     * {@code HSTSServer} next to the edit-lock sweeper. Tests use a manual implementation
     * and move a {@link Clock} instead of waiting, which is what makes "the attempt expires
     * at exactly T+45" a two-line test rather than a forty-five-minute one.
     */
    @FunctionalInterface
    public interface Scheduler {

        /**
         * Runs {@code task} once after {@code delay}.
         *
         * @param task  what to run
         * @param delay how long to wait; may be zero or negative, meaning "as soon as
         *              possible" — an attempt whose deadline is already behind us
         * @return a handle for cancelling it
         */
        Handle schedule(Runnable task, Duration delay);

        /** A scheduled task that has not run yet. */
        @FunctionalInterface
        interface Handle {

            /** Cancels the task if it has not started. Safe to call twice. */
            void cancel();
        }

        /**
         * @param executor the daemon executor to schedule on
         * @return a scheduler backed by it
         */
        static Scheduler on(ScheduledExecutorService executor) {
            Objects.requireNonNull(executor, "executor");
            return (task, delay) -> {
                ScheduledFuture<?> future = executor.schedule(
                        task, Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
                return () -> future.cancel(false);
            };
        }
    }

    /** One attempt's deadline, as handed to {@link #rearmAll}. */
    public record Armed(long attemptId, Instant deadline) {
    }

    /**
     * One live task. The token fences a task that is already running against a re-arm that
     * happened a microsecond ago.
     */
    private record Task(long token, Instant deadline, Scheduler.Handle handle) {
    }

    private final Clock clock;
    private final Scheduler scheduler;
    private final Expiry expiry;
    private final Map<Long, Task> armed = new ConcurrentHashMap<>();
    private final AtomicLong tokens = new AtomicLong();

    /**
     * @param clock     the server's clock; the only clock in this feature
     * @param scheduler where tasks run
     * @param expiry    what expiry does
     */
    public TimerService(Clock clock, Scheduler scheduler, Expiry expiry) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.expiry = Objects.requireNonNull(expiry, "expiry");
    }

    // ===================== Arming ========================================

    /**
     * Arms (or re-arms) the expiry of one attempt.
     *
     * <p>Called when an attempt starts, when an extension moves its deadline, and when the
     * server re-arms after a restart. Re-arming replaces the previous task rather than
     * adding a second, so an execution extended five times still has one timer per student.
     *
     * @param attemptId the attempt
     * @param deadline  when it runs out, extensions included
     */
    public void arm(long attemptId, Instant deadline) {
        Objects.requireNonNull(deadline, "deadline");
        long token = tokens.incrementAndGet();
        Duration delay = Duration.between(clock.instant(), deadline);

        Task previous = armed.put(attemptId, new Task(token, deadline,
                scheduler.schedule(() -> fire(attemptId, token), delay)));
        if (previous != null) {
            previous.handle().cancel();
        }
        log.debug("Attempt {} armed for {} (in {} ms)", attemptId, deadline, delay.toMillis());
    }

    /**
     * Forgets an attempt: it has been submitted, or it has already expired.
     *
     * <p>Disarming something that is not armed is a no-op that answers normally. A submit
     * arriving after the timer already fired is the common case, not an error, and the
     * caller must not have to reason about which of the two got there first.
     *
     * @param attemptId the attempt
     * @return {@code true} when a task was actually cancelled
     */
    public boolean disarm(long attemptId) {
        Task task = armed.remove(attemptId);
        if (task == null) {
            return false;
        }
        task.handle().cancel();
        log.debug("Attempt {} disarmed", attemptId);
        return true;
    }

    /**
     * Re-arms a whole set at once: the boot path, and the path an extension takes.
     *
     * <p>Deliberately additive rather than a replace-everything: an execution being
     * extended hands over only its own attempts, and wiping the map would disarm every
     * other exam running in the school.
     *
     * @param attempts the attempts and their (re)computed deadlines
     * @return how many were armed
     */
    public int rearmAll(Collection<Armed> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            return 0;
        }
        for (Armed attempt : attempts) {
            arm(attempt.attemptId(), attempt.deadline());
        }
        log.info("Armed {} attempt timer(s)", attempts.size());
        return attempts.size();
    }

    // ===================== Firing ========================================

    /**
     * Expires every armed attempt whose deadline has passed (the backstop).
     *
     * <p>Idempotent by construction: expiry is a compare-and-set, so an attempt this sweep
     * and a scheduled task both reach loses nothing. The server schedules this every
     * {@link #SWEEP_INTERVAL}; tests call it directly.
     *
     * @return how many attempts this sweep expired
     */
    public int sweep() {
        Instant now = clock.instant();
        List<Long> overdue = new ArrayList<>();
        for (Map.Entry<Long, Task> entry : armed.entrySet()) {
            if (!now.isBefore(entry.getValue().deadline())) {
                overdue.add(entry.getKey());
            }
        }
        int fired = 0;
        for (Long attemptId : overdue) {
            Task task = armed.get(attemptId);
            if (task != null && fire(attemptId, task.token())) {
                fired++;
            }
        }
        if (fired > 0) {
            log.info("Sweep force-submitted {} overdue attempt(s)", fired);
        }
        return fired;
    }

    /**
     * Runs one attempt's expiry, unless it has been re-armed or disarmed since.
     *
     * @return {@code true} when the expiry actually ran
     */
    private boolean fire(long attemptId, long token) {
        // remove(key, value) is not enough: two tasks for the same attempt can differ only
        // by token, and the losing one must not take the winner's registration with it.
        Task current = armed.get(attemptId);
        if (current == null || current.token() != token) {
            log.debug("Ignoring stale expiry of attempt {} (token {})", attemptId, token);
            return false;
        }
        armed.remove(attemptId, current);
        try {
            expiry.expire(attemptId);
            return true;
        } catch (RuntimeException e) {
            // Put it back so the next sweep tries again. A timer thread that died on one
            // bad row would quietly stop ending every other exam in the building.
            armed.putIfAbsent(attemptId, current);
            log.error("Force-submit of attempt {} failed; it stays armed for the next sweep",
                    attemptId, e);
            return false;
        }
    }

    // ===================== Queries (diagnostics and tests) ===============

    /** @return how many attempts currently have a timer. */
    public int armedCount() {
        return armed.size();
    }

    /**
     * @param attemptId the attempt
     * @return the deadline it is armed for, or empty when it is not armed
     */
    public Optional<Instant> deadlineOf(long attemptId) {
        return Optional.ofNullable(armed.get(attemptId)).map(Task::deadline);
    }

    /** @return {@code true} when this attempt has a live timer. */
    public boolean isArmed(long attemptId) {
        return armed.containsKey(attemptId);
    }

    /** Cancels everything. For shutdown and for tests that want a clean slate. */
    public void disarmAll() {
        for (Long attemptId : List.copyOf(armed.keySet())) {
            disarm(attemptId);
        }
    }
}
