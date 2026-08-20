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
    INTEGRITY_ALERT
}
