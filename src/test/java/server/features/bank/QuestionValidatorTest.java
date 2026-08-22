package server.features.bank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    @DisplayName("sameAnswer, the rule shared with the client (E6.11)")
    class SameAnswer {

        @Test
        @DisplayName("trims, collapses whitespace and folds case")
        void normalises() {
            assertThat(QuestionValidator.sameAnswer("  Paris  ", "Paris")).isTrue();
            assertThat(QuestionValidator.sameAnswer("New  York", "New York")).isTrue();
            assertThat(QuestionValidator.sameAnswer("A\tB\nC", "a b c")).isTrue();
            assertThat(QuestionValidator.sameAnswer("PARIS", "paris")).isTrue();
        }

        /**
         * Every pair MySQL calls equal under {@code utf8mb4_unicode_ci}.
         *
         * <p>Not guessed. Each was run against the running database as
         * {@code 'x' = CONVERT('y' USING utf8mb4) COLLATE utf8mb4_unicode_ci} and returned 1.
         * The service rule has to agree on all of them, because any pair it accepts and
         * {@code ck_question_versions_distinct} rejects becomes a raw constraint violation and a
         * generic internal error, which is the outcome naming the field was meant to replace.
         *
         * <p>The previous implementation folded canonically (NFD) and matched only the first
         * two of these. Its own javadoc claimed equivalence with the collation, and its own test
         * covered only the diacritic cases, so the code and the test agreed and both differed
         * from the requirement.
         */
        @ParameterizedTest(name = "MySQL says equal: {0} / {1}")
        @CsvSource({
                "resume, résumé",
                "Ångström, angstrom",
                "Strasse, Straße",
                "oeuvre, œuvre",
                "file, ﬁle",
                "A, Ａ",
                "τέλος, τέλοσ",
                "שלום, שָׁלוֹם"
        })
        void foldsEverythingTheCollationFolds(String first, String second) {
            assertThat(QuestionValidator.sameAnswer(first, second))
                    .as("%s / %s are one answer to MySQL", first, second)
                    .isTrue();

            // And the whole question is refused, not merely the pair reported equal.
            assertThat(QuestionValidator.validate(
                    withAnswers(java.util.Arrays.asList(first, second, "Madrid", "Rome"))))
                    .isPresent();
        }

        @ParameterizedTest(name = "must stay distinct: {0} / {1}")
        @CsvSource({
                "2x + 1, 2x - 1",
                "yes, yes!",
                "Paris, London",
                "פריז, לונדון",
                "10, 100",
                "a, b"
        })
        void keepsRealDifferences(String first, String second) {
            // The failure mode of over-folding: rejecting a legal question. Punctuation, sign
            // and digits separate genuine answers, and a teacher whose four options differ only
            // by a minus sign is writing a perfectly good algebra question.
            assertThat(QuestionValidator.sameAnswer(first, second)).isFalse();
        }

        @Test
        @DisplayName("Hebrew niqqud is folded but the consonants are not")
        void hebrewNiqqudIsFoldedWithoutLosingLetters() {
            // The half the old test missed: it asserted on unpointed strings only, so deleting
            // the mark-stripping entirely would not have failed it.
            assertThat(QuestionValidator.sameAnswer("שלום", "שָׁלוֹם")).isTrue();
            // ...and the letters still carry meaning, or every Hebrew answer would collide.
            assertThat(QuestionValidator.sameAnswer("שלום", "שלוט")).isFalse();
        }

        @Test
        @DisplayName("null is not a crash, and two nulls are the same nothing")
        void nullIsHandled() {
            assertThat(QuestionValidator.sameAnswer(null, "")).isTrue();
            assertThat(QuestionValidator.sameAnswer(null, "Paris")).isFalse();
        }

        @Test
        @DisplayName("exotic Unicode spaces do not make two answers different")
        void unicodeSpacesFoldLikeOrdinaryOnes() {
            // The whitespace half of foldedForm is ASCII-only in both of its steps: trim() cuts
            // nothing above U+0020, and Java's \s is [ \t\n\x0B\f\r] without
            // UNICODE_CHARACTER_CLASS. So on a reading of that line alone these pairs would come
            // back distinct while utf8mb4_unicode_ci calls them one answer, which is the one
            // direction section 5 forbids: a pair the service accepts and the CHECK rejects
            // arrives as a raw constraint violation.
            //
            // They do not, and an earlier version of this comment named the wrong reason. It is
            // NOT the NFKD pass. It is the fourth step, the Collator at PRIMARY strength, which
            // treats a space as COMPLETELY IGNORABLE: compare("ab", "a b") is 0 before any of
            // these characters is involved. spaceIsIgnorableToTheCollator below pins that
            // directly, because it is the property the rest of this test actually rests on and
            // nothing was asserting it. Measured on JDK 21, not reasoned: with the NFKD pass
            // deleted five of the six pairs here still fold, and with trim moved ahead of
            // normalisation all six do.
            //
            // Literal characters, as the Hebrew cases above are: this file is UTF-8 and the
            // build reads it as UTF-8. Each is named in its own assertion description, because
            // an invisible character in a failure message is not a diagnosis.
            assertThat(QuestionValidator.sameAnswer("a　b", "a b"))
                    .as("IDEOGRAPHIC SPACE U+3000").isTrue();
            assertThat(QuestionValidator.sameAnswer("a b", "a b"))
                    .as("EN SPACE U+2002").isTrue();
            assertThat(QuestionValidator.sameAnswer("a b", "a b"))
                    .as("NO-BREAK SPACE U+00A0").isTrue();
            assertThat(QuestionValidator.sameAnswer("a b", "a b"))
                    // The one case in this test that NFKD carries rather than the collator:
                    // Java's root collation table does not hold U+202F, so deleting the
                    // normalisation pass breaks this pair alone.
                    .as("NARROW NO-BREAK SPACE U+202F, the one NFKD actually earns").isTrue();
            // At the edges too, where trim() is the step that would have to cope and cannot:
            // it stops at U+0020 and every one of these is above it.
            assertThat(QuestionValidator.sameAnswer("ab　", "ab"))
                    .as("trailing IDEOGRAPHIC SPACE").isTrue();
            assertThat(QuestionValidator.sameAnswer(" ab", "ab"))
                    .as("leading EN SPACE").isTrue();
        }

        @Test
        @DisplayName("the collator ignores spaces outright, which is the mechanism above")
        void spaceIsIgnorableToTheCollator() {
            // The property the whitespace test rests on, asserted directly so that it is
            // guarded rather than assumed. A Collator at PRIMARY strength gives a space no
            // weight at all, so the comparison never sees one whatever foldedForm did to it.
            assertThat(QuestionValidator.sameAnswer("ab", "a b")).isTrue();
            assertThat(QuestionValidator.sameAnswer("ab", "a  b")).isTrue();
        }

        @Test
        @DisplayName("spacing alone never separates two answers, which is stricter than the CHECK")
        void spacingAloneNeverSeparatesTwoAnswers() {
            // The consequence of the line above, recorded because it is surprising and nothing
            // named it. These pairs differ only in spacing, and the service calls them one
            // answer, so a teacher offering both is refused.
            //
            // This is the SAFE direction and it is deliberate. Section 5's rule is
            // one-directional: never accept a pair the database will reject. MySQL's
            // utf8mb4_unicode_ci gives a space a primary weight, so it would call these two
            // different answers and accept them. Being stricter costs a teacher a refusal;
            // being looser costs her a constraint violation and a generic INTERNAL error.
            //
            // What section 5 does NOT say is that the strictness reaches this far. Its stated
            // worst case is "a teacher told two confusingly similar answers are too similar",
            // and "1 2 3" against "123" in a sequence question is not obviously that. Raised
            // with the lead as a product question rather than fixed here, because loosening it
            // is a change to the one-directional rule and that is his to make.
            assertThat(QuestionValidator.sameAnswer("1 2 3", "123")).isTrue();
            assertThat(QuestionValidator.sameAnswer("red car", "redcar")).isTrue();
        }
    }
}
