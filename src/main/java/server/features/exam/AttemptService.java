package server.features.exam;

import common.dto.exam.AttemptForm;
import common.dto.exam.AttemptOutcome;
import common.dto.exam.AttemptResumeRequest;
import common.dto.exam.AttemptStartRequest;
import common.dto.exam.AttemptState;
import common.dto.exam.AttemptSummaryEntry;
import common.dto.exam.AttemptTiming;
import common.dto.exam.ExamHeader;
import common.dto.exam.ExamJoinRequest;
import common.dto.exam.ExamQuestion;
import common.dto.exam.IntegrityFlag;
import common.dto.exam.SaveAnswerRequest;
import common.dto.exam.SaveAnswerResult;
import common.dto.exam.SavedAnswer;
import common.dto.exam.SubmitAttemptRequest;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.Authorization;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.db.entities.AttemptStatus;
import server.db.projections.AnswerRow;
import server.db.projections.AttemptRecord;
import server.db.projections.ExecutionContext;
import server.db.projections.QuestionOutline;
import server.db.projections.TakeExamQuestion;
import server.features.notify.NotificationCatalog;
import server.features.notify.Notifier;
import server.realtime.PushGateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Taking an exam, server-side (Logic tier, E10 — F6).
 *
 * <p>This is the epic whose v1 version failed the team's first defence: the timer stayed
 * open and students saw answers. Both failures were architectural rather than careless, so
 * both fixes are structural, and they are the two rules everything in this class obeys.
 *
 * <h2>Rule one: the server owns time</h2>
 *
 * <p>The client's countdown renders what it is told and decides nothing. Every answer this
 * service sends carries an {@link AttemptTiming} built from the injected {@link Clock},
 * and every write path re-derives the deadline and re-checks it <em>inside the
 * transaction that does the write</em>. The deadline itself is never stored: it is the
 * attempt's start plus the execution's allotted minutes, so an extension granted while a
 * student was offline applies the instant she resumes, with nothing to migrate and nothing
 * to go stale (E11.4).
 *
 * <p>An answer arriving after the bell is not merely ignored: {@link #saveAnswer} finds the
 * attempt overdue and closes it there and then, so "the client was still open" cannot
 * produce a paper that was edited after time (§6, E10.8 ⚑).
 *
 * <h2>Rule two: no correctness on a student's wire</h2>
 *
 * <p>Papers are read through the no-correctness projection and mapped by
 * {@link #toWire(TakeExamQuestion)} into {@link ExamQuestion}, which has no field for an
 * answer key. Neither type can carry one, so no handler here can leak one by assembly.
 *
 * <h2>The race, and who wins it</h2>
 *
 * <p>A student pressing submit at the same instant her timer fires is the normal case, not
 * the exotic one, and both writers are legitimate. Finalisation is therefore a
 * status-guarded atomic UPDATE (§5): whoever changes the row wins, the loser sees zero rows
 * and <b>reads the final state and answers with it</b>. A student who submitted a moment
 * too late sees the Time Up takeover, not an error, because she did nothing wrong. That
 * behaviour is the same in both directions and is pinned by E10.8's race tests.
 *
 * <p>Everything is constructor-injected — the store, the clock, the push gateway, the
 * grading seam — so every rule above is unit-testable against an in-memory store and a
 * clock a test moves by hand, and then again against real MySQL for the races.
 */
public final class AttemptService implements AttemptTracker, TimerService.Expiry {

    private static final Logger log = LoggerFactory.getLogger(AttemptService.class);

    private final ExamStore store;
    private final Clock clock;
    private final PushGateway pushGateway;
    private final Notifier notifier;
    private final AttemptFinalizedListener finalizedListener;
    private final AttemptRegistry registry = new AttemptRegistry();
    private final TimerService timers;

    private volatile MonitorPublisher monitor = MonitorPublisher.NO_OP;

    /**
     * @param store             the transactional data seam
     * @param clock             the server's clock; the only clock this feature has
     * @param scheduler         where expiry tasks run (a daemon executor in production)
     * @param pushGateway       the push channel, for {@code PUSH_FORCE_SUBMITTED}
     * @param notifier          durable notifications, for the C-4 integrity alert
     * @param finalizedListener the E12 grading seam; {@link AttemptFinalizedListener#NO_OP}
     *                          until that epic lands
     */
    public AttemptService(ExamStore store,
                          Clock clock,
                          TimerService.Scheduler scheduler,
                          PushGateway pushGateway,
                          Notifier notifier,
                          AttemptFinalizedListener finalizedListener) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pushGateway = Objects.requireNonNull(pushGateway, "pushGateway");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.finalizedListener = Objects.requireNonNull(finalizedListener, "finalizedListener");
        // The timer belongs to this service: expiry IS force-submission, and giving the
        // scheduler its own owner would leave two objects that only make sense together.
        // `this` escapes into the lambda, which is safe because TimerService only stores it
        // and cannot fire before this constructor returns (nothing is armed yet).
        this.timers = new TimerService(clock, Objects.requireNonNull(scheduler, "scheduler"), this::expire);
    }

    /** Registers the five student verbs; all authenticated, none open. */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.EXAM_JOIN, this::join);
        router.register(Verb.ATTEMPT_START, this::start);
        router.register(Verb.ATTEMPT_RESUME, this::resume);
        router.register(Verb.ANSWER_SAVE, this::saveAnswer);
        router.register(Verb.ATTEMPT_SUBMIT, this::submit);
    }

    /**
     * @return the expiry timers, for the server to schedule the backstop sweep on and for
     *         {@link ExtendService} to re-arm after granting minutes
     */
    public TimerService timers() {
        return timers;
    }

    /** @return the live-attempt index, which is also this service's {@link AttemptTracker}. */
    public AttemptRegistry registry() {
        return registry;
    }

    /**
     * Wires the live monitor in (E11.2).
     *
     * <p>A setter rather than a constructor argument because the dependency genuinely runs
     * both ways: the monitor reads this service's tracker for integrity flags, and this
     * service tells the monitor when to repaint. One of the two has to be attached after
     * construction, and the monitor is the optional half — a server with no teacher
     * watching still runs exams correctly.
     *
     * @param publisher where "this execution changed" goes; {@code null} restores the no-op
     */
    public void publishTo(MonitorPublisher publisher) {
        this.monitor = publisher == null ? MonitorPublisher.NO_OP : publisher;
    }

    /**
     * Re-arms every live attempt from the database (ARCHITECTURE §4).
     *
     * <p>Called at boot. The timers died with the previous process; the deadlines did not,
     * because they are derived from rows that survived. An attempt whose deadline passed
     * while the server was down is armed with a delay in the past and expires immediately,
     * which is the difference between "the server restarted" and v1's "the exam never
     * closed".
     *
     * @return how many attempts were armed
     */
    public int rearmFromDatabase() {
        List<TimerService.Armed> armed = store.inTx(data -> {
            List<TimerService.Armed> found = new ArrayList<>();
            for (AttemptRecord attempt : data.allLiveAttempts()) {
                data.executionById(attempt.executionId()).ifPresent(ctx -> {
                    found.add(new TimerService.Armed(attempt.attemptId(),
                            attempt.deadline(ctx.allottedMinutes())));
                    registry.started(activeAttempt(attempt, ctx));
                });
            }
            return found;
        });
        int count = timers.rearmAll(armed);
        log.info("Re-armed {} in-progress attempt(s) after start-up", count);
        return count;
    }

    // ===================== EXAM_JOIN =====================================

    /**
     * Step one of entry: what is this code, and may I sit it? (E10.9 — F6.1, C-1).
     *
     * <p>Answers the header and <b>never the questions</b>: the paper does not exist on a
     * client until an identity has been confirmed, because that is what starts the clock
     * (S-18).
     *
     * <p>Four different refusals, on four different error codes, because a student standing
     * in an exam hall with the wrong message loses minutes she cannot get back. See
     * {@link ExamMessages}.
     */
    Message join(CallerContext caller, Message request) {
        Authorization.requireAuthenticated(caller);
        if (!(request.getPayload() instanceof ExamJoinRequest join)) {
            return Message.error(request, ErrorCode.VALIDATION, ExamMessages.MALFORMED_REQUEST);
        }
        if (!join.isWellFormed()) {
            return Message.error(request, ErrorCode.VALIDATION, ExamMessages.CODE_MALFORMED);
        }
        long studentId = caller.userId();
        return store.inTx(data -> {
            List<ExecutionContext> matches = data.executionsByCode(join.code());
            if (matches.isEmpty()) {
                log.debug("Join refused for user {}: unknown code", studentId);
                return Message.error(request, ErrorCode.NOT_FOUND, ExamMessages.CODE_UNKNOWN);
            }
            Instant now = clock.instant();
            Optional<ExecutionContext> joinable = matches.stream()
                    .filter(ctx -> ctx.isOpenAt(now))
                    .findFirst();
            if (joinable.isEmpty()) {
                ExecutionContext newest = matches.get(0);
                return Message.error(request, ErrorCode.CONFLICT,
                        ExamMessages.notJoinable(newest.status(), !now.isBefore(newest.openAt())));
            }
            ExecutionContext ctx = joinable.get();
            if (!data.isEnrolled(studentId, ctx.courseCode())) {
                log.info("Join refused for user {}: not enrolled in {}", studentId, ctx.courseCode());
                return Message.error(request, ErrorCode.FORBIDDEN, ExamMessages.NOT_ENROLLED);
            }
            AttemptState state = data.attemptOf(ctx.executionId(), studentId)
                    .map(AttemptService::toWire)
                    .orElse(AttemptState.NOT_STARTED);
            return Message.ok(request, headerOf(ctx, data.questionCountOf(ctx.examVersionId()), state));
        });
    }

    // ===================== ATTEMPT_START =================================

    /**
     * Step two of entry: confirm identity, start the clock, hand over the paper (E10.1 —
     * S-18).
     *
     * <p>Every gate from {@link #join} is checked again here, and not because the client
     * might have skipped one: minutes can pass between the two screens, and an execution
     * that closed in between must not admit anybody. Identity is confirmed against the
     * <b>caller's own</b> record, so a classmate's number identifies nobody.
     *
     * <p>Starting twice is deliberately not an error (F6.7). A student who double-clicks,
     * or who reopens the screen, gets the resumable state of her first attempt: the unique
     * key on {@code (execution_id, student_id)} guarantees there is only ever one, and this
     * handler simply reports it.
     */
    Message start(CallerContext caller, Message request) {
        Authorization.requireAuthenticated(caller);
        if (!(request.getPayload() instanceof AttemptStartRequest begin)) {
            return Message.error(request, ErrorCode.VALIDATION, ExamMessages.MALFORMED_REQUEST);
        }
        if (!begin.hasIdentity()) {
            return Message.error(request, ErrorCode.VALIDATION, ExamMessages.ID_MISSING);
        }
        long studentId = caller.userId();

        Started outcome;
        try {
            outcome = store.inTx(data -> startInTx(data, studentId, begin));
        } catch (DuplicateAttemptException raced) {
            // Two clicks a millisecond apart: the unique key refused the second insert and
            // rolled its transaction back. Read the attempt that won, in a clean one. To
            // the student this is indistinguishable from having clicked once (F6.7).
            log.info("Start of execution {} by student {} raced itself; resuming the winner",
                    begin.executionId(), studentId);
            outcome = store.inTx(data -> resumeAfterRace(data, studentId, begin.executionId()));
        }

        if (outcome.error() != null) {
            return Message.error(request, outcome.error(), outcome.message());
        }
        if (outcome.attempt().isInProgress()) {
            timers.arm(outcome.attempt().attemptId(),
                    outcome.attempt().deadline(outcome.context().allottedMinutes()));
            registry.started(activeAttempt(outcome.attempt(), outcome.context()));
        }
        if (outcome.fresh()) {
            monitor.executionChanged(outcome.context().executionId());
        }
        return Message.ok(request, outcome.form());
    }

    /**
     * The whole of {@code ATTEMPT_START}'s decision, inside one transaction.
     *
     * <p>Every gate from the join screen is checked again here, and not because the client
     * might have skipped one: minutes can pass between the two screens.
     */
    private Started startInTx(ExamData data, long studentId, AttemptStartRequest begin) {
        Optional<ExecutionContext> found = data.executionById(begin.executionId());
        if (found.isEmpty()) {
            return Started.refused(ErrorCode.NOT_FOUND, ExamMessages.CODE_UNKNOWN);
        }
        ExecutionContext ctx = found.get();
        Instant now = clock.instant();
        if (!ctx.isOpenAt(now)) {
            return Started.refused(ErrorCode.CONFLICT,
                    ExamMessages.notJoinable(ctx.status(), !now.isBefore(ctx.openAt())));
        }
        if (!data.isEnrolled(studentId, ctx.courseCode())) {
            return Started.refused(ErrorCode.FORBIDDEN, ExamMessages.NOT_ENROLLED);
        }
        Optional<StudentIdentity> identity = data.user(studentId);
        if (identity.isEmpty() || !identity.get().matches(begin.nationalId())) {
            log.info("Identity check failed for user {} on execution {}",
                    studentId, ctx.executionId());
            return Started.refused(ErrorCode.VALIDATION, ExamMessages.ID_MISMATCH);
        }

        Optional<AttemptRecord> existing = data.attemptOf(ctx.executionId(), studentId);
        if (existing.isPresent()) {
            // Not an error: F6.7's "one attempt per student" is answered by showing her the
            // one she has, in whatever state it is in.
            return Started.of(ctx, formFor(data, ctx, existing.get(), now), existing.get(), false);
        }
        AttemptRecord attempt = data.createAttempt(ctx.executionId(), studentId, now);
        log.info("Student {} started attempt {} on execution {} ({} min allotted)",
                studentId, attempt.attemptId(), ctx.executionId(), ctx.allottedMinutes());
        return Started.of(ctx, formFor(data, ctx, attempt, now), attempt, true);
    }

    /** The second, clean transaction after a lost double-start race. */
    private Started resumeAfterRace(ExamData data, long studentId, long executionId) {
        Optional<ExecutionContext> found = data.executionById(executionId);
        Optional<AttemptRecord> raced = found.isEmpty()
                ? Optional.empty()
                : data.attemptOf(executionId, studentId);
        if (found.isEmpty() || raced.isEmpty()) {
            // The constraint fired but the row is not there: the database is telling two
            // different stories, and a generic refusal is the only honest answer.
            return Started.refused(ErrorCode.INTERNAL, ExamMessages.ATTEMPT_UNKNOWN);
        }
        ExecutionContext ctx = found.get();
        Instant now = clock.instant();
        return Started.of(ctx, formFor(data, ctx, raced.get(), now), raced.get(), false);
    }

    /** The two shapes {@link #start} can produce, so the transaction returns one value. */
    private record Started(ExecutionContext context, AttemptForm form, AttemptRecord attempt,
                           boolean fresh, ErrorCode error, String message) {

        static Started of(ExecutionContext context, AttemptForm form, AttemptRecord attempt, boolean fresh) {
            return new Started(context, form, attempt, fresh, null, null);
        }

        static Started refused(ErrorCode error, String message) {
            return new Started(null, null, null, false, error, message);
        }
    }

    // ===================== ATTEMPT_RESUME ================================

    /**
     * Come back to a paper (E10.6 — F6.3, E10.15).
     *
     * <p>Rebuilds the client from scratch: the questions, the answers the server holds, and
     * the authoritative remaining time. Nothing is merged with what the client remembers,
     * because what it remembers may be minutes stale and is never the truth.
     *
     * <p><b>Resume is also an expiry check.</b> If the attempt is still marked in progress
     * but its deadline has passed, this closes it here, in the same transaction, before
     * answering. That covers the one case a scheduled timer cannot: the server was not
     * running when the deadline arrived. The student then gets a form whose state is
     * {@code TIMED_OUT} with the outcome attached, which is exactly what E10.14's takeover
     * renders.
     */
    Message resume(CallerContext caller, Message request) {
        Authorization.requireAuthenticated(caller);
        if (!(request.getPayload() instanceof AttemptResumeRequest resume)) {
            return Message.error(request, ErrorCode.VALIDATION, ExamMessages.MALFORMED_REQUEST);
        }
        long studentId = caller.userId();

        Started outcome = store.inTx(data -> {
            Optional<ExecutionContext> found = data.executionById(resume.executionId());
            if (found.isEmpty()) {
                return Started.refused(ErrorCode.NOT_FOUND, ExamMessages.CODE_UNKNOWN);
            }
            ExecutionContext ctx = found.get();
            Optional<AttemptRecord> existing = data.attemptOf(ctx.executionId(), studentId);
            if (existing.isEmpty()) {
                return Started.refused(ErrorCode.NOT_FOUND, ExamMessages.ATTEMPT_UNKNOWN);
            }
            Instant now = clock.instant();
            Closed closed = expireIfOverdue(data, ctx, existing.get(), now);
            AttemptRecord attempt = closed.attempt();
            return Started.of(ctx, formFor(data, ctx, attempt, now), attempt, closed.won());
        });

        if (outcome.error() != null) {
            return Message.error(request, outcome.error(), outcome.message());
        }
        AttemptRecord attempt = outcome.attempt();
        if (attempt.isInProgress()) {
            // Re-arm rather than assume: the process may have restarted, or the extension
            // that moved this deadline may have landed while nothing was watching.
            timers.arm(attempt.attemptId(), attempt.deadline(outcome.context().allottedMinutes()));
            registry.started(activeAttempt(attempt, outcome.context()));
        } else if (attempt.status() == AttemptStatus.TIMED_OUT
                && outcome.form().outcome() != null) {
            // `fresh` carries whether THIS resume was the one that closed it: only then may
            // it trigger grading, or the same attempt would be marked twice.
            afterFinalized(outcome.context(), attempt, outcome.form().outcome(), false, outcome.fresh());
        }
        return Message.ok(request, outcome.form());
    }

    // ===================== ANSWER_SAVE ===================================

    /**
     * Autosave one choice (E10.3 ⚑ — F6.3).
     *
     * <p>The most-called verb in the epic and the one with the sharpest rule: it re-reads
     * the attempt's status <em>and</em> re-derives its deadline inside the same transaction
     * as the write. Both checks are needed. Status alone would let an answer land in the
     * window between a deadline passing and the timer firing; deadline alone would let one
     * land on an attempt the student already submitted.
     *
     * <p>An answer that arrives late does not simply bounce: the attempt is force-submitted
     * right there, so the student's next screen is the Time Up takeover rather than a form
     * that keeps refusing her.
     *
     * <p>The response carries fresh {@link AttemptTiming}, which makes every keystroke a
     * clock re-sync (S-18).
     */
    Message saveAnswer(CallerContext caller, Message request) {
        Authorization.requireAuthenticated(caller);
        if (!(request.getPayload() instanceof SaveAnswerRequest save)) {
            return Message.error(request, ErrorCode.VALIDATION, ExamMessages.MALFORMED_REQUEST);
        }
        if (!save.isSelectionLegal()) {
            return Message.error(request, ErrorCode.VALIDATION, ExamMessages.ANSWER_INVALID);
        }
        long studentId = caller.userId();

        Saved outcome = store.inTx(data -> {
            Optional<AttemptRecord> found = data.attemptById(save.attemptId());
            if (found.isEmpty() || found.get().studentId() != studentId) {
                // Somebody else's attempt id and a non-existent one answer the same way, so
                // probing tells an attacker nothing (the notifications pattern, E17.6).
                return Saved.refused(ErrorCode.NOT_FOUND, ExamMessages.ATTEMPT_UNKNOWN, null, null);
            }
            AttemptRecord attempt = found.get();
            ExecutionContext ctx = data.executionById(attempt.executionId()).orElse(null);
            if (ctx == null) {
                return Saved.refused(ErrorCode.NOT_FOUND, ExamMessages.ATTEMPT_UNKNOWN, null, null);
            }
            if (!attempt.isInProgress()) {
                return Saved.refused(ErrorCode.CONFLICT, closedMessage(attempt), null, null);
            }
            Instant now = clock.instant();
            if (!now.isBefore(attempt.deadline(ctx.allottedMinutes()))) {
                // The bell has gone and the timer has not fired yet. Close it here rather
                // than merely refusing: leaving it open is exactly the v1 bug.
                Closed closed = expireIfOverdue(data, ctx, attempt, now);
                AttemptOutcome result = closed.attempt().isInProgress()
                        ? null : outcomeFor(data, ctx, closed.attempt());
                return Saved.refused(ErrorCode.CONFLICT, ExamMessages.TIME_IS_UP, ctx, closed.attempt())
                        .withOutcome(result, closed.won());
            }
            if (!data.isOnPaper(ctx.examVersionId(), save.questionVersionId())) {
                return Saved.refused(ErrorCode.VALIDATION, ExamMessages.QUESTION_NOT_ON_PAPER, null, null);
            }

            data.upsertAnswer(attempt.attemptId(), save.questionVersionId(),
                    save.selected() == null ? null : save.selected().byteValue(), now);
            int answered = data.countAnswered(attempt.attemptId());
            SaveAnswerResult result = new SaveAnswerResult(save.questionVersionId(), save.selected(),
                    answered, data.questionCountOf(ctx.examVersionId()),
                    timingOf(attempt, ctx, now));
            return Saved.of(ctx, attempt, result);
        });

        if (outcome.error() != null) {
            if (outcome.attempt() != null && outcome.outcome() != null) {
                afterFinalized(outcome.context(), outcome.attempt(), outcome.outcome(),
                        outcome.won(), outcome.won());
            }
            return Message.error(request, outcome.error(), outcome.message());
        }
        monitor.executionChanged(outcome.context().executionId());
        return Message.ok(request, outcome.result());
    }

    /**
     * What {@link #saveAnswer}, {@link #submit} and {@link #expire} return from their
     * transaction: a result, or a refusal, plus whether this caller was the one that
     * actually closed the attempt.
     *
     * <p>{@code won} is not bookkeeping. Exactly one of the racing writers may trigger
     * grading, and the loser is the one holding a closed attempt it did not close, so the
     * flag has to travel out of the transaction with the row it describes.
     */
    private record Saved(ExecutionContext context, AttemptRecord attempt, SaveAnswerResult result,
                         ErrorCode error, String message, AttemptOutcome outcome, boolean won) {

        static Saved of(ExecutionContext context, AttemptRecord attempt, SaveAnswerResult result) {
            return new Saved(context, attempt, result, null, null, null, false);
        }

        static Saved refused(ErrorCode error, String message,
                             ExecutionContext context, AttemptRecord attempt) {
            return new Saved(context, attempt, null, error, message, null, false);
        }

        Saved withOutcome(AttemptOutcome finalOutcome, boolean wonTheRace) {
            return new Saved(context, attempt, result, error, message, finalOutcome, wonTheRace);
        }
    }

    /**
     * An attempt after a close was attempted, and whether this caller is the one that did
     * it (§5).
     *
     * @param attempt the row as it now stands
     * @param won     {@code true} when this caller's compare-and-set changed it
     */
    private record Closed(AttemptRecord attempt, boolean won) {
    }

    // ===================== ATTEMPT_SUBMIT ================================

    /**
     * Hand the paper in (E10.4 — F6.9, S-19).
     *
     * <p>A state transition, not an upload: the answers are already stored, saved as she
     * made them. That is what makes the race with expiry survivable at all.
     *
     * <p>Three outcomes, and none of them is an error at the student:
     * <ul>
     *   <li>she is in time: the compare-and-set closes it as {@code SUBMITTED} and she gets
     *       the Submitted screen;</li>
     *   <li>she is a moment late, and the timer has not fired: this closes it as
     *       {@code TIMED_OUT} at the deadline, because that is what actually happened;</li>
     *   <li>the timer already fired: the compare-and-set changes nothing, and she is told
     *       the outcome that won. Her paper was handed in either way, and telling her it
     *       "failed" would be both false and frightening.</li>
     * </ul>
     *
     * <p>Re-submitting an attempt that is already closed is idempotent for the same reason.
     */
    Message submit(CallerContext caller, Message request) {
        Authorization.requireAuthenticated(caller);
        if (!(request.getPayload() instanceof SubmitAttemptRequest submit)) {
            return Message.error(request, ErrorCode.VALIDATION, ExamMessages.MALFORMED_REQUEST);
        }
        long studentId = caller.userId();

        Saved outcome = store.inTx(data -> {
            Optional<AttemptRecord> found = data.attemptById(submit.attemptId());
            if (found.isEmpty() || found.get().studentId() != studentId) {
                return Saved.refused(ErrorCode.NOT_FOUND, ExamMessages.ATTEMPT_UNKNOWN, null, null);
            }
            AttemptRecord attempt = found.get();
            ExecutionContext ctx = data.executionById(attempt.executionId()).orElse(null);
            if (ctx == null) {
                return Saved.refused(ErrorCode.NOT_FOUND, ExamMessages.ATTEMPT_UNKNOWN, null, null);
            }
            if (!attempt.isInProgress()) {
                // Already closed, by her or by the timer. Answer with what happened, and
                // claim nothing: whoever closed it has already told the grader.
                return Saved.of(ctx, attempt, null).withOutcome(outcomeFor(data, ctx, attempt), false);
            }

            Instant now = clock.instant();
            Instant deadline = attempt.deadline(ctx.allottedMinutes());
            boolean inTime = now.isBefore(deadline);
            Instant endedAt = inTime ? now : deadline;
            AttemptStatus status = inTime ? AttemptStatus.SUBMITTED : AttemptStatus.TIMED_OUT;
            int minutes = solvingMinutes(attempt.startedAt(), endedAt);

            int changed = data.finalizeAttempt(attempt.attemptId(), status, endedAt, minutes);
            AttemptRecord closed = changed == 1
                    ? new AttemptRecord(attempt.attemptId(), attempt.executionId(), attempt.studentId(),
                            attempt.startedAt(), endedAt, minutes, status)
                    // Lost the race: the timer got there first. Read what won.
                    : data.attemptById(attempt.attemptId()).orElse(attempt);
            if (changed == 1) {
                log.info("Attempt {} finalised as {} after {} min", attempt.attemptId(), status, minutes);
            } else {
                log.info("Attempt {} submit lost the race; it is {}", attempt.attemptId(), closed.status());
            }
            return Saved.of(ctx, closed, null).withOutcome(outcomeFor(data, ctx, closed), changed == 1);
        });

        if (outcome.error() != null) {
            return Message.error(request, outcome.error(), outcome.message());
        }
        // The student already has the answer in her response, so the force-submit push is
        // pointless noise for her; the monitor still needs telling, and the grader only if
        // this call is what closed the attempt.
        afterFinalized(outcome.context(), outcome.attempt(), outcome.outcome(), false, outcome.won());
        return Message.ok(request, outcome.outcome());
    }

    // ===================== Expiry (the timer's callback) =================

    /**
     * Force-submits one attempt whose time is up (E10.5 ⚑ — F6.4).
     *
     * <p><b>This runs with the client gone.</b> It is a database transaction fired by a
     * scheduled task, so a laptop that was closed, a browser tab that crashed and a student
     * who walked out all end the same way: the attempt becomes {@code TIMED_OUT} with the
     * answers that were saved, the solving minutes are recorded (S-19), and the derived
     * counters move because they are counted rather than incremented. The push is a
     * courtesy for whoever happens to be online.
     *
     * <p>Idempotent: the compare-and-set means a sweep, a scheduled task and the student's
     * own late submit can all reach the same attempt and only one of them changes anything.
     */
    @Override
    public void expire(long attemptId) {
        close(attemptId, true);
    }

    /**
     * Force-submits an attempt because the teacher closed the execution early (F5.5, E11.5).
     *
     * <p>Deliberately the same path as an expiry, with one difference: the deadline check
     * is skipped, because the whole point of an early close is that the bell has been moved
     * forward. The student ends {@code TIMED_OUT} with the answers she had saved and gets
     * the same push, so a closed-early execution and an expired one produce rows the grader
     * cannot tell apart, which is exactly what it should not have to.
     *
     * @param attemptId the attempt to close
     */
    public void closeEarly(long attemptId) {
        close(attemptId, false);
    }

    private void close(long attemptId, boolean requireOverdue) {
        Saved outcome = store.inTx(data -> {
            Optional<AttemptRecord> found = data.attemptById(attemptId);
            if (found.isEmpty()) {
                return Saved.refused(ErrorCode.NOT_FOUND, ExamMessages.ATTEMPT_UNKNOWN, null, null);
            }
            AttemptRecord attempt = found.get();
            ExecutionContext ctx = data.executionById(attempt.executionId()).orElse(null);
            if (ctx == null) {
                return Saved.refused(ErrorCode.NOT_FOUND, ExamMessages.ATTEMPT_UNKNOWN, null, null);
            }
            if (!attempt.isInProgress()) {
                return Saved.refused(ErrorCode.CONFLICT, ExamMessages.ALREADY_SUBMITTED, ctx, attempt);
            }
            Instant now = clock.instant();
            Instant deadline = attempt.deadline(ctx.allottedMinutes());
            if (requireOverdue && now.isBefore(deadline)) {
                // Fired early: an extension landed between the task being scheduled and it
                // running. Say so and let the caller re-arm rather than ending an exam that
                // has just been given more time.
                return Saved.refused(ErrorCode.CONFLICT, ExamMessages.TIME_IS_UP, ctx, attempt);
            }
            Closed closed = closeAsTimedOut(data, ctx, attempt, now, requireOverdue);
            if (closed.attempt().isInProgress()) {
                return Saved.refused(ErrorCode.CONFLICT, ExamMessages.TIME_IS_UP, ctx, closed.attempt());
            }
            return Saved.of(ctx, closed.attempt(), null)
                    .withOutcome(outcomeFor(data, ctx, closed.attempt()), closed.won());
        });

        if (outcome.outcome() == null) {
            if (outcome.attempt() != null && outcome.attempt().isInProgress()) {
                // Still live: re-arm for its (possibly extended) deadline.
                timers.arm(outcome.attempt().attemptId(),
                        outcome.attempt().deadline(outcome.context().allottedMinutes()));
            } else {
                timers.disarm(attemptId);
            }
            return;
        }
        afterFinalized(outcome.context(), outcome.attempt(), outcome.outcome(),
                outcome.won(), outcome.won());
    }

    // ===================== AttemptTracker (E10.7 → E16) ==================

    @Override
    public Set<String> coursesInProgressFor(long studentId) {
        return registry.coursesInProgressFor(studentId);
    }

    @Override
    public List<ActiveAttempt> activeAttemptsFor(long studentId) {
        return registry.activeAttemptsFor(studentId);
    }

    @Override
    public Optional<ActiveAttempt> activeAttemptFor(long studentId, String courseCode) {
        return registry.activeAttemptFor(studentId, courseCode);
    }

    @Override
    public Optional<IntegrityFlag> flagOf(long attemptId) {
        return registry.flagOf(attemptId);
    }

    @Override
    public void addListener(AttemptTracker.Listener listener) {
        registry.addListener(listener);
    }

    /**
     * A student used another course's bot mid-attempt (E10.7 ⚑ — C-4, F6.8).
     *
     * <p>The spec forbids the exam's own course bot and says nothing about another's, so
     * HSTS allows it, warns her first, and then tells the teacher: a durable
     * {@code INTEGRITY_ALERT} notification through {@link NotificationCatalog}, and a flag
     * on her row in the live monitor, pushed to whoever is watching.
     *
     * <p>Both halves matter and neither is enough alone. The push is what makes it
     * <em>live</em>, which is the point of an integrity net; the notification is what makes
     * it survive a teacher who was not looking at the monitor at that second.
     *
     * <p>A repeat report changes nothing: the flag keeps the first time, and the teacher is
     * not notified forty times because a student had a conversation.
     */
    @Override
    public boolean reportCrossCourseBotUse(long studentId, String courseCode, String courseName) {
        List<ActiveAttempt> sittings = registry.activeAttemptsFor(studentId);
        if (sittings.isEmpty()) {
            // She finished a moment ago, or never started. Not an error: the bot must not
            // have to check first, and an alert about a finished exam is noise.
            return false;
        }
        Instant now = clock.instant();
        boolean raised = false;
        Set<Long> teachers = new LinkedHashSet<>();
        for (ActiveAttempt sitting : sittings) {
            if (sitting.isSameCourseAs(courseCode)) {
                // Same course is the locked branch; the bot should never have got here.
                log.warn("Ignoring integrity report for attempt {}: {} is its own course",
                        sitting.attemptId(), courseCode);
                continue;
            }
            if (registry.flag(sitting.attemptId(), courseCode, courseName, now)) {
                raised = true;
                teachers.add(sitting.executingTeacherId());
                log.info("Integrity flag on attempt {}: student {} used the {} bot",
                        sitting.attemptId(), studentId, courseCode);
            }
            monitor.executionChanged(sitting.executionId());
        }
        if (raised) {
            for (Long teacherId : teachers) {
                sittings.stream()
                        .filter(sitting -> sitting.executingTeacherId() == teacherId)
                        .findFirst()
                        .ifPresent(sitting -> notifier.notifyUser(teacherId,
                                NotificationCatalog.integrityAlert(
                                        courseName == null || courseName.isBlank() ? courseCode : courseName,
                                        sitting.executionId())));
            }
        }
        return raised;
    }

    // ===================== Shared internals ==============================

    /**
     * Closes an attempt whose deadline has passed, in the caller's transaction.
     *
     * <p>The single place a {@code TIMED_OUT} is written, reached from four directions —
     * the scheduled timer, a resume that finds the attempt overdue, an autosave that
     * arrives late, and a teacher closing the execution early. One implementation, so all
     * four record the same {@code endedAt} and the same minutes.
     *
     * @return the attempt as it now stands, and whether this caller closed it. Unchanged
     *         and {@code won == false} when it was not overdue, or when somebody else got
     *         there first
     */
    private Closed expireIfOverdue(ExamData data, ExecutionContext ctx,
                                   AttemptRecord attempt, Instant now) {
        return closeAsTimedOut(data, ctx, attempt, now, true);
    }

    /**
     * The one write that ends an attempt without the student asking.
     *
     * @param requireOverdue {@code true} for the timer's own path, which must refuse to end
     *                       an exam that has just been given more time; {@code false} for a
     *                       teacher closing the execution early (F5.5), where the whole
     *                       point is that the bell has been moved forward
     * @return the attempt afterwards, and whether this caller is what closed it
     */
    private Closed closeAsTimedOut(ExamData data, ExecutionContext ctx, AttemptRecord attempt,
                                   Instant now, boolean requireOverdue) {
        if (!attempt.isInProgress()) {
            return new Closed(attempt, false);
        }
        Instant deadline = attempt.deadline(ctx.allottedMinutes());
        boolean overdue = !now.isBefore(deadline);
        if (requireOverdue && !overdue) {
            return new Closed(attempt, false);
        }
        // The exam ended when the bell went, not when the task happened to run; an early
        // close ends it now, which is earlier than the bell by definition.
        Instant endedAt = overdue ? deadline : now;
        int minutes = solvingMinutes(attempt.startedAt(), endedAt);
        int changed = data.finalizeAttempt(attempt.attemptId(), AttemptStatus.TIMED_OUT, endedAt, minutes);
        if (changed == 1) {
            log.info("Attempt {} timed out after {} min (ended {})",
                    attempt.attemptId(), minutes, endedAt);
            return new Closed(new AttemptRecord(attempt.attemptId(), attempt.executionId(),
                    attempt.studentId(), attempt.startedAt(), endedAt, minutes,
                    AttemptStatus.TIMED_OUT), true);
        }
        // Lost to her own submit, which landed a microsecond earlier. Read what won.
        return new Closed(data.attemptById(attempt.attemptId()).orElse(attempt), false);
    }

    /**
     * Everything that happens after an attempt is closed, in one place.
     *
     * <p>Deliberately outside the transaction that closed it. Grading reads answers and
     * writes a grade; a monitor push touches sockets. Joining either to the finalisation
     * would let a slow grader or a dead socket roll back a submission the student has
     * already been told about, and a submission that silently vanished is not recoverable.
     *
     * @param pushToStudent whether to send {@code PUSH_FORCE_SUBMITTED}; false when she is
     *                      holding the answer in her hand already
     * @param won           whether this caller's compare-and-set is what closed the attempt.
     *                      Only the winner tells the grader: a submit that lost the race to
     *                      the timer reaches here holding a perfectly valid closed attempt,
     *                      and marking it a second time would double-grade every race
     */
    private void afterFinalized(ExecutionContext ctx, AttemptRecord attempt,
                                AttemptOutcome outcome, boolean pushToStudent, boolean won) {
        timers.disarm(attempt.attemptId());
        registry.finished(attempt.attemptId());
        if (pushToStudent) {
            boolean delivered = pushGateway.toUser(attempt.studentId(),
                    Verb.PUSH_FORCE_SUBMITTED, outcome);
            log.debug("Force-submit of attempt {} {} the student",
                    attempt.attemptId(), delivered ? "reached" : "did not reach");
        }
        monitor.executionChanged(ctx.executionId());
        if (!won) {
            return;
        }
        try {
            finalizedListener.attemptFinalized(new AttemptFinalizedListener.FinalizedAttempt(
                    attempt.attemptId(), ctx.executionId(), ctx.examVersionId(), attempt.studentId(),
                    toWire(attempt), attempt.endedAt(),
                    attempt.actualMinutes() == null ? 0 : attempt.actualMinutes()));
        } catch (RuntimeException e) {
            // A broken grader must not turn a successful submission into an error.
            log.error("Grading listener failed for attempt {}", attempt.attemptId(), e);
        }
    }

    /** Builds the whole paper as it stands, for a start or a resume. */
    private AttemptForm formFor(ExamData data, ExecutionContext ctx, AttemptRecord attempt, Instant now) {
        List<ExamQuestion> questions = data.questionsOf(ctx.examVersionId()).stream()
                .map(AttemptService::toWire)
                .toList();
        List<SavedAnswer> answers = data.answersOf(attempt.attemptId()).stream()
                .filter(AnswerRow::isAnswered)
                .map(row -> new SavedAnswer(row.questionVersionId(), row.selected()))
                .toList();
        AttemptState state = toWire(attempt);
        return new AttemptForm(attempt.attemptId(),
                headerOf(ctx, questions.size(), state),
                questions, answers,
                timingOf(attempt, ctx, now),
                state,
                state.isFinished() ? outcomeFor(data, ctx, attempt) : null);
    }

    /** Builds the answer-summary outcome both terminal screens render. */
    private AttemptOutcome outcomeFor(ExamData data, ExecutionContext ctx, AttemptRecord attempt) {
        List<QuestionOutline> outline = data.outlineOf(ctx.examVersionId());
        Set<Long> answered = new LinkedHashSet<>();
        for (AnswerRow row : data.answersOf(attempt.attemptId())) {
            if (row.isAnswered()) {
                answered.add(row.questionVersionId());
            }
        }
        List<AttemptSummaryEntry> summary = outline.stream()
                .map(question -> new AttemptSummaryEntry(question.ordinal(), question.displayId(),
                        answered.contains(question.questionVersionId())))
                .toList();
        return new AttemptOutcome(attempt.attemptId(), toWire(attempt), ctx.examName(),
                attempt.endedAt(),
                attempt.actualMinutes() == null ? 0 : attempt.actualMinutes(),
                (int) summary.stream().filter(AttemptSummaryEntry::answered).count(),
                summary.size(), summary);
    }

    private ExamHeader headerOf(ExecutionContext ctx, int questionCount, AttemptState state) {
        return new ExamHeader(ctx.executionId(), ctx.examName(), ctx.courseCode(), ctx.courseName(),
                ctx.allottedMinutes(), ctx.generalText(), questionCount, state);
    }

    /** The authoritative clock for one attempt, extensions included. */
    private AttemptTiming timingOf(AttemptRecord attempt, ExecutionContext ctx, Instant now) {
        Instant deadline = attempt.deadline(ctx.allottedMinutes());
        if (!attempt.isInProgress()) {
            Instant ended = attempt.endedAt() == null ? deadline : attempt.endedAt();
            return AttemptTiming.finished(now, ended,
                    Duration.between(attempt.startedAt(), deadline).toMillis());
        }
        return AttemptTiming.between(now, attempt.startedAt(), deadline);
    }

    private ActiveAttempt activeAttempt(AttemptRecord attempt, ExecutionContext ctx) {
        return new ActiveAttempt(attempt.attemptId(), ctx.executionId(), attempt.studentId(),
                ctx.courseCode(), ctx.courseName(), ctx.examName(),
                ctx.executingTeacherId(), attempt.startedAt());
    }

    private static String closedMessage(AttemptRecord attempt) {
        return attempt.status() == AttemptStatus.TIMED_OUT
                ? ExamMessages.TIME_IS_UP
                : ExamMessages.ALREADY_SUBMITTED;
    }

    /**
     * Solving time in whole minutes (S-19).
     *
     * <p>Rounded to the nearest minute rather than truncated: a paper handed in after 44
     * minutes and 50 seconds took 45 minutes by any reading a teacher would accept, and
     * truncation would report 44. Never negative, and never more precise than the spec asks
     * for.
     */
    static int solvingMinutes(Instant startedAt, Instant endedAt) {
        long seconds = Math.max(0, Duration.between(startedAt, endedAt).getSeconds());
        return (int) Math.round(seconds / 60.0);
    }

    /** The wire state of a stored attempt. */
    private static AttemptState toWire(AttemptRecord attempt) {
        return switch (attempt.status()) {
            case IN_PROGRESS -> AttemptState.IN_PROGRESS;
            case SUBMITTED -> AttemptState.SUBMITTED;
            case TIMED_OUT -> AttemptState.TIMED_OUT;
        };
    }

    /**
     * The one mapping from a stored question to a student's wire (F6.6 ⚑).
     *
     * <p>Both sides of it are types with no field for a correct answer, so this method
     * could not leak one even by trying. That is the whole v1 fix in one signature.
     */
    private static ExamQuestion toWire(TakeExamQuestion question) {
        return new ExamQuestion(question.questionVersionId(), question.displayId(),
                question.ordinal(), question.points(), question.text(),
                question.answer1(), question.answer2(), question.answer3(), question.answer4(),
                question.image());
    }

}
