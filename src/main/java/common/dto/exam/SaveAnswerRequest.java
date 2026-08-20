package common.dto.exam;

import java.io.Serializable;

/**
 * "I picked option 3 for question 7" (Common tier, E10.3 ⚑ — F6.3).
 *
 * <p>Sent by the debounced autosave as the student works. It names the attempt rather than
 * the execution because the client already has one, and the server re-checks that the
 * attempt is the caller's own regardless: an attempt id belonging to somebody else answers
 * {@code NOT_FOUND}, indistinguishably from one that does not exist.
 *
 * <p>{@link #selected} is nullable, and that is the "clear my answer" path: a student who
 * changes her mind and deselects is saving "unanswered", which scores zero (§6) and is a
 * different thing from never having touched the question. The server validates 1..4 for
 * anything non-null and rejects the write outright when the attempt is not
 * {@code IN_PROGRESS} or the deadline has passed (E10.8 ⚑).
 *
 * @param attemptId         the caller's own live attempt
 * @param questionVersionId the question being answered, by its pinned version
 * @param selected          the chosen option 1..4, or {@code null} to clear
 */
public record SaveAnswerRequest(long attemptId, long questionVersionId, Integer selected)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /** @return {@code true} when the selection is absent or a legal option. */
    public boolean isSelectionLegal() {
        return selected == null || SavedAnswer.isSelectable(selected);
    }
}
