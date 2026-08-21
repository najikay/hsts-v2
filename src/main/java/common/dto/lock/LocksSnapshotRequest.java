package common.dto.lock;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The payload of {@code LOCKS_SNAPSHOT} (Common tier, E18.8).
 *
 * <p>One entity type plus the ids currently on screen. A list screen asks about
 * the page it is painting, not about the table: forty rows means forty ids, and
 * a bank of five thousand questions never produces a five-thousand-id request
 * because nobody is looking at five thousand rows.
 *
 * <p><b>Why a type and a list rather than a list of {@link EntityRef}.</b> Every
 * row on one list screen is the same kind of thing, so repeating the type per id
 * would put a field on the wire whose only possible values are all equal, and
 * would admit a request mixing questions and grades that no screen wants and the
 * answer could not be keyed by id. The shape is the constraint.
 *
 * <p>Ids are de-duplicated and the type is normalised exactly as
 * {@link EntityRef} normalises it, so {@code "Question"} and {@code "question"}
 * cannot describe two different snapshots of the same rows.
 *
 * <p>Identity is absent by design: the caller is read from the socket-bound
 * session, never from here (ARCHITECTURE §3).
 *
 * @param entityType what kind of thing, normalised to lower case; see the
 *                   constants on {@link EntityRef}
 * @param entityIds  the ids to report on, de-duplicated, order preserved
 */
public record LocksSnapshotRequest(String entityType, List<Long> entityIds) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * How many ids one request may carry.
     *
     * <p>A bound rather than none, because this is an authenticated verb a
     * hostile client can still call in a loop: without a cap, one request could
     * ask the server to walk an arbitrarily long list and build an arbitrarily
     * large answer. Five hundred is far more rows than any screen in this product
     * paints at once, so the limit is invisible to the real callers and present
     * for the other kind.
     */
    public static final int MAX_IDS = 500;

    /** The refusal message when a request exceeds {@link #MAX_IDS}. */
    public static final String TOO_MANY_IDS =
            "That list is too long to check in one request. Show fewer rows at a time.";

    public LocksSnapshotRequest {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityIds, "entityIds");
        entityType = entityType.trim().toLowerCase(Locale.ROOT);
        if (entityType.isEmpty()) {
            throw new IllegalArgumentException("A locks snapshot needs an entity type");
        }
        entityIds = List.copyOf(new LinkedHashSet<>(entityIds));
    }

    /** @return a request for one entity type and the given ids. */
    public static LocksSnapshotRequest of(String entityType, List<Long> entityIds) {
        return new LocksSnapshotRequest(entityType, entityIds);
    }

    /** @return {@code true} when this request asks about more than {@link #MAX_IDS} rows. */
    public boolean isOversized() {
        return entityIds.size() > MAX_IDS;
    }

    /** @return the ids as entity references, the form the lock service is keyed by. */
    public List<EntityRef> refs() {
        return entityIds.stream().map(id -> new EntityRef(entityType, id)).toList();
    }
}
