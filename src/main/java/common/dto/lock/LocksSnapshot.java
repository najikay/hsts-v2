package common.dto.lock;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The answer to {@code LOCKS_SNAPSHOT}: who is editing what, right now
 * (Common tier, E18.8).
 *
 * <p>Sparse on purpose. Only entities somebody is <em>currently</em> holding
 * appear; an id that is free, expired or does not exist is simply absent. A list
 * screen therefore reads "is there an entry for this row?" and gets the chip
 * decision from that, with no null-versus-missing distinction to get wrong.
 *
 * <p>The value is a whole {@link LockHolder} rather than the display name alone,
 * and the extra field earns its place: the chip on the holder's own row must say
 * something different from the chip on a colleague's, and a viewer decides "is
 * this me?" by comparing the user id, never by matching names (two teachers
 * called Dana Cohen are a real possibility and a name comparison would merge
 * them). It is also the same value type {@link LockChange} and
 * {@link LockResponse} already carry, so the list screen and the editor read one
 * shape rather than two.
 *
 * <p>A snapshot is a photograph, not a subscription. It is true at the instant it
 * was taken and goes stale immediately: {@code PUSH_LOCK_CHANGED} is what keeps a
 * screen live, and a client that wants both sends {@code LOCK_WATCH} per row.
 *
 * @param entityType the type every key belongs to, matching the request
 * @param holders    entity id → who holds it; only live holds, never empty values
 */
public record LocksSnapshot(String entityType, Map<Long, LockHolder> holders) implements Serializable {

    private static final long serialVersionUID = 1L;

    public LocksSnapshot {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(holders, "holders");
        holders = Collections.unmodifiableMap(new LinkedHashMap<>(holders));
    }

    /** @return a snapshot in which nothing is held. */
    public static LocksSnapshot empty(String entityType) {
        return new LocksSnapshot(entityType, Map.of());
    }

    /** @return who holds {@code entityId}, empty when nobody does. */
    public Optional<LockHolder> holderOf(long entityId) {
        return Optional.ofNullable(holders.get(entityId));
    }

    /** @return {@code true} when somebody is editing {@code entityId}. */
    public boolean isHeld(long entityId) {
        return holders.containsKey(entityId);
    }

    /** @return how many of the requested entities are being edited. */
    public int heldCount() {
        return holders.size();
    }
}
