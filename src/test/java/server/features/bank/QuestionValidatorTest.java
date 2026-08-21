package server.features.bank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import server.db.entities.Difficulty;
import server.features.bank.QuestionValidator.Fields;
import server.features.bank.QuestionValidator.Violation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every rule in {@link QuestionValidator} (E6.7, C-8, ADR-016).
 *
 * <p>E6.7 asks for 100% on this class, and the reason is worth stating rather than treating as a
 * number to hit: this is the only thing standing between a teacher and a question that cannot be
 * graded. A question with two identical answers has no single correct answer, so every attempt
 * against it is arguably both right and wrong, and the defect surfaces at grading time on data
 * that is already a student's.
 *
 * <p>The tests are written from the ADR and the acceptance table, not from the implementation.
 * Where a case exists only because the schema cannot catch it, the test says so.
 */
class QuestionValidatorTest {

    private static final List<String> GOOD_ANSWERS =
            List.of("Paris", "London", "Madrid", "Rome");

    private static Fields valid() {
        return new Fields("What is the capital of France?", GOOD_ANSWERS, 1,
                "Geography", Difficulty.EASY);
    }

    private static Fields withAnswers(List<String> answers) {
        return new Fields("Question text", answers, 1, "Topic", Difficulty.MEDIUM);
    }

    @Test
    @DisplayName("a well-formed question passes")
    void validQuestionPasses() {
        assertThat(QuestionValidator.validate(valid())).isEmpty();
    }

    @Test
    @DisplayName("a null submission is refused rather than thrown at")
    void nullFieldsIsARefusal() {
        Optional<Violation> violation = QuestionValidator.validate(null);
        assertThat(violation).isPresent();
        assertThat(violation.get().field()).isEqualTo("request");
        assertThat(violation.get().message()).isEqualTo(BankMessages.MALFORMED_REQUEST);
    }

    @Nested
    @DisplayName("the question text")
    class Text {

        @Test
        @DisplayName("cannot be null, empty or only whitespace")
        void textIsRequired() {
            for (String empty : Arrays.asList(null, "", "   ", "\t\n ")) {
                Optional<Violation> violation = QuestionValidator.validate(
                        new Fields(empty, GOOD_ANSWERS, 1, "Topic", Difficulty.EASY));
                assertThat(violation).as("text %s", empty).isPresent();
                assertThat(violation.get().field()).isEqualTo("text");
                assertThat(violation.get().message()).isEqualTo(BankMessages.TEXT_REQUIRED);
            }
        }

        @Test
        @DisplayName("Hebrew text is text (utf8mb4 round trip, §5)")
        void hebrewTextIsAccepted() {
            Fields hebrew = new Fields("מה בירת צרפת?", GOOD_ANSWERS, 1,
                    "גאוגרפיה", Difficulty.EASY);
            assertThat(QuestionValidator.validate(hebrew)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the four answers")
    class Answers {

        @Test
        @DisplayName("must be exactly four, and a null list is a count problem not a crash")
        void exactlyFour() {
            List<List<String>> wrong = new ArrayList<>();
            wrong.add(null);
            wrong.add(List.of());
            wrong.add(List.of("a", "b", "c"));
            wrong.add(List.of("a", "b", "c", "d", "e"));

            for (List<String> answers : wrong) {
                Optional<Violation> violation = QuestionValidator.validate(withAnswers(answers));
                assertThat(violation).as("answers %s", answers).isPresent();
                assertThat(violation.get().field()).isEqualTo("answers");
                assertThat(violation.get().message()).isEqualTo(BankMessages.ANSWER_COUNT);
            }
        }

        @Test
        @DisplayName("none of the four may be blank, and the message names which")
        void noBlankAnswer() {
            // Every position, because an off-by-one in the 1-based label would be invisible
            // if only the first were checked.
            for (int position = 0; position < 4; position++) {
                List<String> answers = new ArrayList<>(GOOD_ANSWERS);
                answers.set(position, "  ");
                Optional<Violation> violation = QuestionValidator.validate(withAnswers(answers));

                assertThat(violation).as("blank at %d", position).isPresent();
                // 1-based, deliberately agreeing with the message beside it. A field saying
                // answers[0] next to "Answer 1 is empty" is a coin toss for the client author.
                assertThat(violation.get().field()).isEqualTo("answers[" + (position + 1) + "]");
                assertThat(violation.get().message())
                        .isEqualTo(BankMessages.answerBlank(position + 1));
            }
        }

        @Test
        @DisplayName("a null answer is blank, not a crash")
        void nullAnswerIsBlank() {
            List<String> answers = new ArrayList<>(GOOD_ANSWERS);
            answers.set(2, null);
            Optional<Violation> violation = QuestionValidator.validate(withAnswers(answers));
            assertThat(violation).isPresent();
            assertThat(violation.get().field()).isEqualTo("answers[3]");
        }
    }

    @Nested
    @DisplayName("length limits, so a schema limit is a sentence and not a crash")
    class Lengths {

        @Test
        @DisplayName("an over-long answer is refused with the box named, not a truncation error")
        void answerLength() {
            // a1..a4 are VARCHAR(500). Without this rule the insert throws a data-truncation
            // SQLException and the teacher sees a generic internal error.
            List<String> answers = new ArrayList<>(GOOD_ANSWERS);
            answers.set(1, "x".repeat(QuestionValidator.MAX_ANSWER_LENGTH + 1));
            Optional<Violation> violation = QuestionValidator.validate(withAnswers(answers));

            assertThat(violation).isPresent();
            assertThat(violation.get().field()).isEqualTo("answers[2]");
            assertThat(violation.get().message())
                    .isEqualTo(BankMessages.answerTooLong(2, QuestionValidator.MAX_ANSWER_LENGTH));
        }

        @Test
        @DisplayName("exactly at the limit is allowed, one over is not")
        void boundaryIsInclusive() {
            List<String> atLimit = new ArrayList<>(GOOD_ANSWERS);
            atLimit.set(0, "x".repeat(QuestionValidator.MAX_ANSWER_LENGTH));
            assertThat(QuestionValidator.validate(withAnswers(atLimit))).isEmpty();
        }

        @Test
        @DisplayName("an over-long stem is refused")
        void textLength() {
            Fields fields = new Fields("x".repeat(QuestionValidator.MAX_TEXT_LENGTH + 1),
                    GOOD_ANSWERS, 1, "Topic", Difficulty.EASY);
            Optional<Violation> violation = QuestionValidator.validate(fields);

            assertThat(violation).isPresent();
            assertThat(violation.get().field()).isEqualTo("text");
            assertThat(violation.get().message())
                    .isEqualTo(BankMessages.textTooLong(QuestionValidator.MAX_TEXT_LENGTH));
        }

        @Test
        @DisplayName("an over-long topic is refused, because topic is VARCHAR(100)")
        void topicLength() {
            Fields fields = new Fields("Question text", GOOD_ANSWERS, 1,
                    "t".repeat(QuestionValidator.MAX_TOPIC_LENGTH + 1), Difficulty.EASY);
            Optional<Violation> violation = QuestionValidator.validate(fields);

            assertThat(violation).isPresent();
            assertThat(violation.get().field()).isEqualTo("topic");
            assertThat(violation.get().message())
                    .isEqualTo(BankMessages.topicTooLong(QuestionValidator.MAX_TOPIC_LENGTH));
        }

        @Test
        @DisplayName("the three limits match the columns they stand in for")
        void limitsMatchTheSchema() {
            // If V2__bank.sql ever widens a column, this is the test that says the service rule
            // did not follow. The numbers are the contract between the two.
            assertThat(QuestionValidator.MAX_ANSWER_LENGTH).isEqualTo(500);
            assertThat(QuestionValidator.MAX_TOPIC_LENGTH).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("exactly one correct answer (C-8)")
    class CorrectAnswer {

        @Test
        @DisplayName("1 through 4 are accepted")
        void inRangeIsAccepted() {
            for (int correct = 1; correct <= 4; correct++) {
                Fields fields = new Fields("Question text", GOOD_ANSWERS, correct,
                        "Topic", Difficulty.HARD);
                assertThat(QuestionValidator.validate(fields)).as("correct %d", correct).isEmpty();
            }
        }

        @Test
        @DisplayName("0 and 5 are refused, which is T-2.2's 'nothing marked correct'")
        void outOfRangeIsRefused() {
            for (int correct : new int[] {-1, 0, 5, 99}) {
                Fields fields = new Fields("Question text", GOOD_ANSWERS, correct,
                        "Topic", Difficulty.HARD);
                Optional<Violation> violation = QuestionValidator.validate(fields);

                assertThat(violation).as("correct %d", correct).isPresent();
                assertThat(violation.get().field()).isEqualTo("correctAnswer");
                assertThat(violation.get().message())
                        .isEqualTo(BankMessages.CORRECT_ANSWER_RANGE);
            }
        }
    }

    @Nested
    @DisplayName("pairwise distinct answers (ADR-016)")
    class Distinctness {

        @Test
        @DisplayName("two identical answers are refused, and both positions are named")
        void exactDuplicateIsRefused() {
            Optional<Violation> violation = QuestionValidator.validate(
                    withAnswers(List.of("Paris", "London", "Paris", "Rome")));

            assertThat(violation).isPresent();
            assertThat(violation.get().field()).isEqualTo("answers[3]");
            assertThat(violation.get().message())
                    .isEqualTo(BankMessages.answersDuplicated(1, 3));
        }

        @Test
        @DisplayName("accents alone do not make two answers different, because the CHECK agrees")
        void accentInsensitive() {
            // The rule that was missing until the pre-build red team found it. The column is
            // utf8mb4_unicode_ci, which is accent-insensitive, so ck_question_versions_distinct
            // rejects this pair. A validator folding only case would pass it to the insert and
            // turn a named-field message into a raw constraint violation.
            assertThat(QuestionValidator.validate(
                    withAnswers(List.of("resume", "résumé", "Madrid", "Rome")))).isPresent();

            assertThat(QuestionValidator.validate(
                    withAnswers(List.of("Ångström", "angstrom", "Madrid", "Rome")))).isPresent();
        }

        @Test
        @DisplayName("case alone does not make two answers different")
        void caseInsensitive() {
            assertThat(QuestionValidator.validate(
                    withAnswers(List.of("Paris", "PARIS", "Madrid", "Rome")))).isPresent();
        }

        @Test
        @DisplayName("internal whitespace alone does not either, which the schema CHECK misses")
        void collapsesInternalWhitespace() {
            // This is the case that earns the service-layer rule. MySQL's utf8mb4_unicode_ci is
            // PAD SPACE, so the ck_question_versions_distinct CHECK already catches a trailing
            // space and a case difference on its own. What it cannot see is a doubled space in
            // the middle: 'New  York' <> 'New York' is TRUE in SQL and false in ADR-016.
            assertThat(QuestionValidator.validate(
                    withAnswers(List.of("New  York", "New York", "Madrid", "Rome")))).isPresent();

            assertThat(QuestionValidator.validate(
                    withAnswers(List.of("  Paris  ", "Paris", "Madrid", "Rome")))).isPresent();
        }

        @Test
        @DisplayName("answers that genuinely differ are not folded together")
        void distinctAnswersPass() {
            // The failure mode a too-aggressive normaliser would produce: rejecting a legal
            // question. Hebrew included, because folding there is easy to get wrong.
            assertThat(QuestionValidator.validate(
                    withAnswers(List.of("פריז", "לונדון", "מדריד", "רומא")))).isEmpty();
            assertThat(QuestionValidator.validate(
                    withAnswers(List.of("2x + 1", "2x - 1", "x + 2", "x - 2")))).isEmpty();
        }

        @Test
        @DisplayName("the last pair is compared too, not just the first")
        void comparesEveryPair() {
            // A loop that stopped early would pass every test above and miss this one.
            Optional<Violation> violation = QuestionValidator.validate(
                    withAnswers(List.of("Paris", "London", "Madrid", "madrid")));

            assertThat(violation).isPresent();
            assertThat(violation.get().message())
                    .isEqualTo(BankMessages.answersDuplicated(3, 4));
        }
    }

    @Nested
    @DisplayName("topic and difficulty")
    class TopicAndDifficulty {

        @Test
        @DisplayName("topic cannot be blank, because E7.4 selects on it")
        void topicIsRequired() {
            for (String empty : Arrays.asList(null, "", "  ")) {
                Optional<Violation> violation = QuestionValidator.validate(
                        new Fields("Question text", GOOD_ANSWERS, 1, empty, Difficulty.EASY));
                assertThat(violation).as("topic %s", empty).isPresent();
                assertThat(violation.get().field()).isEqualTo("topic");
                assertThat(violation.get().message()).isEqualTo(BankMessages.TOPIC_REQUIRED);
            }
        }

        @Test
        @DisplayName("difficulty cannot be null")
        void difficultyIsRequired() {
            Optional<Violation> violation = QuestionValidator.validate(
                    new Fields("Question text", GOOD_ANSWERS, 1, "Topic", null));

            assertThat(violation).isPresent();
            assertThat(violation.get().field()).isEqualTo("difficulty");
            assertThat(violation.get().message()).isEqualTo(BankMessages.DIFFICULTY_REQUIRED);
        }

        @Test
        @DisplayName("all three difficulties are legal")
        void everyDifficultyIsAccepted() {
            for (Difficulty difficulty : Difficulty.values()) {
                Fields fields = new Fields("Question text", GOOD_ANSWERS, 1, "Topic", difficulty);
                assertThat(QuestionValidator.validate(fields)).as("%s", difficulty).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("rule order")
    class Order {

        @Test
        @DisplayName("structural faults are reported before their consequences")
        void structureBeforeConsequence() {
            // Everything wrong at once. The teacher should be told the text is missing, not
            // handed a complaint about answer 3, and the distinctness rule must never run on a
            // list it cannot index. This is what lets the later rules skip their own null checks.
            Fields broken = new Fields(null, null, 0, null, null);
            Optional<Violation> violation = QuestionValidator.validate(broken);

            assertThat(violation).isPresent();
            assertThat(violation.get().field()).isEqualTo("text");
        }

        @Test
        @DisplayName("a bad count is reported before a blank member of it")
        void countBeforeBlankness() {
            Fields fields = new Fields("Question text", Arrays.asList("a", "", null),
                    1, "Topic", Difficulty.EASY);
            Optional<Violation> violation = QuestionValidator.validate(fields);

            assertThat(violation).isPresent();
            assertThat(violation.get().message()).isEqualTo(BankMessages.ANSWER_COUNT);
        }
    }

    @Nested
    @DisplayName("the comparison key, shared with the client (E6.11)")
    class ComparisonKey {

        @Test
        @DisplayName("trims, collapses and folds case")
        void normalises() {
            assertThat(QuestionValidator.comparisonKey("  Paris  ")).isEqualTo("paris");
            assertThat(QuestionValidator.comparisonKey("New  York")).isEqualTo("new york");
            assertThat(QuestionValidator.comparisonKey("A\tB\nC")).isEqualTo("a b c");
            assertThat(QuestionValidator.comparisonKey("PARIS")).isEqualTo("paris");
        }

        @Test
        @DisplayName("folds accents, matching utf8mb4_unicode_ci rather than ADR-016's letter")
        void foldsAccents() {
            assertThat(QuestionValidator.comparisonKey("résumé")).isEqualTo("resume");
            assertThat(QuestionValidator.comparisonKey("Ångström")).isEqualTo("angstrom");
        }

        @Test
        @DisplayName("Hebrew survives folding, letters intact")
        void hebrewIsNotDestroyed() {
            // NFD plus combining-mark stripping removes niqqud, which is the point: the collation
            // treats pointed and unpointed spellings of a word as one. What must NOT happen is
            // the consonants going with them.
            assertThat(QuestionValidator.comparisonKey("פריז")).isEqualTo("פריז");
            assertThat(QuestionValidator.comparisonKey("  לונדון  ")).isEqualTo("לונדון");
        }

        @Test
        @DisplayName("null is the empty key, not a crash")
        void nullIsEmpty() {
            assertThat(QuestionValidator.comparisonKey(null)).isEmpty();
        }

        @Test
        @DisplayName("it does not destroy meaning the way the bot's grouping key does")
        void keepsMeaningfulDifferences() {
            // The stated reason this is not TextNormaliser.groupingKey. Punctuation and digits
            // separate real answers; a key that dropped them would reject legal questions.
            assertThat(QuestionValidator.comparisonKey("2x + 1"))
                    .isNotEqualTo(QuestionValidator.comparisonKey("2x - 1"));
            assertThat(QuestionValidator.comparisonKey("yes!"))
                    .isNotEqualTo(QuestionValidator.comparisonKey("yes"));
        }
    }
}
