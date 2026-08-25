package client.features.approval;

import common.dto.approval.ApprovalRow;
import common.dto.approval.ApprovalState;
import common.dto.approval.ExamRejectRequest;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Every sentence the approval screens say (Presentation tier, E8 — PRD §4.1).
 *
 * <p>The client half of what {@code ApprovalMessages} is on the server, and separate from it
 * for the reason the two tiers ship as separate JARs: these are the sentences the client
 * composes for itself — empty states, confirmations, column headers — while the server's are
 * the ones it composes for the client. Keeping both in one class per tier is what makes the
 * copy rules checkable by a test rather than by reading eight view classes.
 *
 * <p>FX-free on purpose, so the copy is unit-tested without a toolkit (TEAM_SPLIT §3.2).
 */
public final class ApprovalCopy {

    private ApprovalCopy() {
    }

    // ===================== Queue screen ==================================

    /** Page title. */
    public static final String QUEUE_TITLE = "Approvals";

    /** Under the title: what this list is and is not. */
    public static final String QUEUE_SUBTITLE =
            "Exams waiting for your decision, in the subjects you coordinate.";

    /** The finished-inbox empty state. */
    public static final String QUEUE_EMPTY_TITLE = "Nothing waiting";

    /** Its hint: an empty queue is good news, and saying so is the point. */
    public static final String QUEUE_EMPTY_HINT =
            "No exams need your approval right now. New submissions appear here on their own.";

    /**
     * The other empty state, which means the opposite thing.
     *
     * <p>A caller who coordinates no subject has not finished her work, she is on a screen
     * that will never have anything on it. PRD §4.1 forbids answering both with the same
     * blank panel.
     */
    public static final String QUEUE_NOT_COORDINATOR_TITLE = "You do not coordinate a subject";

    /** Its hint, naming who to ask. */
    public static final String QUEUE_NOT_COORDINATOR_HINT =
            "Only a subject coordinator approves exams. If that should be you, "
                    + "ask the principal to set it up.";

    /** Shown when the queue could not be loaded; deliberately says nothing about why. */
    public static final String QUEUE_LOAD_FAILED =
            "The approvals list could not be loaded. Please try again.";

    // ===================== Preview screen ================================

    /** Shown when the preview could not be loaded. */
    public static final String PREVIEW_LOAD_FAILED =
            "This exam could not be opened. Go back to the approvals list and try again.";

    /** Heading of the staff-only side panel. */
    public static final String TEACHER_PANEL_TITLE = "Teacher only";

    /** Said above the notes block when the author wrote none. */
    public static final String NO_TEACHER_NOTES =
            "The teacher left no notes on this exam.";

    /** Heading of the answer-key block inside the side panel. */
    public static final String ANSWER_KEY_TITLE = "Answer key";

    /** The one line that explains what the left-hand pane is. */
    public static final String PREVIEW_BANNER =
            "This is the exam exactly as a student will see it. "
                    + "The notes and the answer key are on the right, and no student ever sees them.";

    /** Said above the paper when the exam has no questions at all. */
    public static final String NO_QUESTIONS =
            "This version has no questions yet, so there is nothing to approve.";

    // ===================== Decisions =====================================

    /** Confirm-dialog title for an approval. */
    public static final String APPROVE_TITLE = "Approve this exam?";

    /** Confirm button on that dialog: a verb, never "OK". */
    public static final String APPROVE_CONFIRM = "Approve";

    /** Reject dialog title. */
    public static final String REJECT_TITLE = "Send this exam back?";

    /** Reject dialog explanation. */
    public static final String REJECT_EXPLANATION =
            "The teacher gets your reason and can fix the exam and submit it again.";

    /** Label of the required reason field. */
    public static final String REJECT_REASON_LABEL = "Why are you sending it back";

    /** Prompt inside the reason field: an example, so the bar is obvious. */
    public static final String REJECT_REASON_PROMPT =
            "Question 4 has two correct answers, and question 7 is out of syllabus.";

    /** Confirm button on the reject dialog. */
    public static final String REJECT_CONFIRM = "Send back";

    /** Cancel button on both dialogs. */
    public static final String KEEP_LOOKING = "Keep looking";

    /**
     * The live hint under the reason field, as it is being typed.
     *
     * <p>Phrased as what is still needed rather than as a character count, because "8 more
     * characters" is an instruction and "12/10" is a puzzle. Once the bar is met it stops
     * counting and says the thing that actually matters about the box: somebody is going to
     * read this.
     *
     * @param typed what is in the field so far
     * @return the hint to show under it
     */
    public static String reasonHint(String typed) {
        int missing = ExamRejectRequest.charactersStillNeeded(typed);
        if (missing == 0) {
            return "The teacher will see this reason.";
        }
        return missing + (missing == 1 ? " more character" : " more characters") + " needed.";
    }

    // ===================== Teacher side (E8.6) ===========================
    //
    // Retired with MY_APPROVALS_GET (APPROVAL ruling 1, 2026-08-25). The six sentences that
    // stood here belonged to MyApprovalsView, which E7.10's exam list replaced; they live in
    // client.features.exambuild.ExamListCopy now and are measured by ExamListCopyTest. Copy
    // for a screen that no longer exists is copy no reader can ever be shown.

    // ===================== Derived text ==================================

    /** How a submitted-at instant is rendered in a list, in the reader's own zone. */
    private static final DateTimeFormatter SUBMITTED =
            DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH);

    /**
     * @param when a UTC instant from the wire
     * @return it rendered in the reader's local zone, because a coordinator triaging a queue
     *         thinks in her own morning and not in UTC
     */
    public static String submittedAt(Instant when) {
        return when == null ? "" : SUBMITTED.format(when.atZone(ZoneId.systemDefault()));
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
     * The one line a queue row shows under the exam's name.
     *
     * @param row a loaded row
     * @return course, author, length and submission time in one scannable string
     */
    public static String queueSummary(ApprovalRow row) {
        return row.courseLabel() + " · " + row.authorName()
                + " · " + questions(row.questionCount())
                + " · " + minutes(row.durationMinutes());
    }

    /**
     * The badge a coordinator sees on an exam she wrote herself (F4.3).
     *
     * <p>Information, not a warning. The rule permits her to approve it; what she is owed is
     * to know that the system noticed, which is also what the server's log line records.
     */
    public static final String SELF_AUTHORED_BADGE = "You wrote this one";

    /** The line the approve dialog adds when F4.3 applies. */
    public static final String SELF_APPROVAL_NOTE =
            "You wrote this exam. Approving your own exam is allowed, and it is recorded in "
                    + "the approval log.";

    /**
     * @param state the version's state
     * @return the chip label, taken from the wire enum so the two cannot disagree
     */
    public static String stateLabel(ApprovalState state) {
        return state == null ? "" : state.label();
    }
}
