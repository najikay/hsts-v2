package common.dto.grading;

import java.io.Serializable;
import java.util.List;

/**
 * The {@code GRADES_APPROVE} payload (Common tier, E12.2/E12.7).
 *
 * <p>One verb for both gestures. Approving one grade from a review screen and approving a
 * whole execution from the queue are the same operation with a list of one and a list of many,
 * and splitting them would double the handler, the ownership check and the idempotence rule
 * for no behaviour a user could name.
 *
 * <p>What is <b>not</b> here: a teacher id. Which of these ids the caller is actually allowed
 * to approve is resolved server-side and reported back in {@link ApproveResult#refused()} —
 * per id, so a bulk approve of a mixed list never fails as a whole.
 *
 * <p>An empty list is a {@code VALIDATION} answer from the handler, not an exception here (see
 * the package javadoc).
 *
 * @param gradeIds the grades to approve; never {@code null}, defensively copied
 */
public record ApproveRequest(List<Long> gradeIds) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ApproveRequest {
        // List.copyOf yields an immutable, Serializable list — safe on the wire.
        gradeIds = gradeIds == null ? List.of() : List.copyOf(gradeIds);
    }

    /** @return a request approving a single grade (the review screen's button). */
    public static ApproveRequest one(long gradeId) {
        return new ApproveRequest(List.of(gradeId));
    }

    public boolean isEmpty() {
        return gradeIds.isEmpty();
    }

    public int size() {
        return gradeIds.size();
    }
}
