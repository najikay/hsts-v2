package common.dto.report;

import java.io.Serializable;
import java.util.List;

/**
 * The answer to {@code DATA_EXAMS_GET}: every exam in the school (Common tier, E15.2 — F9.3).
 *
 * <p>The whole list and no pagination, on the same reasoning the reports contract gives for its
 * subject lists: these are school-sized (PRD section 6), and a pager is a second place for a
 * count to be wrong.
 *
 * <p>No filter fields travel <em>in</em> either. The principal's scope is the school
 * (spec 7.3.1), so there is nothing for a request to narrow that the screen cannot narrow for
 * itself, and a field a client could set is a field a client could widen.
 *
 * @param exams the exams, ordered by display id; never {@code null}, defensively copied
 */
public record DataExams(List<DataExamRow> exams) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** A school with no exams on record. An empty state to draw, never an error. */
    public static final DataExams EMPTY = new DataExams(List.of());

    public DataExams {
        // List.copyOf yields an immutable, Serializable list - safe on the wire.
        exams = exams == null ? List.of() : List.copyOf(exams);
    }

    /** @return {@code true} when there is nothing on record to browse. */
    public boolean isEmpty() {
        return exams.isEmpty();
    }
}
