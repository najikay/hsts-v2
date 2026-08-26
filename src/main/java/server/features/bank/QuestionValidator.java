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
     * the five, which is step 4 below.
     *
     * <p>And measured the same way, MySQL calls all of these <b>different</b> while
     * {@link Collator} at PRIMARY called them equal — the divergence that runs the other way:
     *
     * <pre>
     *   1 / -1               2, 3 / -2, -3        cos(x) / -cos(x)
     *   (3, 4) / (-3, 4)     x = 1 / x = -1       a / a-        a- / a--
     *   a-b / a&#x2013;b   (hyphen-minus vs en dash: the collation tells most dashes apart too)
     * </pre>
     *
     * <p>...with exactly one exception, which is why {@link #DASH_SENTINELS} is a table rather than
     * a counter: MySQL calls {@code U+2010 HYPHEN} and {@code U+2011 NON-BREAKING HYPHEN}
     * <b>equal</b>, and nothing else in the dash family equal to anything else.
     *
     * <p>Any pair the service accepts and the constraint rejects becomes a raw
     * {@code SQLIntegrityConstraintViolationException} and a generic internal error, which is
     * exactly the outcome naming the field was meant to replace. Any pair the service refuses and
     * the constraint would have stored is B-7: a question the teacher cannot save and no error
     * anywhere to explain why. <b>The rule must therefore agree with the collation in every
     * dimension, and the CsvSource in {@code BankRoundTripIntegrationTest} carries rows on both
     * sides so neither branch can go unexecuted.</b>
     *
     * <h2>What it does, and the honest limit</h2>
     *
     * <p>Five steps, because no single one covers the table above:
     * <ol>
     *   <li><b>substitute the ignorable dashes</b>, before anything else: the eight characters
     *       {@code Collator} drops at PRIMARY and the collation keeps. First because NFKD would
     *       otherwise merge two of them - see {@link #foldedForm};</li>
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
     * edge nobody here has found.
     *
     * <h2>⚑ "Stricter is safe" was wrong, and B-7 is what it cost</h2>
     *
     * <p>This javadoc used to say the goal was one-directional - never accept a pair the database
     * will reject - and that being <em>stricter</em> than the collation was therefore free, "because
     * the worst case is a teacher told two confusingly similar answers are too similar". <b>That
     * argument does not survive the acceptance walk of 2026-08-25</b> and the paragraph is kept here
     * corrected rather than deleted, because the reasoning is the kind that looks sound until
     * somebody measures it.
     *
     * <p>What it cost: {@code sameAnswer("1", "-1")} answered true, because {@link Collator} at
     * PRIMARY drops the dash family entirely, while {@code utf8mb4_unicode_ci} keeps every one of
     * those characters. So the validator refused pairs {@code ck_question_versions_distinct} had
     * <em>already accepted</em> - five seeded questions could not be written back through
     * {@code QUESTION_UPDATE} at all, and a teacher editing only the stem of one of them met a
     * refusal about answers she never touched. The over-strict pair was not "two confusingly similar
     * answers"; it was {@code 2, 3} against {@code -2, -3}, which is the ordinary shape of a
     * mathematics distractor. See {@link #IGNORED_DASHES} for the fold and the measurement.
     *
     * <p><b>The invariant is therefore agreement in BOTH directions, for both consumers, and the
     * asymmetry documented on {@link #sameTopic} is about which direction is caught soonest rather
     * than which is acceptable.</b> Looser than the collation is still the worse failure - it has a
     * stack trace in it and reaches the teacher as a generic internal error - but stricter is a
     * defect too, and this one sat in the bank silently until a walk tried to re-save a row the
     * system had stored itself.
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
     * {@code utf8mb4_unicode_ci} table as the answer columns, and <b>the bank's own filter</b>
     * selects with {@code qv.topic = :topic}, which the database evaluates under that collation.
     * So a service-side comparison that folds <em>less</em> than the collation splits one
     * candidate pool into two buckets that the database will then serve from the same rows.
     *
     * <p><b>E7's auto-composer does not use that query, and this paragraph said it did</b>
     * (corrected 2026-08-25, found by a cold read). It reads its pool by course and buckets by
     * topic through this method, so here the method is the <em>sole</em> authority for what one
     * topic is. That flips which direction is dangerous, and the flip is worth stating because
     * {@link #sameAnswer}'s "stricter is the safe direction" argument does <b>not</b> carry over:
     * a pair MySQL folds together and this method keeps apart makes an auto-composer shortfall's
     * {@code available} <em>smaller</em> than the count the teacher gets by filtering the bank
     * screen to the same topic - and she is explicitly invited to go and check it (E7 contract
     * §7.2 property 2). Agreement in both directions is what that consumer needs, which is what
     * {@code BankRoundTripIntegrationTest}'s bidirectional agreement test measures.
     *
     * <p>That is the failure contract §5.3 and §7.2 property 2 exist to prevent: two quotas
     * drawing on one pool with no rule saying which of them is short, and a shortfall the
     * teacher can disprove by filtering her own bank to the topic named in it. C-7 / ADR-016
     * states the governing rule for the whole codebase: the service comparison must be
     * <b>at least as strict as {@code utf8mb4_unicode_ci} in every dimension</b>.
     *
     * <h2>The two-consumer invariant, and the test that holds it ⚑</h2>
     *
     * <p>One expression, two consumers, and they tolerate error in <b>opposite</b> directions.
     * State it as an invariant so a future tightening is checked against both rather than
     * against whichever one its author had in mind:
     *
     * <ul>
     *   <li><b>Duplicate detection ({@link #sameAnswer}) tolerates over-folding.</b> Its promise
     *       is one-directional — never accept a pair the database will reject — and the cost of
     *       being stricter than MySQL is a teacher told two similar answers are too similar. An
     *       annoyance, recoverable in the editor she is already in.</li>
     *   <li><b>Availability ({@code sameTopic}, E7.4) requires agreement in BOTH directions.</b>
     *       Over-folding merges two topics she filters separately and inflates a bucket;
     *       under-folding splits one pool and deflates it. Either way the shortfall's
     *       {@code available} stops being the number she can reproduce on the bank screen, and
     *       §7.2 property 2 is a promise about exactly that number.</li>
     * </ul>
     *
     * <p><b>{@code BankRoundTripIntegrationTest}'s bidirectional agreement test is the tripwire,
     * and any future change to the folding must keep it green.</b> It asks this comparison and
     * the real database about the same pair and fails when the two verdicts differ <em>in either
     * direction</em> — not merely when this side is looser. A tightening that only satisfies
     * {@code sameAnswer}'s one-directional promise will pass every unit test in this package and
     * fail there, which is the intended order of discovery: the constraint that binds is the
     * stricter of the two consumers, and it is measured against MySQL rather than argued.
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
     * not decompose them (they have no canonical or compatibility decomposition at all), so step 2
     * leaves them intact and nothing else in this method would reach them.
     *
     * <p>Measured, not assumed: MySQL answers {@code 1} for all three against the two-letter form.
     */
    private static final String YIDDISH_DIGRAPHS = "װױײ";
    private static final String[] YIDDISH_EXPANSIONS = {"וו", "וי", "יי"};

    /**
     * The eight characters Java's {@link Collator} ignores at PRIMARY and the collation does not.
     *
     * <p>{@code U+002D hyphen-minus, U+2010 hyphen, U+2011 non-breaking hyphen, U+2012 figure dash,
     * U+2013 en dash, U+2014 em dash, U+2015 horizontal bar, U+2212 minus sign}.
     *
     * <p><b>This list is a measurement, not a guess</b> <i>(taken 2026-08-26, the third divergence
     * found by executing the comparison instead of reasoning about it)</i>. Every defined non-mark,
     * non-whitespace, non-control BMP code point was asked of {@code Collator} at PRIMARY - "is
     * {@code a} equal to {@code a} + this character" - and exactly these eight answered yes. The
     * whole ASCII punctuation family was then asked of MySQL the same way, and
     * {@code utf8mb4_unicode_ci} answered <b>no</b> to all of it: not one of
     * {@code ! " # $ % &amp; ' ( ) * + , - . / : ; &lt; = &gt; ? @ [ \ ] ^ _ ` { | } ~ ± × ÷ ° · ≤ ≥ ≠ √ ∞ € §}
     * is ignorable to the collation. So the divergence is exactly the dash family and nothing else:
     * brackets, commas and operators were never the problem, and the fold does not touch them.
     *
     * <p><b>Why this direction cost something.</b> {@code sameAnswer("1", "-1")} answered
     * <em>true</em> while MySQL answered different, which made the validator <b>stricter</b> than
     * {@code ck_question_versions_distinct} - the constraint it exists to stand in for - and
     * refused questions the database had already stored. Five seeded questions
     * ({@code 11005, 11006, 11008, 12005, 12007}) could not be written back through
     * {@code QUESTION_UPDATE} at all: a teacher editing only the stem met a refusal about answers
     * she never touched. Sign-differing distractors are not an edge case in a mathematics bank,
     * they are the normal shape of one, so the class javadoc's "stricter is the safe direction"
     * argument does not survive contact with this one.
     */
    private static final String IGNORED_DASHES =
            "\u002D\u2010\u2011\u2012\u2013\u2014\u2015\u2212";

    /**
     * The sentinel each dash folds to, index-aligned with {@link #IGNORED_DASHES}.
     *
     * <p><b>Seven sentinels for eight dashes, and the one repeat is a measurement.</b> All
     * twenty-eight pairs were put to MySQL as {@code 'a?b' = 'a?b'} under
     * {@code utf8mb4_unicode_ci}. Twenty-seven answered {@code 0}: the collation tells a hyphen from
     * an en dash, an em dash, a figure dash, a horizontal bar and a minus sign. <b>Exactly one pair
     * answered {@code 1}</b> - {@code U+2010 HYPHEN} and {@code U+2011 NON-BREAKING HYPHEN}, which
     * therefore share {@code U+E001}.
     *
     * <p>That single repeat is the whole reason this table exists rather than a loop handing out
     * consecutive sentinels. A first draft of this fix gave all eight their own, on the assumption
     * that a collation which keeps the family significant keeps its members apart; the round-trip
     * test failed on that one pair and nothing else, which is the test doing precisely the job its
     * javadoc claims. <b>The equivalence classes are measured, not derived</b> - the same discipline
     * the Hebrew and supplementary folds record, applied to the case where the answer was not the
     * uniform one.
     *
     * <p>(NFKD would merge those same two, since {@code U+2011} decomposes to {@code U+2010}. The
     * table states the merge anyway, so this fold's verdict is its own rather than a side effect of
     * where it happens to sit in {@link #foldedForm}.)
     *
     * <p>Private-use {@code U+E000..U+E006}, chosen the way {@link #SUPPLEMENTARY_SENTINEL} was and
     * measured on the same properties: non-ignorable at PRIMARY (so count and position survive -
     * {@code a} does not equal {@code a-}, and {@code a-} does not equal {@code a--}, which is what
     * MySQL says too), mutually distinct at PRIMARY, distinct from {@code U+FFFD}, and unchanged by
     * NFKD, mark-stripping and the case round trip - so a sentinel laid down in step 1 arrives at
     * the collator intact.
     *
     * <p>The one over-strictness this buys is the sibling of the astral one: an answer containing a
     * literal {@code U+E000} folds together with an answer containing a hyphen. A teacher does not
     * type private-use characters, and that is the safe direction.
     */
    private static final String DASH_SENTINELS =
            "\uE000\uE001\uE001\uE002\uE003\uE004\uE005\uE006";

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
     * The normalisation steps that run before collation.
     *
     * <p>All three of the substitution steps were found the same way: by executing the comparison
     * against MySQL instead of reasoning about it. {@link #foldHebrew} folds final forms and Yiddish
     * digraphs, which {@code Collator} splits and the collation does not. {@link #foldSupplementary}
     * folds the supplementary planes, where the collation is far blunter than Java.
     * {@link #foldDashes} is the mirror image of those two - the one place measured so far where
     * {@code Collator} is blunter than the collation - and it fixes the direction that refuses
     * questions the database would store. See {@link #sameAnswer} for the measurement.
     *
     * <p><b>The dash fold runs first, and its correctness does not depend on that.</b>
     * {@link #DASH_SENTINELS} states the collation's own equivalence classes outright - including
     * the one pair of dashes MySQL folds together - so the step answers the same thing wherever it
     * sits. Putting it before NFKD keeps that independence visible rather than resting on NFKD
     * happening to merge the same pair, and costs nothing: the sentinels survive every later step
     * unchanged, being NFKD-stable, not combining marks, not Hebrew, not supplementary, caseless
     * and not whitespace - each of those six measured on {@code U+E000..U+E006} rather than
     * assumed.
     *
     * @param answer one answer as typed, possibly null
     * @return the folded form, never null
     */
    private static String foldedForm(String answer) {
        if (answer == null) {
            return "";
        }
        String dashFolded = foldDashes(answer);
        String stripped = Normalizer.normalize(dashFolded, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        String hebrewFolded = foldHebrew(stripped);
        String astralFolded = foldSupplementary(hebrewFolded);
        String cased = astralFolded.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
        return cased.trim().replaceAll("\\s+", " ");
    }

    /**
     * Replaces each collator-ignorable dash with its own sentinel, so the comparison can see it.
     *
     * <p>The same technique {@link #foldSupplementary} uses, aimed the other way. There the
     * collation was blunter than Java and the fold made Java blunter to match; here Java is blunter
     * than the collation - it drops these eight characters entirely at PRIMARY - and the fold gives
     * each one a body the collator will weigh. A substitution rather than any collator setting,
     * because raising the strength to SECONDARY or TERTIARY would stop folding case, accents and
     * niqqud, which the collation genuinely does fold: the two behaviours are not on one dial.
     *
     * <p>Index i of {@link #IGNORED_DASHES} becomes index i of {@link #DASH_SENTINELS}, so the
     * eight stay eight. Allocates nothing when the answer holds no dash, which is most of them.
     *
     * @param text one answer exactly as typed, before any normalisation
     * @return the same text with each ignorable dash replaced by its own sentinel
     */
    private static String foldDashes(String text) {
        StringBuilder folded = new StringBuilder(text.length());
        boolean changed = false;
        for (int i = 0; i < text.length(); i++) {
            char at = text.charAt(i);
            int dash = IGNORED_DASHES.indexOf(at);
            if (dash >= 0) {
                folded.append(DASH_SENTINELS.charAt(dash));
                changed = true;
            } else {
                folded.append(at);
            }
        }
        return changed ? folded.toString() : text;
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
