package common.dto.release;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A teacher's releases, and the moment the server drew them (Common tier, E9 — F5.4).
 *
 * <p>{@link #serverNow} travels because the screen counts down to openings and closings, and
 * a countdown anchored to the client's own clock is a countdown that is wrong on the one
 * machine whose time is wrong. It is the same rule the take-exam timer obeys (ADR-010): the
 * server owns time, the client renders it.
 *
 * @param serverNow when the server built this list
 * @param rows      her releases, soonest opening first; possibly empty
 */
public record ReleaseList(Instant serverNow, List<ReleaseRow> rows) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ReleaseList {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /** @return an empty list drawn at {@code now}, for a teacher who has released nothing. */
    public static ReleaseList empty(Instant now) {
        return new ReleaseList(now, List.of());
    }

    /** @return {@code true} when she has released nothing at all. */
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** @return how many of these are running right now, for the header count. */
    public long liveCount() {
        return rows.stream().filter(ReleaseRow::isLive).count();
    }

    /**
     * This list with one row replaced, or added when it is new (the push path).
     *
     * <p>Here rather than in the client session because it is the shape the amendment
     * promises: {@code PUSH_EXECUTION_STATUS} carries one whole row, and a client adopts it
     * by identity. A row for a release the list has never seen is an <em>insert</em>, not a
     * mistake: a release created on this teacher's other machine has to appear.
     *
     * @param fresh the pushed row
     * @return a new list carrying it, ordered as this one was
     */
    public ReleaseList with(ReleaseRow fresh) {
        if (fresh == null) {
            return this;
        }
        List<ReleaseRow> merged = new ArrayList<>(rows.size() + 1);
        boolean replaced = false;
        for (ReleaseRow row : rows) {
            if (row.executionId() == fresh.executionId()) {
                merged.add(fresh);
                replaced = true;
            } else {
                merged.add(row);
            }
        }
        if (!replaced) {
            merged.add(0, fresh);
        }
        return new ReleaseList(serverNow, merged);
    }
}
