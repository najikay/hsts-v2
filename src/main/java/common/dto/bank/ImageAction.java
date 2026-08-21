package common.dto.bank;

/**
 * What an edit does to a question's illustration (Common tier, E6.6 — F2.1).
 *
 * <p><b>Three states rather than two, and the third one is the point.</b> If the edit payload
 * carried nothing but a nullable {@code image}, then a {@code null} would mean both "I did not
 * touch the picture" and "I pressed remove", which are opposite instructions. F2.1's editor has
 * an explicit remove button, so both have to be expressible.
 *
 * @see QuestionEdit
 */
public enum ImageAction {

    /**
     * Leave the illustration as it is.
     *
     * <p>Versions are immutable (C-2, ADR-011), so keeping is a copy rather than a no-op: the
     * blob is written again onto version n+1. An illustrated question edited ten times therefore
     * stores its picture ten times. That is the honest cost of immutable versions, and it is why
     * {@code QUESTION_IMAGE_GET} is addressed by version rather than by question.
     */
    KEEP,

    /** Replace it with the bytes in the same payload. */
    REPLACE,

    /** Write version n+1 with no illustration at all. */
    REMOVE
}
