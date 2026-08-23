package common.dto.authoring;

import java.io.Serializable;

/**
 * The {@code EXAM_VERSION_GET} payload: open one version (Common tier, E7 — F3.5, E7.14).
 *
 * <p>The version id alone. Whether the caller may open it is resolved server-side against the
 * <b>stored</b> row's author (S-12), and a version she did not write answers {@code NOT_FOUND} —
 * the same answer an id that never existed gets, so probing ids tells a caller nothing about
 * what exists or who owns it (P-5). Never {@code FORBIDDEN}: naming the exam would be the
 * existence oracle both frozen contracts already refuse.
 *
 * <p><b>One payload serves two screens</b>, and that is the point of the contract's section 3.
 * The builder opens a {@code DRAFT} with it and the history panel renders a past version
 * read-only with it, and the client decides what is editable from
 * {@link ExamComposition#state()}. A second request shape for the read-only case would be two
 * shapes that can drift.
 *
 * @param examVersionId the version to open
 */
public record ExamVersionRequest(long examVersionId) implements Serializable {

    private static final long serialVersionUID = 1L;
}
