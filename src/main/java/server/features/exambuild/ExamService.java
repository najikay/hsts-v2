package server.features.exambuild;

import server.db.projections.AutoCandidate;
import common.dto.authoring.AutoComposeResult;
import common.dto.authoring.AutoComposeRequest;
import common.dto.authoring.ComposedQuestion;
import common.dto.authoring.ExamComposition;
import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.ExamList;
import common.dto.authoring.ExamListRow;
import common.dto.authoring.ExamVersionAction;
import common.dto.authoring.ExamVersionRequest;
import common.dto.authoring.ExamVersionRow;
import common.dto.authoring.ExamVersionSave;
import common.dto.authoring.QuestionPin;
import common.dto.approval.ApprovalState;
import common.dto.lock.EntityRef;
import common.dto.lock.LockHolder;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.Authorization;
import server.core.CallerContext;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionStatus;
import server.db.projections.AuthoredExamHeader;
import server.db.projections.AuthoredVersionRow;
import server.db.projections.ExamCompositionHeader;
import server.db.projections.PinCandidate;
import server.db.projections.PinnedQuestion;
import server.db.repos.CourseRepository;
import server.db.repos.ExamBuildRepository;
import server.db.repos.ExamRepository;
import server.features.locks.EditLockGuard;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The exam builder's writes and the reads that answer them (E7.1, E7.2, E7.3, E7.5, E7.6, E7.7).
 *
 * <p>Everything here runs inside a transaction the caller already opened, per contract §5.6. Every
 * verb reads a stored row, decides something about it, and writes beside it, and splitting that
 * across two transactions is how an exam gets two version 2s.
 *
 * <h2>Every rule with no database behind it lives here</h2>
 *
 * <p>Five of the contract's rules have <b>no schema backstop at all</b>, which makes this class the
 * only thing between a teacher and a paper that breaks them. In order of how quietly they would
 * fail:
 *
 * <ol>
 *   <li><b>No soft-deleted question.</b> Soft delete is an {@code UPDATE} and no foreign key fires
 *       on an update, so nothing underneath can refuse it. ARCHITECTURE §5's round-2 note assigns
 *       the rule here by name, and the store's MySQL leaf asserts the hole is open on purpose.</li>
 *   <li><b>Every question in the exam's own course</b>, resolved from the stored
 *       {@code question_versions} row rather than trusted from the client.</li>
 *   <li><b>Points summing to exactly 100</b>, with the shortfall named in both directions.</li>
 *   <li><b>No duplicate question, even through two versions of it</b> - this one has a constraint
 *       behind it, but its message names a constraint rather than a next move.</li>
 *   <li><b>Only a DRAFT is savable</b>, answering {@code CONFLICT} rather than
 *       {@code VALIDATION}.</li>
 * </ol>
 *
 * <p>{@link ExamValidator} states them; this class is where they are <em>run</em>, on the path a
 * write actually takes. A rule present in a validator nobody calls is the same defect as a guard
 * watching a feature that is unreachable.
 *
 * <h2>The scope guard, per verb</h2>
 *
 * <p>Contract §2 and ruling 2. {@link #create} takes the course from the caller and uses the
 * <b>throwing</b> {@code requireTeachesCourse}: she supplied the course, so a {@code FORBIDDEN}
 * naming it tells her nothing she did not type. Every other verb addresses a <em>stored</em>
 * version and answers {@link BuildStatus#NOT_FOUND} for an exam she did not author, folding
 * unknown and not-hers into one answer so exam ids cannot be probed and a colleague's name never
 * leaks. Same shape as {@code QuestionService}, for the same reason.
 *
 * <h2>The edit lock consult, and the two sentences that must survive its next copier</h2>
 *
 * <p>E7 is the second and last consumer of {@code EditLockGuard} this phase, and the lead's
 * ruling of 2026-08-24 adopted both of the bank's cold-read findings as the consult's contract.
 * Written here as instructed, because the shape is what the next feature will copy:
 *
 * <ol>
 *   <li><b>The lock is keyed off the resolved {@code exam_versions} row id, never off anything
 *       the client sent.</b> The id is already a {@code long}, so the collation hazard that made
 *       the bank key off its stored row - {@code utf8mb4_unicode_ci} calls strings equal that
 *       {@code Long.parseLong} rejects - <em>dies at the type</em> here rather than at a
 *       convention.</li>
 *   <li><b>The consult-before-version-check ordering is pinned per verb, not per class.</b> Each
 *       of the three writers below is a separate call site, and one verb's ordering is not
 *       evidence about another's: on the bank, hoisting the consult in {@code delete} alone
 *       survived every test that pinned {@code update}, and would have shipped an existence
 *       oracle naming a colleague.</li>
 * </ol>
 *
 * <p>The consult sits after the author check and before the optimistic token, on all three. The
 * lock is the polite refusal; {@code expectedLockVersion} is the correctness guarantee.
 *
 * <h2>Every answer is re-read, never assembled from the request</h2>
 *
 * <p>All four writing verbs answer an {@link ExamComposition} read back out of the database rather
 * than built from what arrived. Contract §5.5's last line asks for this and the reason is worth
 * keeping: the answer then shows what was actually stored, including the {@code lockVersion} the
 * next write has to send back, and a write that silently stored something different cannot be
 * hidden by an answer echoing the request.
 */
public class ExamService {

    private static final Logger log = LoggerFactory.getLogger(ExamService.class);

    /** Every exam starts at version 1. Spelled once so the two writers agree. */
    public static final int FIRST_VERSION = 1;

    /** How a builder verb finished. */
    public enum BuildStatus {

        /** The write happened, or the read answered; the composition is in the outcome. */
        OK,

        /** Unknown version, or one the caller did not author. One answer, on purpose. */
        NOT_FOUND,

        /** A rule in §5.1 to §5.3 was broken; the outcome carries the sentence. */
        INVALID,

        /** The world moved: wrong state for the verb, or a stale lock token. */
        CONFLICT
    }

    /**
     * What a builder verb did.
     *
     * <p>The two payload components are each tied to a status by the compact constructor, so no
     * caller can build an {@link BuildStatus#OK} outcome with nothing to answer with, nor a
     * refusal with no sentence in it. That is the same lesson a cold read taught this codebase on
     * the bank's lock refusal: a component that is required in name only is one null dereference
     * away from turning a refusal into a stack trace on a write path.
     *
     * @param status      what happened
     * @param composition the exam as stored, and {@code null} unless the status is {@code OK}
     * @param message     the sentence for the teacher, and {@code null} when the status is
     *                    {@code OK}
     */
    public record BuildOutcome(BuildStatus status, ExamComposition composition, String message) {

        public BuildOutcome {
            Objects.requireNonNull(status, "status");
            if (status == BuildStatus.OK) {
                if (composition == null) {
                    throw new IllegalArgumentException(
                            "an OK outcome has to carry the composition it is answering with");
                }
                if (message != null) {
                    throw new IllegalArgumentException(
                            "an OK outcome carries no sentence: nothing was refused");
                }
            } else {
                if (message == null) {
                    throw new IllegalArgumentException(
                            "a refusal has to carry its sentence, or the handler has nothing to "
                                    + "say to the teacher");
                }
                if (composition != null) {
                    throw new IllegalArgumentException(
                            "a refusal carries no composition: " + status);
                }
            }
        }

        static BuildOutcome ok(ExamComposition composition) {
            return new BuildOutcome(BuildStatus.OK, composition, null);
        }

        static BuildOutcome notFound() {
            return new BuildOutcome(BuildStatus.NOT_FOUND, null,
                    ExamBuildMessages.EXAM_NOT_FOUND);
        }

        static BuildOutcome invalid(ExamValidator.Violation violation) {
            return new BuildOutcome(BuildStatus.INVALID, null, violation.message());
        }

        static BuildOutcome conflict(String message) {
            return new BuildOutcome(BuildStatus.CONFLICT, null, message);
        }
    }

    private final ExamBuildRepository exams;

    /**
     * The exam reads this epic shares with other screens, and the reason it is a second
     * repository rather than a method on the first.
     *
     * <p>Only {@link ExamRepository#countQuestionsByVersion} is used, and only by {@link #list}.
     * {@code AuthoredVersionRow}'s javadoc is where the decision is argued: the count is one
     * aggregate over a different table, the approval queue already reads it there, and a
     * correlated count added to the authored-versions query would be a second expression of one
     * fact. The first time the two disagreed, the teacher's exam list would show a count no other
     * screen agreed with.
     */
    private final ExamRepository sharedExamReads;

    private final CourseRepository courses;
    private final EditLockGuard locks;
    private final Clock clock;

    public ExamService(ExamBuildRepository exams, ExamRepository sharedExamReads,
                       CourseRepository courses, EditLockGuard locks, Clock clock) {
        this.exams = Objects.requireNonNull(exams, "exams");
        this.sharedExamReads = Objects.requireNonNull(sharedExamReads, "sharedExamReads");
        this.courses = Objects.requireNonNull(courses, "courses");
        // Required, never optional. A null guard would make "locks are not enforced here" a
        // runtime state nobody declares, and the one deployment where it was null is the one
        // where two teachers overwrite each other with the banner showing on both screens.
        this.locks = Objects.requireNonNull(locks, "locks");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The lock consult, keyed off the resolved row (§6, E18.5, ruled 2026-08-24).
     *
     * <p>Takes the stored {@link ExamVersion} rather than a version id off the wire, so the key
     * cannot be built from anything a caller chose. That is this class's version of the fix the
     * bank arrived at the hard way, and here it is free: the id is a {@code long} already.
     *
     * <p>Called by each of the three write verbs separately. Not folded into a shared preamble
     * on purpose, so that a future edit to one verb's ordering cannot silently move the other
     * two, and so each call site is pinned by its own test.
     *
     * @param caller  the authenticated author
     * @param version the version she is writing to, already resolved and author-checked
     * @return the other teacher holding it, or empty
     */
    private Optional<LockHolder> lockHolderOtherThan(CallerContext caller, ExamVersion version) {
        return locks.heldByAnother(
                new EntityRef(EntityRef.EXAM_VERSION, version.getId()), caller.userId());
    }

    // ===================== EXAM_LIST (E7.10) ==============================

    /**
     * Every exam the calling teacher wrote, each with all of its versions (E7.10, F3.6, F9.2).
     *
     * <p>Three reads and no filtering in Java. The scope is in the SQL: both queries take the
     * author's id and return only her rows, so there is no moment where somebody else's exam is
     * in a collection here waiting to be filtered out. That is the shape §2 asks for and it is
     * also the one that cannot be broken by an edit to a later loop.
     *
     * <p><b>The question count comes from a third read on purpose.</b> See
     * {@link #sharedExamReads}. The alternative, a correlated subquery on the versions read, was
     * rejected where the projection was written rather than here.
     *
     * <p>An empty list is a real answer with a designed panel behind it, per the verb's javadoc.
     * There is deliberately no second empty state meaning "she teaches nothing": a teacher who
     * teaches nothing cannot reach this screen at all.
     *
     * <p>No authorization decision is made here beyond the author scope in the queries. The role
     * gate is the handler's, because it does not need a transaction to run.
     *
     * @param session a session inside the caller's transaction
     * @param caller  the authenticated author, already role-checked
     * @return her exams, newest version first within each row
     */
    public ExamList list(Session session, CallerContext caller) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(caller, "caller");

        List<AuthoredExamHeader> headers = exams.findAuthoredExams(session, caller.userId());
        if (headers.isEmpty()) {
            // Neither of the two reads below can return anything for an author with no exams,
            // and the count query refuses an empty id list anyway. Answering here keeps a
            // teacher who has written nothing to one query rather than three.
            return new ExamList(List.of());
        }

        List<AuthoredVersionRow> versions = exams.findAuthoredVersions(session, caller.userId());
        List<Long> versionIds = new ArrayList<>(versions.size());
        for (AuthoredVersionRow version : versions) {
            versionIds.add(version.examVersionId());
        }
        Map<Long, Integer> questionCounts =
                sharedExamReads.countQuestionsByVersion(session, versionIds);

        // Grouped rather than looked up per header, so the number of passes over the versions
        // does not grow with the number of exams on her screen.
        Map<Long, List<ExamVersionRow>> byExam = new HashMap<>();
        for (AuthoredVersionRow version : versions) {
            byExam.computeIfAbsent(version.examId(), examId -> new ArrayList<>())
                    .add(new ExamVersionRow(version.examVersionId(), version.versionNo(),
                            stateOf(version.status()), rejectionOf(version.rejectedReason()),
                            // A version with no pinned rows is absent from the map entirely.
                            // The write path forbids one - every stored version sums to 100
                            // across at least one question - and this read does not re-assert
                            // that, because answering 0 keeps her list on screen where an
                            // unboxed null would take it down.
                            questionCounts.getOrDefault(version.examVersionId(), 0),
                            version.durationMinutes(), version.createdAt(), version.lockVersion()));
        }

        List<ExamListRow> rows = new ArrayList<>(headers.size());
        for (AuthoredExamHeader header : headers) {
            rows.add(new ExamListRow(header.examId(), header.displayId6(), header.courseCode(),
                    header.courseName(), header.name(), header.latestVersionNo(),
                    // An exam always has at least version 1, so the default is unreachable
                    // through the store. It is here because ExamListRow refuses a null list and
                    // a missing group is a defect worth seeing as an empty row rather than as a
                    // NullPointerException inside a record constructor.
                    byExam.getOrDefault(header.examId(), List.of())));
        }
        return new ExamList(rows);
    }

    // ===================== EXAM_CREATE (E7.1) =============================

    /**
     * Writes a new exam and its first DRAFT version (E7.1, S-11, F3.1).
     *
     * <p>The display id is allocated under the course's row lock by {@code ExamIdAllocator}, so
     * two teachers creating in one course at the same moment cannot be handed the same serial.
     *
     * @param session a session inside the caller's transaction
     * @param caller  the authenticated author, already role-checked
     * @param request the exam as submitted
     * @return the stored exam at version 1, or why it was refused
     * @throws server.core.AuthorizationException {@code FORBIDDEN} when this is not one of her
     *                                            courses
     */
    /**
     * The outcome of an auto-composition (E7.4).
     *
     * <p>Its own type rather than {@link BuildOutcome}, because the payload is different in kind:
     * a {@code BuildOutcome} carries a stored {@code ExamComposition} and this carries a proposal
     * that was never stored. Two statuses only - the criteria were acceptable or they were not -
     * because <b>an infeasible request is a successful answer</b>. She asked what the bank could
     * do and was told precisely; nothing failed. Mapping it to an error code would put F3.3's
     * whole report behind a red banner and lose the shortfall rows on the way.
     *
     * @param status  {@code OK} or {@code INVALID}, never the other two
     * @param result  the proposal or the report; null when the criteria were refused
     * @param message the refusal sentence; null on {@code OK}
     */
    public record AutoOutcome(BuildStatus status, AutoComposeResult result, String message) {

        static AutoOutcome ok(AutoComposeResult result) {
            return new AutoOutcome(BuildStatus.OK, result, null);
        }

        static AutoOutcome invalid(ExamValidator.Violation violation) {
            return new AutoOutcome(BuildStatus.INVALID, null, violation.message());
        }
    }

    /**
     * Proposes a composition from a criteria grid, or says exactly what is missing
     * (E7.4 ⚑ — F3.2, F3.3, contract §7).
     *
     * <p><b>It writes nothing.</b> No exam, no version, no allocated serial, and no call on this
     * path reaches a method that inserts. That is what makes T-3.5's "No exam is created" true by
     * construction rather than by a rollback that has to work: there is nothing to undo. A
     * proposal she likes is sent on to {@code EXAM_CREATE} by the client.
     *
     * <p>Runs inside a transaction anyway, because the pool read must see one consistent moment.
     * Counting candidates in one snapshot and picking from another would let the {@code
     * available} number in a shortfall describe a bank that no longer exists, and §7.2 property 2
     * makes that number the one thing she is invited to go and check.
     *
     * <p>Order: the criteria are validated <b>before</b> the course guard runs. A malformed grid
     * is her typing and a course that is not hers is her scope, and answering the scope question
     * first would tell a teacher probing courses which ones exist by the shape of the refusal.
     * {@code requireTeachesCourse} <b>throws</b> here, as it does on create, because she supplied
     * the course and a {@code FORBIDDEN} naming it discloses nothing she did not type.
     *
     * @param session the session inside the reading transaction
     * @param caller  the authenticated teacher or coordinator
     * @param request the criteria as they arrived
     * @return the proposal, the report, or the sentence refusing the criteria
     * @throws server.core.AuthorizationException {@code FORBIDDEN} when the course is not hers
     */
    public AutoOutcome autoCompose(Session session, CallerContext caller,
                                   AutoComposeRequest request) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(request, "request");

        Optional<ExamValidator.Violation> courseProblem =
                ExamValidator.courseProblem(request.courseCode());
        if (courseProblem.isPresent()) {
            return AutoOutcome.invalid(courseProblem.get());
        }
        Optional<ExamValidator.Violation> quotaProblem = ExamValidator.quotaProblem(request);
        if (quotaProblem.isPresent()) {
            return AutoOutcome.invalid(quotaProblem.get());
        }
        Authorization.requireTeachesCourse(caller, request.courseCode(),
                (teacherId, code) -> courses.teaches(session, teacherId, code));

        List<AutoCandidate> pool = exams.findAutoCandidates(session, request.courseCode());
        AutoComposeResult result = AutoComposer.compose(request, pool);

        // The seed is logged whether or not it was hers, which is what makes §7.5's promise real:
        // a teacher who says "it gave me a strange set" can have that exact set reproduced.
        log.debug("Auto-compose in course {} for user {}: feasible={}, {} questions, {} "
                        + "shortfalls, seed={}", request.courseCode(), caller.userId(),
                result.feasible(), result.questionCount(), result.shortfallCount(),
                request.seed());
        return AutoOutcome.ok(result);
    }

    public BuildOutcome create(Session session, CallerContext caller, ExamCreateRequest request) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(request, "request");

        Optional<ExamValidator.Violation> courseProblem =
                ExamValidator.courseProblem(request.courseCode());
        if (courseProblem.isPresent()) {
            return BuildOutcome.invalid(courseProblem.get());
        }
        // Throws FORBIDDEN naming the course, which is the one verb where that tells her nothing
        // she did not supply herself. Against this transaction's data, never the process-wide
        // directory, so the answer comes from the same moment as the rows it is about.
        Authorization.requireTeachesCourse(caller, request.courseCode(),
                (teacherId, code) -> courses.teaches(session, teacherId, code));

        Checked checked = metadataAndComposition(session, request.name(),
                request.durationMinutes(), request.studentText(), request.teacherText(),
                request.questions(), request.courseCode());
        if (checked.violation().isPresent()) {
            return BuildOutcome.invalid(checked.violation().get());
        }

        long examId = exams.insertExam(session, request.courseCode(), caller.userId());
        long versionId = exams.insertDraftVersion(session, examId, FIRST_VERSION, request.name(),
                request.durationMinutes(), request.studentText(), request.teacherText(),
                clock.instant());
        exams.replaceComposition(session, versionId,
                pinsOf(request.questions(), checked.candidates()));

        log.debug("Exam {} created in course {} by user {}", examId, request.courseCode(),
                caller.userId());
        return readBack(session, versionId);
    }

    // ===================== EXAM_VERSION_GET ===============================

    /**
     * One version, for the builder or the read-only history panel (E7.7, E7.14).
     *
     * <p>Serves both screens deliberately: the client decides what is editable from
     * {@code state}, so a past version and a live draft can never render from two shapes that
     * drift.
     *
     * @param session a session inside the caller's transaction
     * @param caller  the authenticated author
     * @param request which version
     * @return the composition, or {@code NOT_FOUND}
     */
    public BuildOutcome get(Session session, CallerContext caller, ExamVersionRequest request) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(request, "request");
        return authoredHeader(session, caller, request.examVersionId())
                .map(header -> readBack(session, header.examVersionId()))
                .orElseGet(BuildOutcome::notFound);
    }

    // ===================== EXAM_VERSION_SAVE (E7.2, E7.3) =================

    /**
     * Replaces a draft's metadata and composition (E7.2, E7.3, §5.6).
     *
     * <p>Order is the contract's and each step earns its place: scope, then state, then the
     * optimistic token, then the rules, then the write. State before token because "you cannot
     * edit a submitted version" is a more useful thing to be told than "somebody changed it",
     * and both are true of a version that was approved while she typed.
     *
     * @param session a session inside the caller's transaction
     * @param caller  the authenticated author
     * @param save    the version as submitted
     * @return the version as stored, or why it was refused
     */
    public BuildOutcome save(Session session, CallerContext caller, ExamVersionSave save) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(save, "save");

        Optional<ExamCompositionHeader> header =
                authoredHeader(session, caller, save.examVersionId());
        if (header.isEmpty()) {
            return BuildOutcome.notFound();
        }
        Optional<ExamVersion> row = exams.findVersionToWrite(session, save.examVersionId());
        if (row.isEmpty()) {
            return BuildOutcome.notFound();
        }
        ExamVersion version = row.get();

        if (version.getStatus() != ExamVersionStatus.DRAFT) {
            return BuildOutcome.conflict(ExamBuildMessages.NOT_A_DRAFT);
        }
        // SAVE's own consult. Pinned by its own test, per the per-verb ruling.
        Optional<LockHolder> heldOnSave = lockHolderOtherThan(caller, version);
        if (heldOnSave.isPresent()) {
            return BuildOutcome.conflict(
                    ExamBuildMessages.lockedBy(heldOnSave.get().displayName()));
        }
        if (version.getLockVersion() != save.expectedLockVersion()) {
            return BuildOutcome.conflict(ExamBuildMessages.STALE_VERSION);
        }

        Checked checked = metadataAndComposition(session, save.name(), save.durationMinutes(),
                save.studentText(), save.teacherText(), save.questions(),
                header.get().courseCode());
        if (checked.violation().isPresent()) {
            return BuildOutcome.invalid(checked.violation().get());
        }

        // The entity's own method rather than four setters: its javadoc names this verb as the
        // one writer of those four fields together, and says the status check is mine.
        version.editDraft(save.name(), save.durationMinutes(), save.studentText(),
                save.teacherText());
        exams.replaceComposition(session, save.examVersionId(),
                pinsOf(save.questions(), checked.candidates()));

        log.debug("Exam version {} saved by user {}", save.examVersionId(), caller.userId());
        return readBack(session, save.examVersionId());
    }

    // ===================== EXAM_VERSION_REVISE (E7.5) =====================

    /**
     * Opens a new DRAFT from a version that is no longer editable (E7.5, C-2, ADR-011).
     *
     * <p>Copies the metadata and the whole composition of its predecessor, so she starts from what
     * was approved rather than from nothing. {@code rejectedReason} is deliberately not copied: it
     * belongs to the version that was rejected, and a fresh draft wearing a stale refusal is a
     * screen nobody can explain.
     *
     * @param session a session inside the caller's transaction
     * @param caller  the authenticated author
     * @param action  which version to revise, with its expected token
     * @return the new DRAFT, or why it was refused
     */
    public BuildOutcome revise(Session session, CallerContext caller, ExamVersionAction action) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(action, "action");

        Optional<ExamCompositionHeader> header =
                authoredHeader(session, caller, action.examVersionId());
        if (header.isEmpty()) {
            return BuildOutcome.notFound();
        }
        Optional<ExamVersion> row = exams.findVersionToWrite(session, action.examVersionId());
        if (row.isEmpty()) {
            return BuildOutcome.notFound();
        }
        ExamVersion previous = row.get();

        if (previous.getStatus() == ExamVersionStatus.DRAFT) {
            // She edits and saves instead: the version she addressed is already the thing revise
            // would make her. This check is about the ADDRESSED version; the one below is about
            // the exam, and the two are not the same question.
            return BuildOutcome.conflict(ExamBuildMessages.ALREADY_A_DRAFT);
        }
        // ONE OPEN DRAFT PER EXAM (§5.4 as amended 2026-08-25, the lead's ruling).
        //
        // The check above is not this one and never was. It reads the version she addressed, so
        // revising an approved v1 while v3 sat unfinished walked straight past it and inserted a
        // second draft. Nothing underneath refuses that: two DRAFT rows of one exam break no
        // constraint, uq_exam_versions_no is satisfied by the new number, and the composition
        // copy is valid. It became reachable when E7.10's list started rendering a card per
        // version with Revise on every non-draft, which is where a cold read found it.
        //
        // The rule is the lead's, and it earns its place beyond tidiness: E7.11's builder opens
        // "the draft" of an exam, and with two of them that phrase has no referent. Refusing at
        // the write is what lets every reader downstream say "the draft" and mean something.
        OptionalInt openDraft = exams.findOpenDraftVersionNo(session, header.get().examId());
        if (openDraft.isPresent()) {
            return BuildOutcome.conflict(
                    ExamBuildMessages.draftAlreadyOpen(openDraft.getAsInt()));
        }
        // REVISE's own consult. A revision reads the predecessor's whole composition forward, so
        // somebody editing it is somebody whose work would be copied mid-write.
        Optional<LockHolder> heldOnRevise = lockHolderOtherThan(caller, previous);
        if (heldOnRevise.isPresent()) {
            return BuildOutcome.conflict(
                    ExamBuildMessages.lockedBy(heldOnRevise.get().displayName()));
        }
        if (previous.getLockVersion() != action.expectedLockVersion()) {
            return BuildOutcome.conflict(ExamBuildMessages.STALE_VERSION);
        }

        // A revision IS a new exam version, so ARCHITECTURE section 5's rule binds it: "adding a
        // soft-deleted question to a new exam version is a service-rule rejection (E7
        // validator)". Copying the composition forward without this check walked straight past
        // it, and nothing underneath would have refused: soft delete is an UPDATE, so no foreign
        // key fires. The other four rules survive the copy - points, course and duplicates are
        // preserved by construction, and a hard delete is blocked by ON DELETE RESTRICT - which
        // is exactly what made this one easy to miss.
        List<PinnedQuestion> carrying = exams.findComposition(session, action.examVersionId());
        Optional<BuildOutcome> retired = refuseRetiredQuestions(session, carrying);
        if (retired.isPresent()) {
            return retired.get();
        }

        long examId = header.get().examId();
        int nextVersionNo = exams.findLatestVersionNo(session, examId) + 1;
        long versionId = exams.insertDraftVersion(session, examId, nextVersionNo,
                previous.getName(), previous.getDurationMinutes(), previous.getStudentText(),
                previous.getTeacherText(), clock.instant());
        exams.replaceComposition(session, versionId, carriedForward(carrying));

        log.debug("Exam {} revised to version {} by user {}", examId, nextVersionNo,
                caller.userId());
        return readBack(session, versionId);
    }

    // ===================== EXAM_SUBMIT (E7.6) =============================

    /**
     * Moves a DRAFT to PENDING and hands off to E8 (E7.6, §5.5).
     *
     * <p><b>E7 owns the transition; E8 owns everything the queue sees.</b> This method flips the
     * status and nothing else. It emits no notification of its own, because §5.5
     * and the approval contract's E8.2 both say the supersede, the supersede notice and the
     * approval request come from that one hook: splitting them would let E7 announce a request for
     * a version whose supersede failed.
     *
     * <p><b>The points rule is re-checked here, and that is a test rather than a restatement.</b>
     * Section 1's invariant means no stored version can fail it, so this check should never fire.
     * If it ever does, the invariant is false and the log line below is the only place that would
     * say so.
     *
     * <h3>Why the hook is NOT called from here, which deviates from §5.5's letter</h3>
     *
     * <p><b>Calling it here notifies nobody at all.</b> This was written the other way first and a
     * cold read caught it. {@code JpaApprovalStore.inTx} goes through
     * {@code Transactions.inTx(factory, ...)}, which opens a <em>fresh session</em>, so the hook
     * runs on another connection and cannot see this transaction's uncommitted flip. It then
     * takes its own guard - {@code if (!version.isPending())} - reads the row as still
     * {@code DRAFT}, logs a warning and returns {@code Superseded.none()}, which short-circuits
     * before either notification. The coordinator's queue is never told, F4.1 is dead, and the
     * only trace is a WARN line.
     *
     * <p>An earlier version of this javadoc said the hazard was that "its notifications are not
     * rolled back". That was wrong in the direction that stops a reader looking: there are no
     * notifications to roll back. E7 is the hook's first production caller - every other call is
     * a test invoking it standalone, already committed - which is why nothing had exercised it.
     *
     * <p>So the transition is this method's and <b>the hook is the handler's, after commit</b>,
     * which is one of the two fixes §5.5's own text allows. {@code EXAM_SUBMIT}'s handler calls
     * {@code approvals.versionSubmitted(examVersionId)} once its transaction has committed and
     * this outcome came back {@code OK}. <b>Flagged for the lead</b>: the alternative is for the
     * hook to join the caller's session, which is a change inside E8 and his to make. Either way
     * E7 still owns the transition and E8 still owns everything the queue sees; only the moment
     * moves.
     *
     * @param session a session inside the caller's transaction
     * @param caller  the authenticated author
     * @param action  which version to submit, with its expected token
     * @return the version now PENDING, or why it was refused
     */
    public BuildOutcome submitForApproval(Session session, CallerContext caller,
                                          ExamVersionAction action) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(action, "action");

        Optional<ExamCompositionHeader> header =
                authoredHeader(session, caller, action.examVersionId());
        if (header.isEmpty()) {
            return BuildOutcome.notFound();
        }
        Optional<ExamVersion> row = exams.findVersionToWrite(session, action.examVersionId());
        if (row.isEmpty()) {
            return BuildOutcome.notFound();
        }
        ExamVersion version = row.get();

        if (version.getStatus() != ExamVersionStatus.DRAFT) {
            return BuildOutcome.conflict(ExamBuildMessages.NOT_SUBMITTABLE);
        }
        // SUBMIT's own consult, and the one with the most to lose: submitting a draft somebody
        // else has open sends her half-finished paper to a coordinator.
        Optional<LockHolder> heldOnSubmit = lockHolderOtherThan(caller, version);
        if (heldOnSubmit.isPresent()) {
            return BuildOutcome.conflict(
                    ExamBuildMessages.lockedBy(heldOnSubmit.get().displayName()));
        }
        if (version.getLockVersion() != action.expectedLockVersion()) {
            return BuildOutcome.conflict(ExamBuildMessages.STALE_VERSION);
        }

        Optional<ExamValidator.Violation> points =
                ExamValidator.pointsProblem(storedPins(session, action.examVersionId()));
        if (points.isPresent()) {
            log.error("Stored exam version {} fails the points rule on submit: {}. Contract "
                            + "section 1's invariant is false.",
                    action.examVersionId(), points.get().message());
            return BuildOutcome.invalid(points.get());
        }

        version.submitForApproval();
        // No hook call here, and that is the fix rather than an omission: see this method's
        // javadoc. The handler runs it after commit, because from inside this transaction it
        // reads the row as DRAFT and returns without notifying anybody.
        log.debug("Exam version {} submitted by user {}; the approval hook is the handler's, "
                + "after commit", action.examVersionId(), caller.userId());
        return readBack(session, action.examVersionId());
    }

    // ===================== Shared =========================================

    /**
     * The stored version this caller may act on, or empty when she may not know it exists.
     *
     * <p>One method for every verb but create, so the five cannot drift into different answers
     * about the same version. It folds unknown and not-hers into one empty result on purpose, per
     * contract §2: both are indistinguishable to her, so neither can be used to enumerate exams
     * or to learn who wrote one.
     *
     * @param session       the current session
     * @param caller        the authenticated caller
     * @param examVersionId the version addressed
     * @return its header, or empty
     */
    private Optional<ExamCompositionHeader> authoredHeader(Session session, CallerContext caller,
                                                           long examVersionId) {
        return exams.findCompositionHeader(session, examVersionId)
                .filter(header -> header.authorId() == caller.userId());
    }

    /**
     * The metadata and composition rules, in the order both writers run them.
     *
     * <p>One method because create and save enforce an identical set and a second copy would be a
     * second chance to leave one rule out of one verb. The single database read for the whole
     * composition happens here, which is also why {@link ExamValidator#compositionProblem} takes
     * rows rather than a session: the fetch belongs to whoever holds the transaction.
     */
    /**
     * What the rules decided, and the rows they decided it from.
     *
     * <p>The rows ride back out because {@link #pinsOf} needs the same {@code questionId} the
     * composition rules just resolved, and fetching them twice would be two answers to one
     * question. Carrying them is also what makes the write structurally unable to invent one.
     *
     * @param violation the first rule broken, or empty
     * @param candidates the rows the store returned, empty when a rule failed before the fetch
     */
    private record Checked(Optional<ExamValidator.Violation> violation,
                           List<PinCandidate> candidates) {

        static Checked refused(Optional<ExamValidator.Violation> violation) {
            return new Checked(violation, List.of());
        }
    }

    private Checked metadataAndComposition(
            Session session, String name, int durationMinutes, String studentText,
            String teacherText, List<QuestionPin> questions, String courseCode) {

        Optional<ExamValidator.Violation> metadata =
                ExamValidator.metadataProblem(name, durationMinutes, studentText, teacherText);
        if (metadata.isPresent()) {
            return Checked.refused(metadata);
        }
        // Points before composition: it is the rule that refuses a null element and an empty
        // list, and compositionProblem reads pin.questionVersionId() off every entry.
        Optional<ExamValidator.Violation> points = ExamValidator.pointsProblem(questions);
        if (points.isPresent()) {
            return Checked.refused(points);
        }
        List<PinCandidate> candidates =
                exams.findPinCandidates(session, ExamValidator.pinnedVersionIds(questions));
        return new Checked(
                ExamValidator.compositionProblem(questions, candidates, courseCode), candidates);
    }

    /**
     * The wire's pins as the store's, ordinals 1-based in the order she arranged them.
     *
     * <p><b>{@code questionId} is resolved here, from the rows the composition rules already
     * fetched, and this is load-bearing rather than tidy.</b> An earlier version passed {@code 0}
     * with a comment claiming the store resolved it. The store does no such thing:
     * {@code replaceComposition} persists the field verbatim, and {@code exam_version_questions}
     * carries a <em>composite</em> foreign key onto {@code question_versions (id, question_id)}
     * plus a unique key on {@code (exam_version_id, question_id)}. A zero therefore matched no
     * parent row and, had it somehow inserted, two questions would have collided at
     * {@code (versionId, 0)}. Every create and every save died on the first acceptance case.
     *
     * <p>Found by a cold read rather than by a test, because the two suites that touch this seam
     * never cross it: the repository's own contract test builds pins with real resolved ids, and
     * the service's tests mock the repository. That is why the caller now captures the pin list
     * and asserts what is in it.
     *
     * @param questions  the pins as she arranged them
     * @param candidates the rows already fetched for the composition rules
     * @return the store's pins, every one carrying the question that owns its version
     */
    private static List<ExamBuildRepository.Pin> pinsOf(List<QuestionPin> questions,
                                                        List<PinCandidate> candidates) {
        Map<Long, Long> ownerByVersionId = new HashMap<>();
        for (PinCandidate candidate : candidates) {
            ownerByVersionId.put(candidate.questionVersionId(), candidate.questionId());
        }
        List<ExamBuildRepository.Pin> pins = new ArrayList<>(questions.size());
        for (int i = 0; i < questions.size(); i++) {
            QuestionPin pin = questions.get(i);
            Long owner = ownerByVersionId.get(pin.questionVersionId());
            if (owner == null) {
                // Unreachable: compositionProblem refuses an unknown version id before this runs.
                // Stated rather than assumed, because the alternative is writing a 0 again.
                throw new IllegalStateException("no question owns version " + pin.questionVersionId()
                        + "; the composition rules should have refused this already");
            }
            pins.add(new ExamBuildRepository.Pin(pin.questionVersionId(), owner, pin.points(),
                    i + 1));
        }
        return pins;
    }

    /**
     * Refuses a revision whose predecessor pins a question since retired from the bank.
     *
     * <p><b>Refuse rather than silently drop</b>, and that choice is worth stating because the
     * alternative is defensible. Dropping the question would leave the points short of 100 and
     * the teacher would meet a sum she did not cause, on a screen she has not edited yet.
     * Refusing names the question and tells her what to do. If the lead prefers the drop, it is
     * this method and one sentence.
     *
     * @param session  the current session
     * @param carrying the predecessor's composition
     * @return a refusal naming the first retired question, or empty when all are live
     */
    private Optional<BuildOutcome> refuseRetiredQuestions(Session session,
                                                          List<PinnedQuestion> carrying) {
        List<Long> versionIds = new ArrayList<>(carrying.size());
        for (PinnedQuestion question : carrying) {
            versionIds.add(question.questionVersionId());
        }
        for (PinCandidate candidate : exams.findPinCandidates(session, versionIds)) {
            if (candidate.deleted()) {
                return Optional.of(BuildOutcome.invalid(new ExamValidator.Violation(
                        ExamValidator.FIELD_QUESTIONS,
                        ExamBuildMessages.questionDeleted(candidate.questionDisplayId5()))));
            }
        }
        return Optional.empty();
    }

    /** A predecessor's composition, carried onto its revision unchanged. */
    private static List<ExamBuildRepository.Pin> carriedForward(List<PinnedQuestion> stored) {
        List<ExamBuildRepository.Pin> pins = new ArrayList<>(stored.size());
        for (PinnedQuestion question : stored) {
            pins.add(new ExamBuildRepository.Pin(question.questionVersionId(),
                    question.questionId(), question.points(), question.ord()));
        }
        return pins;
    }

    /** The stored composition as wire pins, for the submit-time re-check of the points rule. */
    private List<QuestionPin> storedPins(Session session, long examVersionId) {
        List<QuestionPin> pins = new ArrayList<>();
        for (PinnedQuestion question : exams.findComposition(session, examVersionId)) {
            pins.add(new QuestionPin(question.questionVersionId(), question.points()));
        }
        return pins;
    }

    /**
     * The answer every verb gives, read back from the database rather than assembled.
     *
     * @param session       the current session
     * @param examVersionId the version to answer with
     * @return the composition, or {@code NOT_FOUND} when the read finds nothing
     */
    private BuildOutcome readBack(Session session, long examVersionId) {
        return exams.findCompositionHeader(session, examVersionId)
                .map(header -> BuildOutcome.ok(compositionOf(header,
                        exams.findComposition(session, examVersionId))))
                .orElseGet(BuildOutcome::notFound);
    }

    private static ExamComposition compositionOf(ExamCompositionHeader header,
                                                 List<PinnedQuestion> questions) {
        List<ComposedQuestion> composed = new ArrayList<>(questions.size());
        for (PinnedQuestion question : questions) {
            composed.add(new ComposedQuestion(question.questionVersionId(),
                    question.questionDisplayId5(), question.ord(), question.points(),
                    question.text(), question.topic(), difficultyOf(question.difficulty()),
                    question.hasImage(), question.pinnedVersionNo(), question.latestVersionNo(),
                    question.latestVersionId()));
        }
        return new ExamComposition(header.examId(), header.displayId6(), header.courseCode(),
                header.courseName(), header.examVersionId(), header.versionNo(),
                stateOf(header.status()), header.name(), header.durationMinutes(),
                header.studentText(), header.teacherText(), header.authorName(),
                header.createdAt(), rejectionOf(header.rejectedReason()), composed,
                header.lockVersion());
    }

    /**
     * The column's empty as the wire's empty, and the two are not the same.
     *
     * <p>{@code exam_versions.rejected_reason} is nullable: a version nobody has rejected has no
     * reason, and the store's {@code insertDraftVersion} leaves it null on purpose.
     * {@link ExamComposition} refuses null for that field just as deliberately, and its javadoc
     * gives the reason: {@code ""} is its empty, because a nullable field "would have the screen
     * guessing which empty it is looking at".
     *
     * <p>So this bridge is load-bearing rather than defensive, and it is the whole of the
     * difference between the two. <b>Do not simplify it away.</b> Passing the column through
     * unchanged throws on every draft, every pending version and every approved one, which is to
     * say on all of them but the rejected: the failure would arrive on the main path, not on an
     * edge.
     *
     * @param stored the column's value, or {@code null} when nothing was rejected
     * @return the wire's value, never null
     */
    private static String rejectionOf(String stored) {
        return stored == null ? "" : stored;
    }

    /**
     * The stored difficulty as the wire's.
     *
     * <p>A switch rather than the {@code valueOf(name())} the bank's mappers use. Both work today
     * and they fail differently: {@code valueOf} throws at runtime, in front of a teacher, on the
     * first row carrying a constant the wire does not have, while this stops compiling the moment
     * either enum grows one. The bank's form is not wrong, but this is the shape worth copying.
     *
     * @param difficulty the stored difficulty
     * @return the wire difficulty
     */
    private static common.dto.bank.Difficulty difficultyOf(
            server.db.entities.Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> common.dto.bank.Difficulty.EASY;
            case MEDIUM -> common.dto.bank.Difficulty.MEDIUM;
            case HARD -> common.dto.bank.Difficulty.HARD;
        };
    }

    /**
     * The server's status as the wire's state.
     *
     * <p>Mapped by name rather than by ordinal, and exhaustively rather than with a default. The
     * two enums carry the same four constants today; if either grows a fifth, this switch stops
     * compiling, which is the moment somebody should be deciding what the wire says about it
     * rather than the moment a teacher sees the wrong chip.
     *
     * @param status the stored status
     * @return the wire state
     */
    private static ApprovalState stateOf(ExamVersionStatus status) {
        return switch (status) {
            case DRAFT -> ApprovalState.DRAFT;
            case PENDING -> ApprovalState.PENDING;
            case APPROVED -> ApprovalState.APPROVED;
            case REJECTED -> ApprovalState.REJECTED;
        };
    }
}
