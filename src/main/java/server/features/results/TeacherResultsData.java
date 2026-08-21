package server.features.results;

import server.db.entities.ExecutionStats;
import server.db.projections.AuthoredExam;
import server.db.projections.ExecutionContext;
import server.db.projections.StudentResultRow;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The reads the teacher's results screen needs, bound to one transaction (Logic tier, E14.1).
 *
 * <p>The {@code ExamData} pattern, for the same reason: a rule is written inside the
 * transaction that reads the truth it decides from, so nothing here can read a picture, think,
 * and then answer about a world that moved. Handed out by {@link TeacherResultsStore} and
 * valid only for the length of one {@code inTx} call.
 *
 * <p>Every method is a read. E14 shows numbers that were frozen elsewhere (F8.5) and writes
 * nothing at all, which is worth stating rather than inferring: a results screen that could
 * change a grade is a results screen somebody will eventually use to change a grade.
 *
 * <p>Two implementations, deliberately: {@link JpaTeacherResultsStore} over the real
 * repositories, driven against H2 and MySQL, and an in-memory fake in the tests, which is what
 * lets the S-35 scoping rules be proven without a database (TEAM_SPLIT §3.2).
 */
public interface TeacherResultsData {

    /**
     * Every exam this teacher <b>wrote</b>, by display id (S-35).
     *
     * @param teacherId the authenticated caller
     * @return her exams; empty when she has authored none
     */
    List<AuthoredExam> examsAuthoredBy(long teacherId);

    /**
     * Every non-cancelled sitting of the exams this teacher wrote, newest first.
     *
     * <p>Includes runs released by other teachers — that is the whole of S-35 — and excludes
     * cancelled ones, which were never sat (H15.2).
     *
     * @param teacherId the authenticated caller
     * @return the sittings; empty when none of her exams has been released
     */
    List<ExecutionContext> executionsOfExamsAuthoredBy(long teacherId);

    /**
     * One execution's context, whoever it belongs to.
     *
     * <p>Unscoped on purpose: authorship is decided by the caller comparing
     * {@link ExecutionContext#authorId()}, and a read that filtered by teacher here would make
     * "not yours" and "does not exist" indistinguishable in the wrong place — the service
     * needs them to be indistinguishable <em>on the wire</em>, which is not the same thing as
     * being unable to tell them apart internally.
     *
     * @param executionId the execution
     * @return its context, or empty when there is no such row
     */
    Optional<ExecutionContext> executionById(long executionId);

    /**
     * Which of these executions carry frozen statistics (F8.5).
     *
     * @param executionIds the executions to ask about
     * @return the subset whose {@code stats} column is populated
     */
    Set<Long> executionsWithStatistics(Collection<Long> executionIds);

    /**
     * How many students sat each of these executions.
     *
     * @param executionIds the executions to count
     * @return execution id → attempts started; an execution nobody joined is absent
     */
    Map<Long, Integer> participantsByExecution(Collection<Long> executionIds);

    /**
     * How many grade rows exist behind each of these executions.
     *
     * @param executionIds the executions to count
     * @return execution id → grade rows; an unmarked execution is absent
     */
    Map<Long, Integer> gradedByExecution(Collection<Long> executionIds);

    /**
     * The statistics frozen on one execution, exactly as stored.
     *
     * <p>Read, never computed. The service maps this straight onto the wire; nothing between
     * the column and the screen re-derives σ, the pass mark or the buckets (F8.5).
     *
     * @param executionId the execution
     * @return its stored statistics, or empty while grading is unfinished
     */
    Optional<ExecutionStats> statisticsOf(long executionId);

    /**
     * Every marked student in one execution, by name.
     *
     * @param executionId the execution
     * @return one row per grade; empty when nothing has been marked yet
     */
    List<StudentResultRow> resultRowsOf(long executionId);
}
