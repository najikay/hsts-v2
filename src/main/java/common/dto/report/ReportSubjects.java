package common.dto.report;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Everything one dimension can be reported about (Common tier, E15.3 — F9.4).
 *
 * <p>The answer shape of {@code REPORT_SUBJECTS_GET}. School-wide, because that is what spec
 * 7.3.1 gives the principal: every teacher, every course, every student, not a scoped slice.
 * No pagination, on PRD §6's scale ruling.
 *
 * @param dimension which dimension these are the subjects of, echoed back so an answer that
 *                  arrives after the principal has switched segments can be discarded rather
 *                  than rendered under the wrong heading
 * @param subjects  the pickable subjects, ordered by label; subjects with nothing to report are
 *                  included and say so (E15.5)
 */
public record ReportSubjects(ReportDimension dimension, List<ReportSubject> subjects)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /** @throws NullPointerException when either component is missing */
    public ReportSubjects {
        Objects.requireNonNull(dimension, "dimension");
        subjects = List.copyOf(Objects.requireNonNull(subjects, "subjects"));
    }

    /** @return {@code true} when this dimension has no subjects at all. */
    public boolean isEmpty() {
        return subjects.isEmpty();
    }

    /**
     * @return the subject to open on: the first with something to report, else the first of all,
     *         else {@code null}. A picker that landed on an empty subject would show the empty
     *         state on the one path everybody takes
     */
    public ReportSubject defaultSubject() {
        for (ReportSubject subject : subjects) {
            if (!subject.hasNothingToReport()) {
                return subject;
            }
        }
        return subjects.isEmpty() ? null : subjects.get(0);
    }
}
