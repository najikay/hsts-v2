package common.dto.authoring;

import java.io.Serializable;

/**
 * The payload for the two verbs that change a version's status (Common tier, E7.5/E7.6 —
 * F3.5, F3.6).
 *
 * <p>{@code EXAM_VERSION_REVISE} and {@code EXAM_SUBMIT} take the same two facts, so they take
 * the same record. One shape for both, because a screen that had to remember which shape went
 * with which button is a screen that will one day send the wrong one — and the two verbs sit
 * side by side on the same toolbar.
 *
 * <h2>{@code expectedLockVersion} is not optional and not decorative</h2>
 *
 * <p>{@code exam_versions.lock_version} is a real column with {@code @Version} on it and
 * {@code status} is the one mutable field on the row, so an author submitting while a
 * coordinator approves is a genuine race rather than a hypothetical one. The token is the same
 * token {@code ExamApproveRequest} carries against the same row, deliberately: one row, one
 * convention. A stale value answers {@code CONFLICT} with a sentence telling her to open it
 * again, never a silent overwrite.
 *
 * <h2>What the handler checks, and this record does not</h2>
 *
 * <p>Per the package javadoc, the state rules of contract section 5.4 are
 * {@code ExamService}'s: {@code EXAM_SUBMIT} requires {@code DRAFT} and
 * {@code EXAM_VERSION_REVISE} refuses one, and each answers {@code CONFLICT} rather than
 * {@code VALIDATION} because the request was well formed and the world moved. Neither is an
 * exception thrown inside a deserialization on a socket read thread.
 *
 * @param examVersionId       the version to act on
 * @param expectedLockVersion the {@code lockVersion} the caller was looking at when she pressed
 *                            the button; compared server-side inside the transaction
 */
public record ExamVersionAction(long examVersionId, int expectedLockVersion)
        implements Serializable {

    private static final long serialVersionUID = 1L;
}
