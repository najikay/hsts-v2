package server.db.repos;

import org.hibernate.Session;
import server.db.entities.AttemptStatus;
import server.db.entities.ExamExecution;
import server.db.entities.ExecutionStatus;
import server.db.entities.Participation;
import server.db.projections.ExecutionContext;

import java.util.List;
import java.util.Optional;

/** Reads over {@code exam_executions} (E2.11). */
public final class ExecutionRepository {

    /**
     * Finds the executions using a 4-character code.
     *
     * <p>Returns a list, not one row, on purpose. Code uniqueness holds only among
     * non-closed executions and §5 states it is a <b>service</b> rule, because MySQL has no
     * partial unique index: a code is legitimately reused once its execution closes. A
     * method returning {@code Optional} here would be asserting a guarantee the schema does
     * not make, and would throw on perfectly valid data.
     *
     * <p>Codes are compared case-insensitively — students type them, and production
     * collation does this anyway while H2 does not.
     *
     * <p>Consumer: E10 join-by-code (C-1, S-18).
     *
     * @param session the current session
     * @param code    the 4-character code as typed
     * @return matching executions, newest first
     */
    public List<ExamExecution> findByCode(Session session, String code) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        return session.createQuery("""
                        from ExamExecution where lower(code) = lower(:code) order by openAt desc
                        """, ExamExecution.class)
                .setParameter("code", code.trim())
                .getResultList();
    }

    /**
     * The one execution a student may currently join with this code.
     *
     * <p>Consumer: E10 join-by-code, which needs exactly the live one.
     *
     * @param session the current session
     * @param code    the 4-character code as typed
     * @return the live execution, or empty
     */
    public Optional<ExamExecution> findJoinable(Session session, String code) {
        return findByCode(session, code).stream()
                .filter(execution -> execution.getStatus() == ExecutionStatus.LIVE)
                .findFirst();
    }

    /**
     * Everything about one execution that a take-exam or monitoring decision needs (E10/E11).
     *
     * <p>One join across the execution, its pinned exam version, the exam identity row and
     * the course, because every rule in E10 needs several of those facts at once —
     * "is it live, is she enrolled in its course, how long does she get, what is it
     * called". Reading them in four calls would let a service decide from a picture that
     * changed halfway through.
     *
     * <p>Carries no questions and no answer key: the paper comes from
     * {@link QuestionRepository#findForTakeExam}, and that separation is E2.12.
     *
     * <p>Consumers: E10 join/start/resume, E11 extend/monitor.
     *
     * @param session     the current session
     * @param executionId the execution
     * @return the context, or empty when there is no such execution
     */
    public Optional<ExecutionContext> findContext(Session session, long executionId) {
        return session.createQuery("""
                        select new server.db.projections.ExecutionContext(
                            ex.id, ex.examVersionId, e.id, e.courseCode, c.name, v.name,
                            v.durationMinutes, v.studentText, ex.code, ex.status,
                            ex.openAt, ex.closeAt, ex.extraMinutes, ex.createdBy, e.authorId)
                        from ExamExecution ex, ExamVersion v, Exam e, Course c
                        where ex.id = :executionId
                          and v.id = ex.examVersionId
                          and e.id = v.examId
                          and c.code = e.courseCode
                        """, ExecutionContext.class)
                .setParameter("executionId", executionId)
                .uniqueResultOptional();
    }

    /**
     * Every execution using this code, with its full context, newest first (E10.9).
     *
     * <p>Not filtered to LIVE, and that is the point: a student typing a code that belongs
     * to an execution which has closed, has not opened yet, or was cancelled deserves to be
     * told <em>that</em>, not "no such code". The four entry errors PRD §4 asks for
     * (wrong code, not live, not enrolled, wrong id) need this read to be able to tell the
     * first two apart, and a query that hid non-live rows could not.
     *
     * <p>Codes are legitimately reused once an execution closes (see {@link #findByCode}),
     * so this really can return several rows; the service takes the newest live one and
     * explains the rest.
     *
     * @param session the current session
     * @param code    the 4-character code as typed
     * @return matching executions with their context, newest first
     */
    public List<ExecutionContext> findContextsByCode(Session session, String code) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        return session.createQuery("""
                        select new server.db.projections.ExecutionContext(
                            ex.id, ex.examVersionId, e.id, e.courseCode, c.name, v.name,
                            v.durationMinutes, v.studentText, ex.code, ex.status,
                            ex.openAt, ex.closeAt, ex.extraMinutes, ex.createdBy, e.authorId)
                        from ExamExecution ex, ExamVersion v, Exam e, Course c
                        where lower(ex.code) = lower(:code)
                          and v.id = ex.examVersionId
                          and e.id = v.examId
                          and c.code = e.courseCode
                        order by ex.openAt desc
                        """, ExecutionContext.class)
                .setParameter("code", code.trim())
                .getResultList();
    }

    /**
     * Grants extra minutes to a live execution (F7.1, S-20).
     *
     * <p>A read-modify-write through the entity rather than a bulk update, and deliberately
     * so: {@code exam_executions} carries {@code lock_version}, and two teachers pressing
     * "add 15 minutes" at the same moment must not silently produce one grant. The second
     * flush fails with an optimistic-lock exception, the handler answers {@code CONFLICT},
     * and the teacher is told to look again rather than left believing she granted time
     * nobody got.
     *
     * <p>The <em>attempt</em> deadlines need no update at all: they are derived from this
     * column every time they are read, so an extension applies to a student who is offline
     * the moment she comes back (E11.4).
     *
     * @param session     the current session
     * @param executionId the execution
     * @param minutes     minutes to add; must be positive (the service validates)
     * @return the new total of granted extra minutes
     * @throws IllegalStateException when the execution does not exist
     */
    public int addExtraMinutes(Session session, long executionId, int minutes) {
        ExamExecution execution = session.get(ExamExecution.class, executionId);
        if (execution == null) {
            throw new IllegalStateException("No execution " + executionId + " to extend");
        }
        execution.addExtraMinutes(minutes);
        session.flush();
        return execution.getExtraMinutes();
    }

    /**
     * Freezes the derived participation counts into the execution's documentation record
     * (S-21, F7.3, E11.5).
     *
     * <p>The counts are a {@code COUNT} over attempts for as long as the execution is live
     * — no counter columns, no increment races (§5). This is the one moment they stop being
     * derived: at close they are written once into the {@code participation} JSON so the
     * record survives even if attempts are later archived, and so every later reader gets
     * the same number.
     *
     * @param session     the current session
     * @param executionId the execution being closed
     * @param counts      the counts as of now
     */
    public void freezeParticipation(Session session, long executionId,
                                    server.db.projections.ParticipationCounts counts) {
        ExamExecution execution = session.get(ExamExecution.class, executionId);
        if (execution == null) {
            throw new IllegalStateException("No execution " + executionId + " to close");
        }
        execution.setParticipation(new Participation(
                (int) counts.started(), (int) counts.finished(), (int) counts.timedOut()));
        execution.setStatus(ExecutionStatus.CLOSED);
        session.flush();
    }

    /**
     * Every execution that still has somebody sitting it (E10.5 ⚑).
     *
     * <p>What the server re-arms its expiry timers from after a restart: a live attempt
     * whose timer died with the old process must still be force-submitted on time, and the
     * database is the only thing that survived. ARCHITECTURE §4 requires exactly this.
     *
     * @param session the current session
     * @return execution ids with at least one {@code IN_PROGRESS} attempt
     */
    public List<Long> findExecutionIdsWithLiveAttempts(Session session) {
        return session.createQuery("""
                        select distinct a.executionId from ExamAttempt a where a.status = :status
                        """, Long.class)
                .setParameter("status", AttemptStatus.IN_PROGRESS)
                .getResultList();
    }
}
