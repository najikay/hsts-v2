package server.features.approval;

import common.dto.approval.ApprovalDecision;
import common.dto.approval.ApprovalQueue;
import common.dto.approval.ApprovalRow;
import common.dto.approval.ApprovalState;
import common.dto.approval.ExamApproveRequest;
import common.dto.approval.ExamPreview;
import common.dto.approval.ExamPreviewRequest;
import common.dto.approval.ExamRejectRequest;
import common.dto.approval.MyApprovals;
import common.dto.approval.PreviewAnswerRow;
import common.dto.approval.TeacherOnlyBlock;
import common.dto.auth.Role;
import common.dto.exam.ExamQuestion;
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
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionStatus;
import server.db.projections.ExamVersionContext;
import server.features.exam.ExamPaper;
import server.features.notify.NotificationCatalog;
import server.features.notify.Notifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The approval workflow, server-side (Logic tier, E8 — F4, S-14).
 *
 * <p>Two of v1's failures met in this epic: a coordinator who could not see the exam she was
 * approving, and an approval that was not bound to anything in particular. Both fixes are
 * structural, and they are the rules everything here obeys.
 *
 * <h2>Rule one: the coordinator sees the exam, exactly as a student will</h2>
 *
 * <p>{@link #preview} builds its paper with {@link ExamPaper#toWire}, the same mapper a live
 * attempt is built with, over {@code findForTakeExam}, the same no-correctness projection a
 * live attempt is served from. Not a second renderer written to the same specification: the
 * same code path. Everything staff-only — the teacher notes, the author, the answer key —
 * travels beside it in a {@link TeacherOnlyBlock}, so the wall between the two audiences is
 * visible in the types rather than asserted in a comment.
 *
 * <h2>Rule two: a decision binds to one version, and loses races loudly</h2>
 *
 * <p>Approval binds to a <em>version</em> (S-14), and {@code status} is the one mutable
 * field on an otherwise immutable row, which is why {@code exam_versions} carries
 * {@code lock_version}. Every decision here is guarded twice inside one transaction: the
 * version must still be {@code PENDING}, and its {@code lockVersion} must still be the one
 * the coordinator's screen was rendered from. A decision that fails either is refused with
 * {@code CONFLICT} and a sentence telling her to open it again — never applied to whatever
 * the row has since become.
 *
 * <h2>Scoping is in the SQL, not in an if</h2>
 *
 * <p>The queue is read by {@code findPendingForCoordinator}, which joins {@code coordinators}
 * into the query. A version outside her subjects is not fetched, so it cannot be counted, and
 * cannot be returned by a future code path that forgot a filter. Every mutation additionally
 * calls {@link Authorization#requireCoordinatorOf} against the same transaction's data, so
 * the read scoping and the write scoping cannot drift apart.
 *
 * <h2>Self-approval is allowed, and recorded (F4.3 ⚑)</h2>
 *
 * <p>A coordinator may approve her own exam. The seed has exactly that case
 * ({@code michal.sharon} coordinates Computer Science and is the only Databases teacher), and
 * refusing it would leave a subject with one teacher unable to release anything. PRD F4.3
 * permits it <b>on condition that it is recorded</b>, so {@link #approve} emits one
 * structured {@code WARN} line naming the coordinator, the exam and the version. Acceptance
 * case 4.6 greps the server log for it; a permission granted with no record is a silent
 * failure, so the line's shape is fixed by {@link ApprovalMessages#SELF_APPROVAL_MARKER} and
 * pinned by a test.
 *
 * <p>Everything is constructor-injected — the store, the notifier — so every rule above is
 * unit-testable against an in-memory store and a recording notifier, with no database, no
 * socket and no session.
 */
public final class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalStore store;
    private final Notifier notifier;

    /**
     * @param store    the transactional data seam
     * @param notifier durable notifications; the author has to learn her exam was decided
     *                 even if she was signed out when it happened (E17.6)
     */
    public ApprovalService(ApprovalStore store, Notifier notifier) {
        this.store = Objects.requireNonNull(store, "store");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
    }

    /** Registers the five approval verbs; all authenticated, none open. */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.APPROVALS_QUEUE_GET, this::queue);
        router.register(Verb.EXAM_PREVIEW_GET, this::preview);
        router.register(Verb.EXAM_APPROVE, this::approve);
        router.register(Verb.EXAM_REJECT, this::reject);
        router.register(Verb.MY_APPROVALS_GET, this::mine);
    }

    // ===================== APPROVALS_QUEUE_GET ===========================

    /**
     * What is waiting on this coordinator (E8.1 — F4.1).
     *
     * <p>The request carries no payload at all, and that is the security design rather than
     * a convenience: which subjects these are is resolved from the session, so there is no
     * field a client could put somebody else's id in (ARCHITECTURE §3).
     *
     * <p>Answers an empty queue two different ways, because they mean opposite things. A
     * coordinator with nothing waiting has a finished inbox; a caller who coordinates no
     * subject at all is on the wrong screen, and PRD §4.1 forbids answering both with the
     * same blank panel.
     */
    Message queue(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.COORDINATOR);
        long coordinatorId = caller.userId();

        return store.inTx(data -> {
            if (data.coordinatedSubjects(coordinatorId).isEmpty()) {
                // A COORDINATOR session with no coordinators row is a stale login, not an
                // attack: coordinator-ness is per-subject state and can be revoked between
                // sign-in and this request. Say so rather than showing an empty inbox.
                log.info("Approval queue for {} is empty: the caller coordinates no subject", coordinatorId);
                return Message.ok(request, ApprovalQueue.notACoordinator());
            }
            List<ExamVersionContext> pending = data.pendingFor(coordinatorId);
            return Message.ok(request,
                    new ApprovalQueue(rows(data, pending, coordinatorId), true));
        });
    }

    // ===================== MY_APPROVALS_GET ==============================

    /**
     * What became of the exams this teacher submitted (E8.6 — F4.2).
     *
     * <p>The author's half of F4.2's "stored and pushed as a notification <b>and</b> visible
     * on the exam". A notification the reader dismissed is not a record, so the reason has to
     * live somewhere she can go back to, and this is that somewhere until E7's exam list
     * absorbs it.
     *
     * <p>Scoped to the caller in the query itself, like every other "mine" verb in the
     * protocol: another teacher's id would answer with the caller's own rows, because there
     * is no id on the wire to misuse.
     */
    Message mine(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        long authorId = caller.userId();

        return store.inTx(data -> {
            List<ExamVersionContext> submitted = data.submittedByAuthor(authorId);
            return Message.ok(request, new MyApprovals(rows(data, submitted, authorId)));
        });
    }

    // ===================== EXAM_PREVIEW_GET ==============================

    /**
     * One exam version opened for review (E8.4 ⚑ — F4.1).
     *
     * <p><b>The v1 fix.</b> The response carries the paper as {@code List<ExamQuestion>}, the
     * student's own wire type, produced here by {@link ExamPaper#toWire} from the same
     * projection a live attempt uses. The coordinator's screen renders it with the same
     * component the take-exam screen renders, so "she sees exactly what the student sees" is
     * a property of the data path.
     *
     * <p>Two callers are allowed and they are allowed for different reasons. The subject's
     * coordinator, because deciding on an exam she cannot read is the failure this epic
     * exists to fix. The version's <em>own author</em>, because F4.2 requires the rejection
     * reason to be visible on the exam, and a teacher who cannot reopen what she submitted
     * cannot act on the reason she was given. Both see the answer key; both are staff, and
     * the key is on the exam either of them wrote or owns.
     *
     * <p>Anyone else is refused — including a teacher of the same course who did not write
     * it and does not coordinate the subject. The refusal names what to do next rather than
     * pretending the exam does not exist, because a coordinator who followed a notification
     * here already knows it does.
     */
    Message preview(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof ExamPreviewRequest ask)) {
            return Message.error(request, ErrorCode.VALIDATION, ApprovalMessages.MALFORMED_REQUEST);
        }
        long callerId = caller.userId();

        return store.inTx(data -> {
            Optional<ExamVersionContext> found = data.versionContext(ask.examVersionId());
            if (found.isEmpty()) {
                return Message.error(request, ErrorCode.NOT_FOUND, ApprovalMessages.VERSION_UNKNOWN);
            }
            ExamVersionContext version = found.get();
            if (!version.isAuthoredBy(callerId)) {
                requireCoordinatorOf(caller, version, data);
            }

            List<ExamQuestion> questions = ExamPaper.toWire(data.questionsOf(version.examVersionId()));
            List<PreviewAnswerRow> key = data.answerKeyOf(version.examVersionId());
            TeacherOnlyBlock teacherOnly =
                    new TeacherOnlyBlock(version.teacherText(), version.authorName(), key);

            return Message.ok(request, new ExamPreview(
                    toRow(version, questions.size(), callerId),
                    version.studentText(), questions, teacherOnly));
        });
    }

    // ===================== EXAM_APPROVE ==================================

    /**
     * Approve one version (E8.5 — F4.2, F4.3 ⚑).
     *
     * <p>{@code PENDING → APPROVED}, and only from {@code PENDING}: re-approving something
     * already approved, or approving something that was superseded a second earlier, answers
     * {@code CONFLICT} rather than moving it. That is not pedantry — E9 will only release an
     * {@code APPROVED} version, so a status that could be reached from anywhere would let a
     * withdrawn exam be sat.
     *
     * <p>The author is notified, and told she can release it now, which is the next thing she
     * will want to do.
     *
     * <p>Self-approval (F4.3) succeeds and is logged; see the class note.
     */
    Message approve(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.COORDINATOR);
        if (!(request.getPayload() instanceof ExamApproveRequest ask)) {
            return Message.error(request, ErrorCode.VALIDATION, ApprovalMessages.MALFORMED_REQUEST);
        }
        return decide(caller, request, ask.examVersionId(), ask.expectedLockVersion(), null);
    }

    // ===================== EXAM_REJECT ===================================

    /**
     * Send one version back, with a reason (E8.5 — F4.2).
     *
     * <p>The reason is checked before anything is read, so a coordinator who pressed the
     * button with an empty box pays nothing for it and cannot half-apply a rejection. The
     * rule is {@link ExamRejectRequest#validate}, which the client also runs on every
     * keystroke: one definition, enforced on the side that matters.
     *
     * <p>A rejection the author cannot act on is the one message this feature must never
     * send, which is why "required" here means a real sentence rather than a non-empty
     * string.
     */
    Message reject(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.COORDINATOR);
        if (!(request.getPayload() instanceof ExamRejectRequest ask)) {
            return Message.error(request, ErrorCode.VALIDATION, ApprovalMessages.MALFORMED_REQUEST);
        }
        Optional<String> complaint = ExamRejectRequest.validate(ask.reason());
        if (complaint.isPresent()) {
            return Message.error(request, ErrorCode.VALIDATION, complaint.get());
        }
        // The record already trimmed it; taking the trimmed value explicitly is what makes
        // "stored trimmed" a property of this path rather than of a compact constructor
        // somebody might later relax.
        return decide(caller, request, ask.examVersionId(), ask.expectedLockVersion(),
                ask.reason().trim());
    }

    /**
     * The shared body of both decisions.
     *
     * <p>One method because the two differ in exactly three places — which entity method is
     * called, which notification is sent, and whether F4.3 applies — and every other line is
     * a guard both need. Two copies of those guards would be two chances to forget one, and
     * the one most likely to be forgotten is the {@code lock_version} check, which is the
     * only thing standing between a stale screen and a decision nobody can account for.
     *
     * @param reason {@code null} to approve, a trimmed non-empty sentence to reject
     */
    private Message decide(CallerContext caller, Message request, long examVersionId,
                           int expectedLockVersion, String reason) {
        long callerId = caller.userId();
        Decided decided;
        try {
            decided = store.inTx(data -> apply(caller, data, examVersionId, expectedLockVersion, reason));
        } catch (RuntimeException e) {
            if (isStaleWrite(e)) {
                // Somebody else wrote this row between our read and our flush. Legitimate,
                // and the loser is told to look again rather than shown a failure.
                log.info("Decision on exam version {} lost an optimistic-lock race", examVersionId);
                return Message.error(request, ErrorCode.CONFLICT, ApprovalMessages.DECISION_RACED);
            }
            throw e;
        }
        if (decided.error() != null) {
            return Message.error(request, decided.error(), decided.message());
        }

        announce(decided, callerId);
        return Message.ok(request, new ApprovalDecision(
                toRow(decided.after(), decided.questionCount(), callerId), decided.selfApproved()));
    }

    /** The two guards, the write and the re-read, in one transaction. */
    private Decided apply(CallerContext caller, ApprovalData data, long examVersionId,
                          int expectedLockVersion, String reason) {
        Optional<ExamVersionContext> found = data.versionContext(examVersionId);
        if (found.isEmpty()) {
            return Decided.refused(ErrorCode.NOT_FOUND, ApprovalMessages.VERSION_UNKNOWN);
        }
        ExamVersionContext before = found.get();
        requireCoordinatorOf(caller, before, data);

        if (!before.isPending()) {
            // §6: a decision on something that is not waiting for one. CONFLICT rather than
            // VALIDATION, because nothing about the request was malformed: the world moved.
            return Decided.refused(ErrorCode.CONFLICT, ApprovalMessages.NOT_PENDING);
        }
        if (before.lockVersion() != expectedLockVersion) {
            // The screen was rendered from a row that has since been written. Checked here,
            // inside the transaction, rather than trusted to the flush: this way the answer
            // is the same whether the other writer was a supersede (a bulk update, which
            // does not bump @Version) or another decision (which does).
            log.info("Decision on exam version {} refused: screen held lock_version {}, row is at {}",
                    examVersionId, expectedLockVersion, before.lockVersion());
            return Decided.refused(ErrorCode.CONFLICT, ApprovalMessages.DECISION_RACED);
        }

        ExamVersion row = data.versionForUpdate(examVersionId).orElse(null);
        if (row == null) {
            // The projection found it and the entity did not, which can only happen if it was
            // deleted between the two reads inside one transaction. Nothing in the product
            // deletes an exam version, so this is a fault rather than a race.
            return Decided.refused(ErrorCode.NOT_FOUND, ApprovalMessages.VERSION_UNKNOWN);
        }
        if (reason == null) {
            row.approve();
        } else {
            row.reject(reason);
        }
        // Flushed here so an optimistic-lock failure is thrown by this call rather than at
        // commit, where the rule that caused it is no longer on the stack.
        data.flush();

        ExamVersionContext after = data.versionContext(examVersionId).orElse(before);
        boolean selfApproved = reason == null && before.isAuthoredBy(caller.userId());
        if (selfApproved) {
            logSelfApproval(caller.userId(), after);
        }
        int questionCount = data.questionCounts(List.of(examVersionId))
                .getOrDefault(examVersionId, 0);
        return Decided.of(after, questionCount, selfApproved, reason);
    }

    /**
     * The F4.3 record (acceptance case 4.6 ⚑).
     *
     * <p>One WARN line, structured and stable. WARN rather than INFO because the point is
     * that it stands out in a log somebody is scanning afterwards: the action is permitted,
     * and the permission is worth nothing without a trace anybody can find. The marker token
     * comes first so a grep for it needs no pattern, and the three facts that identify the
     * event — who, which exam, which version — follow in a fixed order.
     *
     * <p>The shape is an interface with the acceptance pass, so it is asserted by a test
     * rather than left to whoever next edits this method.
     */
    private static void logSelfApproval(long coordinatorId, ExamVersionContext version) {
        log.warn("{}: coordinator {} ({}) approved her own exam {} '{}' version {} (F4.3)",
                ApprovalMessages.SELF_APPROVAL_MARKER,
                coordinatorId,
                version.authorName(),
                version.examDisplayId(),
                version.examName(),
                version.versionNo());
    }

    /** Tells the author what happened to her exam, durably (F4.2). */
    private void announce(Decided decided, long coordinatorId) {
        ExamVersionContext version = decided.after();
        if (version.authorId() == coordinatorId && decided.selfApproved()) {
            // She has just done it herself and is looking at the confirmation. A notification
            // telling somebody what they did one second ago is the noise that makes people
            // stop reading their bell (the same rule CourseRepository.findOtherTeachers
            // applies). The F4.3 log line is the record that matters here, not a bell.
            return;
        }
        NotificationCatalog.Draft draft = decided.reason() == null
                ? NotificationCatalog.approvalApproved(version.examName(),
                        approverName(version, coordinatorId), version.examVersionId())
                : NotificationCatalog.approvalRejected(version.examName(),
                        approverName(version, coordinatorId), decided.reason(),
                        version.examVersionId());
        notifier.notifyUser(version.authorId(), draft);
    }

    /**
     * @return the name to put in the notification's sentence. The projection carries the
     *         <em>author's</em> name, which is the approver's too in the F4.3 case and
     *         nobody's otherwise; rather than a second join for one string, a decision by
     *         somebody else says "your subject coordinator", which is both true and the
     *         thing the author needs to know
     */
    private static String approverName(ExamVersionContext version, long coordinatorId) {
        return version.authorId() == coordinatorId
                ? version.authorName()
                : "Your subject coordinator";
    }

    // ===================== E8.2: supersede ===============================

    /**
     * <b>The entry point E7 calls when a version enters {@code PENDING}</b> (E8.2 — F4).
     *
     * <p>Not a verb. Submitting for approval is E7.6's transition and stays there; what
     * belongs here is what happens to the <em>queue</em> when it does, and this is the single
     * method that does all of it:
     *
     * <ol>
     *   <li>every <em>other</em> pending version of the same exam is sent back with
     *       {@link ApprovalMessages#SUPERSEDED_REASON}, because a coordinator holding two
     *       submissions of one exam has no way to know which one the teacher means, and the
     *       teacher has already answered that question by submitting again;</li>
     *   <li>the subject's coordinator is notified that a newer version replaced one in her
     *       queue, so a row that vanishes mid-read is explained rather than mysterious;</li>
     *   <li>the coordinator gets the ordinary "waiting for your approval" notification for
     *       the new version.</li>
     * </ol>
     *
     * <p><b>All three, from here, and not two of them from E7.</b> The approval-requested
     * notification was originally E7's to emit; folding it into this hook means E7's submit
     * calls one method and cannot end up emitting a request for a version whose supersede
     * failed, or emitting two notifications in the order that reads backwards. E7 owns the
     * transition; E8 owns everything the queue sees.
     *
     * <p>Idempotent by construction: the supersede is a status-guarded update, so calling it
     * twice sends nothing back the second time. It does send the notifications again, which
     * is the right trade for a hook that should never be the reason a submit fails.
     *
     * @param examVersionId the version that has just been submitted
     * @return how many older versions were sent back; {@code 0} for a first submission
     */
    public int versionSubmitted(long examVersionId) {
        Superseded result = store.inTx(data -> {
            Optional<ExamVersionContext> found = data.versionContext(examVersionId);
            if (found.isEmpty()) {
                log.warn("Submit hook called for exam version {}, which does not exist", examVersionId);
                return Superseded.none();
            }
            ExamVersionContext version = found.get();
            if (!version.isPending()) {
                // A hook called for something that is not waiting would send its siblings
                // back on behalf of a submission that did not happen.
                log.warn("Submit hook called for exam version {}, which is {} rather than PENDING",
                        examVersionId, version.status());
                return Superseded.none();
            }
            int count = data.supersedePending(version.examId(), examVersionId,
                    ApprovalMessages.SUPERSEDED_REASON);
            List<Long> coordinators = coordinatorsOf(data, version);
            return new Superseded(version, count, coordinators);
        });

        if (result.version() == null) {
            return 0;
        }
        if (result.count() > 0) {
            log.info("Exam {} version {} superseded {} pending version(s)",
                    result.version().examDisplayId(), result.version().versionNo(), result.count());
            notifier.notify(result.coordinators(), NotificationCatalog.approvalSuperseded(
                    result.version().examName(), result.version().authorName(), examVersionId));
        }
        notifier.notify(result.coordinators(), NotificationCatalog.approvalRequested(
                result.version().examName(), result.version().authorName(), examVersionId));
        return result.count();
    }

    /**
     * Who to tell about a submission.
     *
     * <p>Exactly one person, or nobody: {@code coordinators} is keyed on the subject alone
     * (§5), so "the coordinator of this exam's subject" has one answer by construction.
     *
     * <p>The author being that person is not excluded, and deliberately. F4.3's dual-hat case
     * is precisely a teacher who coordinates her own subject, and she still needs the request
     * in her queue in order to answer it. This is the one place in the feature where telling
     * somebody about their own action is right, because the action she is being told about is
     * one she now has to act on again wearing the other hat.
     *
     * <p>A subject with no coordinator yields nobody, and that is logged: a submission nobody
     * can approve is an administrative gap, and the teacher would otherwise wait forever for
     * a decision that cannot come.
     */
    private static List<Long> coordinatorsOf(ApprovalData data, ExamVersionContext version) {
        Optional<Long> coordinator = data.coordinatorOf(version.subjectCode());
        if (coordinator.isEmpty()) {
            log.warn("Exam {} version {} was submitted, but subject {} has no coordinator to approve it",
                    version.examDisplayId(), version.versionNo(), version.subjectCode());
            return List.of();
        }
        return List.of(coordinator.get());
    }

    // ===================== Internals =====================================

    /**
     * Applies the subject guard against this transaction's own data.
     *
     * <p>Against {@code data} rather than the process-wide directory
     * {@link Authorization#useSubjectCoordinators} installs, because there is already a
     * session open here: reading the binding through a second connection would answer a
     * question this one can answer, and would answer it about a marginally different moment.
     */
    private static void requireCoordinatorOf(CallerContext caller, ExamVersionContext version,
                                             ApprovalData data) {
        Authorization.requireCoordinatorOf(caller, version.subjectCode(), data::coordinates);
    }

    /** Builds the wire rows for a list of versions, counting their questions in one query. */
    private static List<ApprovalRow> rows(ApprovalData data, List<ExamVersionContext> versions,
                                          long callerId) {
        if (versions.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> counts = data.questionCounts(
                versions.stream().map(ExamVersionContext::examVersionId).toList());
        List<ApprovalRow> rows = new ArrayList<>(versions.size());
        for (ExamVersionContext version : versions) {
            rows.add(toRow(version, counts.getOrDefault(version.examVersionId(), 0), callerId));
        }
        return List.copyOf(rows);
    }

    private static ApprovalRow toRow(ExamVersionContext version, int questionCount, long callerId) {
        return new ApprovalRow(version.examVersionId(), version.examDisplayId(), version.examName(),
                version.courseCode(), version.courseName(), version.versionNo(),
                version.authorName(), version.createdAt(), questionCount,
                version.durationMinutes(), toWire(version.status()), version.rejectedReason(),
                version.isAuthoredBy(callerId), version.lockVersion());
    }

    /**
     * The stored status on the wire.
     *
     * <p>An exhaustive switch rather than {@code ApprovalState.valueOf(status.name())},
     * because the two enums are allowed to diverge and a name-based bridge would turn that
     * into a runtime failure on a coordinator's screen instead of a compile error here.
     */
    static ApprovalState toWire(ExamVersionStatus status) {
        return switch (status) {
            case DRAFT -> ApprovalState.DRAFT;
            case PENDING -> ApprovalState.PENDING;
            case APPROVED -> ApprovalState.APPROVED;
            case REJECTED -> ApprovalState.REJECTED;
        };
    }

    /** What one decision transaction produced: an outcome, or a refusal. */
    private record Decided(ExamVersionContext after, int questionCount, boolean selfApproved,
                           String reason, ErrorCode error, String message) {

        static Decided of(ExamVersionContext after, int questionCount, boolean selfApproved,
                          String reason) {
            return new Decided(after, questionCount, selfApproved, reason, null, null);
        }

        static Decided refused(ErrorCode error, String message) {
            return new Decided(null, 0, false, null, error, message);
        }
    }

    /** What one supersede transaction produced. */
    private record Superseded(ExamVersionContext version, int count, List<Long> coordinators) {

        static Superseded none() {
            return new Superseded(null, 0, List.of());
        }
    }

    /**
     * @return whether this failure is the optimistic-lock loss of a concurrent decision rather
     *         than a real fault. Walked over the cause chain because Hibernate wraps its
     *         {@link StaleStateException} differently depending on where the flush happened,
     *         and matching only the outermost type works until it does not
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
