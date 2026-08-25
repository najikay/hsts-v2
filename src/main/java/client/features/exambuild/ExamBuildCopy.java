package client.features.exambuild;

import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.QuestionPin;

import java.util.Locale;

/**
 * Every sentence the exam builder shows (Presentation tier, E7.11 / E7.12 — F3.1, F3.5).
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

    // ===================== The picker, which is not built yet =============

    /**
     * Why the Add button is disabled.
     *
     * <p>Named rather than left inert. The picker's add path needs a {@code questionVersionId}
     * and the frozen bank wire carries none, which is a contract gap raised with the lead rather
     * than something a teacher did wrong. A disabled control with no explanation is the mystery
     * state PRD §4.1 forbids; this is the honest version of it until the field lands.
     */
    public static final String ADD_UNAVAILABLE =
            "Adding questions is not available in this build yet.";

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
