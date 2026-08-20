package client.features.locks;

import common.dto.lock.LockChange;
import common.dto.lock.LockHolder;
import common.dto.lock.LockResponse;

import java.util.Objects;
import java.util.Optional;

/**
 * The editor's lock state machine (Presentation tier, E18.3 — State pattern).
 *
 * <p>An editor that composes {@code LockAwareEditor} is in exactly one of four
 * modes, and which one it moves to depends on both what the server just said and
 * what the screen was doing before. That "and what it was doing before" is the
 * whole reason this is a state machine rather than a pair of booleans: a lock
 * becoming free means "you may start editing" to a reader and "you have lost
 * your session" to the person who held it, and the only difference is the
 * previous mode.
 *
 * <pre>
 *                    acquire granted
 *   CHECKING ──────────────────────────► OWNED
 *      │                                 │  │
 *      │ acquire refused                 │  │ push: someone else acquired
 *      ▼                                 │  ▼
 *   READ_ONLY ◄───────────────────────────  READ_ONLY
 *      │                                 │
 *      │ push: released / expired        │ renew refused or lock expired
 *      ▼                                 ▼
 *   TAKEOVER(AVAILABLE)              TAKEOVER(LOST)
 * </pre>
 *
 * <p>Completely free of FX and of the network: it is fed {@link LockResponse}s
 * and {@link LockChange}s and answers with a {@link Snapshot} the view renders.
 * Every transition above, including the two that only happen under a race, is a
 * unit test.
 */
public final class EditLockState {

    /** What the editor may do right now. */
    public enum Mode {
        /** No entity open. */
        IDLE,
        /** An acquire is in flight; the editor shows itself disabled, not read-only. */
        CHECKING,
        /** This user holds the lock. Normal editing. */
        OWNED,
        /** Someone else holds it. Read-only, banner naming them. */
        READ_ONLY,
        /** Nobody holds it and this user is not editing. The takeover prompt is due. */
        TAKEOVER_OFFERED
    }

    /**
     * The whole of what a view needs to render.
     *
     * @param mode   what the editor may do
     * @param holder who holds the lock, present only in {@link Mode#READ_ONLY}
     * @param reason why a takeover is offered, present only in {@link Mode#TAKEOVER_OFFERED}
     */
    public record Snapshot(Mode mode, LockHolder holder, TakeoverReason reason) {

        /** @return {@code true} when the user may type. */
        public boolean isEditable() {
            return mode == Mode.OWNED;
        }

        /** @return {@code true} when the editor should show a read-only banner. */
        public boolean isReadOnly() {
            return mode == Mode.READ_ONLY;
        }

        /** @return {@code true} when the user should be asked whether to take over. */
        public boolean offersTakeover() {
            return mode == Mode.TAKEOVER_OFFERED;
        }

        /** @return the holder, empty unless read-only. */
        public Optional<LockHolder> holderName() {
            return Optional.ofNullable(holder);
        }

        /**
         * @param entityNoun what is being edited, lower case singular
         * @return the sentence to show, or empty when this mode has nothing to say
         */
        public Optional<String> bannerText(String entityNoun) {
            return switch (mode) {
                case READ_ONLY -> Optional.of(LockCopy.readOnlyBanner(holder.displayName(), entityNoun));
                case TAKEOVER_OFFERED -> Optional.of(LockCopy.takeoverBanner(reason, entityNoun));
                case CHECKING -> Optional.of(LockCopy.CHECKING);
                default -> Optional.empty();
            };
        }
    }

    private static final Snapshot IDLE = new Snapshot(Mode.IDLE, null, null);
    private static final Snapshot CHECKING = new Snapshot(Mode.CHECKING, null, null);

    private final long selfUserId;
    private Snapshot snapshot = IDLE;

    /**
     * @param selfUserId this client's own user id, from {@code LoginResult}. Every
     *                   "is this me?" decision compares ids, never names, so two
     *                   users with the same display name cannot share an editor.
     */
    public EditLockState(long selfUserId) {
        this.selfUserId = selfUserId;
    }

    /** @return the current state. */
    public Snapshot snapshot() {
        return snapshot;
    }

    public Mode mode() {
        return snapshot.mode();
    }

    // ===================== Transitions ===================================

    /**
     * The editor opened and an acquire is on its way.
     *
     * @return the new state
     */
    public Snapshot opening() {
        return set(CHECKING);
    }

    /** The editor closed. */
    public Snapshot closed() {
        return set(IDLE);
    }

    /**
     * Applies the answer to {@code LOCK_ACQUIRE} or {@code LOCK_RENEW}.
     *
     * @param response what the server said
     * @return the new state
     */
    public Snapshot applyResponse(LockResponse response) {
        Objects.requireNonNull(response, "response");
        if (snapshot.mode() == Mode.IDLE) {
            // A late answer to an editor that has already closed. Ignore it rather
            // than resurrecting a banner over whatever screen is showing now.
            return snapshot;
        }
        if (response.granted()) {
            return set(new Snapshot(Mode.OWNED, response.holder(), null));
        }
        if (response.isFree()) {
            // Refused but unheld: the lock lapsed. Whether that is an opportunity or
            // a loss depends entirely on who held it a moment ago.
            return offerTakeover();
        }
        return set(new Snapshot(Mode.READ_ONLY, response.holder(), null));
    }

    /**
     * Applies a {@code PUSH_LOCK_CHANGED} for this entity.
     *
     * @param change what happened to the lock
     * @return the new state
     */
    public Snapshot applyChange(LockChange change) {
        Objects.requireNonNull(change, "change");
        if (snapshot.mode() == Mode.IDLE) {
            return snapshot;
        }
        if (change.kind() == LockChange.Kind.ACQUIRED) {
            LockHolder holder = change.holder();
            return holder.is(selfUserId)
                    ? set(new Snapshot(Mode.OWNED, holder, null))
                    : set(new Snapshot(Mode.READ_ONLY, holder, null));
        }
        // RELEASED or EXPIRED: nobody holds it now.
        return offerTakeover();
    }

    /**
     * The request failed outright (timeout, dropped socket). The screen cannot
     * claim the lock, so it must not pretend to hold it.
     *
     * @return the new state
     */
    public Snapshot applyFailure() {
        if (snapshot.mode() == Mode.IDLE) {
            return snapshot;
        }
        return offerTakeover();
    }

    /** The user declined the takeover; stop asking until something changes again. */
    public Snapshot declineTakeover() {
        return snapshot.mode() == Mode.TAKEOVER_OFFERED
                ? set(new Snapshot(Mode.READ_ONLY, freeHolder(), null))
                : snapshot;
    }

    /**
     * The lock is free. Which sentence the user gets depends on whether they are
     * the one who just lost it.
     */
    private Snapshot offerTakeover() {
        TakeoverReason reason = snapshot.mode() == Mode.OWNED
                ? TakeoverReason.LOST
                : TakeoverReason.AVAILABLE;
        return set(new Snapshot(Mode.TAKEOVER_OFFERED, null, reason));
    }

    /**
     * A declined takeover leaves the screen read-only with nobody to name. Saying
     * "another user" is honest: the screen genuinely does not know who will take
     * it, and it will be corrected by the next push if somebody does.
     */
    private static LockHolder freeHolder() {
        return new LockHolder(0L, LockHolder.UNKNOWN_NAME);
    }

    private Snapshot set(Snapshot next) {
        snapshot = next;
        return next;
    }
}
