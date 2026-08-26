package common.dto.notify;

/**
 * What a notification is about (Common tier, E17.1 — F11.1).
 *
 * <p>One constant per emit point the PRD names. The type is <b>not</b> the text:
 * the sentence a user reads is composed per event and stored on the row, because
 * it names the exam, the teacher or the number of minutes. The type is what the
 * client switches on for the panel icon and what analytics would group by, so it
 * has to stay a small, stable vocabulary rather than a free string.
 *
 * <p>The features that raise most of these are not merged yet (E7 approvals, E8
 * publishing, E12 grading, E16 bot). They reach notifications through
 * {@code server.features.notify.Notifier} plus the ready-made sentences in
 * {@code NotificationCatalog} — the type constant alone is never enough to send
 * one, by design.
 *
 * <p>Stored as its {@link #name()} in the {@code notifications.type} column
 * (VARCHAR), so constants may be added freely and must never be renamed.
 */
public enum NotificationType {

    /** A teacher submitted an exam version for approval (→ the subject coordinator). */
    APPROVAL_REQUESTED,

    /** A coordinator approved an exam version (→ its author). */
    APPROVAL_APPROVED,

    /** A coordinator rejected an exam version, with a reason (→ its author). */
    APPROVAL_REJECTED,

    /**
     * A newer version arrived and replaced one that was still waiting
     * (→ the subject coordinator, E8.2).
     *
     * <p>Its own constant rather than a second {@code APPROVAL_REQUESTED},
     * because the coordinator's reaction differs: a request is work arriving,
     * this is work she may have half-read disappearing from her queue. A queue
     * row that vanishes with no explanation is exactly the mystery state PRD
     * §4.1 forbids.
     */
    APPROVAL_SUPERSEDED,

    /** A grade was approved and published (→ the student). */
    GRADE_PUBLISHED,

    /** A teacher granted extra time on a live execution (→ the students in it). */
    TIME_EXTENDED,

    /** A course bot's information sources changed (→ the other teachers of that course). */
    BOT_SOURCE_CHANGED,

    /** A scheduled execution opens shortly (→ the teacher who released it). */
    RELEASE_OPENING_SOON,

    /**
     * A student used another course's bot during an attempt (→ the teacher
     * running that execution, C-4). Not an accusation, a flag to look at.
     */
    INTEGRITY_ALERT,

    /**
     * A closed execution's papers are waiting to be graded and approved
     * (→ the teacher who released it).
     *
     * <p><b>Added 2026-08-26 under B-11, and the reason is worth recording.</b> Seed §11 has
     * always held a notification of this kind — {@code N-GRADING-DUE-JAVA}, "8 attempts awaiting
     * your grade approval" to {@code avi.mizrahi} — and this enum had no constant for it, so the
     * seed stored the string {@code GRADING_DUE} and {@code NotificationType.valueOf} threw on
     * every read. The gap was in the vocabulary, not in the fixture: this is an emit point the
     * PRD names, and the class contract above says constants may be added freely. Re-pointing the
     * row at {@link #GRADE_PUBLISHED} instead would have traded a crash for a lie, since that one
     * means "your grade is out" and is addressed to a student.
     *
     * <p>No server code raises it yet — E12's grading queue is where it belongs — which puts it in
     * the same state as several constants above, whose features the class javadoc already notes
     * are not merged.
     */
    GRADING_DUE,

    /**
     * A sitting finished and its results are available (→ staff who watch it rather than sit it).
     *
     * <p>The other half of B-11's vocabulary gap: seed §11's {@code N-EXEC-CLOSED-ALG} is the one
     * notification the principal has, and S-7 makes her read-only, so it is the only thing that
     * can ever populate her panel. Nothing else in this enum describes an execution ending.
     */
    EXECUTION_CLOSED
}
