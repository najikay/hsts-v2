package common.dto.bank;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The answer to {@code QUESTION_VERSIONS}: every version a question has ever had (Common tier,
 * E6.3 — F2.3, C-2).
 *
 * <p>Editing a question never mutates a version, it writes the next one (ADR-011), so this list
 * is the whole life of the question and includes the current version rather than only the
 * superseded ones. That is what makes an exam pinned to v1 explicable a year later: the paper a
 * student sat is still here, word for word, next to the wording that replaced it.
 *
 * <p><b>Newest first.</b> The order is the server's and is not re-sorted client-side: a history
 * panel is read top-down from what is true now backwards, and two screens each sorting by their
 * own idea of the comparator is how one of them ends up upside down.
 *
 * @param displayId5 the question these versions belong to
 * @param versions   every version, newest first; never {@code null}, defensively copied
 */
public record VersionHistory(String displayId5,
                             List<QuestionVersionDetail> versions) implements Serializable {

    private static final long serialVersionUID = 1L;

    public VersionHistory {
        Objects.requireNonNull(displayId5, "displayId5");
        // List.copyOf yields an immutable, Serializable list - safe on the wire.
        versions = versions == null ? List.of() : List.copyOf(versions);
    }

    /** @return the current version, or empty for a question with no versions at all, which is a
     *          state the schema does not permit but a client should not crash on. */
    public Optional<QuestionVersionDetail> latest() {
        return versions.isEmpty() ? Optional.empty() : Optional.of(versions.get(0));
    }

    public int size() {
        return versions.size();
    }

    public boolean isEmpty() {
        return versions.isEmpty();
    }
}
