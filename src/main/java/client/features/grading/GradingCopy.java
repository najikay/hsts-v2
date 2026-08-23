package client.features.grading;

import common.dto.grading.ExecutionGradingSummary;
import common.dto.grading.StudentGradeRow;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Every sentence and formatted value on the teacher's grading screen (Presentation tier, E12).
 *
 * <p>Measured here because {@code GradingQueueView} is a thin renderer excluded from the
 * coverage gate. The two that carry real weight are {@link #progress}, which is what tells a
 * teacher whether a sitting needs her, and {@link #bulkConfirm}, which is the last thing she
 * reads before publishing a class's grades to a class of students.
 */
public final class GradingCopy {

    /** The screen's heading. */
    public static final String TITLE = "Grading";

    /** The style class the root carries. */
    public static final String STYLE_CLASS = "grading";

    /** Heading over the list of sittings awaiting her. */
    public static final String QUEUE_HEADING = "Waiting for you";

    /** Explains what the queue contains, and therefore why it empties. */
    public static final String QUEUE_HINT =
            "Exam sittings that have closed and still have grades to approve.";

    /** Shown when nothing is waiting — a finished inbox, not a failure. */
    public static final String QUEUE_EMPTY_TITLE = "Nothing to grade";

    /** The empty state, explained, so it is not mistaken for a loading failure. */
    public static final String QUEUE_EMPTY_HINT =
            "Every sitting you have run is fully approved. Sittings appear here when they close "
                    + "with grades still awaiting your approval.";

    /** Shown when the queue could not be loaded; says nothing about why (F1.1's discipline). */
    public static final String LOAD_FAILED =
            "The grading queue could not be loaded. Please try again.";

    /** Shown when one sitting could not be opened. */
    public static final String EXECUTION_FAILED =
            "That sitting could not be opened. Please try again.";

    /** Shown when an approval failed outright. */
    public static final String APPROVE_FAILED =
            "Those grades could not be approved. Please try again.";

    /** Shown when an override was refused because the grade is already published. */
    public static final String OVERRIDE_CONFLICT =
            "This grade has already been approved and sent to the student, so it can no longer "
                    + "be changed.";

    /** Column heading: the student. */
    public static final String COLUMN_STUDENT = "Student";

    /** Column heading: what the machine scored. */
    public static final String COLUMN_AUTO = "Auto";

    /** Column heading: the score that counts. */
    public static final String COLUMN_SCORE = "Score";

    /** Column heading: approved or awaiting. */
    public static final String COLUMN_STATE = "State";

    /** Column heading: the adjusted marker. */
    public static final String COLUMN_ADJUSTED = "";

    /** The button that publishes every selected grade. */
    public static final String APPROVE_SELECTED = "Approve selected";

    /** The button that opens the override dialog. */
    public static final String OVERRIDE = "Change score…";

    /** Heading of the override dialog. */
    public static final String OVERRIDE_TITLE = "Change this score";

    /**
     * The label on the justification field, and it is doing product work.
     *
     * <p>It says the reason is stored and that the student will not see it. Both halves matter:
     * a teacher who thinks it is private writes something careless, and one who thinks the
     * student reads it writes nothing useful for the audit trail (S-23).
     *
     * <p>It names the comment box as the place for anything meant for the student, which is the
     * half that stops the justification being written to the wrong reader.
     */
    public static final String JUSTIFICATION_LABEL =
            "Why are you changing it? Stored with the grade for the record. The student does not "
                    + "see this. Use the comment below for anything meant for her.";

    /** The prompt inside the justification box, so an empty dialog says which box is which. */
    public static final String JUSTIFICATION_PROMPT = "Reason for the record";

    /** Refusal shown when the teacher leaves the justification blank. */
    public static final String JUSTIFICATION_REQUIRED =
            "Please say why you are changing this score.";

    /**
     * The label on the comment field (S-22), and the sentence the whole feature turns on.
     *
     * <p>Three things, in the order a teacher needs them. That it is <b>optional</b>, so she is
     * not made to write something to move a score. That <b>the student reads it</b>, which is
     * the entire difference from the box above and the reason the two are separated rather than
     * being one note. And that <b>leaving it empty keeps what is already saved</b>, because the
     * second time she corrects a paper this box opens empty and she would otherwise have to
     * guess whether pressing OK erases what she wrote the first time. It does not.
     */
    public static final String COMMENT_LABEL =
            "Add a comment for the student? Optional, and she will see it with her grade. "
                    + "Leaving it empty keeps any comment already saved.";

    /** The prompt inside the comment box, in the second person she should be writing in. */
    public static final String COMMENT_PROMPT = "Comment the student will read";

    /** Refusal shown when the score is outside 0..100. */
    public static final String SCORE_OUT_OF_RANGE = "A score must be between 0 and 100.";

    /** "20 Aug 14:30" — when a sitting closed, as a teacher reads it off a schedule. */
    private static final DateTimeFormatter CLOSED_AT =
            DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);

    private GradingCopy() {
        // static helper — no instances
    }

    /**
     * How far through a sitting she is.
     *
     * <p>The line the queue exists for. It states the remainder rather than only the totals,
     * because "6 of 8 approved" makes a teacher subtract and "2 still to approve" does not, and
     * the number she is acting on is the remainder.
     *
     * @param summary a queue row
     * @return for example {@code "8 sat · 8 marked · 2 still to approve"}
     */
    public static String progress(ExecutionGradingSummary summary) {
        Objects.requireNonNull(summary, "summary");
        int remaining = summary.gradedCount() - summary.approvedCount();
        return summary.participants() + " sat · " + summary.gradedCount() + " marked · "
                + remaining + " still to approve";
    }

    /**
     * @param summary a queue row
     * @param zone    the zone to render in, normally the system default
     * @return when the sitting closed, or an em dash when it has no closing time
     */
    public static String closedAt(ExecutionGradingSummary summary, ZoneId zone) {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(zone, "zone");
        return summary.closedAt() == null ? "—" : CLOSED_AT.format(summary.closedAt().atZone(zone));
    }

    /**
     * @param summary a queue row
     * @return the exam and its course, for the queue row and the table header
     */
    public static String examLabel(ExecutionGradingSummary summary) {
        Objects.requireNonNull(summary, "summary");
        return summary.examName() + " · " + summary.courseCode();
    }

    /**
     * The confirmation before a bulk approve.
     *
     * <p><b>Names the consequence, not the action.</b> "Approve 8 grades?" describes what the
     * teacher clicked; what she needs to weigh is that eight students are about to be able to
     * see their marks, and that approving cannot be undone in v1 — overriding an approved grade
     * answers {@code CONFLICT} by design.
     *
     * @param count how many grades are about to be approved
     * @return the sentence for the confirm dialog
     */
    public static String bulkConfirm(int count) {
        if (count == 1) {
            return "Approve this grade? The student will be able to see it straight away, and an "
                    + "approved grade cannot be changed afterwards.";
        }
        return "Approve " + count + " grades? Those " + count + " students will be able to see "
                + "their marks straight away, and an approved grade cannot be changed afterwards.";
    }

    /**
     * The state of one row, in the teacher's vocabulary.
     *
     * @param row a grade row
     * @return "Approved" or "Awaiting approval"
     */
    public static String state(StudentGradeRow row) {
        Objects.requireNonNull(row, "row");
        return switch (row.state()) {
            case APPROVED -> "Approved";
            case AUTO -> "Awaiting approval";
        };
    }

    /**
     * The adjusted marker (S-23).
     *
     * <p>Keyed on the two scores <b>differing</b>, not on a final score existing: approving sets
     * {@code finalScore} to the auto score when nobody overrode, so every approved row has one.
     *
     * @param row a grade row
     * @return "Adjusted" when a teacher moved the score, otherwise an empty string
     */
    public static String adjustedMarker(StudentGradeRow row) {
        Objects.requireNonNull(row, "row");
        return wasAdjusted(row) ? "Adjusted" : "";
    }

    /**
     * @param row a grade row
     * @return whether a teacher's score replaced the machine's with a different number
     */
    public static boolean wasAdjusted(StudentGradeRow row) {
        Objects.requireNonNull(row, "row");
        return row.finalScore() != null && row.finalScore() != row.autoScore();
    }

    /**
     * Whether this row may still be changed.
     *
     * <p>Override is allowed only while {@code AUTO} (contract). The screen disables the control
     * rather than letting her type a justification and then be refused — the refusal is correct
     * but it wastes the one piece of writing she actually had to do.
     *
     * @param row a grade row
     * @return {@code true} when the override dialog should be available
     */
    public static boolean canOverride(StudentGradeRow row) {
        Objects.requireNonNull(row, "row");
        return row.state() == common.dto.grading.GradeState.AUTO;
    }
}
