package common.dto.approval;

import java.io.Serializable;
import java.util.Optional;

/**
 * Send one exam version back, with the reason F4.2 requires (Common tier, E8.5).
 *
 * <p>The request for {@code EXAM_REJECT}. The reason is <b>mandatory</b>, and this record is
 * where "mandatory" is defined once for both tiers: {@link #validate(String)} is the single
 * rule, the client runs it on every keystroke to disable the button and show an inline
 * message, and the server runs it again before it writes anything. The client's copy is a
 * courtesy; the server's is the enforcement, and they cannot disagree because there is only
 * one of them.
 *
 * <h2>Why there is a minimum length at all</h2>
 *
 * <p>A required field with no floor is satisfied by "no", and a teacher who receives "no"
 * has been refused without being told anything she can act on. That is precisely the outcome
 * F4.2 exists to prevent, and it is the one message this feature must never send
 * ({@code NotificationCatalog.approvalRejected} says so too). {@link #MIN_REASON_LENGTH}
 * characters is not a magic threshold for quality; it is the smallest bar that a reflexive
 * one-word dismissal fails and a real sentence clears, and the error message names the
 * number so the person typing knows exactly what is being asked.
 *
 * <p>The reason is compared and stored <b>trimmed</b>: ten spaces are not a reason, and a
 * stored reason with a ragged edge shows up in a notification body.
 *
 * @param examVersionId       the version to send back
 * @param reason              why, in the coordinator's own words; trimmed by the server
 *                            before it is measured or stored
 * @param expectedLockVersion the {@code lockVersion} the caller was looking at; see
 *                            {@link ExamApproveRequest}
 */
public record ExamRejectRequest(long examVersionId, String reason, int expectedLockVersion)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The shortest reason that counts as one, in characters, after trimming. */
    public static final int MIN_REASON_LENGTH = 10;

    /** Said when nothing at all was typed. */
    public static final String REASON_REQUIRED =
            "Type why you are sending this exam back. The teacher sees this reason.";

    /** Said when something was typed but it is too short to act on. */
    public static final String REASON_TOO_SHORT =
            "Give the teacher something to work with: at least " + MIN_REASON_LENGTH
                    + " characters explaining what to change.";

    public ExamRejectRequest {
        // Normalised here so the value that travels is the value that is measured, stored
        // and shown. A record that trimmed only on the server would let the client validate
        // a different string from the one it sent.
        reason = reason == null ? "" : reason.trim();
    }

    /**
     * The one definition of an acceptable rejection reason, for both tiers.
     *
     * @param candidate what the coordinator has typed so far; {@code null} is treated as
     *                  nothing typed
     * @return the message to show, or empty when the reason is acceptable
     */
    public static Optional<String> validate(String candidate) {
        String trimmed = candidate == null ? "" : candidate.trim();
        if (trimmed.isEmpty()) {
            return Optional.of(REASON_REQUIRED);
        }
        if (trimmed.length() < MIN_REASON_LENGTH) {
            return Optional.of(REASON_TOO_SHORT);
        }
        return Optional.empty();
    }

    /** @return {@code true} when this request's reason passes {@link #validate(String)}. */
    public boolean hasUsableReason() {
        return validate(reason).isEmpty();
    }

    /**
     * @param candidate what has been typed so far
     * @return how many more characters are needed, {@code 0} once the minimum is met; the
     *         live counter under the field
     */
    public static int charactersStillNeeded(String candidate) {
        String trimmed = candidate == null ? "" : candidate.trim();
        return Math.max(0, MIN_REASON_LENGTH - trimmed.length());
    }
}
