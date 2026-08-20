package common.dto.grading;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * The answer to {@code GRADING_EXECUTION_GET}: one execution and every grade in it (Common
 * tier, E12.1).
 *
 * <p>The summary travels with the rows for the same reason the unread count travels with the
 * notification list: the header and the table are two views of one truth, and a client that
 * recomputed "3 of 28 approved" from the rows it happened to receive would drift the moment
 * anything about that list changed.
 *
 * @param summary the execution header, echoing the queue row the teacher clicked
 * @param rows    every student's grade in this execution; never {@code null}, defensively
 *                copied. Teacher-side, so {@code overrideReason} is populated here
 */
public record ExecutionGrades(ExecutionGradingSummary summary,
                              List<StudentGradeRow> rows) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ExecutionGrades {
        Objects.requireNonNull(summary, "summary");
        // List.copyOf yields an immutable, Serializable list — safe on the wire.
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public int size() {
        return rows.size();
    }
}
