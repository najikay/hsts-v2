package common.dto.bank;

import java.io.Serializable;

/**
 * The {@code QUESTION_GET} and {@code QUESTION_VERSIONS} payload (Common tier, E6.3).
 *
 * <p>The display id alone. Which questions the caller may open is resolved server-side from her
 * session against the courses she reaches, and a question outside that set answers
 * {@code NOT_FOUND} - the same answer an unknown id and a soft-deleted one get, so probing ids
 * tells a caller nothing about what exists.
 *
 * @param displayId5 the 5-digit id of the question to open (S-8)
 */
public record QuestionRequest(String displayId5) implements Serializable {

    private static final long serialVersionUID = 1L;
}
