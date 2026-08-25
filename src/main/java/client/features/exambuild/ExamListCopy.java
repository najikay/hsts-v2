package client.features.exambuild;

import common.dto.approval.ApprovalState;
import common.dto.authoring.ExamListRow;
import common.dto.authoring.ExamVersionRow;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Every sentence the exam list shows (Presentation tier, E7.10 — F3.6, F4.2).
 *
 * <p>Separate from the view for the reason {@code BankCopy} and {@code ApprovalCopy} are: the
 * wording is checkable without a JavaFX toolkit, so a pluralisation or a state label that stops
 * being true fails a unit test rather than a screenshot. Every string a teacher can read on this
 * screen is here, and the view holds none of its own.
 *
 * <p><b>House rule PRD §4.1: no em dashes in user-visible text.</b> That governs every constant
 * and every returned string in this file. It does not govern this javadoc.
 *
 * <h2>State labels are not written here</h2>
 *
 * <p>{@link ApprovalState#label()} is on the wire enum and already says "Draft", "Waiting for
 * approval", "Approved" and "Sent back". Copying those four words into this file would create a
 * second place for them to disagree with the coordinator's queue, which reads the same enum.
 * {@link #stateLabel(ApprovalState)} delegates, exactly as {@code ApprovalCopy} does.
 */
public final class ExamListCopy {

    // ===================== The screen ====================================

    /** The screen title, and the rail label it sits under. */
    public static final String TITLE = "My exams";

    /**
     * What the screen is, in one line under the title.
     *
     * <p>Says "every version" on purpose: this is the sentence that tells a teacher the drafts
     * she cannot see today are here now, which is the visible half of the retirement of
     * {@code MY_APPROVALS_GET} (contract section 8).
     */
    public static final String SUBTITLE =
            "Every exam you have written, with every version and what became of it.";

    /** The heading of the panel listing the selected exam's versions. */
    public static final String VERSIONS_TITLE = "Versions";

    // ===================== Table headers ==================================
    //
    // Here rather than inline in the view, and the reason is the guard rather than tidiness: the
    // em-dash check below reflects over THIS class's constants, so a header left as a literal in
    // ExamListView is user-visible text the house rule cannot see. The class javadoc claims the
    // view holds no strings of its own; these five are what make that claim true.

    /** The exam's name, which is the latest version's name. */
    public static final String COLUMN_EXAM = "Exam";

    /** The 6-digit id staff quote when they talk about an exam (S-10). */
    public static final String COLUMN_ID = "Id";

    /** The owning course. */
    public static final String COLUMN_COURSE = "Course";

    /** How many versions the exam has had, which under C-2 only ever grows. */
    public static final String COLUMN_VERSIONS = "Versions";

    /** The chip column, describing the latest version and not the row's whole history. */
    public static final String COLUMN_LATEST = "Latest";

    /** What the versions panel says before an exam has been picked. */
    public static final String NO_SELECTION =
            "Pick an exam to see its versions.";

    // ===================== Empty and error ===============================

    /**
     * The empty state's heading.
     *
     * <p>There is deliberately exactly one empty state on this screen, which is what
     * {@code ExamList}'s javadoc means by "an empty list is a real answer": a teacher who
     * teaches nothing cannot reach the screen at all, so "you have no exams" is never
     * ambiguous with "you teach nothing".
     */
    public static final String EMPTY_TITLE = "No exams yet";

    /** The empty state's second line, which says what to do rather than restating the first. */
    public static final String EMPTY_HINT =
            "Exams you write appear here, with every version you save.";

    /** Shown when {@code EXAM_LIST} fails, above a retry. */
    public static final String LOAD_FAILED =
            "Your exams could not be loaded. Check the connection and try again.";

    // ===================== Actions =======================================

    /** The button that sends a draft to the coordinator (E7.6). */
    public static final String SUBMIT = "Submit for approval";

    /** The button that opens a new draft from a version that is no longer editable (E7.5). */
    public static final String REVISE = "Revise";

    /** The cancel half of every confirmation on this screen. */
    public static final String CANCEL = "Cancel";

    // ===================== Submit confirmation (E7.15) ===================

    /** The submit dialog's heading. */
    public static final String SUBMIT_TITLE = "Send this exam for approval?";

    /**
     * What submitting costs her, said before she does it.
     *
     * <p>The one fact she cannot get back afterwards: F3.6 makes PENDING non-editable, so a
     * submit ends the editing session for that version. Saying it here is cheaper than a
     * support question later.
     */
    public static final String SUBMIT_EXPLANATION =
            "Your coordinator will be notified. While it is waiting for approval you cannot "
                    + "change this version, but you can always revise it into a new draft.";

    // ===================== Revise confirmation ===========================

    /** The revise dialog's heading. */
    public static final String REVISE_TITLE = "Start a new version?";

    /**
     * What revising does, said in terms of what she will see.
     *
     * <p>C-2 is the reason this is worth a sentence: nothing is overwritten and the old version
     * stays exactly where it is, which is not what "revise" suggests on its own.
     */
    public static final String REVISE_EXPLANATION =
            "This copies the exam into a new draft you can change. The version you are looking "
                    + "at stays as it is, and both keep their place in the list.";

    // ===================== Outcomes ======================================

    /** The rejection panel's heading, which matches the wording E8's screen used (F4.2). */
    public static final String REJECTED_PANEL_TITLE = "Sent back by your coordinator";

    /** Shown after a successful submit. */
    public static final String SUBMITTED_NOTICE =
            "Sent for approval. Your coordinator has been notified.";

    /** Shown when the server refused the action because the row had moved on (CONFLICT). */
    public static final String STALE_NOTICE =
            "This exam changed while the list was open. It has been reloaded, so check the "
                    + "version before trying again.";

    /** Shown when the version is no longer there at all (NOT_FOUND). */
    public static final String GONE_NOTICE =
            "That version is no longer there. The list has been reloaded.";

    /** Shown when the action failed for any other reason. */
    public static final String ACTION_FAILED =
            "That did not go through. Check the connection and try again.";

    // ===================== Derived text ==================================

    /** How a version's creation instant is rendered, in the reader's own zone (ADR-010). */
    private static final DateTimeFormatter CREATED =
            DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH);

    /**
     * @param when a UTC instant from the wire
     * @return it rendered in the reader's local zone, because a teacher thinks in her own
     *         afternoon and not in UTC
     */
    public static String createdAt(Instant when) {
        return when == null ? "" : CREATED.format(when.atZone(ZoneId.systemDefault()));
    }

    /**
     * @param count how many questions the paper has
     * @return "1 question" or "12 questions", never "1 questions"
     */
    public static String questions(int count) {
        return count + (count == 1 ? " question" : " questions");
    }

    /**
     * @param minutes the exam's duration
     * @return "1 minute" or "60 minutes"
     */
    public static String minutes(int minutes) {
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    /**
     * @param count how many versions an exam has had
     * @return "1 version" or "3 versions". C-2 means this only ever grows
     */
    public static String versions(int count) {
        return count + (count == 1 ? " version" : " versions");
    }

    /**
     * @param versionNo a 1-based version number
     * @return how a version is named everywhere on this screen, so the panel, the dialog and
     *         the notice cannot spell it three ways
     */
    public static String versionLabel(int versionNo) {
        return "Version " + versionNo;
    }

    /**
     * @param state the version's state
     * @return the chip label, taken from the wire enum so this screen and the coordinator's
     *         queue cannot disagree about what a state is called
     */
    public static String stateLabel(ApprovalState state) {
        return state == null ? "" : state.label();
    }

    /**
     * The course line, spelled the way every other screen spells it.
     *
     * <p>{@code ExamListRow} carries the code and the name separately and has no label method of
     * its own, unlike {@code ApprovalRow}. The format is copied from that one deliberately, so a
     * teacher moving between her exam list and her coordinator's queue reads "12 · Calculus" on
     * both rather than two spellings of one course.
     *
     * @param row a loaded exam
     * @return "12 · Calculus", or just the code when the name is blank
     */
    public static String courseLabel(ExamListRow row) {
        if (row == null) {
            return "";
        }
        return row.courseName().isBlank()
                ? row.courseCode()
                : row.courseCode() + " · " + row.courseName();
    }

    /**
     * The one line an exam row shows under its name.
     *
     * @param row a loaded exam
     * @return course and version count in one scannable string
     */
    public static String examSummary(ExamListRow row) {
        return row == null ? "" : courseLabel(row) + " · " + versions(row.versionCount());
    }

    /**
     * The one line a version row shows.
     *
     * @param row a loaded version
     * @return its number, size, length and date, in the order a teacher scans them
     */
    public static String versionSummary(ExamVersionRow row) {
        if (row == null) {
            return "";
        }
        return versionLabel(row.versionNo())
                + " · " + questions(row.questionCount())
                + " · " + minutes(row.durationMinutes())
                + " · " + createdAt(row.createdAt());
    }

    /**
     * The summary inside the submit confirmation (E7.15).
     *
     * <p>Everything it names comes off the row already on screen, which is why this dialog needs
     * no second call: {@code ExamVersionRow} carries the count, the duration and the version
     * number, and the points always total 100 because contract section 5.1 refuses a save that
     * does not. So the summary is exact rather than approximate, and cannot describe a version
     * other than the one whose button she pressed.
     *
     * @param exam    the exam the version belongs to
     * @param version the version being submitted
     * @return the sentence the dialog shows above its buttons
     */
    public static String submitSummary(ExamListRow exam, ExamVersionRow version) {
        if (exam == null || version == null) {
            return "";
        }
        return exam.name() + ", " + versionLabel(version.versionNo()).toLowerCase(Locale.ENGLISH)
                + ": " + questions(version.questionCount())
                + ", " + minutes(version.durationMinutes()) + ".";
    }

    /**
     * The line the revise dialog shows above its buttons.
     *
     * <p><b>It does not predict the new version's number.</b> The obvious sentence here is
     * "version 3 becomes version 4", and it is one concurrent revise away from being false: the
     * number is allocated server-side against {@code uq_exam_versions_no}, so a second client
     * revising the same exam first makes this dialog's arithmetic wrong on a screen that had
     * already promised it. {@link #revisedNotice(int)} names the number afterwards, from the
     * version the server actually created, which is the only place it is knowable.
     *
     * @param exam    the exam being revised
     * @param version the version being copied
     * @return which version is being copied, and what happens to it
     */
    public static String reviseSummary(ExamListRow exam, ExamVersionRow version) {
        if (exam == null || version == null) {
            return "";
        }
        return exam.name() + ": " + versionLabel(version.versionNo())
                + " is copied into a new draft, and stays as it is.";
    }

    /**
     * Shown after a successful revise, naming the version the server made.
     *
     * @param versionNo the version number the server allocated, read off its answer
     * @return the notice naming what she now has
     */
    public static String revisedNotice(int versionNo) {
        return versionLabel(versionNo) + " is ready as a draft.";
    }

    private ExamListCopy() {
    }
}
