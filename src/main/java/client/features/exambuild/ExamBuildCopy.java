package client.features.exambuild;

import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.QuestionPin;
import common.dto.authoring.Shortfall;
import common.dto.bank.Difficulty;

import java.util.Locale;

/**
 * Every sentence the exam builder shows (Presentation tier, E7.11 to E7.14 — F3.1, F3.3, F3.5).
 *
 * <p>Separate from the view for the reason {@link ExamListCopy} is: the wording is checkable
 * without a JavaFX toolkit, so a pluralisation or a limit that has drifted from the contract
 * fails a unit test rather than a screenshot. Column headers and field labels are here too, which
 * is what makes the em-dash guard below cover the whole screen rather than most of it.
 *
 * <p><b>House rule PRD §4.1: no em dashes in user-visible text.</b> That governs every constant
 * and every returned string here. It does not govern this javadoc.
 *
 * <h2>Every number comes from the wire record, never from a literal ⚑</h2>
 *
 * <p>The limits a teacher reads in a hint - 150 characters, 480 minutes, 100 points - are read
 * off {@code ExamCreateRequest} and {@code QuestionPin}, which are the same constants
 * {@code ExamValidator} refuses with. Typing "480" into a sentence here would create a second
 * place for the ceiling to live, and the first time the lead moved it (he already moved it once,
 * from 600) the screen would promise one thing while the server enforced another.
 */
public final class ExamBuildCopy {

    // ===================== The screen =====================================

    /**
     * What the course line calls itself (U-30).
     *
     * <p>The builder never showed which course it was writing for in {@code Mode.CREATE}: the
     * course is picked in the exam list's New exam menu and then travels as a nav parameter, so
     * the one screen where it decides what the bank picker offers and what {@code EXAM_CREATE}
     * carries was the one screen that never named it. A teacher who teaches four courses had no
     * way to check she was in the right one short of opening the picker.
     */
    public static final String COURSE_PREFIX = "Course";

    /** The screen title when a new exam is being written. */
    public static final String TITLE_NEW = "New exam";

    /** The screen title when an existing draft is open. */
    public static final String TITLE_EDIT = "Edit exam";

    /** The screen title when a finished version is open for reading (§8's read path). */
    public static final String TITLE_READ_ONLY = "Exam";

    /**
     * The banner a read-only version carries.
     *
     * <p>Says why rather than only that. A screen that is simply inert reads as broken; one that
     * names the reason and the way forward reads as a rule.
     */
    public static final String READ_ONLY_BANNER =
            "This version has been sent for approval, so it can no longer be changed. Revise it "
                    + "from your exam list to start a new draft.";

    /**
     * What the lock banner calls the thing being held (E18.5).
     *
     * <p>{@code LockBanner} builds the sentence around it: "Dana Cohen is editing this exam".
     * "exam" rather than "exam version", because the teacher opened an exam and the version is
     * bookkeeping she has no reason to meet in a warning.
     */
    public static final String LOCK_NOUN = "exam";

    // ===================== Metadata (E7.11) ===============================

    /** The metadata step's heading. */
    public static final String DETAILS_TITLE = "Exam details";

    /** Label for the exam's name. */
    public static final String NAME_LABEL = "Exam name";

    /** Label for the sitting length. */
    public static final String DURATION_LABEL = "How long students get";

    /** The tab holding the text students read on the paper. */
    public static final String STUDENT_TEXT_TAB = "Student instructions";

    /** The tab holding the notes only staff ever read. */
    public static final String TEACHER_TEXT_TAB = "Teacher notes";

    /** What the teacher-only tab is for, said once so it cannot be mistaken. */
    public static final String TEACHER_TEXT_HINT =
            "Only staff ever see this. It is not printed on the student's paper.";

    /** The default a new exam opens with, in minutes. */
    public static final int DEFAULT_DURATION_MINUTES = 60;

    // ===================== The paper (E7.12) ==============================

    /** The composition step's heading. */
    public static final String PAPER_TITLE = "Questions on this paper";

    /** What the paper says when nothing is on it yet. */
    public static final String PAPER_EMPTY =
            "No questions yet. Add them from your question bank, and the points have to reach "
                    + "100 before you can save.";

    /** The per-question points field's label. */
    public static final String POINTS_LABEL = "Points";

    /** The button that moves a question towards the front. */
    public static final String MOVE_UP = "Move up";

    /** The button that moves a question towards the back. */
    public static final String MOVE_DOWN = "Move down";

    /** The button that takes a question off the paper. */
    public static final String REMOVE = "Remove";

    /** The badge on a question the bank has since edited (E7.7). */
    public static final String NEWER_VERSION_BADGE = "The bank has a newer version";

    /** The action beside that badge (E7.14): take the newer version onto this paper. */
    public static final String USE_NEWER_VERSION = "Use the newer version";

    // ===================== The answers on a picked row (U-53) =============

    /**
     * The toggle that opens one picked row's four answers (2026-08-30, Findings.txt, U-53).
     *
     * <p>The finding was that a teacher composing an exam cannot see what the exam says: a row
     * carried a stem, an id, a topic and a difficulty, and the four things a student actually
     * chooses between were nowhere on the screen. The wording names the state she is moving to
     * rather than the state she is in, which is how every other disclosure control here reads.
     */
    public static final String SHOW_ANSWERS = "Show answers";

    /** @see #SHOW_ANSWERS */
    public static final String HIDE_ANSWERS = "Hide answers";

    /**
     * Said in the opened row while the bank read is in flight.
     *
     * <p>The answers are not on the composition wire and deliberately so:
     * {@code ComposedQuestion} carries no key, which is what keeps E7 off the correctness
     * boundary. Opening a row is therefore a round trip, and a round trip has a moment worth
     * naming.
     */
    public static final String ANSWERS_LOADING = "Loading the answers...";

    /**
     * Said when that read failed.
     *
     * <p>Names the retry rather than offering a button for it: hiding the answers and showing
     * them again re-sends the read, so the way out is the control she has just used, and a
     * second control beside it would be a second way to do one thing.
     */
    public static final String ANSWERS_FAILED =
            "The answers could not be loaded. Try showing them again.";

    /**
     * Said when the bank answered without the version this paper pins.
     *
     * <p>Distinct from a failed read on purpose. A history that does not contain the pinned
     * version is a paper pointing at something the bank no longer serves, and calling that a
     * load failure would send her retrying a read that had already succeeded.
     */
    public static final String ANSWERS_VERSION_GONE =
            "The bank no longer holds the version this paper pins, so its answers cannot be "
                    + "shown here.";

    // ===================== The preview (U-53) =============================

    /**
     * The header control that opens the paper as a coordinator reads it (U-53).
     *
     * <p>"Preview" rather than "View exam": the builder is already a view of the exam, and what
     * this opens is the other one, the one with the student's own question cards and the
     * teacher-only notes beside them.
     */
    public static final String PREVIEW_BUTTON = "Preview";

    /**
     * Why the control is inert on a paper that has never been saved.
     *
     * <p>A tooltip rather than a sentence on the header, because it answers a question only the
     * teacher who tried the button is asking. The preview is served by
     * {@code EXAM_PREVIEW_GET}, which addresses an exam <em>version</em>, and a draft that has
     * never been saved is not one yet.
     */
    public static final String PREVIEW_NEEDS_SAVE =
            "Save this draft first. The preview reads the saved version.";

    /**
     * Shown once on a paper where something has been re-pinned and not yet saved (E7.14).
     *
     * <p>The row moves its pin immediately and keeps the old wording, because this screen has
     * never been sent the new version's text: the wire carries the newer version's id and number
     * and none of its content. Saying so is the difference between a screen that is behind and a
     * screen that is lying. The wording refreshes when the save comes back, because the save is a
     * full replace whose answer is the server's own re-read.
     */
    public static final String REPINNED_NOTICE =
            "Updated questions still show the old version's wording, topic and difficulty "
                    + "until you save.";

    // ===================== The auto tab (E7.13, F3.3) =====================

    /** The two segments across the top of the builder. */
    public static final String MANUAL_TAB = "Choose questions";

    /** The auto segment. */
    public static final String AUTO_TAB = "Compose automatically";

    /** The criteria grid's heading. */
    public static final String CRITERIA_TITLE = "What the exam should contain";

    /** The label on the course-wide row, which has no topic of its own. */
    public static final String COURSE_WIDE_ROW = "Anywhere in this course";

    /** The control that adds a topic row. */
    public static final String ADD_TOPIC = "Add a topic";

    /** The control that removes one. */
    public static final String REMOVE_TOPIC = "Remove";

    /** The topic box's prompt. */
    public static final String TOPIC_PROMPT = "Topic";

    /**
     * Said when she has asked for questions on a row whose topic box is still empty.
     *
     * <p>The one criteria rule this client owns, and only because the server never sees the state:
     * an unnamed row is not sent, so {@code ExamBuildMessages} has no sentence for it. Without
     * this she got either a refusal about two whole-course rows she could not see, or - quieter
     * and worse - a paper composed from the entire course while she believed she had named a
     * topic.
     */
    public static final String TOPIC_REQUIRED =
            "Name the topic on every row that asks for questions, or set its counts back to zero.";

    /** Column headings on the criteria grid, in bucket order. */
    public static final String EASY_LABEL = "Easy";

    /** @see #EASY_LABEL */
    public static final String MEDIUM_LABEL = "Medium";

    /** @see #EASY_LABEL */
    public static final String HARD_LABEL = "Hard";

    /** The any-difficulty column, which is a count rather than a grade. */
    public static final String ANY_LABEL = "Any";

    /** The button that asks the server to compose. */
    public static final String GENERATE = "Compose the exam";

    /**
     * Said beside the button, before it is pressed.
     *
     * <p>A successful compose replaces whatever is on the paper, and a teacher who has spent ten
     * minutes picking questions must not discover that afterwards. The unsaved draft on the
     * server is untouched, so reopening is the way back, and this says which way is which.
     */
    public static final String GENERATE_REPLACES =
            "Composing replaces the questions currently on this paper. Nothing is saved until "
                    + "you press save.";

    /** Shown while the server composes. */
    public static final String COMPOSING = "Composing...";

    /** Shown when the compose could not be run at all, as opposed to being refused. */
    public static final String COMPOSE_FAILED =
            "The exam could not be composed. Try again.";

    /** The heading over the shortfall report (§7.1, F3.3). */
    public static final String INFEASIBLE_TITLE =
            "No exam was created. The bank cannot satisfy these criteria:";

    /**
     * What the teacher is invited to do about it (§7.3).
     *
     * <p>Every sentence in the report is a claim she can disprove by filtering her own bank to
     * the topic named in it, and the contract leans on her being able to. Saying so turns a
     * refusal into an instruction.
     */
    public static final String INFEASIBLE_HINT =
            "Lower a count, widen a difficulty, or add questions to the bank.";

    /**
     * One shortfall as a sentence (§7.1's four shapes, ruling 4).
     *
     * <p><b>Composed here and nowhere else.</b> Ruling 4 kept the sentence on the client and
     * {@code Shortfall} structural, precisely so there is one expression of it: a {@code summary}
     * string on the wire would be a second copy of what the four fields already say, and the two
     * would eventually disagree.
     *
     * <p><b>The wording is the PRD's own, not the contract table's.</b> PRD F3.3 writes
     * {@code Topic 'Algebra': requested 5 Hard, bank has 2} with single quotes and no full stop,
     * and that string is F3.3's acceptance artefact. §7.1's table renders the same shapes with
     * double quotes and a trailing stop; where they differ the PRD wins, and
     * {@code AutoComposeResultTest} and {@code AutoComposerTest} both already quote the PRD form.
     *
     * @param shortfall one row of the report; null topic is course-wide, null difficulty is the
     *                  any bucket
     * @return the sentence for it
     */
    public static String shortfallLine(Shortfall shortfall) {
        String what = shortfall.difficulty() == null
                ? shortfall.requested() + " questions"
                : shortfall.requested() + " " + gradeOf(shortfall.difficulty());
        String tail = "requested " + what + ", bank has " + shortfall.available();
        return shortfall.topic() == null
                ? capitalise(tail)
                : "Topic '" + shortfall.topic() + "': " + tail;
    }

    /** Title case for the grade, because the PRD's sentence writes "5 Hard" and not "5 HARD". */
    private static String gradeOf(Difficulty difficulty) {
        String name = difficulty.name();
        return name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String capitalise(String sentence) {
        return Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1);
    }

    /**
     * What a successful compose did.
     *
     * @param questions how many it put on the paper
     * @return the notice
     */
    public static String composedNotice(int questions) {
        return questions == 1
                ? "One question was composed onto the paper. Edit it, then save."
                : questions + " questions were composed onto the paper. Edit them, then save.";
    }

    // ===================== The bank picker (E7.12) ========================

    /**
     * The control that opens the picker.
     *
     * <p>{@code ADD_UNAVAILABLE} stood here until 2026-08-26 and said "adding questions is not
     * available in this build yet", because the add path needed a version id the frozen bank wire
     * did not carry. BANK amendment A1 landed it; the apology and its label went with it.
     */
    public static final String ADD_BUTTON = "Add from the bank";

    /** The picker's own heading, which names the course it is scoped to. */
    public static String pickerTitle(String courseName) {
        return "Add a question from " + courseName;
    }

    /** The picker's filter box. */
    public static final String PICKER_SEARCH_PROMPT = "Search by id, text or topic";

    /** The picker's close control. */
    public static final String PICKER_CLOSE = "Done";

    /** The per-row control. */
    public static final String PICKER_ADD = "Add";

    /**
     * The per-row control when the question is already on the paper (§5.2, T-3.9).
     *
     * <p>Says which rule refused it rather than greying out silently: a teacher looking at a
     * question she knows is in her bank and cannot add needs to be told it is already on her own
     * paper, possibly as a different version of itself.
     */
    public static final String PICKER_ALREADY_ADDED = "Already on this exam";

    /** Shown while the course bank is being fetched. */
    public static final String PICKER_LOADING = "Loading the question bank...";

    /** Shown when the bank could not be read. */
    public static final String PICKER_LOAD_FAILED =
            "The question bank could not be loaded. Try again.";

    /** Shown when the course bank has more pages than the picker will fetch. */
    public static final String PICKER_TOO_MANY =
            "This course has more questions than the picker can show. Use the search box to "
                    + "narrow it down.";

    /** Shown when the course has no questions at all. */
    public static final String PICKER_EMPTY =
            "This course has no questions in the bank yet.";

    /** Shown when the filter matches nothing, which is not the same as an empty bank. */
    public static final String PICKER_NO_MATCH =
            "No question in this course matches that search.";

    // ===================== Saving =========================================

    /** The save button when a new exam is being written. */
    public static final String CREATE_BUTTON = "Create exam";

    /** The save button when an existing draft is open. */
    public static final String SAVE_BUTTON = "Save draft";

    /** Shown when a save or create lands. */
    public static final String SAVED_NOTICE = "Saved.";

    /** The control beside a failed load, which is also the way out of a stale-token refusal. */
    public static final String RETRY = "Try again";

    /** Shown when the load fails, beside {@link #RETRY}. */
    public static final String LOAD_FAILED =
            "This exam could not be opened. Check the connection and try again.";

    /** Shown when a save fails for a reason the server did not put a sentence to. */
    public static final String SAVE_FAILED =
            "That did not save. Check the connection and try again.";

    /** Shown when the version moved under her and the server had nothing else to say. */
    public static final String STALE_NOTICE =
            "This exam changed while you had it open. Open it again before saving.";

    // ===================== Derived text ===================================

    /**
     * @param count how many questions are on the paper
     * @return "1 question" or "12 questions", never "1 questions"
     */
    public static String questions(int count) {
        return count + (count == 1 ? " question" : " questions");
    }

    /**
     * The live points indicator (E7.3, S-11, T-3.2).
     *
     * <p>Always shows the target, not only the total. "62" alone makes her work out what is
     * missing; "62 of 100" is the same glance and the answer.
     *
     * @param total what the paper currently adds up to
     * @return the indicator's text
     */
    public static String pointsIndicator(int total) {
        return total + " of " + ExamCreateRequest.POINTS_TOTAL + " points";
    }

    /**
     * The hint under the name field.
     *
     * @return the limit, read off the wire record rather than typed here
     */
    public static String nameHint() {
        return "Up to " + ExamCreateRequest.MAX_NAME_LENGTH + " characters.";
    }

    /**
     * The hint under the duration field.
     *
     * @return the range, read off the wire record; the ceiling has already moved once
     */
    public static String durationHint() {
        return "Between " + ExamCreateRequest.MIN_DURATION_MINUTES + " and "
                + ExamCreateRequest.MAX_DURATION_MINUTES + " minutes.";
    }

    /**
     * The hint under a points field.
     *
     * @return the per-question range, read off {@link QuestionPin}
     */
    public static String pointsHint() {
        return "Between " + QuestionPin.MIN_POINTS + " and " + QuestionPin.MAX_POINTS
                + " per question.";
    }

    /**
     * @param length how long the text currently is
     * @param limit  the ceiling for that field
     * @return the counter under a long-text box, so she is not refused after typing
     */
    public static String textCounter(int length, int limit) {
        return length + " of " + limit + " characters.";
    }

    /**
     * The line under a question on the paper.
     *
     * @param line one line of the composition
     * @return its id, topic and difficulty in one scannable string
     */
    public static String questionSummary(ExamBuilderSession.Line line) {
        if (line == null) {
            return "";
        }
        String topic = line.topic() == null || line.topic().isBlank() ? "No topic" : line.topic();
        String difficulty = line.difficulty() == null
                ? ""
                : " · " + line.difficulty().name().toLowerCase(Locale.ENGLISH);
        return line.displayId5() + " · " + topic + difficulty;
    }

    /**
     * The course this exam belongs to, as the header says it (2026-08-29, manual round 3, U-30).
     *
     * <p><b>The same "code &middot; name" shape {@link ExamListCopy#courseLabel} uses</b>, and
     * copied rather than shared for the reason that method's own javadoc gives: that one takes a
     * loaded {@code ExamListRow} and this one takes two strings, because in {@code Mode.CREATE}
     * there is no exam yet and the name has to come off the sign-in payload's {@code CourseRef}.
     * A teacher moving between her exam list and the builder reads one spelling of one course.
     *
     * <p>Prefixed with the word rather than left bare: the subtitle beside it is a six-digit id,
     * and "110101 &middot; 11 &middot; Algebra 11" is three numbers in a row with nothing saying
     * which is which.
     *
     * @param code the course code, which {@code ExamBuilderSession} always has
     * @param name its name, which may be blank when the payload carries no name for it
     * @return "Course: 11 &middot; Algebra 11", or "Course: 11" when the name is unknown
     */
    public static String courseLine(String code, String name) {
        String safeCode = code == null ? "" : code.trim();
        String safeName = name == null ? "" : name.trim();
        if (safeCode.isEmpty() && safeName.isEmpty()) {
            return "";
        }
        if (safeCode.isEmpty()) {
            return COURSE_PREFIX + ": " + safeName;
        }
        return COURSE_PREFIX + ": " + safeCode
                + (safeName.isEmpty() ? "" : " · " + safeName);
    }

    /**
     * The title, which says which of the three things the screen is doing.
     *
     * @param mode the derived mode
     * @return the heading for it
     */
    public static String title(ExamBuilderSession.Mode mode) {
        if (mode == null) {
            return TITLE_NEW;
        }
        return switch (mode) {
            case CREATE -> TITLE_NEW;
            case EDIT -> TITLE_EDIT;
            case READ_ONLY -> TITLE_READ_ONLY;
        };
    }

    /**
     * The save button's label, which has to match the verb the mode will send.
     *
     * @param mode the derived mode
     * @return "Create exam" or "Save draft"
     */
    public static String saveButton(ExamBuilderSession.Mode mode) {
        return mode == ExamBuilderSession.Mode.CREATE ? CREATE_BUTTON : SAVE_BUTTON;
    }

    private ExamBuildCopy() {
    }
}
