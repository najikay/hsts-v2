package server.features.exam;

import server.db.entities.AttemptStatus;
import server.db.projections.AnswerRow;
import server.db.projections.AttemptRecord;
import server.db.projections.AttemptRow;
import server.db.projections.ExecutionContext;
import server.db.projections.ParticipationCounts;
import server.db.projections.QuestionOutline;
import server.db.projections.TakeExamQuestion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything take-exam and monitoring do to the database, <b>inside one transaction</b>
 * (Logic tier, E10/E11).
 *
 * <p>An instance of this is handed to a unit of work by {@link ExamStore#inTx}; it is
 * valid only for the duration of that call and must never be stored. That scoping is the
 * whole design, and it exists because of one requirement: every write path has to re-check
 * the attempt's status <em>and</em> its deadline against the server clock <em>in the same
 * transaction</em> as the write. A per-call data object makes that shape the natural one to
 * write, whereas a repository with a session parameter makes "read here, decide there,
 * write later" equally easy — and that gap is precisely where an answer saved after the
 * bell gets in.
 *
 * <p>The rules stay in the services. This interface has no opinion about whether a student
 * may start, whether a code is live, or who wins a race; it moves rows. The one exception
 * is {@link #finalizeAttempt}, which is a compare-and-set rather than a write because the
 * atomicity <em>is</em> the operation and splitting it would be handing the caller a
 * loaded gun (§5, ADR-010).
 *
 * <p>Being an interface is also what makes E10.8's concurrency scenarios testable two ways:
 * a fast in-memory implementation for the unit tests of every rule, and the real JPA one
 * driven against MySQL for the races that only mean something with a database underneath.
 */
public interface ExamData {

    // ===================== Executions and people =========================

    /**
     * Every execution using this code, newest first, whatever its state (E10.9).
     *
     * <p>Unfiltered on purpose. "There is no such code" and "that exam has already closed"
     * are different things to tell a student standing in an exam hall, and a read that
     * returned only live rows could not tell them apart.
     *
     * @param code the 4-character code as typed; compared case-insensitively (C-1)
     * @return the matching executions, newest first; empty when the code is unknown
     */
    List<ExecutionContext> executionsByCode(String code);

    /**
     * One execution by id, whatever its state.
     *
     * @param executionId the execution
     * @return its context, or empty
     */
    Optional<ExecutionContext> executionById(long executionId);

    /**
     * A user's identity row, for the S-18 check and for display names.
     *
     * @param userId the user
     * @return the identity, or empty when there is no such user
     */
    Optional<StudentIdentity> user(long userId);

    /**
     * @param studentId  the student
     * @param courseCode the course
     * @return {@code true} when she is enrolled (S-15)
     */
    boolean isEnrolled(long studentId, String courseCode);

    // ===================== The paper =====================================

    /**
     * The questions of one exam version, <b>without any correctness data</b> (E2.12 ⚑).
     *
     * <p>Returns the no-correctness projection, not entities. The answer key is not
     * selected by the query behind this, so on this path it never leaves the database.
     *
     * @param examVersionId the pinned version being sat
     * @return the paper, in order
     */
    List<TakeExamQuestion> questionsOf(long examVersionId);

    /**
     * How many questions the paper has, without fetching it.
     *
     * <p>What the join screen shows before an identity has been entered: a student may see
     * how long the paper is before she starts, but not the paper (S-18).
     *
     * @param examVersionId the pinned version
     * @return the question count
     */
    int questionCountOf(long examVersionId);

    /**
     * The paper's shape without its content: positions, ids and points.
     *
     * <p>What the answer-summary grid is built from, on submit, on expiry and on every
     * monitor push. Using {@link #questionsOf} for those would fetch every illustration in
     * the exam to draw a row of numbered chips.
     *
     * @param examVersionId the pinned version
     * @return the outline in exam order
     */
    List<QuestionOutline> outlineOf(long examVersionId);

    /**
     * Whether a question is actually on this paper.
     *
     * <p>The autosave verb is handed a question id by a client, and a client can send any
     * number at all. Without this, an answer could be written against a question from
     * somebody else's exam and would then be marked.
     *
     * @param examVersionId     the paper being sat
     * @param questionVersionId the question the client claims to be answering
     * @return {@code true} when it belongs to that paper
     */
    boolean isOnPaper(long examVersionId, long questionVersionId);

    // ===================== Attempts ======================================

    /**
     * @param executionId the execution
     * @param studentId   the student
     * @return her attempt at it, or empty when she has not started
     */
    Optional<AttemptRecord> attemptOf(long executionId, long studentId);

    /**
     * @param attemptId an attempt id, typically one a client sent
     * @return the attempt, or empty; the caller still has to check whose it is
     */
    Optional<AttemptRecord> attemptById(long attemptId);

    /**
     * Opens an attempt (S-18).
     *
     * <p>Throws rather than answering empty when the unique key refuses it, because a
     * failed insert poisons the transaction it happened in: the caller must let this one
     * roll back and read the winning attempt in a fresh one. See
     * {@link DuplicateAttemptException}.
     *
     * @param executionId the execution
     * @param studentId   the student
     * @param startedAt   the server clock reading; S-18's clock starts here
     * @return the new attempt
     * @throws DuplicateAttemptException when this student already has one
     */
    AttemptRecord createAttempt(long executionId, long studentId, Instant startedAt);

    /**
     * The compare-and-set that ends an attempt (§5).
     *
     * @param attemptId     the attempt
     * @param status        SUBMITTED or TIMED_OUT
     * @param endedAt       when it closed
     * @param actualMinutes solving time to record (S-19)
     * @return 1 when this caller won, 0 when somebody else already closed it
     */
    int finalizeAttempt(long attemptId, AttemptStatus status, Instant endedAt, int actualMinutes);

    /**
     * @param executionId the execution
     * @return its in-progress attempts, which is who an extension has to reach
     */
    List<AttemptRecord> liveAttemptsOf(long executionId);

    /**
     * Every in-progress attempt on the server, for re-arming timers after a restart.
     *
     * @return the live attempts
     */
    List<AttemptRecord> allLiveAttempts();

    // ===================== Answers =======================================

    /**
     * @param attemptId the attempt
     * @return the choices it is holding, for a resuming client
     */
    List<AnswerRow> answersOf(long attemptId);

    /**
     * Writes one autosaved choice, creating or replacing the row.
     *
     * @param attemptId         the attempt
     * @param questionVersionId the question
     * @param selected          1..4, or {@code null} to clear
     * @param savedAt           the server clock reading
     */
    void upsertAnswer(long attemptId, long questionVersionId, Byte selected, Instant savedAt);

    /**
     * @param attemptId the attempt
     * @return how many of its questions carry a choice
     */
    int countAnswered(long attemptId);

    // ===================== Monitoring ====================================

    /**
     * The three participation numbers, counted from attempts (S-21, §5: no counters).
     *
     * @param executionId the execution
     * @return the counts
     */
    ParticipationCounts participationOf(long executionId);

    /**
     * @param executionId the execution
     * @return one row per student sitting it, by name
     */
    List<AttemptRow> rowsOf(long executionId);

    /**
     * @param executionId the execution
     * @return attempt id → answered count, in one query rather than one per row
     */
    Map<Long, Integer> answeredCountsOf(long executionId);

    // ===================== Writes on the execution =======================

    /**
     * Grants extra minutes to a live execution (S-20).
     *
     * <p>Attempt deadlines are derived from this number, so nothing else has to be
     * rewritten and a student who is offline gains the time the moment she resumes (E11.4).
     *
     * @param executionId the execution
     * @param minutes     minutes to add, positive
     * @return the new total of granted minutes
     */
    int addExtraMinutes(long executionId, int minutes);

    /**
     * Moves the window's close so granted minutes can actually be taken (B-14 ⚑ — F7.1).
     *
     * <p>The other half of an extension. Adding minutes moves every derived deadline, and
     * before B-14 nothing checked that the window they now land in is still open: a teacher
     * granted fifteen, the toast announced them, and {@code ReleaseScheduler} closed the
     * execution at the old bell with the student forty-five minutes short of what she had
     * been promised.
     *
     * <p>Only ever called with a <b>later</b> close than the stored one — the extend path
     * takes a {@code max} first — so this can widen a sitting and can never shorten one out
     * from under the students in it. Closing early is a different verb with its own rules
     * (F5.5).
     *
     * @param executionId the execution
     * @param closeAt     the new stored close, extensions <b>not</b> included
     */
    void moveCloseAt(long executionId, Instant closeAt);

    /**
     * Closes an execution and freezes its counts into the documentation record (S-21, F7.3).
     *
     * @param executionId the execution
     * @param counts      the counts as of now
     */
    void closeExecution(long executionId, ParticipationCounts counts);
}
