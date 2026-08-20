package common.dto.grading;

import java.io.Serializable;

/**
 * The {@code GRADE_REVIEW_GET} payload (Common tier, E12.3).
 *
 * <p>The grade id alone. Ownership is resolved server-side from the caller's session, and a
 * grade belonging to an execution this teacher does not run or author answers
 * {@code NOT_FOUND} — the same answer an unknown id gets, so probing ids reveals nothing.
 *
 * @param gradeId the grade to open for review
 */
public record GradeReviewRequest(long gradeId) implements Serializable {

    private static final long serialVersionUID = 1L;
}
