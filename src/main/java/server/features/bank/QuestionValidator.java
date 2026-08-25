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
     * Whether two topics are one topic as far as the database is concerned (E7.4, §5.3).
     *
     * <p><b>The same comparison as {@link #sameAnswer}, deliberately, because it is the same
     * job.</b> {@code question_versions.topic VARCHAR(100)} sits in the same
     * {@code utf8mb4_unicode_ci} table as the answer columns, and the bank's own filter and
     * E7's auto-composer both select candidates with {@code qv.topic = :topic}, which the
     * database evaluates under that collation. So a service-side comparison that folds
     * <em>less</em> than the collation splits one candidate pool into two buckets that the
     * database will then serve from the same rows.
     *
     * <p>That is the failure contract §5.3 and §7.2 property 2 exist to prevent: two quotas
     * drawing on one pool with no rule saying which of them is short, and a shortfall the
     * teacher can disprove by filtering her own bank to the topic named in it. C-7 / ADR-016
     * states the governing rule for the whole codebase: the service comparison must be
     * <b>at least as strict as {@code utf8mb4_unicode_ci} in every dimension</b>.
     *
     * <p>Exposed here rather than reimplemented in {@code ExamValidator} because a second
     * expression of "what the database calls the same string" is exactly the defect
     * {@code docs/PROBLEMS.md} P-6 is about: the two would agree on the cases their author
     * thought of and diverge silently everywhere else. The four folding steps and their honest
     * limit are documented on {@link #sameAnswer}; the cases measured against the real database
     * are in the bank's tests and cover this method by construction, since it is the same call.
     *
     * @param first  one topic as typed
     * @param second another
     * @return whether the database would treat them as one topic
     */
    public static boolean sameTopic(String first, String second) {
        return sameAnswer(first, second);
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
     * The three Yiddish digraphs, and the letter pairs MySQL <em>expands</em> them to.
     *
     * <p>{@code U+05F0 double vav, U+05F1 vav-yod, U+05F2 double yod}. Unlike the final forms these
     * are one character folding to <b>two</b>, which is why they need their own table: NFKD does
     * not decompose them (they have no canonical or compatibility decomposition at all), so step 1
     * leaves them intact and nothing else in this method would reach them.
     *
     * <p>Measured, not assumed: MySQL answers {@code 1} for all three against the two-letter form.
     */
    private static final String YIDDISH_DIGRAPHS = "װױײ";
    private static final String[] YIDDISH_EXPANSIONS = {"וו", "וי", "יי"};

    /**
     * What every supplementary-plane character folds to.
     *
     * <p>{@code U+FFFD REPLACEMENT CHARACTER}, chosen because it means "a character we cannot
     * tell apart" and because a teacher will not type it. It has to be a character the collator
     * treats as real: a fully ignorable sentinel would make {@code 😀} equal the empty string and
     * {@code 😀} equal {@code 😀😀}, which is the <em>loose</em> direction and the whole defect.
     * Measured: {@code U+FFFD} is non-ignorable at PRIMARY and counts.
     */
    private static final char SUPPLEMENTARY_SENTINEL = '�';

    /**
     * The five normalisation steps that run before collation.
     *
     * <p>Steps 3 and 4 are the ones that were missing, and both were found the same way: by
     * executing the comparison against MySQL instead of reasoning about it. Step 3 folds Hebrew
     * final forms and Yiddish digraphs, which {@code Collator} splits and the collation does not.
     * Step 4 folds the supplementary planes, where the collation is far blunter than Java. See
     * {@link #sameAnswer} and {@link #foldSupplementary}.
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
        String hebrewFolded = foldHebrew(stripped);
        String astralFolded = foldSupplementary(hebrewFolded);
        String cased = astralFolded.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
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
    private static String foldHebrew(String text) {
        StringBuilder folded = new StringBuilder(text.length());
        boolean changed = false;
        for (int i = 0; i < text.length(); i++) {
            char at = text.charAt(i);
            int finalForm = HEBREW_FINAL_FORMS.indexOf(at);
            int digraph = YIDDISH_DIGRAPHS.indexOf(at);
            if (finalForm >= 0) {
                folded.append(HEBREW_BASE_LETTERS.charAt(finalForm));
                changed = true;
            } else if (digraph >= 0) {
                // One character becoming two. The collation expands these, so a fold that
                // replaced them one-for-one would still disagree with it.
                folded.append(YIDDISH_EXPANSIONS[digraph]);
                changed = true;
            } else {
                folded.append(at);
            }
        }
        return changed ? folded.toString() : text;
    }

    /**
     * Folds every supplementary-plane character onto one sentinel, preserving how many there were.
     *
     * <p><b>This is the same defect as the Hebrew one, one plane up, and it is not an edge case.</b>
     * {@code utf8mb4_unicode_ci} is a UCA 4.0.0 table, and that table gives <em>every</em>
     * character above the BMP the same weight. To the constraint, {@code 😀} and {@code 😁} are one
     * string; so are {@code 😀} and {@code 𝐀}. Java tells all of them apart. Two different emoji
     * pasted into two answer boxes therefore passed validation and violated
     * {@code ck_question_versions_distinct}, reproduced against a real schema as {@code ERROR 3819}
     * before this method existed.
     *
     * <p><b>Count is preserved because the collation preserves it.</b> Measured: {@code 😀} does
     * <em>not</em> equal the empty string, {@code a} does not equal {@code a😀}, and {@code 😀}
     * does not equal {@code 😀😀}. So these characters are equal-<em>weight</em> rather than
     * ignorable, and the fold emits one sentinel per code point rather than deleting them.
     *
     * <p>Iterates by code point, not by {@code char}: a supplementary character is a surrogate
     * pair, and folding each half separately would emit two sentinels for one character and make
     * {@code 😀} collide with a genuine two-character string.
     *
     * <p>The one over-strictness this buys: an answer containing a literal {@code U+FFFD} folds to
     * the same form as an answer containing one emoji. That is the safe direction the class
     * javadoc already accepts, and a teacher typing a replacement character is not a case worth
     * widening the rule for.
     *
     * @param text the text after the Hebrew fold
     * @return the same text with every astral character replaced by one sentinel
     */
    private static String foldSupplementary(String text) {
        if (text.codePointCount(0, text.length()) == text.length()) {
            // No surrogate pairs, so nothing above the BMP. The common case allocates nothing.
            return text;
        }
        StringBuilder folded = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (Character.isSupplementaryCodePoint(codePoint)) {
                folded.append(SUPPLEMENTARY_SENTINEL);
            } else {
                folded.appendCodePoint(codePoint);
            }
        });
        return folded.toString();
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
