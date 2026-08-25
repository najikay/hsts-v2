package server.db.repos;

import org.hibernate.Session;
import server.db.entities.Exam;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionQuestion;
import server.db.entities.ExamVersionStatus;
import server.db.projections.AuthoredExam;
import server.db.projections.ExamVersionContext;
import server.db.projections.SchoolExam;

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
     * The approved versions one teacher may release (E9.1 — F5.1, S-14).
     *
     * <p><b>The filter is the requirement.</b> F5.1 says only an approved version may be
     * released and PRD §6 spells out the UI half as "release unapproved version →
     * impossible (not listed)". Both are this {@code where} clause: a draft, a pending or a
     * rejected version is not fetched, cannot be counted and cannot be returned by a code
     * path that skipped a check. The release service checks approval again on create,
     * because a list is a courtesy and never a gate.
     *
     * <p>Scoped by <b>teaching</b>, not by authorship. A course's exams are the course's,
     * and a teacher covering a colleague's class has to be able to take one out of the
     * drawer; scoping by {@code exams.author} would make that impossible while adding no
     * safety, since she can already write an exam for the same course. It is the join to
     * {@code course_teachers} that keeps a teacher out of another course's drawer, and it
     * is in the query rather than applied afterwards for the reason
     * {@link #findPendingForCoordinator}'s is.
     *
     * <p>All approved versions, including superseded ones: S-2 allows an exam to be
     * released many times, and an older approved version is a legitimate thing to release
     * again. The picker shows the version number so the two are tellable apart.
     *
     * <p>Carries no questions and no answer key; the count per version comes separately
     * from {@link #countQuestionsByVersion}, which the approval queue already uses.
     *
     * <p>Consumer: E9.1's {@code RELEASE_OPTIONS_GET}.
     *
     * @param session   the current session
     * @param teacherId the caller, from the session and never from a payload
     * @return the approved versions of her courses, newest first; empty when she has none
     */
    public List<ExamVersionContext> findReleasableForTeacher(Session session, long teacherId) {
        return session.createQuery(CONTEXT_SELECT + """
                          and v.status = :approved
                          and exists (
                              select 1 from CourseTeacher ct
                              where ct.id.teacherId = :teacherId and ct.id.courseCode = c.code)
                        order by v.createdAt desc, v.id desc
                        """, ExamVersionContext.class)
                .setParameter("approved", ExamVersionStatus.APPROVED)
                .setParameter("teacherId", teacherId)
                .getResultList();
    }

    /**
     * Whether this teacher has any exam at all in the courses she teaches (E9.1).
     *
     * <p>One boolean, and it exists to tell two empty states apart. A teacher whose picker
     * is empty because nothing has been approved yet needs to be told to ask her
     * coordinator; a teacher whose drawer is empty needs to be told to write an exam. Both
     * render zero rows, and a screen that could not distinguish them would give half its
     * readers the wrong next step, which is exactly what PRD §4.1 forbids.
     *
     * <p>Consumer: E9.1's {@code RELEASE_OPTIONS_GET}, for {@code ReleaseOptions.anyExams}.
     *
     * @param session   the current session
     * @param teacherId the caller
     * @return {@code true} when at least one exam exists in a course she teaches
     */
    public boolean hasAnyExamInTaughtCourses(Session session, long teacherId) {
        return session.createQuery("""
                        select count(e) from Exam e
                        where exists (
                            select 1 from CourseTeacher ct
                            where ct.id.teacherId = :teacherId and ct.id.courseCode = e.courseCode)
                        """, Long.class)
                .setParameter("teacherId", teacherId)
                .getSingleResult() > 0;
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

    // ===================== The principal's data browser (E15.2) ============
    // One read, added under TEAM_SPLIT rule 5 - repositories grow with their callers.

    /**
     * Every exam in the school, with its course, its author and its latest version
     * (E15.2 - F9.3).
     *
     * <p>The school-wide sibling of {@link #findAuthoredSummaries}, and deliberately a second
     * query rather than that one with a nullable author parameter. A scoped read whose scope can
     * be switched off with a {@code null} is one call site away from serving a teacher the whole
     * school, and S-35's guarantee is that {@code findAuthoredSummaries} has an author in its
     * {@code WHERE} clause and cannot be asked not to.
     *
     * <p><b>Unscoped by caller, and that is the requirement rather than an oversight.</b> The
     * only caller is the principal's data browser, whose scope is the school (spec 7.3.1, F9.3).
     * A caller-id parameter here would be a scope this read does not have, and the role gate on
     * {@code DATA_EXAMS_GET} is where "may you ask this at all" is decided.
     *
     * <p>The name and the date come from the exam's <b>highest version number</b>, through the
     * same correlated subquery {@link #findAuthoredSummaries} uses and for the same reason: an
     * exam always has at least one version, so no exam is left out, while a join to "the approved
     * version" would silently drop every exam still in draft or rejected - which are exactly the
     * ones a catalogue should still list.
     *
     * <p>Carries no questions, no answer key, no instructions and no approval status.
     *
     * <p>Consumer: E15.2's {@code DATA_EXAMS_GET}.
     *
     * @param session the current session
     * @return every exam ordered by display id; empty only before any exam is written
     */
    public List<SchoolExam> findAllSummaries(Session session) {
        return session.createQuery("""
                        select new server.db.projections.SchoolExam(
                            e.displayId, e.courseCode, c.name, v.name, u.fullName,
                            v.versionNo, v.createdAt)
                        from Exam e, Course c, ExamVersion v, User u
                        where c.code = e.courseCode
                          and u.id = e.authorId
                          and v.examId = e.id
                          and v.versionNo = (
                              select max(later.versionNo) from ExamVersion later
                              where later.examId = e.id)
                        order by e.displayId
                        """, SchoolExam.class)
                .getResultList();
    }
}
