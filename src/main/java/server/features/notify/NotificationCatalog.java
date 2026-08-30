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

    /** Teacher's grading queue (E12). Added with {@link #gradingDue} under B-11. */
    public static final String ROUTE_GRADING = "grading";

    /**
     * Teacher and principal results (E14), where a finished sitting is read.
     *
     * <p>Added with {@link #executionClosed} under B-11. Matches {@code Routes.RESULTS.id()}; the
     * literal is repeated rather than imported for the reason stated at the top of this block -
     * these ids cross the wire and the two tiers ship as separate JARs.
     */
    public static final String ROUTE_RESULTS = "results";

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

    /**
     * A closed sitting's papers are waiting to be graded and approved (→ its teacher) ⚑.
     *
     * <p><b>Added 2026-08-26 under B-11, and it exists because the seed was already sending it.</b>
     * Seed §11 holds {@code N-GRADING-DUE-JAVA} — "8 attempts awaiting your grade approval" to
     * {@code avi.mizrahi} — and until this batch there was neither a {@link NotificationType}
     * constant nor a sentence for it, so the seed wrote a string the read path could not parse and
     * every staff bell answered {@code INTERNAL}. The copy below is the seed document's own,
     * parameterised: it is content the owner already wrote and reviewed, not new product text
     * invented here.
     *
     * <p><b>No server code raises it yet</b> — E12's grading queue is where it belongs — which is
     * the same state several drafts above are in, and is why
     * {@code NotificationCatalogTest.everyTypeHasASentence} is the invariant that matters: a type
     * with no sentence is a type nothing can send, and a seed row that bypasses the catalog to
     * write one anyway is exactly how B-11 happened.
     *
     * @param examName    the sitting's exam, as its teacher knows it
     * @param waiting     how many attempts are awaiting approval
     * @param executionId the sitting to open on click
     */
    public static Draft gradingDue(String examName, int waiting, long executionId) {
        return new Draft(NotificationType.GRADING_DUE,
                "Grading waiting for you",
                waiting + " " + attempts(waiting) + " for " + examName
                        + " are awaiting your grade approval.",
                // ROUTE_GRADING, not ROUTE_GRADES: this is the teacher's queue, and ROUTE_GRADES
                // is a student's own My Grades screen, which reads an attempt id.
                NavRef.to(ROUTE_GRADING, executionId));
    }

    /** Keeps "1 attempt" from reading as "1 attempts". */
    private static String attempts(int count) {
        return count == 1 ? "attempt" : "attempts";
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

    /**
     * A sitting finished and its results are available (→ staff who watch it rather than sit it) ⚑.
     *
     * <p>The other half of B-11's vocabulary gap. Seed §11's {@code N-EXEC-CLOSED-ALG} is the one
     * notification {@code principal.avia} has, and S-7 makes her read-only, so it is the only thing
     * that can ever populate her panel (NFR-21). The mean travels in the sentence because that is
     * what the seed's own title does — and it is derived data in a text column, so anything that
     * changes the seeded grades changes this string too.
     *
     * @param examName    the exam that was sat
     * @param sitters     how many students sat it
     * @param mean        the class mean, already rounded by the caller
     * @param executionId the sitting to open on click
     */
    public static Draft executionClosed(String examName, int sitters, double mean,
                                        long executionId) {
        return new Draft(NotificationType.EXECUTION_CLOSED,
                "Sitting finished",
                examName + " is over: " + sitters + " " + students(sitters)
                        + ", average " + mean + ".",
                NavRef.to(ROUTE_RESULTS, executionId));
    }

    /** Keeps "1 student" from reading as "1 students". */
    private static String students(int count) {
        return count == 1 ? "student" : "students";
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
     * A course's study bot was deleted (→ the other teachers of that course) ⚑ (U-39).
     *
     * <p><b>{@link NotificationType#BOT_SOURCE_CHANGED} on purpose, and no new constant.</b>
     * The type is what the panel switches on for an icon and what a future aggregate would
     * group by; a co-teacher's reaction to "the material changed" and to "the bot is gone" is
     * the same one, which is to open the manager and look. The sentence is where the two
     * differ, and the sentence is stored on the row. Adding a constant for one event that
     * nothing would route differently is how a small stable vocabulary stops being either.
     *
     * <p>The reference carries the id of the bot that is gone, exactly as
     * {@link #botSourceChanged} carries the id of one that is not. {@link NavRef} is documented
     * as holding no foreign key for this reason: notifications outlive what they point at, and
     * this route ignores the id anyway ({@code NotificationPresenter} deliberately leaves
     * {@code bot.manager} out of its parameter table, since the manager wants a course code).
     * The teacher lands on her list of bots, which since U-26 is where "it is not there any
     * more" is a thing she can see rather than a screen that says nothing.
     *
     * @param courseName the course whose bot went
     * @param editorName the teacher who deleted it
     * @param botId      the bot that was deleted
     */
    public static Draft botDeleted(String courseName, String editorName, long botId) {
        return new Draft(NotificationType.BOT_SOURCE_CHANGED,
                "Study bot deleted",
                editorName + " deleted the study bot for " + courseName + ".",
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
