package common.dto.bank;

import java.io.Serializable;
import java.util.Objects;

/**
 * A question edit together with the values it was based on (Common tier, E18.4).
 *
 * <p>This is optimistic concurrency by <b>value</b>: the client sends what it
 * intends to write plus what it read, and the server writes only if the row
 * still says what the client last saw. Someone else's save in between changes
 * the row, the guarded {@code UPDATE} matches nothing, and the caller gets
 * {@link common.protocol.ErrorCode#CONFLICT} instead of silently overwriting
 * work that is not on their screen.
 *
 * <p>Value-based rather than a version column because the prototype's
 * {@code Questions} table is the one table outside the Flyway schema and has no
 * {@code lock_version} to compare (ARCHITECTURE §5 puts that column on the real
 * {@code questions} table, which E6 will use). It is the same technique
 * Hibernate offers as {@code OptimisticLockType.ALL}, and the client-side half —
 * the CONFLICT dialog and the reload — is written generically in
 * {@code client.features.locks} so E6's editor inherits it unchanged.
 *
 * <p>Backward compatible on purpose: {@code UPDATE_QUESTION} still accepts a
 * bare {@link Question} and writes it unguarded, so an older client keeps
 * working exactly as before.
 *
 * @param edited       the question as the user wants it saved
 * @param expectedText the question text the client loaded, before editing
 * @param expectedAnswer the answer the client loaded, before editing
 */
public record QuestionUpdate(Question edited,
                             String expectedText,
                             String expectedAnswer) implements Serializable {

    private static final long serialVersionUID = 1L;

    public QuestionUpdate {
        Objects.requireNonNull(edited, "edited");
        expectedText = expectedText == null ? "" : expectedText;
        expectedAnswer = expectedAnswer == null ? "" : expectedAnswer;
    }

    /** @return the id of the row this update targets. */
    public int id() {
        return edited.getId();
    }
}
