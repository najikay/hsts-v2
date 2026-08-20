package server.db.repos;

import org.hibernate.Session;
import server.db.entities.AttemptAnswer;
import server.db.entities.AttemptStatus;
import server.db.entities.ExamAttempt;
import server.db.projections.ParticipationCounts;

import java.util.List;
import java.util.Optional;

/** Reads over {@code exam_attempts} (E2.11). */
public final class AttemptRepository {

    /**
     * A student's attempt at one execution.
     *
     * <p>{@code UNIQUE(execution_id, student_id)} makes this at most one row, so
     * {@code Optional} is honest here in a way it would not be for execution codes.
     *
     * <p>Consumer: E10, which must resume an in-progress attempt rather than start a second.
     *
     * @param session     the current session
     * @param executionId the execution
     * @param studentId   the student
     * @return the attempt, or empty when the student has not started
     */
    public Optional<ExamAttempt> findByExecutionAndStudent(Session session, long executionId, long studentId) {
        return session.createQuery("""
                        from ExamAttempt where executionId = :executionId and studentId = :studentId
                        """, ExamAttempt.class)
                .setParameter("executionId", executionId)
                .setParameter("studentId", studentId)
                .uniqueResultOptional();
    }

    /**
     * Participation for a live execution, counted rather than stored.
     *
     * <p>Consumers: E9's execution monitor while live; E12 freezing the numbers into the
     * stats JSON at close.
     *
     * @param session     the current session
     * @param executionId the execution
     * @return the counts, all zero when nobody has started
     */
    /**
     * The answers saved for one attempt.
     *
     * <p>Rows exist only for questions the student actually touched, so an attempt that was
     * force-submitted with nothing answered returns an empty list rather than a list of nulls.
     *
     * <p>Carries the student's selection but no answer key — which answer is <i>right</i> comes
     * separately from {@code QuestionRepository#findVersionsForGrading} — so this read needs no
     * sanctioned suffix.
     *
     * <p>Consumer: E12.1's auto-grading, via {@code server.features.grading.GradingReads}.
     *
     * @param session   the current session
     * @param attemptId the attempt
     * @return the saved answers, ordered by question version for a stable result
     */
    public List<AttemptAnswer> findAnswers(Session session, long attemptId) {
        return session.createQuery("""
                        from AttemptAnswer
                        where id.attemptId = :attemptId
                        order by id.questionVersionId
                        """, AttemptAnswer.class)
                .setParameter("attemptId", attemptId)
                .getResultList();
    }

    public ParticipationCounts countParticipation(Session session, long executionId) {
        long started = count(session, executionId, null);
        long finished = count(session, executionId, AttemptStatus.SUBMITTED);
        long timedOut = count(session, executionId, AttemptStatus.TIMED_OUT);
        return new ParticipationCounts(started, finished, timedOut);
    }

    private static long count(Session session, long executionId, AttemptStatus status) {
        String hql = "select count(a) from ExamAttempt a where a.executionId = :executionId"
                + (status == null ? "" : " and a.status = :status");
        var query = session.createQuery(hql, Long.class).setParameter("executionId", executionId);
        if (status != null) {
            query.setParameter("status", status);
        }
        return query.getSingleResult();
    }
}
