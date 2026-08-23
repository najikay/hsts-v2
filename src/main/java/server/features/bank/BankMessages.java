package server.features.bank;

import server.db.projections.ReferencingExam;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Every sentence the question bank sends a human (Logic tier, E6.2 - PRD §4.1).
 *
 * <p>One class, for the three reasons {@code ExamMessages}, {@code BotMessages} and
 * {@code NotificationCatalog} exist: the copy rules are checkable by one test rather than by
 * reading a service, a refusal and the screen that renders it cannot drift apart, and the
 * wording is reviewed once instead of once per handler.
 *
 * <p>Two rules bind every string here. <b>No em dashes</b> (PRD §4.1). And <b>every error says
 * what to do next</b>, which is the harder one: "invalid question" is a dead end, "two answers
 * are the same, change one of them" is a next move.
 *
 * <h2>Why the validation messages name a specific answer</h2>
 *
 * <p>Acceptance case T-2.2 saves three bad questions in a row and expects three different
 * sentences. That is the visible reason. The invisible one is E6.11: the editor maps a server
 * error back onto the field that caused it, so a message that says only "answers are invalid"
 * leaves the client guessing which of four boxes to highlight. Every sentence below that can
 * name a position does, which is what lets a client put a refusal under the right box.
 *
 * <p><b>The client does match on text, and this paragraph used to say it did not.</b>
 * {@link server.features.bank.QuestionValidator.Violation} carries a field name, but
 * {@code BankHandlers} keeps only the message, so the wire has never carried one. E6.11's editor
 * maps a refusal to a box by <b>exact equality against the constants below</b>, which is sound
 * because both tiers ship in one artifact, and which is why nothing here may be reworded without
 * checking {@code QuestionEditorSession.locate}. Corrected 2026-08-23 after a cold read.
 */
public final class BankMessages {

    private BankMessages() {
        // static copy - no instances
    }

    // ===================== Malformed or unauthorized ======================

    /** The payload was not the type this verb expects, or failed its own well-formedness check. */
    public static final String MALFORMED_REQUEST =
            "That request did not arrive in a form the server could read. Please try again.";

    /**
     * A draft arrived with no course on it (E6.1).
     *
     * <p>{@code VALIDATION} rather than the authorization refusal that used to answer this.
     * A draft with no course is a malformed payload, and section 6 gives malformed payloads
     * {@code VALIDATION} with the field named. The guard's own no-course sentence is written
     * for an edit and says the question "cannot be changed", which is the wrong verb and the
     * wrong screen for a teacher who has not saved anything yet.
     */
    public static final String COURSE_REQUIRED =
            "Pick the course this question belongs to before saving it.";

    /**
     * The caller does not teach the course she is writing into (S-5, F2.1).
     *
     * <p>Deliberately does not name the course's teachers. A teacher who is not on a course has
     * no business learning its roster from an error message, and the next step she needs is the
     * same either way.
     */
    public static final String COURSE_NOT_TAUGHT =
            "You can only add questions to courses you teach. Pick one of your own courses, "
                    + "or ask the coordinator to add you to that course.";

    /** No such question, soft deleted, or outside the caller's courses. All three read alike. */
    public static final String QUESTION_NOT_FOUND =
            "That question is not in your bank. It may have been deleted, or it may belong to a "
                    + "course you do not teach, so go back to the bank list and pick another.";

    /**
     * E6.6: no such illustration, on any of the four routes that can mean.
     *
     * <p>Unknown question, out of the caller's reach, no such version number, and a version that
     * simply carries no picture all answer with this one sentence. Uniform on purpose: the first
     * two must stay indistinguishable per the contract's section 6, and once they are, splitting
     * the other two out would be the same oracle with extra steps.
     *
     * <p>Separate from {@link #QUESTION_NOT_FOUND} because a teacher looking at a question she
     * has open should not be told the question is missing when it is the picture that is.
     */
    public static final String IMAGE_NOT_FOUND =
            "There is no illustration on that version of the question. Go back and open the "
                    + "question again to see the version that is current.";

    // ===================== Validation, C-8 and ADR-016 ====================

    /** F2.1: a question must actually ask something. */
    public static final String TEXT_REQUIRED =
            "The question text is empty. Write the question a student will read.";

    /** C-8: four answers, no more and no fewer. Reachable only from a non-UI caller. */
    public static final String ANSWER_COUNT =
            "A question needs exactly four answers. Fill in all four and try again.";

    /** Which box is empty matters, so this one is a method rather than a constant. */
    public static String answerBlank(int position) {
        return "Answer " + position + " is empty. Fill in all four answers before saving.";
    }

    /**
     * ADR-016: the four answers must be pairwise distinct.
     *
     * <p>Names both positions, because with four boxes on screen "two answers are the same" still
     * leaves the teacher comparing them by eye.
     *
     * <p><b>And names the rule, per the lead's ruling of 2026-08-22.</b> Without the last sentence
     * a teacher who typed {@code "1 2 3"} and {@code "123"} sees a refusal she can read as a bug,
     * retypes one of them with a different space, and gets the same refusal again. The hint is
     * what turns a wall into a rule she can satisfy.
     *
     * <p><b>"Spacing or hyphens", not "punctuation", and the difference was measured.</b> The
     * ruling's own wording said punctuation, but {@code sameAnswer} does not fold it: at
     * {@code Collator} PRIMARY strength only whitespace and the hyphen are ignorable. Verified on
     * JDK 21 against the shipped validator - {@code "1 2 3"}/{@code "123"}, {@code "co-op"}/
     * {@code "coop"} and {@code "e-mail"}/{@code "email"} all fold, while {@code "cat."}/
     * {@code "cat"}, {@code "it's"}/{@code "its"} and {@code "3+4"}/{@code "34"} do not. Telling
     * her punctuation will not save her would be false in the direction that costs her work: she
     * would rewrite an answer semantically when a full stop would in fact have been accepted.
     */
    public static String answersDuplicated(int first, int second) {
        return "Answers " + first + " and " + second + " are the same. Two identical answers make "
                + "the correct one ambiguous, so change one of them. They have to differ by more "
                + "than spacing or hyphens.";
    }

    /**
     * C-8: exactly one correct answer, identified by position.
     *
     * <p>The editor's radio group makes this unreachable from our own client, which is the point
     * of ADR-016. It stays enforced because the server does not get to assume its client.
     */
    public static final String CORRECT_ANSWER_RANGE =
            "Mark exactly one of the four answers as the correct one.";

    /**
     * The three length refusals, which exist so a schema limit is a sentence and not a crash.
     *
     * <p>Each names the limit rather than only saying "too long", because a teacher who has just
     * pasted a paragraph needs to know how much to cut, and "shorten it" without a number means
     * trying twice.
     */
    public static String textTooLong(int limit) {
        return "The question text is longer than " + limit + " characters. Shorten it and save "
                + "again.";
    }

    /** @see #textTooLong(int) */
    public static String answerTooLong(int position, int limit) {
        return "Answer " + position + " is longer than " + limit + " characters. Shorten it and "
                + "save again.";
    }

    /** @see #textTooLong(int) */
    public static String topicTooLong(int limit) {
        return "The topic is longer than " + limit + " characters. Shorten it and save again.";
    }

    /** F2.1: the topic is what the auto generator selects on in E7.4, so it cannot be blank. */
    public static final String TOPIC_REQUIRED =
            "Pick a topic. Exam auto generation selects questions by topic, so a question without "
                    + "one can never be chosen.";

    /** F2.1: EASY, MEDIUM or HARD. */
    public static final String DIFFICULTY_REQUIRED =
            "Pick a difficulty: easy, medium or hard.";

    // ===================== Editing and versions ===========================

    /**
     * F10.3, ADR-008: somebody else saved a new version while this editor was open.
     *
     * <p>Says what happened to her work, because the honest answer is "nothing was lost, but you
     * are not looking at the newest version any more".
     */
    public static final String STALE_EDIT =
            "Someone else saved a new version of this question while you had it open. Nothing you "
                    + "typed was lost, but reopen the question to edit the newest version.";

    /** E6.14, F2.6: the advisory edit lock is held by another teacher. */
    public static String lockedBy(String editorName) {
        return "This question is being edited by " + editorName + " right now. You can read it, "
                + "and it becomes editable as soon as that editor closes it.";
    }

    // ===================== Deleting =======================================

    /**
     * F2.5, T-2.7: deletion refused because exams reference the question.
     *
     * <p>Names the exams, which is the whole requirement. A teacher told only "cannot delete" has
     * no route forward; told which exams, she can go and change them.
     *
     * <p><b>Takes {@link ReferencingExam}, not a list of names.</b> An earlier signature took
     * names alone and threw the display id away, which reintroduced one layer up the exact defect
     * the per-exam de-duplication in {@code findReferencingExams} was built to remove: two exams
     * called "Algebra Midterm" in different terms produce "2 exams use it: Algebra Midterm,
     * Algebra Midterm". The id is also what she sees on her own exam list, so it is how she finds
     * the thing she has been told to go and change.
     */
    public static String deleteBlocked(List<ReferencingExam> exams) {
        if (exams.isEmpty()) {
            // Unreachable while DeleteOutcome.deleted=false implies a non-empty list, but that
            // invariant lives in a service, and "0 exams use it: ." is a sentence no teacher
            // should ever be shown by a guard that was one line away.
            return "This question cannot be deleted right now. Reload the bank and try again.";
        }
        String list = exams.stream()
                .map(exam -> exam.displayId() + " " + exam.name())
                .collect(Collectors.joining(", "));
        return exams.size() == 1
                ? "This question cannot be deleted because the exam " + list + " uses it. Remove "
                        + "it from that exam first."
                : "This question cannot be deleted because " + exams.size() + " exams use it: "
                        + list + ". Remove it from those exams first.";
    }

    // ===================== Images, E6.6 ===================================

    /** NFR-18 and E6.6: 2MB ceiling, stated in the units the teacher sees on her own file. */
    public static final String IMAGE_TOO_LARGE =
            "That image is larger than 2 MB. Save a smaller copy and attach that instead.";

    /**
     * E6.6: PNG or JPEG only, and sniffed from the bytes rather than the file name.
     *
     * <p>Says "we looked at the file", because a teacher who renamed a HEIC to .png would
     * otherwise read this as the server being broken.
     */
    public static final String IMAGE_WRONG_TYPE =
            "Illustrations must be PNG or JPEG images, and the file you attached is neither, "
                    + "whatever its name ends with. Save it as a PNG and attach it again.";

    /**
     * An edit asked to replace the illustration and carried no file (E6.3, E6.10).
     *
     * <p>Refused rather than treated as a removal, which is what silently happened before.
     * Section 4 gives {@code ImageAction} three states precisely so that clearing a picture is
     * never implicit: a null image is ambiguous between "unchanged" and "cleared", and REPLACE
     * with nothing would quietly resolve that ambiguity the destructive way. The teacher whose
     * file picker returned nothing gets a sentence instead of a question that lost its diagram
     * in every exam built from the new version.
     */
    public static final String IMAGE_REPLACE_WITHOUT_FILE =
            "You chose to replace the illustration but no file arrived. Attach the new image "
                    + "and save again, or use Remove if you meant to take the picture off the "
                    + "question.";
}
