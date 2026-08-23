package common.dto.authoring;

import java.io.Serializable;

/**
 * One question on a composition as the client sends it (Common tier, E7.2 — F3.1, S-11).
 *
 * <p>Two fields, and the two that are missing are the interesting part.
 *
 * <h2>{@code ord} is the list index and is not a field</h2>
 *
 * <p>The position of a question on the paper is where it sits in
 * {@link ExamCreateRequest#questions()} or {@link ExamVersionSave#questions()}, 1-based on
 * arrival. Carrying an explicit {@code ord} beside the list would be two orderings that can
 * disagree, and {@code uq_exam_version_questions_ord} would catch the disagreement only after
 * the client had already shown the teacher the wrong one. A drag-and-drop reorder is therefore
 * a list reorder and nothing else.
 *
 * <h2>{@code questionId} is not sent either</h2>
 *
 * <p>Only {@code questionVersionId} travels. The owning question is derived server-side from
 * the version, which is what the composite foreign key
 * {@code (question_version_id, question_id)} exists to police: a client that sent both could
 * send a pair that does not belong together, and the constraint would refuse it with a message
 * no teacher can act on.
 *
 * <h2>What the handler checks, and this record does not</h2>
 *
 * <p>Per the package javadoc, every rule belongs to {@code ExamValidator}, shared by create and
 * save so the two cannot diverge:
 *
 * <ul>
 *   <li>{@code points} in {@link #MIN_POINTS}..{@link #MAX_POINTS} ({@code ck_evq_points}),
 *       integers — there are no fractional points anywhere on this wire because
 *       {@code points INT};</li>
 *   <li>the points of every pin summing to exactly
 *       {@link ExamCreateRequest#POINTS_TOTAL} (contract section 5.1);</li>
 *   <li>no duplicate question, <em>even through two different versions of it</em>
 *       (T-3.9, section 5.2), which is a rule about the whole list and cannot be a rule about
 *       one pin;</li>
 *   <li>the pinned version existing, not being soft-deleted, and belonging to the exam's own
 *       course — resolved from {@code question_versions}, never trusted from the client.</li>
 * </ul>
 *
 * <p>An unknown {@code questionVersionId} answers {@code VALIDATION} naming its position in the
 * list rather than {@code NOT_FOUND}: the caller is describing a composition, so the thing that
 * was not found is a field of her request and not the object she addressed.
 *
 * @param questionVersionId the exact bank version pinned onto the paper; pinning a version
 *                          rather than a question is what makes E7.7's "the bank has moved on"
 *                          badge expressible at all
 * @param points            what this question is worth, {@link #MIN_POINTS}..{@link #MAX_POINTS}
 *                          (checked by the handler)
 */
public record QuestionPin(long questionVersionId, int points) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The least a question on a paper may be worth ({@code ck_evq_points}).
     *
     * <p>It is also the reason the contract does not state a maximum question count: points of
     * at least 1 summing to exactly 100 caps the paper at 100 questions, and writing that
     * ceiling down separately would be a second rule that could one day disagree with the first.
     */
    public static final int MIN_POINTS = 1;

    /** The most a question may be worth: the whole paper ({@code ck_evq_points}). */
    public static final int MAX_POINTS = 100;
}
