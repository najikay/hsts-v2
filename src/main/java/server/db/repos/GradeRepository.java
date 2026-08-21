package server.db.repos;

import org.hibernate.Session;
import server.db.entities.Grade;
import server.db.entities.GradeStatus;
import server.db.projections.StudentResultRow;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads over {@code grades} (E2.11). */
public final class GradeRepository {

    /**
     * The grade for one attempt.
     *
     * <p>{@code attempt_id} is unique, so at most one row exists.
     *
     * <p>Consumers: E11 grading review; E11 publishing a result to the student.
     *
     * @param session   the current session
     * @param attemptId the attempt
     * @return the grade, or empty when grading has not run
     */
    public Optional<Grade> findByAttempt(Session session, long attemptId) {
        return session.createQuery("from Grade where attemptId = :attemptId", Grade.class)
                .setParameter("attemptId", attemptId)
                .uniqueResultOptional();
    }

    /**
     * A student's approved grades, newest first.
     *
     * <p><b>Scoped in the query, not by the caller.</b> The student id is a filter here rather
     * than something a handler checks afterwards, so no code path loads someone else's rows and
     * then remembers to drop them (E13.1 &#9873;). Only {@code APPROVED} rows are returned:
     * auto-checking alone publishes nothing (C-3, S-24).
     *
     * <p>Carries no correctness data — {@code Grade} is scores and audit fields — so this read is
     * outside {@code CorrectnessLeakGuardTest}'s remit by construction.
     *
     * <p>Consumer: E13.3's {@code MY_GRADES_GET}.
     *
     * @param session   the current session
     * @param studentId the student, always the authenticated caller
     * @return approved grades, most recently approved first
     */
    public List<Grade> findApprovedForStudent(Session session, long studentId) {
        return session.createQuery("""
                        select g from Grade g, ExamAttempt a
                        where g.attemptId = a.id
                          and a.studentId = :studentId
                          and g.status = :status
                        order by g.approvedAt desc, g.id desc
                        """, Grade.class)
                .setParameter("studentId", studentId)
                .setParameter("status", GradeStatus.APPROVED)
                .getResultList();
    }

    /**
     * One grade, but only if it belongs to this student.
     *
     * <p>The ownership check <b>is</b> the query. A grade id belonging to someone else comes back
     * empty and the handler answers {@code NOT_FOUND} — indistinguishable from an id that does
     * not exist, so probing reveals nothing (contract, E13.1 &#9873;).
     *
     * <p>Consumer: E13.4's {@code CHECKED_FORM_GET}, which applies its own two further conditions
     * (state approved, execution closed) on top of ownership.
     *
     * @param session   the current session
     * @param gradeId   the requested grade
     * @param studentId the student, always the authenticated caller
     * @return the grade when it is this student's, otherwise empty
     */
    public Optional<Grade> findForStudent(Session session, long gradeId, long studentId) {
        return session.createQuery("""
                        select g from Grade g, ExamAttempt a
                        where g.id = :gradeId
                          and g.attemptId = a.id
                          and a.studentId = :studentId
                        """, Grade.class)
                .setParameter("gradeId", gradeId)
                .setParameter("studentId", studentId)
                .uniqueResultOptional();
    }

    /**
     * Grades by id, for a bulk approval.
     *
     * <p>Returns what exists and says nothing about what does not: an id belonging to another
     * teacher's execution, and an id that was never a grade, both simply fail to appear. The
     * caller reports them the same way (`refused` in {@code ApproveResult}), which is the same
     * no-oracle rule the student reads follow.
     *
     * <p>Order is by id so a partial result is stable to compare against the request.
     *
     * <p>Consumer: E12.2's {@code GRADES_APPROVE}.
     *
     * @param session  the current session
     * @param gradeIds the requested grades; an empty collection returns an empty list
     * @return the grades that exist, by id
     */
    public List<Grade> findByIds(Session session, Collection<Long> gradeIds) {
        if (gradeIds == null || gradeIds.isEmpty()) {
            // `in ()` is a syntax error on some engines and a full scan on others.
            return List.of();
        }
        return session.createQuery("""
                        from Grade where id in (:ids) order by id
                        """, Grade.class)
                .setParameter("ids", gradeIds)
                .getResultList();
    }

    /**
     * Every grade of one execution, whatever its status.
     *
     * <p>Two callers need this and both need the whole set rather than a filtered one:
     * deciding whether an approval <b>completed</b> an execution means asking whether any
     * {@code AUTO} row remains, and computing the frozen statistics means reading every final
     * score. Splitting it into a count and a projection would read the same rows twice inside
     * one transaction and invite the two to disagree.
     *
     * <p>Consumer: E12.2, when an approval may have completed the execution.
     *
     * @param session     the current session
     * @param executionId the execution
     * @return its grades, by attempt id
     */
    public List<Grade> findAllForExecution(Session session, long executionId) {
        return session.createQuery("""
                        select g from Grade g, ExamAttempt a
                        where g.attemptId = a.id
                          and a.executionId = :executionId
                        order by g.attemptId
                        """, Grade.class)
                .setParameter("executionId", executionId)
                .getResultList();
    }

    /**
     * Grades for an execution that a teacher has not approved yet.
     *
     * <p>Auto-grading produces {@code AUTO} rows and a teacher approves them into
     * {@code APPROVED} (C-3); this is the queue in between. Joined through
     * {@code exam_attempts} because a grade does not know its execution.
     *
     * <p>Consumer: E12.5's grading queue.
     *
     * @param session     the current session
     * @param executionId the execution
     * @return grades awaiting approval, by attempt id
     */
    public List<Grade> findAwaitingApproval(Session session, long executionId) {
        return session.createQuery("""
                        select g from Grade g, ExamAttempt a
                        where g.attemptId = a.id
                          and a.executionId = :executionId
                          and g.status = :status
                        order by g.attemptId
                        """, Grade.class)
                .setParameter("executionId", executionId)
                .setParameter("status", GradeStatus.AUTO)
                .getResultList();
    }

    /**
     * How many grade rows exist behind each of these executions (E14.1 — F9.2).
     *
     * <p>What turns the results picker into a progress list: a sitting with eight participants
     * and six grades is visibly still being marked, and the teacher learns that before opening
     * it. Grouped for the same reason {@code AttemptRepository.countAttemptsByExecution} is —
     * one query for a whole screen rather than one per row.
     *
     * <p>Counts every grade, approved or not. "How much marking has happened" is the question;
     * how much of it has been signed off is {@code approvedCount}'s job in the grading queue,
     * and conflating them would make an execution look unmarked while a teacher was reviewing
     * it.
     *
     * <p>An execution with no grades is absent from the map rather than present with a zero.
     *
     * <p>Consumer: E14.1's {@code RESULTS_EXAMS_GET}.
     *
     * @param session      the current session
     * @param executionIds the executions to count
     * @return execution id → grade rows; empty when {@code executionIds} is empty
     */
    public Map<Long, Integer> countGradesByExecution(Session session,
                                                     Collection<Long> executionIds) {
        if (executionIds == null || executionIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = session.createQuery("""
                        select a.executionId, count(g)
                        from Grade g, ExamAttempt a
                        where g.attemptId = a.id
                          and a.executionId in (:executionIds)
                        group by a.executionId
                        """, Object[].class)
                .setParameterList("executionIds", executionIds)
                .getResultList();
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        return counts;
    }

    /**
     * Every marked student in one execution, by name (E14.1 — F9.2, T-10).
     *
     * <p>The teacher's results table, in one read: the grade, the attempt's recorded solving
     * time and the student's name, which is what the table shows and what neither
     * {@link #findAwaitingApproval} nor {@code AttemptRepository.findRows} supplies on its own.
     *
     * <p><b>This read is not scoped to a teacher, and must not be.</b> Authorship is settled
     * one step earlier, on the execution ({@code ExecutionRepository.findContext} against
     * {@code exams.author}), because that is where the fact lives; a second filter here would
     * be a check that looks like scoping while depending on a caller passing the right id.
     * The service refuses before it ever reaches this method.
     *
     * <p>Ordered by student name, then grade id, so the table is stable across refreshes and a
     * teacher scanning for one student scans alphabetically. Carries no answers and no
     * correctness data, so it needs no sanctioned correctness suffix (E2.12).
     *
     * <p>Consumer: E14.1's {@code RESULTS_EXECUTION_GET}.
     *
     * @param session     the current session
     * @param executionId the execution
     * @return one row per grade, by student name; empty when nothing has been marked
     */
    public List<StudentResultRow> findResultRows(Session session, long executionId) {
        return session.createQuery("""
                        select new server.db.projections.StudentResultRow(
                            g.id, a.studentId, u.fullName, g.autoScore, g.finalScore,
                            g.status, g.overrideReason, g.teacherComment, g.approvedAt,
                            a.actualMinutes)
                        from Grade g, ExamAttempt a, User u
                        where g.attemptId = a.id
                          and u.id = a.studentId
                          and a.executionId = :executionId
                        order by u.fullName, g.id
                        """, StudentResultRow.class)
                .setParameter("executionId", executionId)
                .getResultList();
    }
}
