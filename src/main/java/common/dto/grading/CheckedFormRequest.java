package common.dto.grading;

import java.io.Serializable;

/**
 * The {@code CHECKED_FORM_GET} payload (Common tier, E13.2).
 *
 * <p>The grade id alone, and the most heavily gated request in the contract: the handler
 * serves it only when the grade belongs to the calling student, its state is
 * {@link GradeState#APPROVED}, and the execution is closed. Anything else — someone else's
 * grade, an unapproved one, a live execution — answers {@code NOT_FOUND}, indistinguishably,
 * so a student cannot learn from the error which of the three gates stopped them.
 *
 * @param gradeId the student's own grade to open as a checked form
 */
public record CheckedFormRequest(long gradeId) implements Serializable {

    private static final long serialVersionUID = 1L;
}
