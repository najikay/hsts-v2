package common.dto.approval;

/**
 * The approval state of one exam version, as the wire carries it (Common tier, E8 — F4).
 *
 * <p>A deliberate copy of {@code server.db.entities.ExamVersionStatus} rather than the
 * entity enum itself. The two tiers ship as separate JARs and the client must not depend on
 * a persistence type: an enum travels by name, so the copy costs one mapping method in the
 * service and buys the client a wire vocabulary it can render without ever seeing Hibernate.
 * {@code ApprovalServiceTest} pins the two name sets together, so a value added on one side
 * and forgotten on the other fails the build rather than a screen.
 */
public enum ApprovalState {

    /** Being written; the author may still change composition and metadata. */
    DRAFT,

    /** Submitted, waiting on the subject's coordinator (S-14). */
    PENDING,

    /** Approved, and only now may an execution be scheduled from it (E9.1). */
    APPROVED,

    /** Sent back with a required reason, which travels to the author (F4.2). */
    REJECTED;

    /** @return the chip label a screen shows for this state (F3.6). */
    public String label() {
        return switch (this) {
            case DRAFT -> "Draft";
            case PENDING -> "Waiting for approval";
            case APPROVED -> "Approved";
            case REJECTED -> "Sent back";
        };
    }

    /** @return {@code true} when this version is waiting on a coordinator's decision. */
    public boolean isPending() {
        return this == PENDING;
    }

    /** @return {@code true} when this version carries a rejection reason worth showing. */
    public boolean isRejected() {
        return this == REJECTED;
    }
}
