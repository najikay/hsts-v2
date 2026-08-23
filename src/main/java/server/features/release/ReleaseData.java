package server.features.release;

import server.db.entities.ExecutionStatus;
import server.db.projections.ExamVersionContext;
import server.db.projections.ExecutionContext;
import server.db.projections.ParticipationCounts;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the release manager does to the database, <b>inside one transaction</b>
 * (Logic tier, E9).
 *
 * <p>The same shape as {@code ExamData}, and for the same reason: an instance is handed to a
 * unit of work by {@link ReleaseStore#inTx} and is valid only for that call. One rule here
 * genuinely needs it. Code uniqueness is a service rule (§5, C-1) with no constraint behind
 * it, so "is this code free" and "insert a release using it" have to be one transaction, or
 * two teachers creating a release in the same second can both be told yes.
 *
 * <p>The rules stay in {@link ReleaseService} and {@link ReleaseScheduler}. This interface
 * has no opinion about whether a version may be released, who owns a release, or what state
 * it should be in; it moves rows. The one exception is {@link #transition}, which is a
 * compare-and-set rather than a write because the atomicity <em>is</em> the operation: the
 * scheduled check flipping a release to live and a teacher cancelling it are two writers a
 * second apart and exactly one of them has to win.
 *
 * <p>Being an interface is what makes every rule in E9 testable without a database, and then
 * testable again against both engines through the contract suite over the JPA one.
 */
public interface ReleaseData {

    // ===================== The drawer ====================================

    /**
     * The approved versions this teacher may take out of the drawer (F5.1, S-14).
     *
     * <p>Filtered to {@code APPROVED} in the query, which is where PRD §6's "release
     * unapproved version → impossible (not listed)" actually lives.
     *
     * @param teacherId the caller, from the session
     * @return her releasable versions, newest first
     */
    List<ExamVersionContext> releasableVersionsFor(long teacherId);

    /**
     * @param teacherId the caller
     * @return {@code true} when she has any exam at all in the courses she teaches, which is
     *         what tells "nothing approved yet" apart from "nothing written yet"
     */
    boolean hasAnyExam(long teacherId);

    /**
     * One version by id, <b>whatever its status</b>.
     *
     * <p>Unfiltered on purpose: the create path has to be able to tell "no such version"
     * from "that version is still a draft", because only the second has the F5.1 sentence as
     * its answer. A read that returned approved versions only could not.
     *
     * @param examVersionId the version a client named
     * @return its context, or empty
     */
    Optional<ExamVersionContext> versionById(long examVersionId);

    /**
     * @param teacherId  the caller
     * @param courseCode the course the version belongs to
     * @return {@code true} when she teaches it; the ownership half of the create gate
     */
    boolean teaches(long teacherId, String courseCode);

    // ===================== Codes and creation ============================

    /**
     * @param code a candidate 4-character code
     * @return {@code true} when a scheduled or live release already holds it (C-1, §5)
     */
    boolean isCodeInUse(String code);

    /**
     * Inserts a release, always {@code SCHEDULED} (F5.1, S-2).
     *
     * @param examVersionId the approved version; approval is the service's check
     * @param code          the generated code, checked free in this same transaction
     * @param openAt        when the window opens
     * @param closeAt       when it shuts
     * @param createdBy     the releasing teacher, always the authenticated caller
     * @return the new release's id
     */
    long createExecution(long examVersionId, String code, Instant openAt, Instant closeAt,
                         long createdBy);

    // ===================== Reading releases ==============================

    /**
     * @param executionId the release
     * @return its context, or empty; the caller still checks whose it is
     */
    Optional<ExecutionContext> executionById(long executionId);

    /**
     * Every release one teacher may act on: hers to run, or hers to have written (S-35).
     *
     * @param teacherId the caller, from the session
     * @return her releases, newest window first
     */
    List<ExecutionContext> executionsFor(long teacherId);

    /**
     * Participation for several releases in one query (S-21, §5: counted, never accumulated).
     *
     * @param executionIds the releases on screen
     * @return execution id → counts; releases nobody joined are absent, and the caller
     *         defaults them
     */
    Map<Long, ParticipationCounts> participationOf(Collection<Long> executionIds);

    // ===================== The scheduled check ===========================

    /**
     * Scheduled releases whose window opens at or before {@code limit} (E9.2).
     *
     * <p>One read for both halves of the check: the ones due to open now, and the ones due
     * soon enough to warn about. The caller partitions them against its own clock.
     *
     * @param limit the far edge of the window to look at
     * @return them, soonest first
     */
    List<ExecutionContext> scheduledOpeningBy(Instant limit);

    /**
     * Live releases whose <b>stored</b> close time has passed (E9.2).
     *
     * <p>Extensions are not in the filter, because no portable query adds a column of
     * minutes to a timestamp; the caller re-checks the effective end and skips the extended
     * ones. Over-fetching a handful costs nothing, and a query that guessed would close an
     * exam a teacher had just added fifteen minutes to.
     *
     * @param limit the server clock reading
     * @return them, oldest first
     */
    List<ExecutionContext> liveClosingBy(Instant limit);

    /**
     * Moves a release between states, if it is still in the first (§5).
     *
     * @param executionId the release
     * @param from        the state it must still be in
     * @param to          where it goes
     * @return 1 when this caller won, 0 when the row had already moved
     */
    int transition(long executionId, ExecutionStatus from, ExecutionStatus to);

    /**
     * @param courseCode the course
     * @return the enrolled students' ids, for the "opens soon" notice (F11.1)
     */
    List<Long> enrolledStudents(String courseCode);
}
