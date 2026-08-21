package common.dto.approval;

import java.io.Serializable;
import java.util.List;

/**
 * The answer to {@code APPROVALS_QUEUE_GET}: what is waiting on this coordinator (E8.1 — F4.1).
 *
 * <p>A record rather than a bare {@code List} for the reason every other page type here is
 * one: a list has nowhere to grow a count, a filter or a "you coordinate nothing" flag
 * without breaking the wire, and the first of those is already needed by the rail badge.
 *
 * <p>{@link #coordinatesAnything} is the difference between two empty states that look the
 * same and mean opposite things. "Nothing is waiting for you" is a finished inbox; "you do
 * not coordinate a subject" is a person on the wrong screen, and PRD §4.1 forbids answering
 * both with the same blank panel. The server knows which it is, so it says.
 *
 * @param rows                 pending versions, oldest first, scoped to the caller's
 *                             coordinated subjects by the query itself
 * @param coordinatesAnything  whether the caller coordinates any subject at all
 */
public record ApprovalQueue(List<ApprovalRow> rows, boolean coordinatesAnything)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    public ApprovalQueue {
        // List.copyOf yields an immutable, Serializable list — safe on the wire.
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /** @return the queue of a coordinator with nothing waiting. */
    public static ApprovalQueue empty() {
        return new ApprovalQueue(List.of(), true);
    }

    /** @return the queue of a caller who coordinates no subject at all. */
    public static ApprovalQueue notACoordinator() {
        return new ApprovalQueue(List.of(), false);
    }

    /** @return how many versions are waiting; the number the rail badge shows. */
    public int size() {
        return rows.size();
    }

    /** @return {@code true} when there is nothing to decide. */
    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
