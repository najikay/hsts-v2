package common.dto.results;

import java.io.Serializable;

/**
 * The {@code RESULTS_EXECUTION_GET} payload (Common tier, E14.1).
 *
 * <p>An execution id and nothing else. No teacher id and no exam id: both are resolved from
 * the session and from the database, so a client cannot widen its own scope by asking nicely.
 * An execution whose exam the caller did not write answers {@code NOT_FOUND} — the same answer
 * an id that never existed gets, so this verb is not a membership oracle.
 *
 * @param executionId the execution whose results are wanted
 */
public record ExecutionResultsRequest(long executionId) implements Serializable {

    private static final long serialVersionUID = 1L;
}
