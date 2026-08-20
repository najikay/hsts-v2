package server.features.locks;

import java.util.Optional;

/**
 * How the lock service turns a user id into the name a banner can show
 * (Logic tier, E18.2).
 *
 * <p>{@link EditLockService} knows user ids and nothing else — that is the point
 * of identity coming from {@code SessionManager}. But "someone else is editing
 * this" is a useless banner: the person waiting needs to know who to go and ask.
 * So the service is handed this one function and stays free of any directory,
 * repository or user model.
 *
 * <p>Today it is backed by {@code InMemoryUserDirectory.findById}; after E2 PR3
 * it is backed by {@code UserRepository}, and this interface is the only line
 * that has to change.
 *
 * <p>Implementations must never throw for an unknown id: a deleted account must
 * degrade to {@link common.dto.lock.LockHolder#UNKNOWN_NAME}, not fail somebody
 * else's lock acquisition.
 */
@FunctionalInterface
public interface DisplayNames {

    /** A lookup that knows nobody; every holder shows as "Another user". */
    DisplayNames NONE = userId -> Optional.empty();

    /**
     * @param userId internal user id
     * @return that user's full name, or empty when it cannot be resolved
     */
    Optional<String> displayName(long userId);
}
