package common.dto.report;

import java.io.Serializable;
import java.util.Objects;

/**
 * "Run the {@code dimension} report about {@code subjectId}" (Common tier, E15.3 — F9.4).
 *
 * <p>Two components, and they are the whole parameterisation of the report mechanism. A fourth
 * comparison reuses this record unchanged, which is the point of the subject id being an opaque
 * string: only the strategy that issued the id in {@link ReportSubjects} interprets it.
 *
 * <p>A subject id the caller invented rather than picked is answered {@code NOT_FOUND}, the same
 * as one that no longer exists. There is nothing to protect here — the principal reads
 * school-wide either way — but two answers would still be one more thing than the verb needs to
 * say.
 *
 * @param dimension the dimension to compare across
 * @param subjectId the subject, as {@link ReportSubject#id()} gave it
 */
public record ReportRequest(ReportDimension dimension, String subjectId) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * @throws NullPointerException     when either component is null
     * @throws IllegalArgumentException when the subject id is blank
     */
    public ReportRequest {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(subjectId, "subjectId");
        if (subjectId.isBlank()) {
            throw new IllegalArgumentException("A report needs a subject to be about.");
        }
    }
}
