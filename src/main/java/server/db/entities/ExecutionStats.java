package server.db.entities;

import java.util.List;

/**
 * The frozen statistics of one finished execution — the {@code stats} JSON column of
 * {@code exam_executions} (V4, §5, C-5, S-25).
 *
 * <p>Computed once, when the last grade of an execution is approved, and stored rather
 * than recalculated. That is C-5, and it is what lets the report engine (E15.3) compare
 * executions cheaply and consistently: a report run next year sees exactly the numbers
 * the teacher saw on the day, even if a grade is later overridden.
 *
 * <p>Null while the execution is still running or ungraded.
 *
 * @param average   mean score
 * @param median    middle score
 * @param stdDev    population standard deviation — the one v1 omitted (S-25)
 * @param min       lowest score awarded
 * @param max       highest score awarded
 * @param passRate  fraction scoring at or above the pass mark, 0..1
 * @param deciles   ten bucket counts, index 0 = scores 0–9, index 9 = 90–100
 */
public record ExecutionStats(double average,
                             double median,
                             double stdDev,
                             int min,
                             int max,
                             double passRate,
                             List<Integer> deciles) {

    public ExecutionStats {
        deciles = deciles == null ? List.of() : List.copyOf(deciles);
    }
}
