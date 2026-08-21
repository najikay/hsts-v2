package common.dto.approval;

import java.io.Serializable;

/**
 * Open one exam version for review (Common tier, E8.4 — F4.1).
 *
 * <p>The request for {@code EXAM_PREVIEW_GET}. One field, and no user id: who is asking is
 * the session's business, not the payload's, and a coordinator id in here could only ever be
 * somebody else's (ARCHITECTURE §3, security). A version whose subject this caller does not
 * coordinate, and did not write, answers {@code FORBIDDEN}.
 *
 * @param examVersionId the version to open
 */
public record ExamPreviewRequest(long examVersionId) implements Serializable {

    private static final long serialVersionUID = 1L;
}
