package server.db.entities;

/**
 * Lifecycle of one exam execution — {@code exam_executions.status} (V4, §5, F5).
 *
 * <p>An Exam is a definition in the drawer; an Execution is one occasion of taking it
 * out (C-6). One approved exam version may have many executions (S-2), each with its
 * own window, code and statistics.
 */
public enum ExecutionStatus {

    /** Created, window not yet open. The only state from which cancelling is legal. */
    SCHEDULED,

    /** Open: students may enter with the code, and the server clock is authoritative. */
    LIVE,

    /** Window over. Participation counts are frozen into the stats JSON (S-21). */
    CLOSED,

    /**
     * Called off before it ever opened (F5.5).
     *
     * <p>Added in the E2 PR 1 review, because cancelling had nowhere to go: a row
     * delete is refused once anyone has sat it, and {@code CLOSED} would pollute the
     * F9.4 report corpus with a zero-participant execution. Cancelled executions are
     * excluded from statistics and reports — a service rule enforced in E9.
     */
    CANCELLED
}
