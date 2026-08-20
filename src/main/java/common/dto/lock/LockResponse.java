package common.dto.lock;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * The answer to every lock verb (Common tier, E18.2).
 *
 * <p>A refusal is <b>not</b> an error. "Someone else is editing this" is an
 * ordinary, expected answer that the screen turns into a read-only mode with a
 * banner, so it comes back as {@code OK} carrying {@code granted = false} and
 * the holder's name. Reserving {@code ErrorCode} for real failures keeps the
 * client's error handling honest: an {@code ERROR} on a lock verb means
 * something actually went wrong.
 *
 * <p>{@code holder} is populated either way — on a grant it is the caller, on a
 * refusal the person in the way — so the client always knows who to name.
 *
 * @param granted   {@code true} when the caller now owns the lock
 * @param entity    the entity this answer is about
 * @param holder    who holds it now; {@code null} only after a release left it free
 * @param expiresAt when the current hold lapses without a renewal, UTC;
 *                  {@code null} when nobody holds it
 */
public record LockResponse(boolean granted,
                           EntityRef entity,
                           LockHolder holder,
                           Instant expiresAt) implements Serializable {

    private static final long serialVersionUID = 1L;

    public LockResponse {
        Objects.requireNonNull(entity, "entity");
    }

    /** @return a grant: the caller owns {@code entity} until {@code expiresAt}. */
    public static LockResponse granted(EntityRef entity, LockHolder holder, Instant expiresAt) {
        return new LockResponse(true, entity, Objects.requireNonNull(holder, "holder"), expiresAt);
    }

    /** @return a refusal naming the person already editing. */
    public static LockResponse refused(EntityRef entity, LockHolder holder, Instant expiresAt) {
        return new LockResponse(false, entity, Objects.requireNonNull(holder, "holder"), expiresAt);
    }

    /** @return the answer to a release: nobody holds {@code entity} any more. */
    public static LockResponse free(EntityRef entity) {
        return new LockResponse(false, entity, null, null);
    }

    /** @return {@code true} when nobody holds this entity. */
    public boolean isFree() {
        return holder == null;
    }
}
