package common.dto.exam;

import java.io.Serializable;

/**
 * "I am done, hand it in" (Common tier, E10.4 — F6.9).
 *
 * <p>Carries the attempt and nothing else. There is nothing to negotiate: the answers are
 * already on the server, saved as she made them, so a submit is a state transition rather
 * than an upload. That is what makes the submit-vs-expiry race survivable — both writers
 * are doing the same thing to the same row, and the compare-and-set decides which name
 * ends up on it.
 *
 * @param attemptId the caller's own live attempt
 */
public record SubmitAttemptRequest(long attemptId) implements Serializable {

    private static final long serialVersionUID = 1L;
}
