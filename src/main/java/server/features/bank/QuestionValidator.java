package server.features.bank;

import server.db.entities.Difficulty;

import java.text.Collator;
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
 * collapsing whitespace, case-insensitively. {@link #sameAnswer} is that rule <em>and more</em>,
 * deliberately: the storage constraint compares under {@code utf8mb4_unicode_ci}, which folds
 * far more than case, so a rule matching only ADR-016's literal words is looser than the
 * constraint it is supposed to make unreachable. See {@link #sameAnswer} for what that costs and
 * for the cases measured against the real database.
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
     * @param field   the field name. <b>Server-internal: it does not reach the wire.</b>
     *                {@code BankHandlers.fieldProblem} keeps only the message, because the
     *                contract's error shape is a single sentence and nothing carries a field
     *                beside it. This javadoc said the opposite until 2026-08-23, and E6.11's
     *                editor had been written against the sentence: it maps a refusal to a box by
     *                exact equality against {@link BankMessages}, which works because client and
     *                server are one artifact. Adding the field to the wire is an amendment to a
     *                FROZEN contract and is raised in PR-B's report rather than done here
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
     * A collator at primary strength: differences of case, accent and script variant only.
     *
     * <p>{@link Collator} is not thread safe, and this runs on request threads, so each gets
     * its own.
     */
    private static final ThreadLocal<Collator> PRIMARY = ThreadLocal.withInitial(() -> {
        Collator collator = Collator.getInstance(Locale.ROOT);
        collator.setStrength(Collator.PRIMARY);
        return collator;
    });

    /**
     * ADR-016's notion of "the same answer".
     *
     * <p>Exposed rather than private because the E6.11 editor validates duplicates live while
     * typing and must reach the same verdict as the server. Two implementations of one rule is
     * the drift this method exists to prevent.
     *
     * <h2>Why this is stricter than ADR-016's literal words</h2>
     *
     * <p>ADR-016 says "trimming and whitespace collapse, case-insensitive". That is not enough,
     * because {@code question_versions} is {@code utf8mb4_unicode_ci} and
     * {@code ck_question_versions_distinct} compares under it. Verified against the running
     * database rather than assumed, MySQL calls all of these <b>equal</b>:
     *
     * <pre>
     *   resume / résumé      Strasse / Straße      oeuvre / œuvre
     *   file / &#xfb01;le         A / &#xff21;                 &#x3c4;&#x3ad;&#x3bb;&#x3bf;&#x3c2; / &#x3c4;&#x3ad;&#x3bb;&#x3bf;&#x3c3;
     *   &#x5e9;&#x5dc;&#x5d5;&#x5dd; / &#x5e9;&#x5b8;&#x5c1;&#x5dc;&#x5d5;&#x5b9;&#x5dd;   (Hebrew, unpointed vs pointed)
     *   &#x5de;&#x5d9;&#x5dd; / &#x5de;&#x5d9;&#x5de;         (Hebrew, final form vs base letter)
     * </pre>
     *
     * <p><b>That last row was a real divergence and not a hypothetical one</b> <i>(found
     * 2026-08-25 by the first test in this codebase to execute the comparison against MySQL)</i>.
     * Java's {@link Collator} at {@link Collator#PRIMARY} treats the five Hebrew final forms as
     * distinct letters from their bases; {@code utf8mb4_unicode_ci} gives them the same primary
     * weight and calls the strings equal. So this method was <em>looser</em> than the constraint
     * on the one language the system is written in: a teacher typing {@code &#x5de;&#x5d9;&#x5dd;} and
     * {@code &#x5de;&#x5d9;&#x5de;} as two answers passed validation and hit
     * {@code ck_question_versions_distinct}, and the exception came out of
     * {@code QuestionService.create} as a generic internal error. {@link #foldedForm} now folds
     * the five, which is step 3 below.
     *
     * <p>Any pair the service accepts and the constraint rejects becomes a raw
     * {@code SQLIntegrityConstraintViolationException} and a generic internal error, which is
     * exactly the outcome naming the field was meant to replace. <b>The rule must therefore be
     * at least as strict as the collation, in every dimension.</b>
     *
     * <h2>What it does, and the honest limit</h2>
     *
     * <p>Four steps, because no single one covers the table above:
     * <ol>
     *   <li><b>NFKD</b>, not NFD: compatibility decomposition is what folds the &#xfb01; ligature and
     *       fullwidth &#xff21;. Canonical decomposition leaves both alone;</li>
     *   <li><b>strip combining marks</b>: accents, and Hebrew niqqud;</li>
     *   <li><b>upper then lower</b>: folds Greek final sigma to medial, which
     *       {@code toLowerCase} alone does not, since &#x3c2; is already lower case;</li>
     *   <li><b>collate at primary strength</b>: catches the <em>expansions</em> no normalisation
     *       performs, &#xdf; to ss and &#x153; to oe.</li>
     * </ol>
     *
     * <p><b>This is not exact equivalence with MySQL's UCA table and does not claim to be.</b>
     * Java's collation data and MySQL's are separate implementations and will differ at some
     * edge nobody here has found. The design goal is one-directional: never accept a pair the
     * database will reject. Being <em>stricter</em> than the database is safe, because the worst
     * case is a teacher told two confusingly similar answers are too similar. Being looser is
     * the failure that has a stack trace in it.
     *
     * @param first  one answer as typed
     * @param second another
     * @return whether ADR-016 and the storage constraint would call them the same answer
     */
    public static boolean sameAnswer(String first, String second) {
        return PRIMARY.get().compare(foldedForm(first), foldedForm(second)) == 0;
    }

    /**
     * The five Hebrew final forms, and the base letters MySQL weighs them as.
     *
     * <p>Parallel strings rather than a {@code Map}: the pairing is the whole content, and two
     * aligned literals show it at a glance where a map of five entries would not. Index i of one
     * folds to index i of the other.
     *
     * <p>{@code kaf, mem, nun, pe, tsadi}. Each final form happens to sit one code point below its
     * base, and this does <b>not</b> rely on that: an arithmetic version would read as a trick and
     * would silently mis-fold if anyone extended it to a script where the pattern does not hold.
     */
    private static final String HEBREW_FINAL_FORMS = "ךםןףץ";
    private static final String HEBREW_BASE_LETTERS = "כמנפצ";

    /**
     * The four normalisation steps that run before collation.
     *
     * <p>Step 3 is Hebrew-specific and is the one that was missing. See {@link #sameAnswer} for
     * what its absence cost; in short, {@code Collator} splits ם from מ and the collation does not,
     * so without this the service accepted pairs {@code ck_question_versions_distinct} rejects.
     *
     * @param answer one answer as typed, possibly null
     * @return the folded form, never null
     */
    private static String foldedForm(String answer) {
        if (answer == null) {
            return "";
        }
        String stripped = Normalizer.normalize(answer, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        String hebrewFolded = foldHebrewFinalForms(stripped);
        String cased = hebrewFolded.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
        return cased.trim().replaceAll("\\s+", " ");
    }

    /**
     * Folds each Hebrew final form onto its base letter, so this side agrees with the collation.
     *
     * <p>Applied everywhere rather than only at the end of a word, deliberately. The collation
     * weighs the character, not its position, so {@code &#x5de;&#x5d9;&#x5dd;} and {@code &#x5de;&#x5d9;&#x5de;} are equal to MySQL
     * whether or not the final form is where Hebrew orthography would put it. Folding positionally
     * would reintroduce the gap for exactly the mistyped input this exists to catch.
     *
     * <p>Allocates nothing when there is no final form to fold, which is every non-Hebrew answer
     * and most Hebrew ones.
     *
     * @param text the text after decomposition and mark-stripping
     * @return the same text with the five final forms replaced by their base letters
     */
    private static String foldHebrewFinalForms(String text) {
        StringBuilder folded = null;
        for (int i = 0; i < text.length(); i++) {
            int at = HEBREW_FINAL_FORMS.indexOf(text.charAt(i));
            if (at >= 0) {
                if (folded == null) {
                    folded = new StringBuilder(text);
                }
                folded.setCharAt(i, HEBREW_BASE_LETTERS.charAt(at));
            }
        }
        return folded == null ? text : folded.toString();
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
                if (sameAnswer(answers.get(i), answers.get(j))) {
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
