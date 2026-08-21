package server.features.bank;

import server.db.entities.Difficulty;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The one definition of a valid question, shared by create and edit (E6.7, E6.2).
 *
 * <p>Strategy pattern: each rule is a {@link Rule}, the rules are an ordered list, and
 * {@link #validate} answers the first violation or nothing. Adding a rule is adding a lambda to
 * {@link #RULES}, and no caller changes.
 *
 * <h2>Why one validator and not two</h2>
 *
 * <p>Create and edit validate the same thing, and in v1 they did not: the add form checked
 * duplicate answers and the edit form did not, so a question could be edited into a state it
 * could never have been created in. One shared object is the fix, and it is why {@link Fields}
 * exists as a plain server-side value rather than the validator taking the two wire payloads.
 * {@code QuestionDraft} and {@code QuestionEdit} both map into {@code Fields}, so there is no
 * path that validates one and not the other.
 *
 * <h2>What is deliberately NOT here</h2>
 *
 * <p><b>The S-5 course check.</b> "Does this teacher teach this course" needs a database session,
 * and putting it here would make every one of these rules untestable without one. It lives in
 * {@code QuestionService}, next to the other authorization, where it belongs.
 *
 * <p><b>Image validation.</b> Same reason in reverse: it is about bytes, not fields, and E6.6's
 * rules (2MB, PNG or JPEG sniffed from content) have nothing to say about a question's text.
 *
 * <h2>Comparing answers: ADR-016's rule, and why it is not the bot's</h2>
 *
 * <p>ADR-016 says the four answers must be pairwise distinct, compared after trimming and
 * collapsing whitespace, case-insensitively. {@link #comparisonKey} is that rule and only that
 * rule.
 *
 * <p>It is not {@code TextNormaliser.groupingKey}, which exists in the bot feature and looks
 * similar. That one is documented as needing to "destroy quite a lot" of meaning, because it
 * groups questions students phrased differently. Destroying meaning is exactly wrong here: two
 * answers that a student can tell apart are two different answers, and a comparison that folded
 * them together would reject a legitimate question. Different jobs that happen to share a first
 * step, in different features (ADR-013), kept separate on purpose.
 */
public final class QuestionValidator {

    /** The number of answers a question has (C-7, C-8). Not configurable, spelled out once. */
    public static final int ANSWER_COUNT = 4;

    /**
     * Maximum answer length, from {@code a1..a4 VARCHAR(500)} in {@code V2__bank.sql}.
     *
     * <p>These three maxima are not tidiness. Without them an over-long value reaches the insert
     * and comes back as a data-truncation {@code SQLException}, so the teacher sees a generic
     * internal error instead of a sentence naming the box she overfilled. A schema limit that no
     * service rule mirrors is a limit whose enforcement is a stack trace.
     */
    public static final int MAX_ANSWER_LENGTH = 500;

    /** Maximum topic length, from {@code topic VARCHAR(100)}. */
    public static final int MAX_TOPIC_LENGTH = 100;

    /**
     * Maximum question-stem length.
     *
     * <p>Unlike the other two this is a <b>product</b> limit rather than a schema one: the column
     * is {@code TEXT}, which holds 65535 bytes. 4000 characters stays well inside that even for
     * Hebrew at two bytes a character, and a question stem longer than that is a reading
     * comprehension passage rather than a question.
     */
    public static final int MAX_TEXT_LENGTH = 4000;

    /**
     * One thing that can be wrong, and which field it is about.
     *
     * @param field   the wire field name, so E6.11's editor can highlight the right box without
     *                matching on message text
     * @param message the sentence shown to the teacher, from {@link BankMessages}
     */
    public record Violation(String field, String message) {
    }

    /**
     * The values a question is validated on, whether it is being created or edited.
     *
     * @param text          the stem
     * @param answers       the four answers, in display order; may be null or the wrong size,
     *                      which is one of the things being validated
     * @param correctAnswer 1-based position of the correct answer (C-8)
     * @param topic         free text, used by E7.4's auto generator
     * @param difficulty    EASY, MEDIUM or HARD
     */
    public record Fields(String text, List<String> answers, int correctAnswer,
                         String topic, Difficulty difficulty) {
    }

    /** One validation rule. Answers a violation, or empty when it has nothing to say. */
    @FunctionalInterface
    public interface Rule {

        /**
         * @param fields the question under test
         * @return the violation this rule found, or empty
         */
        Optional<Violation> check(Fields fields);
    }

    /**
     * The rules, in the order they run.
     *
     * <p>Order is not cosmetic. Structural rules come first so that the later ones can assume
     * four non-null answers: without that, the distinctness rule would need its own null
     * handling and would report "two answers are the same" for a question that has two answers.
     * The first violation wins, so the teacher is told the most basic thing wrong with the form
     * rather than a consequence of it.
     */
    private static final List<Rule> RULES = List.of(
            QuestionValidator::textPresent,
            QuestionValidator::textWithinLimit,
            QuestionValidator::fourAnswers,
            QuestionValidator::noBlankAnswer,
            QuestionValidator::answersWithinLimit,
            QuestionValidator::correctAnswerInRange,
            QuestionValidator::answersDistinct,
            QuestionValidator::topicPresent,
            QuestionValidator::topicWithinLimit,
            QuestionValidator::difficultyPresent);

    private QuestionValidator() {
        // static rules - no instances
    }

    /**
     * Validates a question, whether new or edited.
     *
     * @param fields the values as the teacher submitted them
     * @return the first violation, or empty when the question is valid
     */
    public static Optional<Violation> validate(Fields fields) {
        if (fields == null) {
            return Optional.of(new Violation("request", BankMessages.MALFORMED_REQUEST));
        }
        for (Rule rule : RULES) {
            Optional<Violation> violation = rule.check(fields);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    /**
     * ADR-016's notion of "the same answer": trimmed, whitespace collapsed, case folded, accent
     * folded.
     *
     * <p>Exposed rather than private because the E6.11 editor validates live while typing and
     * must reach the same verdict as the server. Two implementations of one rule is the drift
     * this method exists to prevent.
     *
     * <h2>Why accents are folded, which ADR-016 does not say</h2>
     *
     * <p>Because the storage backstop already folds them and this rule has to be at least as
     * strict as the thing it claims to back up. {@code question_versions} is
     * {@code utf8mb4_unicode_ci}, which is accent-insensitive as well as case-insensitive, so
     * {@code ck_question_versions_distinct} rejects {@code resume} beside {@code résumé}. A
     * validator folding only case would pass that question, hand it to the insert, and turn a
     * sentence naming the field into a raw constraint violation and a generic error.
     *
     * <p>The relationship the contract claims - service rule strict, CHECK a backstop - is only
     * true if it holds in <em>every</em> dimension. It did not, in exactly one.
     *
     * @param answer one answer as typed
     * @return the key two answers share when ADR-016 calls them identical
     */
    public static String comparisonKey(String answer) {
        if (answer == null) {
            return "";
        }
        String folded = Normalizer.normalize(answer, Normalizer.Form.NFD)
                // Combining marks only. Decomposing first turns 'é' into 'e' plus an accent,
                // so this strips the accent and keeps the letter.
                .replaceAll("\\p{M}+", "");
        return folded.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    // ===================== The rules ======================================

    private static Optional<Violation> textPresent(Fields fields) {
        return isBlank(fields.text())
                ? Optional.of(new Violation("text", BankMessages.TEXT_REQUIRED))
                : Optional.empty();
    }

    private static Optional<Violation> fourAnswers(Fields fields) {
        List<String> answers = fields.answers();
        return answers == null || answers.size() != ANSWER_COUNT
                ? Optional.of(new Violation("answers", BankMessages.ANSWER_COUNT))
                : Optional.empty();
    }

    private static Optional<Violation> textWithinLimit(Fields fields) {
        return fields.text().length() > MAX_TEXT_LENGTH
                ? Optional.of(new Violation("text", BankMessages.textTooLong(MAX_TEXT_LENGTH)))
                : Optional.empty();
    }

    private static Optional<Violation> noBlankAnswer(Fields fields) {
        List<String> answers = fields.answers();
        for (int i = 0; i < answers.size(); i++) {
            if (isBlank(answers.get(i))) {
                return Optional.of(new Violation(answerField(i), BankMessages.answerBlank(i + 1)));
            }
        }
        return Optional.empty();
    }

    private static Optional<Violation> answersWithinLimit(Fields fields) {
        List<String> answers = fields.answers();
        for (int i = 0; i < answers.size(); i++) {
            if (answers.get(i).length() > MAX_ANSWER_LENGTH) {
                return Optional.of(new Violation(answerField(i),
                        BankMessages.answerTooLong(i + 1, MAX_ANSWER_LENGTH)));
            }
        }
        return Optional.empty();
    }

    private static Optional<Violation> topicWithinLimit(Fields fields) {
        return fields.topic().length() > MAX_TOPIC_LENGTH
                ? Optional.of(new Violation("topic", BankMessages.topicTooLong(MAX_TOPIC_LENGTH)))
                : Optional.empty();
    }

    /**
     * The field name for one answer, <b>1-based</b> to agree with the message beside it.
     *
     * <p>A violation whose field said {@code answers[0]} while its message said "Answer 1 is
     * empty" gives the client author a coin toss over which box to highlight, and a wrong guess
     * highlights a box the teacher filled in correctly. The index the wire uses and the number
     * the human reads are now the same number.
     *
     * @param zeroBasedIndex the position in the list
     * @return the wire field name, 1-based
     */
    private static String answerField(int zeroBasedIndex) {
        return "answers[" + (zeroBasedIndex + 1) + "]";
    }

    private static Optional<Violation> correctAnswerInRange(Fields fields) {
        int correct = fields.correctAnswer();
        return correct < 1 || correct > ANSWER_COUNT
                ? Optional.of(new Violation("correctAnswer", BankMessages.CORRECT_ANSWER_RANGE))
                : Optional.empty();
    }

    private static Optional<Violation> answersDistinct(Fields fields) {
        List<String> answers = fields.answers();
        for (int i = 0; i < answers.size(); i++) {
            for (int j = i + 1; j < answers.size(); j++) {
                if (comparisonKey(answers.get(i)).equals(comparisonKey(answers.get(j)))) {
                    return Optional.of(new Violation(answerField(j),
                            BankMessages.answersDuplicated(i + 1, j + 1)));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Violation> topicPresent(Fields fields) {
        return isBlank(fields.topic())
                ? Optional.of(new Violation("topic", BankMessages.TOPIC_REQUIRED))
                : Optional.empty();
    }

    private static Optional<Violation> difficultyPresent(Fields fields) {
        return fields.difficulty() == null
                ? Optional.of(new Violation("difficulty", BankMessages.DIFFICULTY_REQUIRED))
                : Optional.empty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
