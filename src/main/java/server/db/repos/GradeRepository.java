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
