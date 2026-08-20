package server.features.grading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import server.features.grading.AutoGrader.PinnedQuestion;
import server.features.grading.AutoGrader.Result;
import server.features.grading.AutoGrader.ScoredQuestion;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link AutoGrader} — E12.1's scoring rules (F8.1, C-8).
 *
 * <p>The fixture is the seeded Algebra Midterm, exam <b>101101 v2</b> from
 * {@code docs/seed/SEED_CONTENT.md} §8.1: seven questions worth 15/15/15/15/15/15/10, totalling
 * 100. Its question version ids stand in for the pinned rows of
 * {@code exam_version_questions} — including 11005 at <b>version 1</b>, which is the seed's
 * deliberate proof that a released exam does not follow later edits (C-2, and H12.6 in the
 * hardening plan).
 *
 * <p>The tests that matter most here are the ones about a plausible wrong answer: an unanswered
 * question that silently vanishes from the detail, a stray answer quietly ignored, or points
 * that do not total 100. Each produces a score that looks entirely reasonable and is wrong,
 * which is the failure mode a defence panel cannot spot and a student would.
 */
class AutoGraderTest {

    // Seed §8.1, exam 101101 v2 — seven questions, 15×6 + 10 = 100.
    private static final long Q_11001 = 11_001;
    private static final long Q_11002 = 11_002;
    private static final long Q_11005_V1 = 11_005;   // pinned at version 1 (seed §7.5)
    private static final long Q_11007 = 11_007;
    private static final long Q_11009 = 11_009;
    private static final long Q_11010 = 11_010;
    private static final long Q_11011 = 11_011;

    /** Correct answers per seed §7.1 for those seven questions. */
    private static final List<PinnedQuestion> ALGEBRA_MIDTERM_V2 = List.of(
            new PinnedQuestion(Q_11001, 15, (byte) 1),
            new PinnedQuestion(Q_11002, 15, (byte) 2),
            new PinnedQuestion(Q_11005_V1, 15, (byte) 2),
            new PinnedQuestion(Q_11007, 15, (byte) 3),
            new PinnedQuestion(Q_11009, 15, (byte) 1),
            new PinnedQuestion(Q_11010, 15, (byte) 3),
            new PinnedQuestion(Q_11011, 10, (byte) 2));

    /** Every question answered correctly. */
    private static Map<Long, Byte> allCorrect() {
        Map<Long, Byte> answers = new LinkedHashMap<>();
        for (PinnedQuestion question : ALGEBRA_MIDTERM_V2) {
            answers.put(question.questionVersionId(), question.correctAnswer());
        }
        return answers;
    }

    @Nested
    @DisplayName("scoring against the seeded Algebra Midterm")
    class Scoring {

        @Test
        @DisplayName("all seven correct scores exactly 100")
        void perfectScore() {
            Result result = AutoGrader.grade(ALGEBRA_MIDTERM_V2, allCorrect());

            assertThat(result.score()).isEqualTo(100);
            assertThat(result.questions()).hasSize(7).allMatch(ScoredQuestion::correct);
        }

        @Test
        @DisplayName("a correct question awards its full points — no partial credit in the auto pass")
        void fullPointsOrNothing() {
            Map<Long, Byte> answers = allCorrect();
            answers.put(Q_11011, (byte) 4);   // the 10-point question, wrong

            Result result = AutoGrader.grade(ALGEBRA_MIDTERM_V2, answers);

            assertThat(result.score()).isEqualTo(90);
            ScoredQuestion wrong = result.questions().get(6);
            assertThat(wrong.correct()).isFalse();
            assertThat(wrong.pointsAwarded()).isZero();
            assertThat(wrong.chosen()).isEqualTo((byte) 4);
        }

        @Test
        @DisplayName("questions are returned in the exam's presentation order, one row each")
        void preservesOrder() {
            Result result = AutoGrader.grade(ALGEBRA_MIDTERM_V2, allCorrect());

            assertThat(result.questions())
                    .extracting(ScoredQuestion::questionVersionId)
                    .containsExactly(Q_11001, Q_11002, Q_11005_V1, Q_11007,
                            Q_11009, Q_11010, Q_11011);
        }

        @Test
        @DisplayName("only the exact correct answer scores — a near miss is still zero (C-8)")
        void onlyTheCorrectAnswerScores() {
            Map<Long, Byte> answers = new HashMap<>();
            answers.put(Q_11007, (byte) 2);   // correct is 3
            answers.put(Q_11009, (byte) 1);   // correct is 1

            Result result = AutoGrader.grade(ALGEBRA_MIDTERM_V2, answers);

            assertThat(result.score()).isEqualTo(15);
        }
    }

    @Nested
    @DisplayName("edge cases from the hardening plan")
    class EdgeCases {

        @Test
        @DisplayName("H12.4 — a timed-out attempt with no answers at all scores 0, and is still graded")
        void noAnswersAtAll() {
            Result result = AutoGrader.grade(ALGEBRA_MIDTERM_V2, Map.of());

            assertThat(result.score()).isZero();
            assertThat(result.questions()).hasSize(7);
            assertThat(result.questions()).allMatch(q -> q.chosen() == null && !q.correct());
        }

        @Test
        @DisplayName("H12.5 — unanswered questions score 0 and still appear in the detail")
        void unansweredQuestionsAppear() {
            Map<Long, Byte> answers = new HashMap<>();
            answers.put(Q_11001, (byte) 1);   // correct, 15
            answers.put(Q_11002, (byte) 2);   // correct, 15
            // the other five are left unanswered

            Result result = AutoGrader.grade(ALGEBRA_MIDTERM_V2, answers);

            assertThat(result.score()).isEqualTo(30);
            assertThat(result.questions()).hasSize(7);
            assertThat(result.questions()).filteredOn(q -> q.chosen() == null)
                    .hasSize(5)
                    .allMatch(q -> q.pointsAwarded() == 0);
        }

        @Test
        @DisplayName("H12.6 — an answer against a version the exam did not pin is rejected, not ignored")
        void answersMustMatchThePinnedVersions() {
            Map<Long, Byte> answers = allCorrect();
            // Question 11005 version 2 — the edited version that exists in the bank but which
            // this released exam does not use. Ignoring it would score 100 and look fine.
            answers.put(99_999L, (byte) 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AutoGrader.grade(ALGEBRA_MIDTERM_V2, answers))
                    .withMessageContaining("99999")
                    .withMessageContaining("does not contain");
        }
    }

    @Nested
    @DisplayName("input that means something upstream is wrong")
    class Guards {

        @Test
        @DisplayName("points not totalling 100 fail here, naming the cause")
        void pointsMustTotal100() {
            List<PinnedQuestion> short90 = List.of(
                    new PinnedQuestion(Q_11001, 45, (byte) 1),
                    new PinnedQuestion(Q_11002, 45, (byte) 2));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AutoGrader.grade(short90, Map.of()))
                    .withMessageContaining("90")
                    .withMessageContaining("100");
        }

        @Test
        @DisplayName("the same question twice in one exam version is refused")
        void duplicateQuestion() {
            List<PinnedQuestion> duplicated = List.of(
                    new PinnedQuestion(Q_11001, 50, (byte) 1),
                    new PinnedQuestion(Q_11001, 50, (byte) 1));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AutoGrader.grade(duplicated, Map.of()))
                    .withMessageContaining("twice");
        }

        @Test
        @DisplayName("an exam version with no questions is refused")
        void emptyExam() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AutoGrader.grade(List.of(), Map.of()))
                    .withMessageContaining("no questions");
        }

        @Test
        @DisplayName("null arguments")
        void nullArguments() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AutoGrader.grade(null, Map.of()));
            assertThatNullPointerException()
                    .isThrownBy(() -> AutoGrader.grade(ALGEBRA_MIDTERM_V2, null));
        }

        @Test
        @DisplayName("a correct answer outside 1..4 is refused when the question is built (C-8)")
        void correctAnswerRange() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PinnedQuestion(Q_11001, 15, (byte) 5))
                    .withMessageContaining("1..4");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PinnedQuestion(Q_11001, 15, (byte) 0))
                    .withMessageContaining("1..4");
        }

        @Test
        @DisplayName("points outside 1..100 are refused when the question is built")
        void pointsRange() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PinnedQuestion(Q_11001, 0, (byte) 1));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PinnedQuestion(Q_11001, 101, (byte) 1));
        }

        @Test
        @DisplayName("the per-question detail is immutable")
        void detailIsImmutable() {
            Result result = AutoGrader.grade(ALGEBRA_MIDTERM_V2, allCorrect());

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> result.questions().clear());
        }
    }

    @Nested
    @DisplayName("the score feeds ScoreStatistics")
    class FeedsStatistics {

        @Test
        @DisplayName("every reachable score is inside the 0..100 range ScoreStatistics accepts")
        void scoresAreAlwaysInRange() {
            assertThat(AutoGrader.grade(ALGEBRA_MIDTERM_V2, Map.of()).score()).isZero();
            assertThat(AutoGrader.grade(ALGEBRA_MIDTERM_V2, allCorrect()).score()).isEqualTo(100);
        }

        @Test
        @DisplayName("a 55 is reachable — the pass mark is not an unreachable boundary on this exam")
        void passMarkIsReachable() {
            // 15+15+15+10 = 55 exactly: the seed's yael.azulay sits on this boundary after
            // her override, so the exam must be able to produce it.
            Map<Long, Byte> answers = new HashMap<>();
            answers.put(Q_11001, (byte) 1);
            answers.put(Q_11002, (byte) 2);
            answers.put(Q_11005_V1, (byte) 2);
            answers.put(Q_11011, (byte) 2);

            Result result = AutoGrader.grade(ALGEBRA_MIDTERM_V2, answers);

            assertThat(result.score()).isEqualTo(ScoreStatistics.PASS_MARK);
        }
    }
}
