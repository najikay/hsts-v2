package common.dto.grading;

import java.io.Serializable;
import java.util.List;

/**
 * The answer to {@code GRADING_QUEUE_GET} (Common tier, E12.1).
 *
 * <p>Every closed execution this teacher may grade, in one list. The request carries no
 * payload at all: <b>which</b> executions those are is resolved from the caller's session
 * against the repositories — the executing teacher, or the exam's author — and never from
 * anything a client could put in a field (P-5).
 *
 * <p>No pagination, by decision: a school-sized queue is tens of rows (§6), and a page token
 * would be a parameter with no caller and one more thing to get wrong while executions close
 * underneath it.
 *
 * @param executions the teacher's gradable executions; never {@code null}, defensively copied
 */
public record GradingQueue(List<ExecutionGradingSummary> executions) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** What a teacher with nothing to mark gets. */
    public static final GradingQueue EMPTY = new GradingQueue(List.of());

    public GradingQueue {
        // List.copyOf yields an immutable, Serializable list — safe on the wire.
        executions = executions == null ? List.of() : List.copyOf(executions);
    }

    public boolean isEmpty() {
        return executions.isEmpty();
    }

    public int size() {
        return executions.size();
    }
}
