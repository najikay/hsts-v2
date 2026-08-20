package common.dto.grading;

import java.io.Serializable;

/**
 * The {@code GRADING_EXECUTION_GET} payload (Common tier, E12.1).
 *
 * <p>One field, and deliberately so: <b>who</b> is asking is never on the wire. The handler
 * reads the caller from the socket-bound session and proves ownership of this execution from
 * the repositories — the executing teacher or the exam's author — so a client that named
 * someone else's execution gets {@code NOT_FOUND}, which is the same answer an execution id
 * that does not exist gets. The two are indistinguishable on purpose.
 *
 * @param executionId the execution whose grades to load
 */
public record ExecutionGradesRequest(long executionId) implements Serializable {

    private static final long serialVersionUID = 1L;
}
