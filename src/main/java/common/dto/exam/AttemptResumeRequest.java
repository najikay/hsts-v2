package common.dto.exam;

import java.io.Serializable;

/**
 * "I am back, give me my paper as it stands" (Common tier, E10.6 — F6.3).
 *
 * <p>Sent after a reconnect, after a client crash, or simply when the screen is reopened.
 * It names the execution rather than the attempt on purpose: a client that was killed
 * before it ever saw an attempt id must still be able to come back, and the server can
 * always find the one attempt this student has at this execution
 * ({@code UNIQUE(execution_id, student_id)}).
 *
 * <p>No identity check here, and that is deliberate: S-18 makes the national id the act
 * that <em>starts</em> the clock, so demanding it again after a dropped socket would
 * punish a student for her network.
 *
 * @param executionId the execution she was sitting
 */
public record AttemptResumeRequest(long executionId) implements Serializable {

    private static final long serialVersionUID = 1L;
}
