package common.dto.lock;

import java.io.Serializable;
import java.util.Objects;

/**
 * The {@code PUSH_LOCK_CHANGED} payload (Common tier, E18.2).
 *
 * <p>Sent to everyone watching an entity whenever its lock changes hands. This
 * is what makes the read-only banner live: the second teacher's screen does not
 * poll and has no refresh button (NFR-18), it simply hears that the lock was
 * released and offers to take over.
 *
 * <p>Renewals raise nothing. A heartbeat every twelve seconds per open editor
 * would be a push storm carrying no news, and the only thing a watcher learns
 * from it — "still held" — it already assumes.
 *
 * @param entity what changed
 * @param kind   what happened to it
 * @param holder who holds it now, or {@code null} for {@link Kind#RELEASED} and
 *               {@link Kind#EXPIRED}
 */
public record LockChange(EntityRef entity, Kind kind, LockHolder holder) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** What happened to the lock. */
    public enum Kind {
        /** Someone took it, including a takeover of an expired hold. */
        ACQUIRED,
        /** The holder gave it back (closed the editor, navigated away, logged out). */
        RELEASED,
        /** The holder stopped renewing and the TTL ran out (a crashed or disconnected client). */
        EXPIRED
    }

    public LockChange {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(kind, "kind");
    }

    /** @return the push announcing that {@code holder} now owns {@code entity}. */
    public static LockChange acquired(EntityRef entity, LockHolder holder) {
        return new LockChange(entity, Kind.ACQUIRED, Objects.requireNonNull(holder, "holder"));
    }

    /** @return the push announcing that {@code entity} was given back. */
    public static LockChange released(EntityRef entity) {
        return new LockChange(entity, Kind.RELEASED, null);
    }

    /** @return the push announcing that {@code entity}'s lock lapsed. */
    public static LockChange expired(EntityRef entity) {
        return new LockChange(entity, Kind.EXPIRED, null);
    }

    /** @return {@code true} when nobody holds the entity after this change. */
    public boolean isFree() {
        return holder == null;
    }
}
