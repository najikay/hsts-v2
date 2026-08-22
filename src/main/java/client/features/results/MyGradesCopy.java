package client.features.results;

import common.dto.grading.GradeState;
import common.dto.grading.StudentGradeRow;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Every sentence and every formatted value on the student's <b>My Grades</b> screen
 * (Presentation tier, E13.3 — F9.1, T-9.1).
 *
 * <p>Separate from {@link ResultsCopy}, which is the teacher's. They describe the same rows and
 * must not share wording: a teacher reads a class, a student reads herself, and the one place
 * that difference matters most is the override. To a teacher it is "adjusted, and here is the
 * justification"; to a student it is "your teacher reviewed this" and <em>never</em> the
 * justification, which the wire has already stripped (S-23). Two audiences, two files, so a
 * convenient reuse cannot quietly put teacher wording in front of a student.
 *
 * <p>All of it is static and testable, because {@code MyGradesView} is a thin renderer excluded
 * from the coverage gate. Anything on this screen that involves a decision — what a score reads
 * as, what an adjusted grade says, how a date is written — lives here so that it is measured.
 */
public final class MyGradesCopy {

    /** The screen's heading. */
    public static final String TITLE = "My Grades";

    /**
     * The subtitle, and it is doing real work.
     *
     * <p>A student who has sat an exam and sees nothing needs to know that the silence means
     * "not approved yet" rather than "lost". Saying it once, above the list, means the empty
     * state is not the only place the rule appears — the same student refreshing a list that
     * already has three grades in it still learns why a fourth is missing (C-3, S-24).
     */
    public static final String SUBTITLE =
            "Grades appear here once your teacher has approved them.";

    /** Column heading: which exam the row is for. */
    public static final String COLUMN_EXAM = "Exam";

    /** Column heading: the course it belonged to. */
    public static final String COLUMN_COURSE = "Course";

    /** Column heading: the score that counts. */
    public static final String COLUMN_SCORE = "Grade";

    /** Column heading: when the teacher approved it. */
    public static final String COLUMN_APPROVED = "Approved";

    /** Column heading: the teacher's note, when there is one. */
    public static final String COLUMN_COMMENT = "Teacher's note";

    /**
     * The marker on a row whose score a teacher changed by hand (S-22).
     *
     * <p>Deliberately gentler than the teacher table's bare "Adjusted". A teacher scanning her
     * class wants the exception to stand out; a student reading her own transcript is being told
     * something about her own paper, and "Adjusted" alone reads like a correction was needed
     * rather than that a person looked at it.
     *
     * <p>It says a teacher set the grade. It does <b>not</b> say why: {@code overrideReason} is
     * teacher and audit material and never reaches this tier — {@link common.dto.grading.MyGrades}
     * strips it structurally. What the student is offered instead is {@code teacherComment},
     * which was written for her.
     */
    public static final String ADJUSTED_MARKER = "Reviewed by your teacher";

    /** Shown in the comment column when the teacher wrote nothing. */
    public static final String NO_COMMENT = "—";

    /** The style class the screen's root carries, for the stylesheet and for tests. */
    public static final String STYLE_CLASS = "my-grades";

    /** "20 Aug 2026" — a date a student reads off a transcript, no clock time. */
    private static final DateTimeFormatter APPROVED_ON =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private MyGradesCopy() {
        // static helper — no instances
    }

    /**
     * The grade as the student reads it.
     *
     * <p>{@code effectiveScore} and never a re-derivation: the server computed which of the two
     * scores counts and put it on the wire precisely so no screen has to get that null check
     * right (contract, {@link StudentGradeRow}).
     *
     * <p>Out of 100 is stated rather than assumed. Every exam totals 100 by construction
     * (§8.1, {@code AutoGrader.REQUIRED_TOTAL_POINTS}), and a bare "70" invites a student to
     * wonder out of what — which is the sort of small ambiguity that produces an email to a
     * teacher the night before a defence.
     *
     * @param row a loaded row
     * @return for example {@code "70 / 100"}
     */
    public static String score(StudentGradeRow row) {
        Objects.requireNonNull(row, "row");
        return row.effectiveScore() + " / 100";
    }

    /**
     * The exam's name, or an honest placeholder.
     *
     * <p>{@code examName} is v1.1 and nullable: it is populated on the student paths, but a row
     * whose joins did not resolve arrives unlabelled rather than mislabelled (that is the
     * server's rule, and this is its consequence on screen). A placeholder is the right answer
     * because the grade itself is real — hiding the row would lose a grade over a missing label.
     *
     * @param row a loaded row
     * @return the exam's name, or {@code "(exam unavailable)"}
     */
    public static String examName(StudentGradeRow row) {
        Objects.requireNonNull(row, "row");
        return blankToNull(row.examName()) == null ? "(exam unavailable)" : row.examName();
    }

    /**
     * @param row a loaded row
     * @return the course code, or an em dash when the row arrived unlabelled
     */
    public static String courseCode(StudentGradeRow row) {
        Objects.requireNonNull(row, "row");
        return blankToNull(row.courseCode()) == null ? NO_COMMENT : row.courseCode();
    }

    /**
     * When the teacher approved it, in the student's own time zone.
     *
     * <p>The wire is UTC (ADR-010) and this is the tier that converts. Approval is the moment
     * the grade became hers, which is why it is the date shown rather than when she sat the
     * paper: a student looking at this list is asking "what do I have", not "what did I do".
     *
     * @param row  a loaded row
     * @param zone the zone to render in, normally the system default
     * @return for example {@code "20 Aug 2026"}; an em dash if somehow unapproved
     */
    public static String approvedOn(StudentGradeRow row, ZoneId zone) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(zone, "zone");
        Instant approvedAt = row.approvedAt();
        if (approvedAt == null) {
            // Only APPROVED rows reach this screen, so this is a server bug rather than a
            // state. Render a dash rather than throwing: one odd row must not blank the list.
            return NO_COMMENT;
        }
        return APPROVED_ON.format(approvedAt.atZone(zone));
    }

    /**
     * @param row a loaded row
     * @return the teacher's note, or an em dash. Never the justification — see
     *         {@link #ADJUSTED_MARKER}
     */
    public static String comment(StudentGradeRow row) {
        Objects.requireNonNull(row, "row");
        String comment = blankToNull(row.teacherComment());
        return comment == null ? NO_COMMENT : comment;
    }

    /**
     * Whether this row should carry the adjusted marker.
     *
     * <p>Deliberately <b>not</b> "has a final score". Approving a grade sets {@code finalScore}
     * to the auto score when nobody overrode it ({@code Grade.approve}), so every approved row
     * has one — and a chip driven by its presence would tell every student in the class that
     * their paper had been changed by hand. The question is whether the two scores <em>differ</em>.
     *
     * @param row a loaded row
     * @return {@code true} when a teacher's score replaced the machine's with a different number
     */
    public static boolean wasAdjusted(StudentGradeRow row) {
        Objects.requireNonNull(row, "row");
        return row.finalScore() != null && row.finalScore() != row.autoScore();
    }

    /**
     * The adjusted marker, or an empty string (S-22, S-23).
     *
     * <p>Empty rather than an em dash for the ordinary case: the column exists for the
     * exception, and filling every other row with a placeholder would draw the eye to the rows
     * where nothing happened.
     *
     * @param row a loaded row
     * @return {@link #ADJUSTED_MARKER}, or {@code ""}
     */
    public static String adjustedMarker(StudentGradeRow row) {
        Objects.requireNonNull(row, "row");
        return wasAdjusted(row) ? ADJUSTED_MARKER : "";
    }

    /**
     * Text for a screen reader, for one row.
     *
     * <p>The table's columns make sense side by side and not one at a time, so a row read aloud
     * cell by cell says "Algebra midterm, 11, 70 out of 100, 20 Aug 2026" and leaves the listener
     * assembling it. NFR accessibility is not a stretch goal on the one screen whose entire
     * content is a number about the person reading it.
     *
     * @param row  a loaded row
     * @param zone the zone to render the date in
     * @return one sentence describing the row
     */
    public static String rowDescription(StudentGradeRow row, ZoneId zone) {
        Objects.requireNonNull(row, "row");
        StringBuilder text = new StringBuilder()
                .append(examName(row))
                .append(", ")
                .append(score(row))
                .append(", approved ")
                .append(approvedOn(row, zone));
        if (wasAdjusted(row)) {
            text.append(", ").append(ADJUSTED_MARKER.toLowerCase(Locale.ENGLISH));
        }
        return text.toString();
    }

    /**
     * @param row a loaded row
     * @return {@code true} when the row is approved, which every row on this screen must be
     */
    public static boolean isApproved(StudentGradeRow row) {
        Objects.requireNonNull(row, "row");
        return row.state() == GradeState.APPROVED;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
