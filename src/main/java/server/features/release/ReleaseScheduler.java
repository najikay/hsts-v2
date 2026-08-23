package server.features.release;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.entities.ExecutionStatus;
import server.db.projections.ExecutionContext;
import server.features.exam.ExecutionCloseService;
import server.features.notify.NotificationCatalog;
import server.features.notify.Notifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The clock that opens and closes releases whether or not anybody is watching (Logic tier,
 * E9.2 — F5.2, F5.4, F11.1).
 *
 * <p>The counterpart to {@code TimerService}, one level up. That one ends individual
 * attempts; this one moves the release they belong to through
 * {@code SCHEDULED → LIVE → CLOSED}, and warns the people about to sit it. Same shape, same
 * reasons: it holds no state that matters, it reads its "now" from an injected
 * {@link Clock}, and everything it does is idempotent, so a tick that is lost to a pause, a
 * restart, or a saturated executor costs nothing but the delay.
 *
 * <h2>Polling, not per-release timers</h2>
 *
 * <p>A scheduled task per release would be the obvious mirror of the attempt timers and is
 * the wrong answer here. An attempt's deadline is created and consumed inside one process's
 * lifetime and there are as many of them as there are students in a room; a release's window
 * is set weeks ahead, survives every restart in between, and there are a handful. Rearming
 * hundreds of long-lived tasks at boot to fire a status flip buys precision nobody can
 * perceive. A sweep every {@link #INTERVAL} means a release opens within half a minute of
 * its time, which is the same resolution the attempt sweep already runs at and far finer
 * than the granularity anyone schedules an exam with.
 *
 * <h2>The three jobs, in this order</h2>
 *
 * <ol>
 *   <li><b>Open</b> what is due: a guarded transition {@code SCHEDULED → LIVE}, so a release
 *       a teacher cancelled a second ago is not reopened by a check that read it first. Only
 *       the winner announces.</li>
 *   <li><b>Warn</b> about what opens within {@link #OPENING_SOON} through
 *       {@link NotificationCatalog#releaseOpeningSoon}. Runs after the opening pass so a
 *       release that just went live is no longer a candidate, which is what stops "opens in
 *       0 minutes" ever being sent.</li>
 *   <li><b>Close</b> what has run out, by handing it to {@link ExecutionCloseService} — the
 *       same call the teacher's close-early button makes, so a release that ends by the
 *       clock and one ended by hand produce identical rows.</li>
 * </ol>
 *
 * <h2>Warning once</h2>
 *
 * <p>The sweep runs every half minute and the warning window is thirty, so the naive version
 * sends the same notice sixty times. The ids already warned about are held in memory, and
 * that is the right place for them: a column would be schema for a thing whose whole
 * lifetime is one half-hour, and the worst case of losing the set to a restart is one
 * duplicate notification. The set is pruned on every tick against the releases still
 * pending, so it cannot grow with the ids of releases that have long since opened or been
 * called off.
 *
 * <h2>Nothing here throws at its caller</h2>
 *
 * <p>A tick that fails is logged and the next one tries again, exactly as the attempt sweep
 * behaves. A scheduler thread that died on one bad row would silently stop opening every
 * other exam in the school.
 */
public final class ReleaseScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReleaseScheduler.class);

    /**
     * How often the check runs.
     *
     * <p>Thirty seconds, matching {@code TimerService.SWEEP_INTERVAL}: a release opens within
     * half a minute of its window, which nobody in a classroom can tell from exact, and a
     * server with nothing scheduled is not waking a thread constantly.
     */
    public static final Duration INTERVAL = Duration.ofSeconds(30);

    /**
     * How far ahead the "opens soon" notice goes out (F11.1).
     *
     * <p>Thirty minutes: long enough to be worth acting on (walk to the room, close the
     * study bot, find the ID card), short enough that it is still about today. It is also
     * the number the PRD's own wording implies, and it is a constant rather than a literal
     * so the notification's "opens in N minutes" and the query's horizon cannot drift apart.
     */
    public static final Duration OPENING_SOON = Duration.ofMinutes(30);

    private final ReleaseStore store;
    private final ExecutionCloseService closeService;
    private final Notifier notifier;
    private final ReleaseAnnouncer announcer;
    private final Clock clock;

    /** Releases whose "opens soon" notice has already gone out. Pruned every tick. */
    private final Set<Long> warned = ConcurrentHashMap.newKeySet();

    /**
     * @param store        the transactional data seam
     * @param closeService the force-submit-and-freeze seam, shared with the close-early verb
     * @param notifier     durable notifications; the "opens soon" notice
     * @param announcer    where "this release changed" goes, normally the release service
     * @param clock        the server's clock; the only clock this feature has
     */
    public ReleaseScheduler(ReleaseStore store, ExecutionCloseService closeService,
                            Notifier notifier, ReleaseAnnouncer announcer, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.closeService = Objects.requireNonNull(closeService, "closeService");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.announcer = announcer == null ? ReleaseAnnouncer.NO_OP : announcer;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * One pass: open what is due, warn about what is close, close what has run out.
     *
     * <p>The method the server schedules every {@link #INTERVAL}, and the method tests call
     * directly after moving a test clock. Nothing here waits, sleeps or measures elapsed
     * time, which is what makes "the notice goes out at T minus 30 and not again at T minus
     * 29" a two-line test.
     *
     * @return how many releases changed state in this pass, for the log and for tests
     */
    public int tick() {
        Instant now = clock.instant();
        int changed = 0;
        try {
            List<ExecutionContext> pending = store.inTx(data ->
                    data.scheduledOpeningBy(now.plus(OPENING_SOON)));
            changed += open(pending, now);
            warn(pending, now);
            changed += closeExpired(now);
        } catch (RuntimeException e) {
            // The next tick tries again. A scheduler that died here would stop opening every
            // exam in the school over one bad row.
            log.error("Release check failed; the next pass will try again", e);
        }
        return changed;
    }

    // ===================== Opening =======================================

    /** Flips every release whose window has begun, and announces the ones this pass won. */
    private int open(List<ExecutionContext> pending, Instant now) {
        List<ExecutionContext> due = new ArrayList<>();
        for (ExecutionContext context : pending) {
            if (!now.isBefore(context.openAt())) {
                due.add(context);
            }
        }
        if (due.isEmpty()) {
            return 0;
        }
        List<Long> opened = store.inTx(data -> {
            List<Long> won = new ArrayList<>();
            for (ExecutionContext context : due) {
                // Guarded: a release a teacher cancelled between the read above and this
                // write changes zero rows, and her decision stands.
                if (data.transition(context.executionId(), ExecutionStatus.SCHEDULED,
                        ExecutionStatus.LIVE) == 1) {
                    won.add(context.executionId());
                }
            }
            return won;
        });
        for (Long executionId : opened) {
            warned.remove(executionId);
            announcer.executionChanged(executionId);
        }
        if (!opened.isEmpty()) {
            log.info("Opened {} release(s): {}", opened.size(), opened);
        }
        return opened.size();
    }

    // ===================== Warning =======================================

    /**
     * Sends the "opens soon" notice, once per release (F11.1).
     *
     * <p>Recipients are the students enrolled in the course <b>and</b> the teacher who
     * released it. Both, deliberately: PRD F11.1 lists this notification as the teacher's,
     * and she is the one who has to be in the room with the code; the students are who the
     * sentence in {@link NotificationCatalog#releaseOpeningSoon} is actually written for, and
     * a class that is not told an exam opens in half an hour is the failure this notice
     * exists to prevent. The catalogue's {@code NavRef} points at the release manager, which
     * a student's client does not know and therefore renders as a plain, non-clickable row —
     * the documented, safe degradation.
     */
    private void warn(List<ExecutionContext> pending, Instant now) {
        // Prune first: ids of releases that have opened or been cancelled are no longer
        // pending, so the set cannot grow for the life of the server.
        Set<Long> stillPending = new HashSet<>();
        for (ExecutionContext context : pending) {
            stillPending.add(context.executionId());
        }
        warned.retainAll(stillPending);

        for (ExecutionContext context : pending) {
            if (!now.isBefore(context.openAt()) || !warned.add(context.executionId())) {
                // Already open (this pass opened it, or it lost its transition), or already
                // warned about. Either way it is not news.
                continue;
            }
            int minutesAway = minutesUntil(now, context.openAt());
            Set<Long> recipients = new LinkedHashSet<>(
                    store.inTx(data -> data.enrolledStudents(context.courseCode())));
            recipients.add(context.executingTeacherId());
            Notifier.Outcome outcome = notifier.notify(recipients,
                    NotificationCatalog.releaseOpeningSoon(
                            context.examName(), minutesAway, context.executionId()));
            log.info("Release {} opens in {} min; told {} people ({} live)",
                    context.executionId(), minutesAway, outcome.persisted(), outcome.pushed());
        }
    }

    /**
     * @return whole minutes until the window opens, never below 1. A notice reading "opens in
     *         0 minutes" would be both wrong and useless, and rounding down can produce it
     *         for a release fifty seconds away
     */
    private static int minutesUntil(Instant now, Instant openAt) {
        long minutes = Duration.between(now, openAt).toMinutes();
        return (int) Math.max(1, minutes);
    }

    // ===================== Closing =======================================

    /**
     * Ends every live release whose window has run out.
     *
     * <p>Delegated whole to {@link ExecutionCloseService}, which is the same call the
     * teacher's close-early button makes. That is not code reuse for its own sake: it is why
     * a release that ends by the clock and one ended by hand produce identical rows, and why
     * neither has a second force-submit implementation that could drift from the attempt
     * timers.
     *
     * <p>Extensions are honoured here rather than in the query: the read filters on the
     * stored close time because no portable query adds a column of minutes to a timestamp,
     * and this skips the ones a teacher has just extended (S-20).
     */
    private int closeExpired(Instant now) {
        List<ExecutionContext> candidates = store.inTx(data -> data.liveClosingBy(now));
        int closed = 0;
        for (ExecutionContext context : candidates) {
            if (now.isBefore(context.effectiveCloseAt())) {
                // Extended after the window was set; it has more time and is not overdue.
                continue;
            }
            closeService.close(context.executionId());
            announcer.executionChanged(context.executionId());
            closed++;
        }
        if (closed > 0) {
            log.info("Closed {} release(s) whose window ran out", closed);
        }
        return closed;
    }

    // ===================== Diagnostics ===================================

    /** @return how many releases are currently holding an "already warned" mark (tests). */
    int warnedCount() {
        return warned.size();
    }
}
