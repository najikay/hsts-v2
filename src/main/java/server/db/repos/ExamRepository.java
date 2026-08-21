package server.db.repos;

import org.hibernate.Session;
import server.db.entities.Exam;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionQuestion;
import server.db.entities.ExamVersionStatus;
import server.db.projections.AuthoredExam;
import server.db.projections.ExamVersionContext;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads over {@code exams} and {@code exam_versions} (E2.11). */
public final class ExamRepository {

    /**
     * Looks an exam up by the 6-digit id people type (S-10).
     *
     * <p>Consumers: E7 exam builder; the E2.15 seed loader's idempotency check.
     *
     * @param session   the current session
     * @param displayId the 6-character display id
     * @return the exam, or empty
     */
    public Optional<Exam> findByDisplayId(Session session, String displayId) {
        return session.createQuery("from Exam where displayId = :displayId", Exam.class)
                .setParameter("displayId", displayId)
                .uniqueResultOptional();
    }

    /**
     * One version of an exam by its number.
     *
     * <p>Consumers: E7 composition and E8 approval, which both act on a named version rather
     * than "the latest" — an approval race that landed on the wrong version is exactly what
     * {@code lock_version} on {@code exam_versions} exists to stop.
     *
     * @param session   the current session
     * @param examId    the exam's internal id
     * @param versionNo the 1-based version number
     * @return the version, or empty
     */
    public Optional<ExamVersion> findVersion(Session session, long examId, int versionNo) {
        return session.createQuery("""
                        from ExamVersion where examId = :examId and versionNo = :versionNo
                        """, ExamVersion.class)
                .setParameter("examId", examId)
                .setParameter("versionNo", versionNo)
                .uniqueResultOptional();
    }

    /**
     * The questions an exam version pinned, in presentation order.
     *
     * <p>Returns the rows of {@code exam_version_questions} — the versions recorded when the exam
     * was built, never the latest version of each question. That distinction is the whole point:
     * grading an attempt against a question edited after release would silently rewrite past
     * grades (PRD §6, C-2).
     *
     * <p>Carries points and question-version ids only, no answer key, so it needs no sanctioned
     * suffix; the key comes separately from
     * {@code QuestionRepository#findVersionsForGrading}.
     *
     * <p>Consumer: E12.1's auto-grading.
     *
     * @param session       the current session
     * @param examVersionId the exam version
     * @return the pinned questions ordered by {@code ordinal}
     */
    public List<ExamVersionQuestion> findPinnedQuestions(Session session, long examVersionId) {
        return session.createQuery("""
                        from ExamVersionQuestion
                        where id.examVersionId = :examVersionId
                        order by ordinal
                        """, ExamVersionQuestion.class)
                .setParameter("examVersionId", examVersionId)
                .getResultList();
    }

    /**
     * Versions of an exam awaiting a coordinator's decision.
     *
     * <p>Consumer: E8's supersede rule (E8.2), which needs the pending siblings of the
     * version that has just been submitted.
     *
     * @param session the current session
     * @param examId  the exam's internal id
     * @return pending versions, oldest first
     */
    public List<ExamVersion> findPendingVersions(Session session, long examId) {
        return session.createQuery("""
                        from ExamVersion
                        where examId = :examId and status = :status
                        order by versionNo
                        """, ExamVersion.class)
                .setParameter("examId", examId)
                .setParameter("status", ExamVersionStatus.PENDING)
                .getResultList();
    }

    // ===================== E8: approval reads ============================
    // Added by E8 under TEAM_SPLIT rule 5 (repositories grow with their callers).
    // All three build the same ExamVersionContext projection, which carries no
    // questions and no answer key: the paper comes from the take-exam projection
    // and the key from findAnswerKeyForAuthoring, and keeping the three reads
    // apart is E2.12.

    /**
     * One exam version with everything an approval decision needs (E8.1/E8.4).
     *
     * <p>Joins the version, its exam, that exam's course and the author's name, because
     * every rule in the approval service needs several of those at once. Reading them
     * separately would let a decision be made from a half-loaded picture — and one of the
     * facts here is {@code lockVersion}, which is the value the whole compare-and-set hangs
     * on.
     *
     * <p>Consumers: E8's preview verb and both decision verbs, which re-read through this
     * inside the transaction that writes.
     *
     * @param session       the current session
     * @param examVersionId the version
     * @return the context, or empty when there is no such version
     */
    public Optional<ExamVersionContext> findVersionContext(Session session, long examVersionId) {
        return session.createQuery(CONTEXT_SELECT + """
                          and v.id = :examVersionId
                        """, ExamVersionContext.class)
                .setParameter("examVersionId", examVersionId)
                .uniqueResultOptional();
    }

    /**
     * Everything waiting on one coordinator, <b>scoped in the SQL</b> (E8.1 — F4.1).
     *
     * <p>The scoping is the point, and it is a join rather than a filter applied afterwards.
     * A read that returned every pending version and let the service drop the ones that are
     * not hers would work, and would keep working right up until somebody wrote a second
     * caller that forgot the second step. Joining {@code coordinators} into the query means
     * a version outside her subjects is not fetched, cannot be counted, and cannot be
     * returned by a code path that skipped a check.
     *
     * <p>"One coordinator per subject" is the primary key of {@code coordinators} (§5), so
     * this join can never multiply a row.
     *
     * <p>Consumer: E8's approval queue.
     *
     * @param session       the current session
     * @param coordinatorId the caller, from the session and never from a payload
     * @return the pending versions of her subjects, oldest submission first
     */
    public List<ExamVersionContext> findPendingForCoordinator(Session session, long coordinatorId) {
        return session.createQuery(CONTEXT_SELECT + """
                          and v.status = :status
                          and exists (
                              select 1 from Coordinator co
                              where co.teacherId = :coordinatorId and co.subjectCode = c.subjectCode)
                        order by v.createdAt, v.id
                        """, ExamVersionContext.class)
                .setParameter("status", ExamVersionStatus.PENDING)
                .setParameter("coordinatorId", coordinatorId)
                .getResultList();
    }

    /**
     * One teacher's own versions that have left her desk (E8.6 — F4.2).
     *
     * <p>F4.2 requires a rejection reason to be visible <em>on the exam</em> and not only in
     * a notification, which needs a read the author can open. Drafts are excluded because a
     * draft has no approval outcome to report; everything that has been submitted at least
     * once is here, whatever it became.
     *
     * <p>Newest first: the thing a teacher wants after reading "your exam was sent back" is
     * the exam that was just sent back.
     *
     * <p>Consumer: E8.6's teacher-side approval-status screen. E7's exam list supersedes it.
     *
     * @param session  the current session
     * @param authorId the caller, from the session
     * @return her non-draft versions, newest first
     */
    public List<ExamVersionContext> findSubmittedByAuthor(Session session, long authorId) {
        return session.createQuery(CONTEXT_SELECT + """
                          and e.authorId = :authorId and v.status <> :draft
                        order by v.createdAt desc, v.id desc
                        """, ExamVersionContext.class)
                .setParameter("authorId", authorId)
                .setParameter("draft", ExamVersionStatus.DRAFT)
                .getResultList();
    }

    /**
     * How many questions each of these versions has, in one query (E8.1).
     *
     * <p>The approval queue shows "12 questions" per row so a coordinator can triage without
     * opening anything. Asking {@code QuestionRepository.countForTakeExam} once per row would
     * be a query per exam on a screen whose whole point is to show several at once, which is
     * the classic N+1 — invisible on a seeded demo with three rows, and the reason a real
     * queue would feel slow.
     *
     * <p>Versions with no questions are simply absent from the map rather than mapped to
     * zero, which is what a {@code group by} yields and what the caller wants: it defaults.
     *
     * <p>Consumer: E8's approval queue and the teacher-side approval-status list.
     *
     * @param session        the current session
     * @param examVersionIds the versions on screen; {@code null} or empty yields an empty map
     * @return version id to question count
     */
    public Map<Long, Integer> countQuestionsByVersion(Session session,
                                                      Collection<Long> examVersionIds) {
        if (examVersionIds == null || examVersionIds.isEmpty()) {
            // An `in ()` with no values is a syntax error on some engines and a full scan on
            // others; neither is what an empty queue should do.
            return Map.of();
        }
        List<Object[]> rows = session.createQuery("""
                        select evq.id.examVersionId, count(evq)
                        from ExamVersionQuestion evq
                        where evq.id.examVersionId in (:ids)
                        group by evq.id.examVersionId
                        """, Object[].class)
                .setParameter("ids", examVersionIds)
                .getResultList();

        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    /**
     * Sends every other pending version of one exam back as superseded (E8.2).
     *
     * <p>A bulk, status-guarded UPDATE rather than a read-then-loop, and both halves of that
     * matter. Guarded, because a version that stopped being pending while this statement was
     * being prepared must not be dragged back out of {@code APPROVED}. Bulk, because the
     * rule is "every older pending version of this exam", and a loop that read a list and
     * then wrote each row would be deciding against a list that no longer exists.
     *
     * <p>The reason sentence is the service's, not this method's: repositories do not write
     * user-visible copy. {@code ApprovalMessages.SUPERSEDED_REASON} is the one the service
     * passes.
     *
     * <p>Note this write bypasses the entity's {@code @Version}, as any bulk update does.
     * That is correct here and nowhere else in this feature: superseding is the system acting
     * on rows nobody is looking at, so there is no reader whose view could go stale, and a
     * coordinator who <em>was</em> looking at one of them is refused by the status guard on
     * her own decision instead: this bulk update leaves {@code lock_version} untouched, so her
     * optimistic token still matches, and it is the PENDING status check that stops a stale
     * approve (corrected 2026-08-21; the safety is a cross-file dependency and E7's
     * {@code submitForApproval} is this method's first production caller).
     *
     * <p>Consumer: E8.2's supersede entry point, called by E7's submit.
     *
     * @param session       the current session
     * @param examId        the exam whose older submissions are being invalidated
     * @param keepVersionId the version that has just been submitted and must survive
     * @param reason        the fixed system sentence to store
     * @return how many versions were sent back
     */
    public int supersedePendingVersions(Session session, long examId, long keepVersionId,
                                        String reason) {
        return session.createMutationQuery("""
                        update ExamVersion
                        set status = :rejected, rejectedReason = :reason
                        where examId = :examId and id <> :keepVersionId and status = :pending
                        """)
                .setParameter("rejected", ExamVersionStatus.REJECTED)
                .setParameter("reason", reason)
                .setParameter("examId", examId)
                .setParameter("keepVersionId", keepVersionId)
                .setParameter("pending", ExamVersionStatus.PENDING)
                .executeUpdate();
    }

    /**
     * The select, from and join conditions the three approval reads share.
     *
     * <p>One string rather than three copies: the projection has seventeen components, and
     * three hand-maintained copies of the same constructor expression is how one of them
     * ends up selecting the columns in a different order — a mistake the compiler cannot
     * see, because they are all the same types.
     *
     * <p>It ends inside an open {@code where}, so every caller appends {@code and …}. That
     * is deliberately a little awkward to read: the alternative is each caller repeating the
     * three join conditions, and a caller that forgot one would silently return a cross
     * product rather than fail.
     */
    private static final String CONTEXT_SELECT = """
            select new server.db.projections.ExamVersionContext(
                v.id, e.id, e.displayId, v.name, v.versionNo, v.durationMinutes,
                v.studentText, v.teacherText, v.status, v.rejectedReason,
                v.createdAt, v.lockVersion, e.courseCode, c.name, c.subjectCode,
                e.authorId, u.fullName)
            from ExamVersion v, Exam e, Course c, User u
            where e.id = v.examId
              and c.code = e.courseCode
              and u.id = e.authorId
            """;


    /**
     * Every exam one teacher wrote, with its course and its current name (E14.1 — F9.2, S-35).
     *
     * <p>Scoped on {@code exams.author}, which is the recorded author of the definition and
     * <b>not</b> the teacher who released any particular sitting. That is S-35 expressed as a
     * {@code WHERE} clause: an exam released by a colleague still belongs to its author here,
     * and no handler can widen the scope afterwards because the widening would have to happen
     * in this query.
     *
     * <p>The name comes from the exam's highest version number. An exam always has at least
     * one version, so the inner {@code max} never leaves an exam out; if it somehow did, the
     * exam would silently vanish from its author's results, which is why the correlated
     * subquery is preferred here over a join to "the approved version" — approval status is
     * not what makes an exam hers.
     *
     * <p>Carries no questions and no answer key: the drawer's index, not its contents.
     *
     * <p>Consumer: E14.1's {@code RESULTS_EXAMS_GET}.
     *
     * @param session  the current session
     * @param authorId the teacher who wrote them, always the authenticated caller
     * @return her exams ordered by display id; empty when she has written none
     */
    public List<AuthoredExam> findAuthoredSummaries(Session session, long authorId) {
        return session.createQuery("""
                        select new server.db.projections.AuthoredExam(
                            e.id, e.displayId, e.courseCode, c.name, v.name)
                        from Exam e, Course c, ExamVersion v
                        where e.authorId = :authorId
                          and c.code = e.courseCode
                          and v.examId = e.id
                          and v.versionNo = (
                              select max(later.versionNo) from ExamVersion later
                              where later.examId = e.id)
                        order by e.displayId
                        """, AuthoredExam.class)
                .setParameter("authorId", authorId)
                .getResultList();
    }
}
