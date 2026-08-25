package server.db.repos;

import org.hibernate.Session;
import server.db.entities.Exam;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionQuestion;
import server.db.entities.ExamVersionStatus;
import server.db.ids.AllocatedId;
import server.db.ids.ExamIdAllocator;
import server.db.projections.AuthoredExamHeader;
import server.db.projections.AuthoredVersionRow;
import server.db.projections.ExamCompositionHeader;
import server.db.projections.PinCandidate;
import server.db.projections.PinnedQuestion;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The exam builder's write surface, and the reads only it needs (E7).
 *
 * <p><b>Why this is not {@link ExamRepository}.</b> That class is the drawer's read side and is
 * shared by E8's approval queue, E9's release screen, E14's results and E15's data browser. E7 is
 * the only writer of {@code exams}, {@code exam_versions} and {@code exam_version_questions}, and
 * putting the writes beside a dozen readers owned by other epics would make one file the meeting
 * point of four lanes. The split is by direction and owner, not by table, so a reader here would
 * be the mistake: where E7 needs a read that already exists, it calls the existing one. The exam
 * list's per-version question counts are the worked example, and they come from
 * {@link ExamRepository#countQuestionsByVersion}.
 *
 * <p><b>Every write here assumes it is inside a transaction the service opened.</b> The contract's
 * §5.6 makes create, save, revise and submit one transaction each, because a half-written
 * composition is a version that violates the sum-to-100 invariant while looking valid, which is
 * the failure the whole contract is arranged around. Nothing in this class opens one.
 *
 * <p><b>Nothing here returns a type that can hold an answer key.</b> The composition reads build
 * projections through constructor expressions that never name {@code correct_answer}, so the
 * column is not fetched and no caller can reach it. That is why no method here carries a
 * sanctioned {@code ForAuthoring} suffix: the suffix licenses a read that really does return a
 * key, and spending it on a read that does not would make the licence mean less.
 */
public final class ExamBuildRepository {

    private final ExamIdAllocator ids = new ExamIdAllocator();

    /**
     * One question about to be written onto a paper.
     *
     * <p>Server-side glue rather than the wire's {@code QuestionPin}: by the time a pin reaches
     * this class its {@code questionVersionId} has been resolved to an owning {@code questionId}
     * (see {@link #findPinCandidates}), and that resolved pair is what the composite foreign key
     * and the duplicate constraint are written against. Taking the wire record here would mean
     * taking a shape whose {@code questionId} the client never sent.
     *
     * @param questionVersionId the pinned {@code question_versions} row
     * @param questionId        its owning question, resolved server-side and never trusted
     * @param points            this question's points, 1..100
     * @param ord               position on the paper, 1-based
     */
    public record Pin(long questionVersionId, long questionId, int points, int ord) {
    }

    // ===================== Writes =========================================

    /**
     * Creates the exam row and allocates its 6-digit display id (E7.1 — S-10, F3.4).
     *
     * <p>The allocation is {@link ExamIdAllocator}'s, which locks the course row and reads the
     * subject from it, so the serial cannot be handed to two teachers creating an exam in the
     * same course at the same moment. This method adds no locking of its own; adding some would
     * be a second answer to a question that already has one.
     *
     * <p>Flushes so the generated id is available to the caller, which needs it immediately to
     * write version 1 against it.
     *
     * <p>Consumer: {@code ExamService.create} ({@code EXAM_CREATE}).
     *
     * @param session    the session inside the creating transaction
     * @param courseCode the exam's course, already checked against the caller's taught set
     * @param authorId   the authenticated caller, recorded as the author for ruling 2's guard
     * @return the new {@code exams} row id
     * @throws IllegalArgumentException when no such course exists
     * @throws IllegalStateException    when the course has used all 99 serials
     */
    public long insertExam(Session session, String courseCode, long authorId) {
        AllocatedId allocated = ids.allocate(session, courseCode);
        Exam exam = new Exam(courseCode, (byte) allocated.serial(), allocated.displayId(), authorId);
        session.persist(exam);
        session.flush();
        return exam.getId();
    }

    /**
     * Writes a new {@code DRAFT} version row (E7.1, E7.5).
     *
     * <p>Always {@code DRAFT}: both callers create drafts. {@code EXAM_CREATE} makes version 1,
     * and {@code EXAM_VERSION_REVISE} makes {@code latestVersionNo + 1} from an approved, pending
     * or rejected predecessor. A status parameter here would let a caller write a version straight
     * into {@code PENDING} and skip the transition that notifies the coordinator.
     *
     * <p>{@code rejectedReason} is deliberately not a parameter and is left null. The contract's
     * §5.4 says it is not copied onto a revision: it belongs to the version that was rejected, and
     * carrying it forward would show a fresh draft wearing a stale refusal.
     *
     * <p>Consumers: {@code ExamService.create} and {@code ExamService.revise}.
     *
     * @param session         the session inside the writing transaction
     * @param examId          the exam this version belongs to
     * @param versionNo       its version number, from {@link #findLatestVersionNo} plus one
     * @param name            the exam's name, already validated
     * @param durationMinutes the sitting length, already validated
     * @param studentText     the student-facing block, or {@code null}
     * @param teacherText     the teacher-only block, or {@code null}
     * @param createdAt       the service clock's reading
     * @return the new {@code exam_versions} row id
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public long insertDraftVersion(Session session, long examId, int versionNo, String name,
                                   int durationMinutes, String studentText, String teacherText,
                                   Instant createdAt) {
        ExamVersion version = new ExamVersion(examId, versionNo, name, durationMinutes,
                studentText, teacherText, ExamVersionStatus.DRAFT, createdAt);
        session.persist(version);
        session.flush();
        return version.getId();
    }

    /**
     * Replaces a version's whole composition (E7.2 — §5.6).
     *
     * <p>Full replace rather than a diff, as ARCHITECTURE §5 specifies. A diff would have to
     * express "this question moved from position 3 to position 1" as an update, and
     * {@code uq_exam_version_questions_ord} forbids two rows sharing an ordinal for the length of
     * the statement that swaps them. Deleting and reinserting has no intermediate state to
     * violate.
     *
     * <p><b>No flush is needed between the delete and the inserts, and this was measured rather
     * than assumed.</b> A bulk {@code createMutationQuery} delete is not queued the way
     * {@link org.hibernate.Session#remove} is; it executes against the database when
     * {@code executeUpdate} is called, so the rows are gone before the first insert is enqueued
     * and a save that re-pins the same question at a new ordinal cannot collide with the row it
     * is replacing. An earlier version of this method flushed here and said it had to; removing
     * the flush left all of {@code ExamBuildRepositoryContract} and the MySQL leaf's constraint
     * tests green, which is the evidence the claim did not have.
     *
     * <p><b>That claim is scoped to one call per transaction, which is what §5.6 specifies, and the
     * scope was measured too.</b> The same property it rests on - a bulk delete goes straight to
     * the database - is also why it does <em>not</em> evict anything from the persistence context.
     * {@link ExamVersionQuestion} has an assigned composite id, so instances persisted by an
     * earlier call in the same session stay managed under those keys. Calling this twice in one
     * transaction against the same version throws {@code EntityExistsException} wrapping
     * {@link org.hibernate.NonUniqueObjectException} on both engines, before any SQL is attempted;
     * {@code ExamBuildRepositoryContract.twoCompositionWritesInOneTransactionAreRefused} is that
     * measurement. No supported path does it, and a compose-then-adjust path that wanted to would
     * need a {@code session.clear()} or a second transaction.
     *
     * <p>Consumers: {@code ExamService.create}, {@code save} and {@code revise}.
     *
     * @param session       the session inside the writing transaction
     * @param examVersionId the version whose composition is being written
     * @param pins          the paper, in order; already validated and already resolved
     */
    public void replaceComposition(Session session, long examVersionId, List<Pin> pins) {
        session.createMutationQuery("""
                        delete from ExamVersionQuestion evq
                        where evq.id.examVersionId = :examVersionId
                        """)
                .setParameter("examVersionId", examVersionId)
                .executeUpdate();

        for (Pin pin : pins) {
            session.persist(new ExamVersionQuestion(examVersionId, pin.questionVersionId(),
                    pin.questionId(), pin.points(), pin.ord()));
        }
    }

    // ===================== Reads the builder needs ========================

    /**
     * The version row itself, for the writes that change its state (E7.2, E7.5, E7.6).
     *
     * <p>The entity rather than a projection, and the only method here that returns one, because
     * these three callers <em>mutate</em> it: {@code save} rewrites the metadata through
     * {@link ExamVersion#editDraft}, {@code submit} calls {@link ExamVersion#submitForApproval},
     * and {@code revise} reads the predecessor's fields to copy them. A projection cannot be
     * written back, and the {@code @Version} column that makes {@code expectedLockVersion} mean
     * anything is only enforced by Hibernate on a managed instance.
     *
     * <p>Carries no answer key: {@code exam_versions} has no correctness column, and the questions
     * are a separate table read separately.
     *
     * <p>Consumers: {@code ExamService.save}, {@code revise} and {@code submitForApproval}.
     *
     * @param session       the session inside the current transaction
     * @param examVersionId the version to load
     * @return the managed entity, or empty when there is no such version
     */
    public Optional<ExamVersion> findVersionToWrite(Session session, long examVersionId) {
        return Optional.ofNullable(session.get(ExamVersion.class, examVersionId));
    }

    /**
     * The highest version number an exam has (E7.5).
     *
     * <p>Returns {@code 0} for an exam with no versions, so the caller's {@code + 1} yields
     * version 1 without a special case. That state is not reachable through this class - a create
     * writes the exam and its first version in one transaction - but the query answers it rather
     * than returning an empty that the caller would have to decide about.
     *
     * <p>Consumer: {@code ExamService.revise}, which writes {@code latestVersionNo + 1} against
     * {@code uq_exam_versions_no}.
     *
     * @param session the session inside the current transaction
     * @param examId  the exam
     * @return the highest version number, or 0 when the exam has none
     */
    public int findLatestVersionNo(Session session, long examId) {
        Integer highest = session.createQuery("""
                        select max(v.versionNo) from ExamVersion v
                        where v.examId = :examId
                        """, Integer.class)
                .setParameter("examId", examId)
                .uniqueResult();
        return highest == null ? 0 : highest;
    }

    /**
     * One version's metadata, with its exam, course and author (E7 — every verb that answers).
     *
     * <p>Every writing verb answers an {@code ExamComposition} re-read from the database rather
     * than assembled from the request (§5.5's last line), so this read is on the answering path of
     * all six verbs, not just {@code EXAM_VERSION_GET}. Re-reading is what makes the answer show
     * what was actually stored, including the {@code lockVersion} the next write must send back.
     *
     * <p>{@code authorId} rides along for ruling 2's author-only guard. The guard is the service's,
     * not this method's: a repository that refused to return other people's rows could not be used
     * by the coordinator reads that legitimately cross authors, and the refusal has to be
     * {@code NOT_FOUND} rather than an empty that the caller might read as "no such exam".
     *
     * <p>Consumers: all six of E7's composition verbs.
     *
     * @param session       the session inside the current transaction
     * @param examVersionId the version to read
     * @return its header, or empty when there is no such version
     */
    public Optional<ExamCompositionHeader> findCompositionHeader(Session session,
                                                                 long examVersionId) {
        return session.createQuery("""
                        select new server.db.projections.ExamCompositionHeader(
                            e.id, e.displayId, e.courseCode, c.name,
                            e.authorId, u.fullName,
                            v.id, v.versionNo, v.status, v.name, v.durationMinutes,
                            v.studentText, v.teacherText, v.rejectedReason,
                            v.createdAt, v.lockVersion)
                        from ExamVersion v, Exam e, Course c, User u
                        where v.id = :examVersionId
                          and e.id = v.examId
                          and c.code = e.courseCode
                          and u.id = e.authorId
                        """, ExamCompositionHeader.class)
                .setParameter("examVersionId", examVersionId)
                .uniqueResultOptional();
    }

    /**
     * The questions on one version, in paper order (E7.2, E7.7).
     *
     * <p>The {@code latestVersionNo} in each row is a correlated maximum over the question's own
     * versions, which is E7.7's badge: the builder marks a question whose bank version has moved
     * on since it was pinned. It is read here rather than by a second pass so the comparison
     * cannot be assembled from two reads taken at different moments, which would badge a question
     * that was updated between them and miss one updated just before.
     *
     * <p><b>The projection has nowhere to put {@code correct_answer} and this query never names
     * it.</b> A teacher composing a paper has no need of the key, and the type she gets back is
     * the reason rather than the query being careful.
     *
     * <p><b>This read is unscoped, and the caller owns the gate.</b> It takes a version id and
     * nothing else, so it will return the paper of an exam the caller did not author. Authorship
     * is established from {@link #findCompositionHeader}'s {@code authorId} before this is called,
     * and contract §6 makes the refusal {@code NOT_FOUND} rather than {@code FORBIDDEN} so that
     * "not yours" and "not there" stay indistinguishable. Said here as well as there because this
     * is the method that returns the questions, and a reader who arrives at it first would
     * otherwise not be told there is a gate at all.
     *
     * <p>Consumers: all six of E7's composition verbs, through the same re-read.
     *
     * @param session       the session inside the current transaction
     * @param examVersionId the version whose paper to read
     * @return the composition in ordinal order; empty when the version has no questions, which is
     *         a state only a half-written transaction could produce
     */
    public List<PinnedQuestion> findComposition(Session session, long examVersionId) {
        return session.createQuery("""
                        select new server.db.projections.PinnedQuestion(
                            q.id, qv.id, q.displayId, evq.ordinal, evq.points,
                            qv.text, qv.topic, qv.difficulty,
                            case when qv.image is null then false else true end,
                            qv.versionNo,
                            (select max(later.versionNo) from QuestionVersion later
                             where later.questionId = q.id))
                        from ExamVersionQuestion evq, QuestionVersion qv, Question q
                        where evq.id.examVersionId = :examVersionId
                          and qv.id = evq.id.questionVersionId
                          and q.id = qv.questionId
                        order by evq.ordinal
                        """, PinnedQuestion.class)
                .setParameter("examVersionId", examVersionId)
                .getResultList();
    }

    /**
     * Resolves the question versions a teacher wants to pin (E7.2, E7.8 — §5.2).
     *
     * <p>One read for the whole list rather than one per entry, because a composition is up to a
     * hundred questions and the validator checks every one of them. An unknown id is simply absent
     * from the result, which is how the caller detects it: the validator compares what it asked
     * for against what came back and names the <b>position in the list</b>, per §5.2, because the
     * caller is describing a composition and the thing not found is a field of her request.
     *
     * <p>Returns the owning {@code questionId} because the client never sends it and must not be
     * trusted for it. That resolution is what turns "the same question through two different
     * versions" into a duplicate the service can name (T-3.9) instead of a constraint violation.
     *
     * <p>Carries no answer key.
     *
     * <p>Consumer: {@code ExamValidator}, on the create, save and revise paths.
     *
     * @param session            the session inside the current transaction
     * @param questionVersionIds the ids from the request; {@code null} or empty yields an empty
     *                           list
     * @return one row per id that exists, in no guaranteed order
     */
    public List<PinCandidate> findPinCandidates(Session session,
                                                Collection<Long> questionVersionIds) {
        if (questionVersionIds == null || questionVersionIds.isEmpty()) {
            // An `in ()` is a syntax error on some engines and a full scan on others, and an
            // empty composition is a rule the validator refuses rather than a query to run.
            return List.of();
        }
        return session.createQuery("""
                        select new server.db.projections.PinCandidate(
                            qv.id, q.id, q.displayId, q.courseCode,
                            case when q.deletedAt is null then false else true end)
                        from QuestionVersion qv, Question q
                        where qv.id in (:ids)
                          and q.id = qv.questionId
                        """, PinCandidate.class)
                .setParameter("ids", questionVersionIds)
                .getResultList();
    }

    // ===================== The author's exam list (E7.10) =================

    /**
     * The exams a teacher wrote, newest display id last (E7.10 — F3.5, S-35).
     *
     * <p>Scoped on {@code exams.author} in the {@code WHERE} clause, which is where ruling 2's
     * author-only rule has to live for the same reason
     * {@link ExamRepository#findAuthoredSummaries} gives: a scope no handler can widen afterwards,
     * because the widening would have to happen in this query.
     *
     * <p>The name and version number come from the exam's highest version through a correlated
     * subquery rather than a join to "the approved version". An exam always has at least one
     * version, so no exam is left out, while joining on approval would silently drop every draft
     * and rejected exam - which on this screen are exactly the rows she is looking for.
     *
     * <p>Consumer: {@code ExamService.list} ({@code EXAM_LIST}).
     *
     * <h2>Newest exam first, which this query did not do until E7.10's screen ⚑</h2>
     *
     * <p>It ended {@code order by e.displayId} and was pinned that way on both engines. The
     * contract's ruling of 2026-08-25 settled the disagreement in the contract's favour and named
     * this PR or PR B as where the fix lands: {@code displayId6} is
     * {@code subjectCode + courseCode + serial}, so ascending sorted by subject, then course, then
     * <em>oldest</em> first within a course, and a teacher with exams in two courses got neither
     * recency nor anything else she was looking for.
     *
     * <p>Recency is the latest version's {@code createdAt}, because that is the moment she last
     * touched the exam and {@code v} is already bound to that version. <b>{@code e.id desc} is a
     * tiebreak, not decoration:</b> two versions written inside the same clock tick are ordinary
     * in a seeded database, and MySQL guarantees no row order for the ties without it, which is
     * the defect class that survived a whole green suite in #50.
     *
     * @param session  the session inside the current transaction
     * @param authorId the authenticated caller
     * @return her exams, the most recently touched first; empty when she has written none
     */
    public List<AuthoredExamHeader> findAuthoredExams(Session session, long authorId) {
        return session.createQuery("""
                        select new server.db.projections.AuthoredExamHeader(
                            e.id, e.displayId, e.courseCode, c.name, v.name, v.versionNo)
                        from Exam e, Course c, ExamVersion v
                        where e.authorId = :authorId
                          and c.code = e.courseCode
                          and v.examId = e.id
                          and v.versionNo = (
                              select max(later.versionNo) from ExamVersion later
                              where later.examId = e.id)
                        order by v.createdAt desc, e.id desc
                        """, AuthoredExamHeader.class)
                .setParameter("authorId", authorId)
                .getResultList();
    }

    /**
     * Every version of every exam a teacher wrote (E7.10 — C-2).
     *
     * <p>The same author scope as {@link #findAuthoredExams}, expressed against the exam the
     * version hangs off, so the two reads cannot disagree about whose exams these are.
     *
     * <p><b>Drafts included</b>, which is the difference from the approval-status list this screen
     * replaces. {@code MY_APPROVALS_GET} showed non-draft versions only, because a coordinator
     * never sees a draft; on the author's own list the draft she is working on is the row that
     * matters most.
     *
     * <p>One read for every exam on the screen rather than one per exam. The list opens with the
     * whole drawer expanded, so a query per exam would be the N+1 that is invisible on a seeded
     * demo and slow on a real teacher's account.
     *
     * <p>Ordered newest version first within each exam, which is the order the screen renders and
     * the same order the approval-status list used.
     *
     * <p>Consumer: {@code ExamService.list} ({@code EXAM_LIST}), which groups these under
     * {@link #findAuthoredExams} by {@code examId} and takes their question counts from
     * {@link ExamRepository#countQuestionsByVersion}.
     *
     * @param session  the session inside the current transaction
     * @param authorId the authenticated caller
     * @return her versions, grouped by exam in display-id order and newest first within an exam
     */
    public List<AuthoredVersionRow> findAuthoredVersions(Session session, long authorId) {
        return session.createQuery("""
                        select new server.db.projections.AuthoredVersionRow(
                            e.id, v.id, v.versionNo, v.status, v.rejectedReason,
                            v.durationMinutes, v.createdAt, v.lockVersion)
                        from ExamVersion v, Exam e
                        where e.id = v.examId
                          and e.authorId = :authorId
                        order by e.displayId, v.versionNo desc
                        """, AuthoredVersionRow.class)
                .setParameter("authorId", authorId)
                .getResultList();
    }
}
