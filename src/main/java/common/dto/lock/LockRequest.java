package common.dto.lock;

import java.io.Serializable;
import java.util.Objects;

/**
 * The payload of {@code LOCK_ACQUIRE}, {@code LOCK_RENEW} and
 * {@code LOCK_RELEASE} (Common tier, E18.2).
 *
 * <p>One field, and deliberately so: <b>who</b> is asking is never on the wire.
 * The server reads the caller from the socket-bound session, so a client that
 * put someone else's user id here would be telling the server something it does
 * not read (ARCHITECTURE §3, security).
 *
 * @param entity the thing to lock, renew or release
 */
public record LockRequest(EntityRef entity) implements Serializable {

    private static final long serialVersionUID = 1L;

    public LockRequest {
        Objects.requireNonNull(entity, "entity");
    }

    /** @return a request for one entity. */
    public static LockRequest of(String entityType, long entityId) {
        return new LockRequest(new EntityRef(entityType, entityId));
    }
}
