package client.features.results;

import common.dto.grading.AnswerReviewRow;
import common.dto.grading.CheckedForm;

import java.util.Objects;

/**
 * Every word and marking rule on the student's checked form (Presentation tier, E13.4 — T-9.2).
 *
 * <p>The screen shows a student which of her answers were wrong, so the wording carries more
 * weight here than on any other student screen. It is measured for the usual reason: the view
 * is a thin renderer excluded from the coverage gate.
 */
public final class CheckedFormCopy {

    /** The screen's heading. */
    public static final String TITLE = "Checked exam";

    /** The style class the root carries, for the stylesheet and for tests. */
    public static final String STYLE_CLASS = "checked-form";

    /** Style class on a question the student got right. */
    public static final String CORRECT_STYLE = "answer-correct";

    /** Style class on a question she got wrong. */
    public static final String WRONG_STYLE = "answer-wrong";

    /** Style class on a question she never reached. */
    public static final String UNANSWERED_STYLE = "answer-unanswered";

    /** What a correct question is labelled. */
    public static final String CORRECT = "Correct";

    /** What a wrong question is labelled. */
    public static final String WRONG = "Wrong";

    /**
     * What an unanswered question is labelled.
     *
     * <p>Deliberately not "Wrong". It scored zero exactly as a wrong answer did (§6), but they
     * are different things and a student reviewing her paper is entitled to the difference:
     * one is a mistake to learn from, the other is a question she never reached. Collapsing
     * them would also make a timed-out paper unreadable — every unreached question would look
     * like an error she made.
     */
    public static final String UNANSWERED = "Not answered";

    /** Shown against the option the student picked. */
    public static final String YOUR_ANSWER = "Your answer";

    /** Shown against the option that was right. */
    public static final String CORRECT_ANSWER = "Correct answer";

    /** The heading over the teacher's note, when there is one. */
    public static final String TEACHER_NOTE = "Your teacher's note";

    /**
     * What the teacher's name is labelled with (A6, 2026-08-28).
     *
     * <p>A bare name under an exam title would read as a second title. The word says whose
     * name it is, which is the whole point of the line: a student sitting papers from several
     * teachers should not have to work out who set this one.
     */
    public static final String TEACHER_PREFIX = "Teacher: ";

    private CheckedFormCopy() {
        // static helper — no instances
    }

    /**
     * How one question is marked.
     *
     * <p>Three outcomes, not two. See {@link #UNANSWERED}.
     *
     * @param row a marked question
     * @return the label for the row
     */
    public static String outcome(AnswerReviewRow row) {
        Objects.requireNonNull(row, "row");
        if (row.isUnanswered()) {
            return UNANSWERED;
        }
        return row.isCorrect() ? CORRECT : WRONG;
    }

    /**
     * The style class for one question's marking.
     *
     * @param row a marked question
     * @return the style class matching {@link #outcome}
     */
    public static String outcomeStyle(AnswerReviewRow row) {
        Objects.requireNonNull(row, "row");
        if (row.isUnanswered()) {
            return UNANSWERED_STYLE;
        }
        return row.isCorrect() ? CORRECT_STYLE : WRONG_STYLE;
    }

    /**
     * The points line for one question.
     *
     * @param row a marked question
     * @return for example {@code "15 / 15"} or {@code "0 / 15"}
     */
    public static String points(AnswerReviewRow row) {
        Objects.requireNonNull(row, "row");
        return row.pointsAwarded() + " / " + row.points();
    }

    /**
     * The header line summarising the whole paper.
     *
     * <p>Includes how the attempt ended, which is the checked-form amendment's whole purpose
     * (acceptance case 9.5): a student whose paper was submitted for her at expiry should not
     * have to work that out from the fact that she does not remember pressing anything.
     *
     * @param form the loaded form
     * @return for example {@code "Algebra midterm · 11 · 71 / 100"}
     */
    public static String header(CheckedForm form) {
        Objects.requireNonNull(form, "form");
        return form.examName() + " · " + form.courseCode() + " · "
                + form.grade().effectiveScore() + " / 100";
    }

    /**
     * How the attempt ended, and how long it took (S-19, 9.5).
     *
     * @param form the loaded form
     * @return for example {@code "Submitted · 45 minutes"} or
     *         {@code "Time ran out, submitted automatically · 75 minutes"}
     */
    public static String attemptLine(CheckedForm form) {
        Objects.requireNonNull(form, "form");
        String how = form.wasTimedOut()
                // Says what happened rather than naming a state: "TIMED_OUT" is a database
                // word, and a student reading it would reasonably wonder whether her paper
                // counted. It did, and it was scored like any other (H12.4).
                // A comma, not an em dash: PRD 4.1 keeps em dashes out of user-visible
                // copy (S3 sweep; the rule the notification catalog's test enforces).
                ? "Time ran out, submitted automatically"
                : "Submitted";
        Integer minutes = form.actualMinutes();
        if (minutes == null) {
            return how;
        }
        return how + " · " + minutes + (minutes == 1 ? " minute" : " minutes");
    }

    /**
     * The teacher who set and released this exam (A6).
     *
     * <p>Null when the server could not resolve the name, which it reports as an empty string.
     * The line is then left out entirely rather than shown with nothing after the colon: a
     * label with no value reads as data that failed to load, and on this screen it would be
     * the only thing on the paper that looked broken.
     *
     * @param form the loaded form
     * @return for example {@code "Teacher: Dana Cohen"}, or {@code null} when there is no name
     */
    public static String teacherLine(CheckedForm form) {
        Objects.requireNonNull(form, "form");
        String name = form.teacherName();
        return name == null || name.isBlank() ? null : TEACHER_PREFIX + name;
    }

    /**
     * @param form the loaded form
     * @return the teacher's note, or {@code null} when she wrote none
     */
    public static String teacherNote(CheckedForm form) {
        Objects.requireNonNull(form, "form");
        String note = form.grade().teacherComment();
        return note == null || note.isBlank() ? null : note;
    }

    /**
     * Which option index the student chose, 1-based, or 0 when she answered nothing.
     *
     * @param row a marked question
     * @return the chosen option number, or 0
     */
    public static int chosenOption(AnswerReviewRow row) {
        Objects.requireNonNull(row, "row");
        return row.isUnanswered() ? 0 : row.chosen();
    }
}
