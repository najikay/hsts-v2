package server.features.approval;

import common.dto.approval.PreviewAnswerRow;
import server.db.entities.ExamVersion;
import server.db.projections.ExamVersionContext;
import server.db.projections.TakeExamQuestion;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The repositories the approval rules need, bound to one transaction (Logic tier, E8).
 *
 * <p>Handed to a unit of work by {@link ApprovalStore#inTx} and valid only for the length of
 * that call — the repositories behind it are stateless and shared, the session is not. The
 * shape is deliberately the same as {@code server.features.exam.ExamData}'s, because the
 * discipline is the same one: every rule in this feature is phrased as "in one transaction,
 * read the current truth and act on it", and a service that read, thought, and then wrote
 * against a world that had moved is exactly the bug {@code lock_version} exists to catch.
 *
 * <h2>Three kinds of read, and why they are separate methods</h2>
 *
 * <p>{@link #versionContext} is metadata and carries no paper. {@link #questionsOf} is the
 * paper <em>as a student would receive it</em>, built by the no-correctness projection
 * (E2.12). {@link #answerKeyOf} is the answer key, and it is the only method on this
 * interface that touches one. Keeping them apart is what lets the queue list twenty exams
 * without loading a single illustration or a single correct answer, and it is what makes the
 * one key-bearing call site visible.
 *
 * <p>{@link #answerKeyOf} returns the wire row rather than {@code QuestionVersion}. That is
 * a deliberate exception to "the seam returns projections, the service maps": the entity is
 * the widest key-bearing type in the system, and letting it into a service whose other
 * responsibility is building a student-shaped preview would put the key one field access
 * away from the wrong list. The mapping it replaces is a rename with no rule in it, so
 * nothing testable is lost by doing it in the store.
 */
public interface ApprovalData {

    /**
     * @param examVersionId the version
     * @return its metadata, or empty when there is no such version
     */
    Optional<ExamVersionContext> versionContext(long examVersionId);

    /**
     * Everything waiting on this coordinator, scoped by the query itself (F4.1).
     *
     * @param coordinatorId the caller, from the session
     * @return pending versions of her subjects, oldest submission first
     */
    List<ExamVersionContext> pendingFor(long coordinatorId);

    /**
     * @param teacherId the caller
     * @return the subject codes she coordinates; empty for a plain teacher
     */
    List<String> coordinatedSubjects(long teacherId);

    /**
     * @param teacherId   the caller
     * @param subjectCode the 2-character subject code
     * @return whether a {@code coordinators} row binds the two (S-1)
     */
    boolean coordinates(long teacherId, String subjectCode);

    /**
     * Who to send an approval request to (S-1).
     *
     * @param subjectCode the 2-character subject code
     * @return the coordinating teacher, or empty when the subject has none yet
     */
    Optional<Long> coordinatorOf(String subjectCode);

    /**
     * The paper, with no correctness anywhere in it (E2.12).
     *
     * @param examVersionId the version
     * @return the questions in exam order, exactly as a student would be served them
     */
    List<TakeExamQuestion> questionsOf(long examVersionId);

    /**
     * The answer key of one paper. <b>The one key-bearing read in this feature.</b>
     *
     * @param examVersionId the version
     * @return which option is right for each question, in exam order
     */
    List<PreviewAnswerRow> answerKeyOf(long examVersionId);

    /**
     * How long each of these papers is, in one query rather than one per row.
     *
     * @param examVersionIds the versions on screen; empty yields an empty map
     * @return version id to question count; a version with no questions is absent
     */
    Map<Long, Integer> questionCounts(List<Long> examVersionIds);

    /**
     * The version as a <b>managed entity</b>, for the one write this feature does.
     *
     * <p>An entity rather than a projection precisely because of {@code @Version}: the
     * status change has to go through the row Hibernate is tracking, so that a second writer
     * who got there first makes this flush fail rather than silently lose.
     *
     * @param examVersionId the version
     * @return the managed row, or empty
     */
    Optional<ExamVersion> versionForUpdate(long examVersionId);

    /**
     * Pushes pending changes so an optimistic-lock failure surfaces inside the rule that
     * caused it, rather than at commit time where it can no longer be turned into a sentence.
     */
    void flush();

    /**
     * Sends every other pending version of one exam back as superseded (E8.2).
     *
     * @param examId        the exam
     * @param keepVersionId the version that has just been submitted
     * @param reason        the fixed system sentence to store
     * @return how many were sent back
     */
    int supersedePending(long examId, long keepVersionId, String reason);
}
