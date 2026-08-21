package common.dto.approval;

import java.io.Serializable;

/**
 * Approve one exam version (Common tier, E8.5 — F4.2).
 *
 * <p>The request for {@code EXAM_APPROVE}. Carries no user id, for the reason every payload
 * in this contract carries none: the approver is the session.
 *
 * <p>{@link #expectedLockVersion} is what makes this a compare-and-set rather than a blind
 * write. Two coordinators cannot both hold a subject (the {@code coordinators} primary key is
 * the subject alone), but one coordinator with the screen open twice can, and so can an
 * approval racing E8.2's supersede. The server refuses a decision taken against a row that
 * has moved, with {@code CONFLICT} and a sentence telling her to open it again, rather than
 * quietly approving a version somebody already sent back.
 *
 * @param examVersionId       the version to approve
 * @param expectedLockVersion the {@code lockVersion} the caller was looking at, from the
 *                            {@link ApprovalRow} or {@link ExamPreview} the screen rendered
 */
public record ExamApproveRequest(long examVersionId, int expectedLockVersion)
        implements Serializable {

    private static final long serialVersionUID = 1L;
}
