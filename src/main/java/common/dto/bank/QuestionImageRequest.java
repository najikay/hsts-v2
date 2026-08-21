package common.dto.bank;

import java.io.Serializable;

/**
 * The {@code QUESTION_IMAGE_GET} payload (Common tier, E6.6 — F2.4).
 *
 * <p><b>Addressed by version, not by question.</b> Versions are immutable, so an illustration
 * belongs to the wording it was uploaded with: history showing v1's text beside v3's picture
 * would be a lie of exactly the kind immutable versions exist to prevent, and a marked paper
 * from last term has to render the diagram the student actually saw.
 *
 * <p>The fetch is lazy and separate because neither {@link BankQuestionRow} nor
 * {@link QuestionDetail} ever carries bytes. A list of forty rows costs no image traffic at all,
 * and the detail pane pays for one picture only once somebody opens it (NFR-18).
 *
 * @param displayId5 the question
 * @param versionNo  which version's illustration is wanted
 */
public record QuestionImageRequest(String displayId5, int versionNo) implements Serializable {

    private static final long serialVersionUID = 1L;
}
