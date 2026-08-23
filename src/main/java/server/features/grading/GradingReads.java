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
 * <p><b>A naming decision, now settled.</b> {@link #pinnedQuestions} returns an answer key, and
 * {@code CorrectnessLeakGuardTest} requires every repository read that does so to be named with
 * a sanctioned suffix. Grading was not {@code ForAuthoring} — a teacher auto-grading an attempt
 * is not authoring a question — so {@code ForGrading} was sanctioned for it, and it is what the
 * adapter's read is called. It serves {@code GRADE_REVIEW_GET} too, and E13.4's checked form
 * through the shared assembler; that last one is gated by its three conditions rather than by a
 * name, which is why the {@code ForCheckedForm} suffix was withdrawn on 2026-08-23. Keeping the
 * port here means such a decision changes one adapter class and nothing in the grading logic.
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
