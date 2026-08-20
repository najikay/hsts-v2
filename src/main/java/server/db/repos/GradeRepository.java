package server.db.repos;

import org.hibernate.Session;
import server.db.entities.Grade;
import server.db.entities.GradeStatus;

import java.util.List;
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
     * Grades for an execution that a teacher has not approved yet.
     *
     * <p>Auto-grading produces {@code AUTO} rows and a teacher approves them into
     * {@code APPROVED} (C-3); this is the queue in between. Joined through
     * {@code exam_attempts} because a grade does not know its execution.
     *
     * <p>Consumer: E11's grading queue.
     *
     * @param session     the current session
     * @param executionId the execution
     * @return grades awaiting approval, by attempt id
     */
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
}
