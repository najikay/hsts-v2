package common.dto.report;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * One report, whatever it compares (Common tier, E15.3 — F9.4, S-37).
 *
 * <p>The answer shape of {@code REPORT_GET}, and the reason there is one report screen instead
 * of three: the dimension and the subject are data in this record rather than a choice of type,
 * so the table, the chart and the summary cards render a {@code BY_STUDENT} report with the same
 * code that renders a {@code BY_TEACHER} one. Adding a fourth comparison adds no component here.
 *
 * @param dimension what this report compared across, echoed back so a late answer can be
 *                  matched against what the screen is currently showing
 * @param subject   the subject it is about, carrying the label the heading prints
 * @param rows      its sittings, <b>oldest first</b>: a comparison across time reads left to
 *                  right, and a picker's "newest first" ordering would put the trend backwards
 * @param summary   the cross-row aggregate, {@link ReportSummary#EMPTY} when there are no rows
 */
public record ReportResult(ReportDimension dimension,
                           ReportSubject subject,
                           List<ReportRow> rows,
                           ReportSummary summary) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** @throws NullPointerException when any component is missing */
    public ReportResult {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(summary, "summary");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    }

    /**
     * @return {@code true} when this subject has nothing to compare yet. A real answer about a
     *         real teacher, not an error: she exists and no sitting of her exams has closed
     */
    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
