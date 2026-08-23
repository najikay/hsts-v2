package common.dto.release;

/**
 * What a release is doing right now, on the wire (Common tier, E9 — F5.4).
 *
 * <p>The wire twin of {@code server.db.entities.ExecutionStatus}, and a separate type for
 * the reason every wire enum in this product is separate from its stored one: the client
 * jar does not see server classes, and a stored enum that changed for a database reason
 * would otherwise change the protocol.
 *
 * <p>The three predicates below are the whole of F5.5's rule set, written once so the
 * server's guard and the client's button state cannot disagree about what may be done to a
 * release. A screen that decided "the cancel button is enabled" from its own {@code switch}
 * would be a second copy of a rule the server also enforces, and the second copy is the one
 * that drifts.
 */
public enum ReleaseState {

    /** Created, its window has not opened. The only state a cancel is legal from (F5.5). */
    SCHEDULED,

    /** Open: students may enter with the code. Closing early is legal from here, only. */
    LIVE,

    /** Over, whether by the clock or by the teacher. Nothing may be done to it. */
    CLOSED,

    /** Called off before it ever opened (F5.5). Excluded from the report corpus (PRD §6). */
    CANCELLED;

    /** @return {@code true} while students may be sitting it. */
    public boolean isLive() {
        return this == LIVE;
    }

    /**
     * @return {@code true} when this release may still be called off (F5.5). A live one may
     *         not: ending a sitting people are in the middle of is closing early, which is a
     *         different action with a different warning
     */
    public boolean canCancel() {
        return this == SCHEDULED;
    }

    /**
     * @return {@code true} when this release may be closed early (F5.5). Only a live one:
     *         there is nobody to hand in on a release that never opened
     */
    public boolean canCloseEarly() {
        return this == LIVE;
    }

    /** @return {@code true} when nothing further will happen to this release. */
    public boolean isOver() {
        return this == CLOSED || this == CANCELLED;
    }
}
