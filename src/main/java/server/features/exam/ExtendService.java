package server.features.exam;

import common.dto.auth.Role;
import common.dto.exam.AttemptTiming;
import common.dto.exam.ExecutionMonitor;
import common.dto.exam.ExtendTimeRequest;
import common.dto.exam.TimerExtended;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.StaleStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.Authorization;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.db.entities.ExecutionStatus;
import server.db.projections.AttemptRecord;
import server.db.projections.ExecutionContext;
import server.features.notify.NotificationCatalog;
import server.features.notify.Notifier;
import server.realtime.PushGateway;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Adding time to a running exam (Logic tier, E11.1 — F7.1, S-20).
 *
 * <p>Three things have to be true for an extension to be worth anything, and this service
 * exists to make all three true at once.
 *
 * <h2>It applies to the execution, never to the exam</h2>
 *
 * <p>S-20 is explicit and the distinction is easy to get wrong: the minutes go on
 * {@code exam_executions.extra_minutes}, so this sitting is longer and the stored exam is
 * untouched. Next month's execution of the same exam starts from its own duration again.
 *
 * <h2>Every live deadline moves, including the offline ones</h2>
 *
 * <p>Attempt deadlines are <b>derived</b> — start plus the execution's allotted minutes —
 * so writing that one number is what moves them. There is no per-attempt column to migrate
 * and nothing that can be missed. A student whose laptop is asleep gains the minutes the
 * instant she resumes (E11.4), and the only thing this service has to do afterwards is
 * re-arm the timers, which it does from the same recomputed deadline.
 *
 * <h2>The window moves out of their way ⚑ (B-14)</h2>
 *
 * <p>Deriving the deadlines is not enough on its own, because an attempt ends at
 * <b>min(started + allotted, this execution's effective close)</b> and the second half used
 * to be ignored here. A grant made inside a window that shut earlier bought the student
 * nothing: the minutes were written, the toast announced them, and the scheduler closed the
 * execution at the old bell with her {@code TIMED_OUT} well short of her allotted time.
 *
 * <p>So this verb writes two numbers, in one transaction: {@code extra_minutes}, and — only
 * when the window is genuinely in the way — {@code close_at}, moved to
 * {@code max(current close, the latest new deadline)}. An execution whose window already
 * outlasts every new deadline is untouched. See {@link #widenWindowFor}.
 *
 * <h2>Nobody finds out by accident</h2>
 *
 * <p>Every student sitting it gets {@code PUSH_TIMER_EXTENDED} <em>and</em> a durable
 * {@code TIME_EXTENDED} notification. The push is the designed moment PRD §4 calls
 * <i>Time Extended</i>; the notification is what a student who was disconnected for it
 * finds waiting. Time added is never silent, because a countdown that grows without
 * explanation reads as a bug to somebody under exam pressure.
 *
 * <p>Two teachers pressing the button at the same moment is handled by the
 * {@code lock_version} on {@code exam_executions}: the second write fails and its teacher
 * is told to look again, rather than both grants collapsing into one that neither of them
 * can account for.
 */
public final class ExtendService {

    private static final Logger log = LoggerFactory.getLogger(ExtendService.class);

    private final ExamStore store;
    private final TimerService timers;
    private final MonitorService monitors;
    private final PushGateway pushGateway;
    private final Notifier notifier;
    private final Clock clock;

    /**
     * @param store       the transactional data seam
     * @param timers      the expiry timers to re-arm; {@code AttemptService.timers()}
     * @param monitors    the live monitor, which answers this verb and is pushed by it
     * @param pushGateway the push channel
     * @param notifier    durable notifications, for students who are offline (E11.4)
     * @param clock       the server's clock; the same one attempts and monitors use
     */
    public ExtendService(ExamStore store, TimerService timers, MonitorService monitors,
                         PushGateway pushGateway, Notifier notifier, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.timers = Objects.requireNonNull(timers, "timers");
        this.monitors = Objects.requireNonNull(monitors, "monitors");
        this.pushGateway = Objects.requireNonNull(pushGateway, "pushGateway");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Registers the extend verb. Authenticated, role-gated and ownership-gated inside. */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.EXECUTION_EXTEND, this::extend);
    }

    // ===================== The verb ======================================

    Message extend(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof ExtendTimeRequest ask)) {
            return Message.error(request, ErrorCode.VALIDATION, ExamMessages.MALFORMED_REQUEST);
        }
        if (!ask.isAmountLegal()) {
            // §6: "extend by 0/negative → validation". Checked before anything is read, so
            // a typo costs nothing and cannot half-apply.
            return Message.error(request, ErrorCode.VALIDATION, ExamMessages.EXTENSION_INVALID);
        }

        Granted granted;
        try {
            granted = store.inTx(data -> apply(data, caller.userId(), ask));
        } catch (RuntimeException e) {
            if (isStaleWrite(e)) {
                log.info("Extension of execution {} lost an optimistic-lock race",
                        ask.executionId());
                return Message.error(request, ErrorCode.CONFLICT, ExamMessages.EXTENSION_RACED);
            }
            throw e;
        }
        if (granted.error() != null) {
            return Message.error(request, granted.error(), granted.message());
        }

        rearm(granted);
        announce(granted, ask.extraMinutes());
        monitors.executionChanged(granted.context().executionId());

        return monitors.snapshotFor(caller.userId(), granted.context().executionId())
                .<Message>map(snapshot -> Message.ok(request, snapshot))
                .orElseGet(() -> Message.error(request, ErrorCode.NOT_FOUND,
                        ExamMessages.EXECUTION_UNKNOWN));
    }

    /** The write, and everything read alongside it, in one transaction. */
    private Granted apply(ExamData data, long teacherId, ExtendTimeRequest ask) {
        Optional<ExecutionContext> found = data.executionById(ask.executionId());
        if (found.isEmpty()) {
            return Granted.refused(ErrorCode.NOT_FOUND, ExamMessages.EXECUTION_UNKNOWN);
        }
        ExecutionContext ctx = found.get();
        if (!ctx.isOwnedBy(teacherId)) {
            // Same answer as "no such execution" would give away less, but a teacher who
            // reached this screen already knows the execution exists; telling her whose it
            // is, is what lets her go and ask the right colleague (PRD §4.1).
            return Granted.refused(ErrorCode.FORBIDDEN, ExamMessages.NOT_YOUR_EXECUTION);
        }
        if (ctx.status() != ExecutionStatus.LIVE) {
            // §6: "extension after close → blocked".
            return Granted.refused(ErrorCode.CONFLICT, ExamMessages.EXTENSION_NOT_LIVE);
        }

        int newTotal = data.addExtraMinutes(ctx.executionId(), ask.extraMinutes());
        ExecutionContext extended = ctx.withExtraMinutes(newTotal);
        List<AttemptRecord> live = data.liveAttemptsOf(ctx.executionId());
        extended = widenWindowFor(data, extended, live);
        String teacherName = data.user(teacherId).map(StudentIdentity::displayName).orElse("Your teacher");

        log.info("Teacher {} added {} min to execution {} (now +{} total, {} live attempt(s))",
                teacherId, ask.extraMinutes(), ctx.executionId(), newTotal, live.size());
        return Granted.of(extended, live, teacherName);
    }

    /**
     * Moves the bell out of the way of the minutes just granted (B-14 ⚑ — F7.1, S-20).
     *
     * <p><b>The rule: the new close is {@code max(current close, the latest new deadline)}.</b>
     * Two clocks govern a sitting — the attempt's allotted duration and the execution's
     * window — and an attempt ends at whichever comes first. Adding minutes moves only the
     * first, so before this method a grant made inside a window that shut earlier bought the
     * student nothing at all: {@code dana} granted fifteen, the toast announced them, the
     * deadlines moved to 11:00, and the scheduler closed the execution at 10:15 with her
     * {@code TIMED_OUT} at 45 minutes of an allotted 90. The whole point of the verb is to
     * deliver minutes, so the verb is what moves the window.
     *
     * <p>A {@code max} and never a set: an execution whose window already outlasts every new
     * deadline is left exactly where the teacher who released it put it, and a window is only
     * ever widened here — shortening one is {@code RELEASE_CLOSE_EARLY}'s job and has its own
     * rules about the students inside it.
     *
     * <p>The <em>latest</em> deadline, not each student's own, because there is one window
     * for the room: the last student to have started is the one it has to outlast, and the
     * others were never going to be truncated by it.
     *
     * <p>In the same transaction as the grant, deliberately. A window written afterwards
     * could fail on its own and leave minutes granted that nobody can take, which is the
     * exact state B-14 describes; failing together means the teacher is told it did not
     * happen and can press the button again.
     *
     * @return the context to derive from and to answer with, carrying the window as it now is
     */
    private ExecutionContext widenWindowFor(ExamData data, ExecutionContext extended,
                                            List<AttemptRecord> live) {
        Instant latest = null;
        for (AttemptRecord attempt : live) {
            // The uncapped deadline: what the student would get if the window were no object.
            // Capping here would compare the window against itself and never move anything.
            Instant uncapped = attempt.startedAt()
                    .plusSeconds(Math.max(0, extended.allottedMinutes()) * 60L);
            if (latest == null || uncapped.isAfter(latest)) {
                latest = uncapped;
            }
        }
        if (latest == null || !latest.isAfter(extended.effectiveCloseAt())) {
            // Nobody is sitting it, or the window already outlasts every new deadline. The
            // common case, and the one where the release's own window is left alone.
            return extended;
        }
        // effectiveCloseAt() is closeAt plus the granted minutes, so the stored column has to
        // be set below the target by exactly those minutes for the derived close to land on it.
        Instant newCloseAt = latest.minusSeconds(Math.max(0, extended.extraMinutes()) * 60L);
        data.moveCloseAt(extended.executionId(), newCloseAt);
        log.info("Execution {} window widened to {} so the extension is not eaten by it (B-14)",
                extended.executionId(), latest);
        return extended.withCloseAt(newCloseAt);
    }

    /** Re-arms every live attempt against its recomputed deadline. */
    private void rearm(Granted granted) {
        List<TimerService.Armed> armed = new ArrayList<>();
        for (AttemptRecord attempt : granted.attempts()) {
            armed.add(new TimerService.Armed(attempt.attemptId(),
                    attempt.deadline(granted.context())));
        }
        timers.rearmAll(armed);
    }

    /**
     * Tells every student sitting it, twice: live and durably (F7.1, E11.4).
     *
     * <p>Both, not either. The push is the designed moment and is worth nothing to somebody
     * whose socket dropped a minute ago; the notification is durable and is worth nothing as
     * a live cue. Together they mean no student can be given time she does not know about.
     */
    private void announce(Granted granted, int addedMinutes) {
        ExecutionContext ctx = granted.context();
        Instant now = clock.instant();
        List<Long> students = new ArrayList<>();
        for (AttemptRecord attempt : granted.attempts()) {
            students.add(attempt.studentId());
            AttemptTiming timing = AttemptTiming.between(now, attempt.startedAt(),
                    attempt.deadline(ctx));
            pushGateway.toUser(attempt.studentId(), Verb.PUSH_TIMER_EXTENDED,
                    new TimerExtended(ctx.executionId(), ctx.examName(), granted.teacherName(),
                            addedMinutes, timing));
        }
        if (!students.isEmpty()) {
            notifier.notify(students,
                    NotificationCatalog.timeExtended(ctx.examName(), addedMinutes, ctx.executionId()));
        }
    }

    // ===================== Internals =====================================

    /** What the transaction produced: a grant, or a refusal. */
    private record Granted(ExecutionContext context, List<AttemptRecord> attempts,
                           String teacherName, ErrorCode error, String message) {

        static Granted of(ExecutionContext context, List<AttemptRecord> attempts, String teacherName) {
            return new Granted(context, List.copyOf(attempts), teacherName, null, null);
        }

        static Granted refused(ErrorCode error, String message) {
            return new Granted(null, List.of(), null, error, message);
        }
    }

    /**
     * @return whether this failure is the optimistic-lock loss of a concurrent extension
     *         rather than a real fault. Walked over the cause chain because Hibernate wraps
     *         its {@link StaleStateException} differently depending on where the flush
     *         happened, and matching only the outermost type works until it does not
     */
    private static boolean isStaleWrite(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof StaleStateException || cause instanceof OptimisticLockException) {
                return true;
            }
        }
        return false;
    }
}
