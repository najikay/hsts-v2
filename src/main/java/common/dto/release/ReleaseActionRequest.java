package common.dto.release;

import java.io.Serializable;

/**
 * Cancel this release, or close it early (Common tier, E9 — F5.5).
 *
 * <p>One record for two verbs, because the two requests carry exactly the same thing: which
 * release. Which of the two actions is meant is the verb, not a flag inside the payload —
 * a {@code boolean closeEarly} would make "cancel a live exam" a representable request that
 * the server then has to refuse, and F5.5's whole point is that cancelling and closing early
 * are different actions with different warnings and different legal states.
 *
 * <p>No teacher id: whose release it is comes from the session (P-5), and an id that is not
 * hers answers the same way an id that does not exist does.
 *
 * @param executionId the release to act on
 */
public record ReleaseActionRequest(long executionId) implements Serializable {

    private static final long serialVersionUID = 1L;
}
