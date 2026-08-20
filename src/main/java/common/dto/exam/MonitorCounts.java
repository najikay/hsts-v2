package common.dto.exam;

import java.io.Serializable;

/**
 * The three numbers at the top of the live monitor (Common tier, E11.2 — F7.2, S-21).
 *
 * <p><b>Counted, never accumulated.</b> §5 forbids participation counter columns on
 * {@code exam_executions} precisely so these cannot drift: while an execution is live the
 * server derives them with a {@code COUNT} over {@code exam_attempts} grouped by status,
 * every time it is asked. Nothing increments anything, so a submit racing an expiry cannot
 * double-count and a crashed server cannot come back with the wrong total.
 *
 * <p>They are frozen into the execution's documentation record at close (S-21, E11.5), and
 * the frozen copy is these same three numbers.
 *
 * @param started  attempts that exist at all
 * @param finished attempts the student handed in herself
 * @param timedOut attempts the server closed when time ran out
 */
public record MonitorCounts(long started, long finished, long timedOut) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** An execution nobody has joined yet. */
    public static final MonitorCounts NONE = new MonitorCounts(0, 0, 0);

    /** @return how many students are still working. */
    public long inProgress() {
        return Math.max(0, started - finished - timedOut);
    }
}
