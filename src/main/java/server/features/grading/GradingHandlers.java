package server.features.grading;

import common.dto.auth.Role;
import common.dto.grading.ApproveRequest;
import common.dto.grading.ExecutionGrades;
import common.dto.grading.ExecutionGradesRequest;
import common.dto.grading.GradingQueue;
import common.dto.grading.ApproveResult;
import common.dto.grading.GradeOverrideRequest;
import common.dto.grading.GradeReview;
import common.dto.grading.GradeReviewRequest;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.Authorization;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.db.Transactions;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The teacher grading verbs, on the router (Logic tier, E12.2/E12.3/E12.6).
 *
 * <p>Three verbs, one authorization shape, written once. Every teacher verb in the frozen
 * contract is gated the same way — the role check, then the payload, then ownership of the
 * specific grade — and the reason this class exists at all is that the three would otherwise
 * each carry their own copy of the first two. Three copies of a security rule is three chances
 * for one of them to drift, and the one that drifts is the one nobody re-reads.
 *
 * <h2>The shape</h2>
 *
 * <p>{@link #asTeacher} does the half that is identical everywhere, in a fixed order that is
 * itself part of the design:
 *
 * <ol>
 *   <li><b>Role.</b> Refuse anyone who is not a teacher or coordinator, before anything about
 *       the request is examined. A student sending a malformed grading payload learns that the
 *       verb is not theirs, and nothing at all about what the payload should have looked
 *       like.</li>
 *   <li><b>Payload.</b> Wrong type or missing is {@code VALIDATION}, never an exception: a
 *       request that cannot be read is a client bug, and letting it throw would put a stack
 *       trace in front of a teacher and a dropped connection behind them.</li>
 *   <li><b>Per-verb validation</b>, still outside any transaction. A blank justification is the
 *       commonest mistake on the override screen and it should cost a sentence, not a database
 *       round trip — nothing is read on behalf of a request that was never going to be
 *       honoured.</li>
 *   <li><b>One transaction</b>, with the caller's own id handed to the work.</li>
 * </ol>
 *
 * <p>The half that cannot be shared — <em>which</em> grades this teacher owns — stays in the
 * services, because it is resolved per grade and a bulk approve can span executions. So the
 * split is not arbitrary: the role gate is a property of the verb, and the ownership gate is a
 * property of the row.
 *
 * <p><b>The caller id reaches the services as a parameter and never as a payload field.</b>
 * None of the three request records has a teacher id on it, so there is no field a client
 * could put somebody else's id in (ARCHITECTURE §3, P-5).
 *
 * <h2>Why the role gate is TEACHER or COORDINATOR</h2>
 *
 * <p>A coordinator is a teacher with an extra hat; the contract's rule is the role pair plus
 * ownership, and it is ownership that does the real work. A coordinator who did not release
 * the execution and did not write the exam is refused exactly like anyone else — coordinating
 * a subject is authority over approving exams (E8), not over other people's marking.
 *
 * <p>Unknown grade and somebody else's grade are both {@code NOT_FOUND} with one sentence, so
 * the answers cannot be used to enumerate grades.
 */
public class GradingHandlers {

    private static final Logger log = LoggerFactory.getLogger(GradingHandlers.class);

    /** Work that has passed every gate: a session, the caller's own id, and a checked payload. */
    @FunctionalInterface
    private interface TeacherWork<T> {
        Message apply(Session session, long teacherId, T payload);
    }

    private final SessionFactory sessionFactory;
    private final GradeApprovalService approvals;
    private final OverrideService overrides;
    private final GradeReviewService reviews;
    private final GradingQueueService queues;

    public GradingHandlers(SessionFactory sessionFactory,
                           GradeApprovalService approvals,
                           OverrideService overrides,
                           GradeReviewService reviews,
                           GradingQueueService queues) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.overrides = Objects.requireNonNull(overrides, "overrides");
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.queues = Objects.requireNonNull(queues, "queues");
    }

    /**
     * Registers the three teacher grading verbs.
     *
     * @param router the router to register on
     */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.GRADING_QUEUE_GET, this::queue);
        router.register(Verb.GRADING_EXECUTION_GET, this::executionGrades);
        router.register(Verb.GRADES_APPROVE, this::approve);
        router.register(Verb.GRADE_OVERRIDE, this::override);
        router.register(Verb.GRADE_REVIEW_GET, this::review);
    }

    // ===================== The shared gate ===============================

    /**
     * The authorization shape every teacher grading verb wears.
     *
     * @param request   the incoming request, for its payload and to answer against
     * @param caller    the authenticated caller
     * @param type      the payload type this verb takes
     * @param validate  per-verb checks; returns the sentence to answer with, or {@code null}
     *                  when the payload is acceptable. Runs before any transaction opens
     * @param work      what to do once every gate has passed
     * @param <T>       the payload type
     * @return the work's response, or the first gate's refusal
     */
    private <T> Message asTeacher(Message request,
                                  CallerContext caller,
                                  Class<T> type,
                                  Function<T, String> validate,
                                  TeacherWork<T> work) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);

        Object payload = request.getPayload();
        if (!type.isInstance(payload)) {
            log.debug("{} from {} carried {} rather than {}", request.getVerb(), caller,
                    payload == null ? "no payload" : payload.getClass().getName(),
                    type.getSimpleName());
            return Message.error(request, ErrorCode.VALIDATION, GradingMessages.MALFORMED_REQUEST);
        }
        T checked = type.cast(payload);

        String problem = validate.apply(checked);
        if (problem != null) {
            return Message.error(request, ErrorCode.VALIDATION, problem);
        }

        long teacherId = caller.userId();
        return Transactions.inTx(sessionFactory,
                session -> work.apply(session, teacherId, checked));
    }

    /** The validator for a verb whose payload needs nothing checked beyond its type. */
    private static <T> String noExtraChecks(T payload) {
        return null;
    }

    // ===================== GRADING_QUEUE_GET =============================

    /**
     * {@code GRADING_QUEUE_GET} — what is waiting to be graded (E12.5).
     *
     * <p>Takes no payload at all, and that is the security design rather than a convenience:
     * whose queue this is comes from the session, so there is no field a client could put
     * another teacher's id in (ARCHITECTURE §3).
     *
     * <p>An empty queue is {@code OK} with {@link GradingQueue#EMPTY}, never an error — a
     * teacher who has signed everything off has a finished inbox, which is a state the screen
     * renders rather than a failure.
     *
     * @param caller  the authenticated teacher
     * @param request the request; its payload is ignored, and there is nothing it could say
     * @return {@code OK} with a {@link GradingQueue}
     */
    Message queue(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        long teacherId = caller.userId();

        GradingQueue queue = Transactions.inTx(sessionFactory,
                session -> queues.queue(session, teacherId));
        log.debug("Grading queue for teacher {}: {} execution(s) waiting", teacherId, queue.size());
        return Message.ok(request, queue);
    }

    // ===================== GRADING_EXECUTION_GET =========================

    /**
     * {@code GRADING_EXECUTION_GET} — one sitting opened for grading (E12.5/E12.6).
     *
     * <p>Unknown execution and somebody else's are one {@code NOT_FOUND} with one sentence: a
     * queue row is not a capability, and a teacher who guesses an id must learn nothing from
     * the answer.
     *
     * @param caller  the authenticated teacher
     * @param request the request, carrying an {@link ExecutionGradesRequest}
     * @return {@code OK} with {@link ExecutionGrades}, or {@code NOT_FOUND}
     */
    Message executionGrades(CallerContext caller, Message request) {
        return asTeacher(request, caller, ExecutionGradesRequest.class,
                GradingHandlers::noExtraChecks, (session, teacherId, ask) -> {
                    Optional<ExecutionGrades> found =
                            queues.executionGrades(session, teacherId, ask.executionId());
                    if (found.isEmpty()) {
                        log.debug("Teacher {} asked for execution {} and does not own it (or it "
                                + "does not exist)", teacherId, ask.executionId());
                        return Message.error(request, ErrorCode.NOT_FOUND,
                                GradingMessages.NO_SUCH_EXECUTION);
                    }
                    return Message.ok(request, found.get());
                });
    }

    // ===================== GRADES_APPROVE ================================

    /**
     * {@code GRADES_APPROVE} — approve one grade or a batch (E12.2/E12.7).
     *
     * <p>Always {@code OK}, even when part of the batch was refused. Partial success is the
     * normal outcome of a bulk approve and {@link ApproveResult} is built to describe it;
     * answering {@code ERROR} because eight of ten worked would throw away the eight.
     *
     * @param caller  the authenticated teacher
     * @param request the request, carrying an {@link ApproveRequest}
     * @return {@code OK} with an {@link ApproveResult}
     */
    Message approve(CallerContext caller, Message request) {
        return asTeacher(request, caller, ApproveRequest.class, GradingHandlers::noExtraChecks,
                (session, teacherId, ask) -> {
                    ApproveResult result = approvals.approve(session, teacherId, ask);
                    log.debug("Teacher {} approved {} of {} grade(s)",
                            teacherId, result.approved(), ask.gradeIds().size());
                    return Message.ok(request, result);
                });
    }

    // ===================== GRADE_OVERRIDE ================================

    /**
     * {@code GRADE_OVERRIDE} — move a score by hand, with a reason and optionally a comment
     * for the student (E12.3, S-22/S-23).
     *
     * <p>The comment adds no gate of its own. It is optional, it has already collapsed to null
     * if it was blank ({@link GradeOverrideRequest}'s compact constructor), and it is refused
     * with the override in every case the override is refused — including {@code CONFLICT},
     * which is the point of it riding the same verb rather than having one of its own.
     *
     * @param caller  the authenticated teacher
     * @param request the request, carrying a {@link GradeOverrideRequest}
     * @return {@code OK} with the refreshed {@link GradeReview}; {@code VALIDATION} for a blank
     *         reason or an out-of-range score; {@code NOT_FOUND} for an unknown or unowned
     *         grade; {@code CONFLICT} for one that is already approved
     */
    Message override(CallerContext caller, Message request) {
        return asTeacher(request, caller, GradeOverrideRequest.class, GradingHandlers::checkOverride,
                (session, teacherId, ask) -> {
                    OverrideService.OverrideOutcome outcome =
                            overrides.override(session, teacherId, ask);
                    return switch (outcome.outcome()) {
                        case OVERRIDDEN -> Message.ok(request, outcome.review());
                        case ALREADY_APPROVED -> Message.error(request, ErrorCode.CONFLICT,
                                GradingMessages.ALREADY_APPROVED);
                        case NOT_FOUND -> Message.error(request, ErrorCode.NOT_FOUND,
                                GradingMessages.NO_SUCH_GRADE);
                    };
                });
    }

    /**
     * The two things an override payload must satisfy before anything is read (S-23).
     *
     * @param ask the request
     * @return the sentence to answer with, or {@code null} when it is acceptable
     */
    private static String checkOverride(GradeOverrideRequest ask) {
        if (ask.justification() == null || ask.justification().isBlank()) {
            // Whitespace is not a justification: F8.3 wants a readable audit trail, and a
            // grade whose stored reason is three spaces is a silent override with extra steps.
            return GradingMessages.JUSTIFICATION_REQUIRED;
        }
        if (ask.newScore() < GradeOverrideRequest.MIN_SCORE
                || ask.newScore() > GradeOverrideRequest.MAX_SCORE) {
            return GradingMessages.SCORE_OUT_OF_RANGE;
        }
        return null;
    }

    // ===================== GRADE_REVIEW_GET ==============================

    /**
     * {@code GRADE_REVIEW_GET} — one student's marked paper, for the teacher (E12.6).
     *
     * <p>The verb that hands a teacher the answer key alongside what the student chose, which
     * is what reviewing a paper is. The student's route to the same data is
     * {@code CHECKED_FORM_GET}, and it has three conditions this one does not need: a teacher
     * who owns the execution is entitled to the paper whatever state it is in.
     *
     * @param caller  the authenticated teacher
     * @param request the request, carrying a {@link GradeReviewRequest}
     * @return {@code OK} with a {@link GradeReview}, or {@code NOT_FOUND} when the grade does
     *         not exist <b>or</b> is not this teacher's
     */
    Message review(CallerContext caller, Message request) {
        return asTeacher(request, caller, GradeReviewRequest.class, GradingHandlers::noExtraChecks,
                (session, teacherId, ask) -> {
                    Optional<GradeReviewService.ReviewContext> found =
                            reviews.contextOf(session, ask.gradeId());
                    if (found.isEmpty() || !found.get().isOwnedBy(teacherId)) {
                        log.debug("Teacher {} asked for grade {} and does not own it (or it "
                                + "does not exist)", teacherId, ask.gradeId());
                        return Message.error(request, ErrorCode.NOT_FOUND,
                                GradingMessages.NO_SUCH_GRADE);
                    }
                    return Message.ok(request, reviews.review(session, found.get()));
                });
    }
}
