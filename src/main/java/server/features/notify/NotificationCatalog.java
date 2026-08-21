package server.features.notify;

import common.dto.notify.NavRef;
import common.dto.notify.NotificationType;

import java.util.Objects;

/**
 * Every sentence a notification is allowed to say (Logic tier, E17.2 — F11.1).
 *
 * <p>One factory per PRD emit point, so the wording is written once instead of
 * once per feature. That is worth a class of its own for three reasons:
 *
 * <ul>
 *   <li><b>the copy rules are enforceable.</b> PRD §4.1 forbids em dashes in
 *       user-visible text and asks for short plain sentences; one test over this
 *       class checks every string in the product, which is impossible when the
 *       text is inlined in eight different services;</li>
 *   <li><b>the destination is part of the message.</b> A notification that cannot
 *       be clicked is half a feature, so each factory also fixes the
 *       {@link NavRef} — the emitting feature cannot forget it;</li>
 *   <li><b>the emitting features are not written yet.</b> E7, E8, E12 and E16
 *       find their sentence already here, review it against their screen, and
 *       spend their effort on deciding <i>who</i> to send it to.</li>
 * </ul>
 *
 * <p>The route ids below are the client's, from {@code client.core.Routes}. The
 * server does not import client code; an id the client does not know simply
 * renders as a non-clickable row (see {@link NavRef}), so a route arriving one
 * epic before its screen is safe.
 */
public final class NotificationCatalog {

    // --- Route ids the client publishes in client.core.Routes. -----------
    // Kept as literals rather than a shared enum precisely because the two tiers
    // ship as separate JARs: a stale client must degrade to "not clickable",
    // never fail to deserialize.

    /** Coordinator's approval queue (E8). */
    public static final String ROUTE_APPROVALS = "approvals";

    /** Exam list / exam detail (E7). */
    public static final String ROUTE_EXAMS = "exams";

    /** A student's own grades (E13). */
    public static final String ROUTE_GRADES = "grades";

    /** The live execution monitor (E11). */
    public static final String ROUTE_MONITOR = "monitor";

    /** A student's take-exam screen (E10). */
    public static final String ROUTE_ATTEMPT = "attempt";

    /** Teacher's bot manager (E16). */
    public static final String ROUTE_BOT_MANAGER = "bot.manager";

    /** Release manager (E9). */
    public static final String ROUTE_RELEASE = "release";

    private NotificationCatalog() {
    }

    /**
     * A composed notification, ready to hand to {@link Notifier}.
     *
     * @param type  what happened
     * @param title one short line
     * @param body  the detail line
     * @param ref   where clicking it goes
     */
    public record Draft(NotificationType type, String title, String body, NavRef ref) {

        public Draft {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(title, "title");
            body = body == null ? "" : body;
            ref = ref == null ? NavRef.none() : ref;
        }
    }

    // ===================== Approval workflow (E8) ========================

    /**
     * A teacher submitted an exam version for approval.
     *
     * @param examName       the exam's name, as the coordinator knows it
     * @param authorName     who submitted it
     * @param examVersionId  the version to open on click
     */
    public static Draft approvalRequested(String examName, String authorName, long examVersionId) {
        return new Draft(NotificationType.APPROVAL_REQUESTED,
                "Exam waiting for your approval",
                authorName + " submitted " + examName + " for approval.",
                NavRef.to(ROUTE_APPROVALS, examVersionId));
    }

    /** A coordinator approved an exam version. */
    public static Draft approvalApproved(String examName, String approverName, long examVersionId) {
        return new Draft(NotificationType.APPROVAL_APPROVED,
                "Exam approved",
                approverName + " approved " + examName + ". You can release it now.",
                NavRef.to(ROUTE_EXAMS, examVersionId));
    }

    /**
     * A coordinator rejected an exam version. The reason is mandatory in the
     * approval screen (F4), so it is mandatory in the sentence: a rejection the
     * author cannot act on is the one message this feature must never send.
     */
    public static Draft approvalRejected(String examName, String approverName,
                                         String reason, long examVersionId) {
        return new Draft(NotificationType.APPROVAL_REJECTED,
                "Exam sent back for changes",
                approverName + " did not approve " + examName + ". Reason: " + sentence(reason),
                NavRef.to(ROUTE_EXAMS, examVersionId));
    }

    /**
     * A newer version replaced one that was still in the queue (E8.2).
     *
     * <p>Goes to the <b>coordinator</b>, not to the author: the author is the
     * person who just resubmitted, and telling somebody what they have this
     * second done is the noise that makes people stop reading their bell. The
     * coordinator is the one whose queue changed underneath her, possibly
     * mid-read, and a row that vanishes without a word is a mystery state.
     *
     * <p>The reference points at the <em>new</em> version, because the old one is
     * no longer actionable and the whole point of the message is that there is
     * something newer to look at.
     *
     * @param examName         the exam's name
     * @param authorName       who resubmitted it
     * @param newExamVersionId the version that replaced the old one, to open on click
     */
    public static Draft approvalSuperseded(String examName, String authorName,
                                           long newExamVersionId) {
        return new Draft(NotificationType.APPROVAL_SUPERSEDED,
                "A newer version replaced one in your queue",
                authorName + " submitted a newer version of " + examName
                        + ". The earlier one was sent back automatically.",
                NavRef.to(ROUTE_APPROVALS, newExamVersionId));
    }

    /**
     * Finishes a sentence made of somebody's free text.
     *
     * <p>The rejection reason is typed by a coordinator in a text area, and most
     * people do not end a form field with a full stop. Appending one keeps the
     * notification body a sentence; checking first keeps it from becoming two.
     */
    private static String sentence(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return "No reason was given.";
        }
        return ".!?".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0 ? trimmed : trimmed + ".";
    }

    // ===================== Grading (E12) ================================

    /** A grade was approved and published to the student. */
    public static Draft gradePublished(String examName, long attemptId) {
        return new Draft(NotificationType.GRADE_PUBLISHED,
                "Your grade is ready",
                "Your grade for " + examName + " has been published.",
                NavRef.to(ROUTE_GRADES, attemptId));
    }

    // ===================== Execution & timing (E11) =====================

    /** A teacher granted extra time on a live execution. */
    public static Draft timeExtended(String examName, int extraMinutes, long executionId) {
        return new Draft(NotificationType.TIME_EXTENDED,
                "Extra time added",
                "You have " + extraMinutes + " more " + minutes(extraMinutes) + " for " + examName + ".",
                NavRef.to(ROUTE_ATTEMPT, executionId));
    }

    /** A scheduled execution opens shortly. */
    public static Draft releaseOpeningSoon(String examName, int minutesAway, long executionId) {
        return new Draft(NotificationType.RELEASE_OPENING_SOON,
                "Exam opens soon",
                examName + " opens in " + minutesAway + " " + minutes(minutesAway) + ".",
                NavRef.to(ROUTE_RELEASE, executionId));
    }

    // ===================== Study bot (E16) ==============================

    /** A course bot's information sources changed (→ the other teachers of that course). */
    public static Draft botSourceChanged(String courseName, String editorName, long botId) {
        return new Draft(NotificationType.BOT_SOURCE_CHANGED,
                "Study bot sources changed",
                editorName + " changed the study bot sources for " + courseName + ".",
                NavRef.to(ROUTE_BOT_MANAGER, botId));
    }

    /**
     * A student used another course's bot during an attempt (C-4).
     *
     * <p>Worded as something to look at, not as an accusation: the server cannot
     * know intent, and the teacher is the one who decides what it means.
     */
    public static Draft integrityAlert(String courseName, long executionId) {
        return new Draft(NotificationType.INTEGRITY_ALERT,
                "Check an attempt in " + courseName,
                "A student opened another course's study bot during a live attempt. "
                        + "Open the monitor to see which one.",
                NavRef.to(ROUTE_MONITOR, executionId));
    }

    /** Keeps "1 minute" from reading as "1 minutes" in two different sentences. */
    private static String minutes(int count) {
        return count == 1 ? "minute" : "minutes";
    }
}
