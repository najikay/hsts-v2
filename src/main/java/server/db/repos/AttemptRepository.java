package server.db.repos;

import jakarta.persistence.OptimisticLockException;
import org.hibernate.Session;
import org.hibernate.exception.ConstraintViolationException;
import server.db.entities.AttemptAnswer;
import server.db.entities.AttemptStatus;
import server.db.entities.ExamAttempt;
import server.db.projections.AnswerRow;
import server.db.projections.AttemptRecord;
import server.db.projections.AttemptRow;
import server.db.projections.ParticipationCounts;
import server.features.exam.DuplicateAttemptException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads and the two guarded writes over {@code exam_attempts} (E2.11, E10). */
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
     * The same row as an immutable snapshot (E10).
     *
     * <p>Why a projection when the entity is right there: {@link ExamAttempt} deliberately
     * has no status setter, because §5 requires every transition out of
     * {@code IN_PROGRESS} to go through the compare-and-set below. Handing a managed
     * entity to a service is an invitation to do it the other way, and the other way is
     * the last-write-wins bug the guard exists to prevent.
     *
     * @param session     the current session
     * @param executionId the execution
     * @param studentId   the student
     * @return the snapshot, or empty
     */
    public Optional<AttemptRecord> findRecord(Session session, long executionId, long studentId) {
        return session.createQuery(recordSelect() + """
                        where a.executionId = :executionId and a.studentId = :studentId
                        """, AttemptRecord.class)
                .setParameter("executionId", executionId)
                .setParameter("studentId", studentId)
                .uniqueResultOptional();
    }

    /**
     * One attempt by its own id.
     *
     * <p>Consumers: the autosave and submit verbs, which are handed an attempt id by the
     * client and must check whose it is before believing anything about it, and the timer
     * service, which knows only the id it armed.
     *
     * @param session   the current session
     * @param attemptId the attempt
     * @return the snapshot, or empty
     */
    public Optional<AttemptRecord> findRecordById(Session session, long attemptId) {
        return session.createQuery(recordSelect() + " where a.id = :attemptId", AttemptRecord.class)
                .setParameter("attemptId", attemptId)
                .uniqueResultOptional();
    }

    /**
     * Opens an attempt (S-18), or fails because this student already has one (F6.7).
     *
     * <p>The unique key on {@code (execution_id, student_id)} is the real guard, not the
     * caller's check: two clicks a millisecond apart both pass a "does she have one?" read
     * and only one can pass the constraint.
     *
     * <p><b>The violation is translated, not swallowed.</b> A flush that fails a constraint
     * leaves the Hibernate session marked for rollback, so continuing to read or write in it
     * is not safe — answering "empty" and carrying on would look like it worked and would
     * eventually commit something nobody intended. Instead this throws
     * {@link DuplicateAttemptException}, the transaction rolls back cleanly, and the caller
     * re-reads the winning attempt in a fresh one. E10.8's double-attempt scenario pins the
     * behaviour a student actually sees, which is that her second click resumes her first.
     *
     * @param session     the current session
     * @param executionId the execution
     * @param studentId   the student
     * @param startedAt   the server's clock reading; the clock starts here (S-18)
     * @return the new attempt
     * @throws DuplicateAttemptException when this student already had one
     */
    public AttemptRecord createAttempt(Session session, long executionId,
                                       long studentId, Instant startedAt) {
        ExamAttempt attempt = new ExamAttempt(executionId, studentId, startedAt);
        try {
            session.persist(attempt);
            session.flush();
        } catch (RuntimeException e) {
            // Hibernate wraps the driver's duplicate-key differently across engines, so the
            // cause chain is the portable way to recognise it. Anything else is a real
            // failure and must not be mistaken for "she already started".
            if (hasConstraintViolation(e)) {
                throw new DuplicateAttemptException(executionId, studentId, e);
            }
            throw e;
        }
        return new AttemptRecord(attempt.getId(), executionId, studentId,
                startedAt, null, null, AttemptStatus.IN_PROGRESS);
    }

    /**
     * <b>The compare-and-set that decides the submit-vs-expiry race</b> (§5, ADR-010, F6.4).
     *
     * <p>{@code UPDATE … SET status = ? WHERE id = ? AND status = 'IN_PROGRESS'}. The
     * student pressing submit and the server's expiry timer are both legitimate writers
     * arriving at the same row at the same instant, and one of them simply has to lose
     * quietly. Whoever changes a row wins; the loser sees zero and reads the final state
     * instead of erroring, because a student who pressed submit as her time ran out has
     * done nothing wrong and must not see a failure.
     *
     * <p>This is why {@link ExamAttempt} has no {@code @Version}: a version column would
     * turn a resolvable race into a conflict somebody has to retry.
     *
     * @param session       the current session
     * @param attemptId     the attempt to close
     * @param status        {@link AttemptStatus#SUBMITTED} or {@link AttemptStatus#TIMED_OUT}
     * @param endedAt       when it closed
     * @param actualMinutes solving time to record (S-19)
     * @return 1 when this caller closed it, 0 when somebody else already had
     * @throws IllegalArgumentException when asked to set {@code IN_PROGRESS}, which would
     *                                  be reopening a closed attempt
     */
    public int finalizeAttempt(Session session, long attemptId, AttemptStatus status,
                               Instant endedAt, int actualMinutes) {
        if (status == AttemptStatus.IN_PROGRESS) {
            throw new IllegalArgumentException("An attempt is never finalised back to IN_PROGRESS");
        }
        return session.createMutationQuery("""
                        update ExamAttempt
                        set status = :status, endedAt = :endedAt, actualMinutes = :minutes
                        where id = :attemptId and status = :inProgress
                        """)
                .setParameter("status", status)
                .setParameter("endedAt", endedAt)
                .setParameter("minutes", actualMinutes)
                .setParameter("attemptId", attemptId)
                .setParameter("inProgress", AttemptStatus.IN_PROGRESS)
                .executeUpdate();
    }

    /**
     * Writes one autosaved choice, creating or replacing the row (F6.3).
     *
     * <p>Keyed {@code (attempt_id, question_version_id)}, so a student changing her mind
     * eleven times leaves one row rather than eleven. Written as read-then-write rather
     * than an {@code ON DUPLICATE KEY} upsert because that syntax is MySQL's alone and this
     * query runs on H2 in the fast suite too; the primary key makes the two branches
     * exclusive whichever engine is underneath.
     *
     * <p><b>This method does not check whether the attempt is still live.</b> That check
     * belongs with the deadline check, in the same transaction, in the service — see
     * {@code AttemptService.saveAnswer}. Putting half of it here would make it look done.
     *
     * @param session           the current session
     * @param attemptId         the attempt
     * @param questionVersionId the question, by its pinned version
     * @param selected          1..4, or {@code null} to clear the answer
     * @param savedAt           the server's clock reading
     */
    public void upsertAnswer(Session session, long attemptId, long questionVersionId,
                             Byte selected, Instant savedAt) {
        AttemptAnswer.Id key = new AttemptAnswer.Id(attemptId, questionVersionId);
        AttemptAnswer existing = session.get(AttemptAnswer.class, key);
        if (existing == null) {
            session.persist(new AttemptAnswer(attemptId, questionVersionId, selected, savedAt));
        } else {
            existing.select(selected, savedAt);
        }
        session.flush();
    }

    /**
     * The choices this attempt is holding, for a resuming client (E10.6).
     *
     * @param session   the current session
     * @param attemptId the attempt
     * @return one row per question the student has touched, in question-version order
     */
    public List<AnswerRow> findAnswers(Session session, long attemptId) {
        return session.createQuery("""
                        select new server.db.projections.AnswerRow(
                            a.id.questionVersionId, cast(a.selected as Integer))
                        from AttemptAnswer a
                        where a.id.attemptId = :attemptId
                        order by a.id.questionVersionId
                        """, AnswerRow.class)
                .setParameter("attemptId", attemptId)
                .getResultList();
    }

    /**
     * How many questions of one attempt actually carry a choice.
     *
     * <p>Counted rather than tracked, for the same reason participation is: the number on
     * the student's progress line and the number the grader will see have to be the same
     * number, and the only way to guarantee that is to ask the table both times.
     *
     * @param session   the current session
     * @param attemptId the attempt
     * @return the answered count
     */
    public int countAnswered(Session session, long attemptId) {
        return session.createQuery("""
                        select count(a) from AttemptAnswer a
                        where a.id.attemptId = :attemptId and a.selected is not null
                        """, Long.class)
                .setParameter("attemptId", attemptId)
                .getSingleResult()
                .intValue();
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
    public ParticipationCounts countParticipation(Session session, long executionId) {
        long started = count(session, executionId, null);
        long finished = count(session, executionId, AttemptStatus.SUBMITTED);
        long timedOut = count(session, executionId, AttemptStatus.TIMED_OUT);
        return new ParticipationCounts(started, finished, timedOut);
    }

    /**
     * Every student sitting one execution, by name (E11.2 — F7.2).
     *
     * <p>Ordered by name rather than by start time: a teacher scanning for one student
     * looks her up alphabetically, and a list that reorders itself as people submit is
     * unreadable on a screen that live-updates.
     *
     * @param session     the current session
     * @param executionId the execution
     * @return one row per attempt, by student name
     */
    public List<AttemptRow> findRows(Session session, long executionId) {
        return session.createQuery("""
                        select new server.db.projections.AttemptRow(
                            a.id, a.studentId, u.fullName, a.startedAt, a.endedAt,
                            a.actualMinutes, a.status)
                        from ExamAttempt a, User u
                        where a.executionId = :executionId and u.id = a.studentId
                        order by u.fullName, a.id
                        """, AttemptRow.class)
                .setParameter("executionId", executionId)
                .getResultList();
    }

    /**
     * Answered counts for every attempt at one execution, in one query (E11.2).
     *
     * <p>The monitor needs "7/20" on every row; asking per row would be one query per
     * student on a screen that repaints on every push. Grouping once is the difference
     * between a monitor that scales to a class and one that does not.
     *
     * @param session     the current session
     * @param executionId the execution
     * @return attempt id → answered count; attempts with no answers are absent
     */
    public Map<Long, Integer> countAnsweredByAttempt(Session session, long executionId) {
        List<Object[]> rows = session.createQuery("""
                        select ans.id.attemptId, count(ans)
                        from AttemptAnswer ans, ExamAttempt a
                        where a.id = ans.id.attemptId
                          and a.executionId = :executionId
                          and ans.selected is not null
                        group by ans.id.attemptId
                        """, Object[].class)
                .setParameter("executionId", executionId)
                .getResultList();
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    /**
     * Every attempt still running, anywhere (E10.5 ⚑).
     *
     * <p>The boot path: after a restart the expiry timers are gone but the deadlines are
     * not, because they are derived from rows that survived. This is what the server
     * re-arms from, and it is also what makes the idempotent sweep possible — an attempt
     * whose deadline passed while the process was down is expired on the first sweep
     * rather than left open forever, which is the v1 bug this epic exists to kill.
     *
     * @param session the current session
     * @return the live attempts, oldest first
     */
    public List<AttemptRecord> findAllInProgress(Session session) {
        return session.createQuery(recordSelect() + """
                        where a.status = :inProgress order by a.startedAt
                        """, AttemptRecord.class)
                .setParameter("inProgress", AttemptStatus.IN_PROGRESS)
                .getResultList();
    }

    /**
     * The live attempts at one execution — who an extension has to reach (E11.1).
     *
     * @param session     the current session
     * @param executionId the execution
     * @return the in-progress attempts
     */
    public List<AttemptRecord> findInProgressAt(Session session, long executionId) {
        return session.createQuery(recordSelect() + """
                        where a.executionId = :executionId and a.status = :inProgress
                        order by a.startedAt
                        """, AttemptRecord.class)
                .setParameter("executionId", executionId)
                .setParameter("inProgress", AttemptStatus.IN_PROGRESS)
                .getResultList();
    }

    /** The shared head of every {@link AttemptRecord} query; one place to keep in step. */
    private static String recordSelect() {
        return """
                select new server.db.projections.AttemptRecord(
                    a.id, a.executionId, a.studentId, a.startedAt, a.endedAt,
                    a.actualMinutes, a.status)
                from ExamAttempt a
                """;
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

    /** @return whether anything in this exception's cause chain is a constraint violation. */
    private static boolean hasConstraintViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException
                    || cause instanceof OptimisticLockException
                    || cause instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
        }
        return false;
    }
}
