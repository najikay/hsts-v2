package server.features.grading;

import org.hibernate.Session;

import java.util.List;
import java.util.Map;

/**
 * The reads {@link GradingService} needs, as a port it owns (Logic tier, E12.1).
 *
 * <p>Two reasons this is an interface rather than a direct repository call.
 *
 * <p><b>Testability.</b> TEAM_SPLIT §3.2 prescribes services tested against fakes; this is the
 * seam that makes {@code GradingService} provable without a database, exactly as
 * {@link AutoGrader} and {@link ScoreStatistics} are.
 *
 * <p><b>An open naming decision.</b> {@link #pinnedQuestions} returns an answer key, and
 * {@code CorrectnessLeakGuardTest} requires every repository read that does so to be named with
 * a sanctioned suffix — currently only {@code ForAuthoring} and {@code ForCheckedForm}. Grading
 * is neither: a teacher auto-grading an attempt is not authoring a question, and is not a
 * student looking at their own marked paper. The frozen contract needs this read and
 * {@code GRADE_REVIEW_GET} needs another like it, so a third audience exists whether or not it
 * has a name yet. Keeping the port here means the decision changes one adapter class and
 * nothing in the grading logic.
 */
public interface GradingReads {

    /**
     * The questions an exam version pinned, in presentation order, with their points and
     * correct answers.
     *
     * <p>Must return the versions recorded in {@code exam_version_questions} — never the latest
     * version of each question (PRD §6, H12.6). {@link AutoGrader} refuses to grade an attempt
     * whose answers disagree with what this returns, so a wrong read here fails loudly rather
     * than producing a plausible score.
     *
     * @param session       the current session
     * @param examVersionId the exam version the attempt was sat on
     * @return the pinned questions, ordered by {@code ordinal}
     */
    List<AutoGrader.PinnedQuestion> pinnedQuestions(Session session, long examVersionId);

    /**
     * What the student selected, keyed by question version id.
     *
     * <p>A question the student never answered is <b>absent from the map</b> rather than present
     * with a null value: {@link AutoGrader} treats a missing key as unanswered and scores it 0
     * (F6.9), and an explicit null would be a second way to say the same thing.
     *
     * @param session   the current session
     * @param attemptId the attempt
     * @return selections by question version id; empty when nothing was answered
     */
    Map<Long, Byte> selectedAnswers(Session session, long attemptId);

    /**
     * The exam version an execution released.
     *
     * @param session     the current session
     * @param executionId the execution
     * @return the exam version id
     * @throws IllegalStateException if the execution does not exist
     */
    long examVersionOf(Session session, long executionId);
}
