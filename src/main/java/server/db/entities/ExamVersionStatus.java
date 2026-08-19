package server.db.entities;

/**
 * Approval state of one exam version — {@code exam_versions.status} (V3, §5, F4).
 *
 * <p>This is the only mutable field on an otherwise immutable version row (C-2 /
 * ADR-011), which is why {@code exam_versions} carries {@code lock_version}: two
 * coordinators racing to approve and reject the same submission both land here.
 */
public enum ExamVersionStatus {

    /** Being written; the author may still change composition and metadata. */
    DRAFT,

    /** Submitted for approval, waiting on the subject's coordinator (S-14). */
    PENDING,

    /** Approved — and only now may an execution be scheduled from it (E9.1). */
    APPROVED,

    /** Rejected with a required reason, which travels back to the author (F4). */
    REJECTED
}
