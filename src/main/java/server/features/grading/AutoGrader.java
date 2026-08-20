package server.features.grading;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Scores one submitted attempt against the exam version it was sat on (Logic tier, E12.1).
 *
 * <p>Implements F8.1: a question is correct when the student's selection equals the single
 * correct answer (C-8 / ADR-016), and a correct question awards its full points — there is no
 * partial credit in the automatic pass. The manual override (F8.3, T-8.3) is how a teacher
 * awards partial credit, and it is deliberately a separate, justified, audited action.
 *
 * <p>Like {@link ScoreStatistics} this is a <b>pure function</b>: no entities, no repositories,
 * no session. The service loads the pinned question rows and the attempt's answers, calls this,
 * and persists the result. That separation is what lets the scoring rules be tested exhaustively
 * against fixtures rather than against a database, and it keeps the one piece of arithmetic a
 * student's grade depends on free of persistence concerns.
 *
 * <p><b>The caller supplies the PINNED question versions</b> — the ones recorded in
 * {@code exam_version_questions} for the version the attempt was sat on, never the latest
 * version of each question. That rule (PRD §6, "auto-grading always checks against the exam's
 * PINNED question version") lives with the caller because only it can read the exam version;
 * what this class does is refuse to paper over a mismatch — see
 * {@link #grade(List, Map)}.
 */
public final class AutoGrader {

    /** Points across one exam version must total exactly 100 (F3.1, T-3 note). */
    public static final int REQUIRED_TOTAL_POINTS = 100;

    private AutoGrader() {
        // static helper — no instances
    }

    /**
     * One question of the exam version, as pinned when the exam was built.
     *
     * @param questionVersionId the pinned {@code question_versions.id}
     * @param points            points this question is worth
     * @param correctAnswer     the single correct answer, 1..4 (C-8)
     */
    public record PinnedQuestion(long questionVersionId, int points, byte correctAnswer) {

        public PinnedQuestion {
            if (points < 1 || points > REQUIRED_TOTAL_POINTS) {
                throw new IllegalArgumentException(
                        "Points must be 1.." + REQUIRED_TOTAL_POINTS + ", got " + points);
            }
            if (correctAnswer < 1 || correctAnswer > 4) {
                throw new IllegalArgumentException(
                        "Correct answer must be 1..4, got " + correctAnswer);
            }
        }
    }

    /**
     * How one question was scored — the row the checked form (E13.2) and the teacher's review
     * (E12.6) both render.
     *
     * @param questionVersionId the pinned question version
     * @param chosen            what the student selected, or {@code null} when unanswered
     * @param correct           whether {@code chosen} equals the correct answer
     * @param pointsAwarded     the question's full points when correct, otherwise 0
     */
    public record ScoredQuestion(long questionVersionId, Byte chosen, boolean correct, int pointsAwarded) {
    }

    /**
     * The outcome of scoring one attempt.
     *
     * @param score     total awarded points, 0..100
     * @param questions per-question detail, in the order the exam presents them
     */
    public record Result(int score, List<ScoredQuestion> questions) {

        public Result {
            questions = List.copyOf(questions);
        }
    }

    /**
     * Scores an attempt.
     *
     * <p>An unanswered question scores 0 rather than being skipped, so the returned detail has
     * exactly one row per exam question and the checked form can render "unanswered" instead of
     * omitting it (F6.9's "unanswered score 0" promise, honoured server-side rather than only
     * stated in a dialog).
     *
     * <p>An answer for a question version the exam version does not contain is rejected rather
     * than ignored. Silently dropping it would hide precisely the bug it indicates: answers
     * recorded against a different — probably newer — version of a question than the one the
     * exam pinned. Ignoring it would still produce a plausible-looking score, which is the worst
     * possible failure mode for a grade.
     *
     * @param examQuestions the pinned questions of the exam version, in presentation order
     * @param chosenByQuestionVersion the student's selections, keyed by question version id;
     *                                a missing key is an unanswered question
     * @return the score and the per-question detail
     * @throws NullPointerException     if either argument or any element is null
     * @throws IllegalArgumentException if the exam is empty, contains a duplicate question
     *                                  version, does not total {@value #REQUIRED_TOTAL_POINTS}
     *                                  points, or the answers reference a question the exam
     *                                  does not contain
     */
    public static Result grade(List<PinnedQuestion> examQuestions,
                               Map<Long, Byte> chosenByQuestionVersion) {
        Objects.requireNonNull(examQuestions, "examQuestions");
        Objects.requireNonNull(chosenByQuestionVersion, "chosenByQuestionVersion");
        if (examQuestions.isEmpty()) {
            throw new IllegalArgumentException("An exam version has no questions to grade");
        }

        Set<Long> examQuestionIds = new HashSet<>();
        int totalPoints = 0;
        for (PinnedQuestion question : examQuestions) {
            Objects.requireNonNull(question, "examQuestions contains a null question");
            if (!examQuestionIds.add(question.questionVersionId())) {
                throw new IllegalArgumentException(
                        "Question version " + question.questionVersionId()
                                + " appears twice in one exam version");
            }
            totalPoints += question.points();
        }
        if (totalPoints != REQUIRED_TOTAL_POINTS) {
            // A score outside 0..100 would be rejected downstream by ScoreStatistics with a
            // message about the score; failing here names the actual cause.
            throw new IllegalArgumentException(
                    "Exam version points total " + totalPoints
                            + ", must be " + REQUIRED_TOTAL_POINTS + " (F3.1)");
        }

        for (Long answeredId : chosenByQuestionVersion.keySet()) {
            Objects.requireNonNull(answeredId, "answers contain a null question version id");
            if (!examQuestionIds.contains(answeredId)) {
                throw new IllegalArgumentException(
                        "Attempt answers question version " + answeredId
                                + ", which this exam version does not contain —"
                                + " the attempt and the pinned exam disagree");
            }
        }

        List<ScoredQuestion> scored = new ArrayList<>(examQuestions.size());
        int score = 0;
        for (PinnedQuestion question : examQuestions) {
            Byte chosen = chosenByQuestionVersion.get(question.questionVersionId());
            boolean correct = chosen != null && chosen == question.correctAnswer();
            int awarded = correct ? question.points() : 0;
            score += awarded;
            scored.add(new ScoredQuestion(question.questionVersionId(), chosen, correct, awarded));
        }
        return new Result(score, scored);
    }
}
