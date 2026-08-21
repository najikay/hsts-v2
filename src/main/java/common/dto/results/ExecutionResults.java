package common.dto.results;

import common.dto.grading.StudentGradeRow;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The answer to {@code RESULTS_EXECUTION_GET}: one sitting, in full (Common tier, E14.1).
 *
 * <p>The header the teacher clicked, one row per marked student, and the execution's frozen
 * statistics. Three things in one answer because the screen shows them together and a second
 * round trip could return a picture that had moved.
 *
 * <h2>Rows are grades, and that is visible</h2>
 *
 * <p>{@code rows} carries one {@link StudentGradeRow} per <b>grade row</b>, not per attempt: a
 * student whose paper has not been marked yet has no score to show and is absent. The gap is
 * never silent — {@code execution.participants()} against {@code rows.size()} is exactly the
 * "6 of 8 marked" the screen states above the table.
 *
 * <p>These are teacher-path rows, so {@code overrideReason} is populated. The justification is
 * teacher and audit material (S-23); the structural stripping that protects the student wire
 * lives in {@code MyGrades} and {@code CheckedForm} and is not in play here.
 *
 * <h2>Absent statistics are a state, not an error</h2>
 *
 * <p>{@code statistics} is {@code null} until the execution's last grade is approved and F8.5
 * freezes them. An execution mid-marking therefore returns its rows with no statistics, and
 * the screen says grading is not finished rather than showing zeros or refusing to open. Use
 * {@link #statistics()} — an {@link Optional} — so the case cannot be forgotten at the call
 * site, and never synthesise a record of zeros: a class that all scored 0 and a class nobody
 * has marked are different facts.
 *
 * @param execution  the execution's header row, the same one the picker showed
 * @param examName   the exam's name, from the version this run released
 * @param courseCode the two-character course code
 * @param courseName the course's display name
 * @param rows       one row per marked student, by name; never null, possibly empty
 * @param stats      the frozen statistics, or {@code null} while grading is unfinished
 */
public record ExecutionResults(ExecutionResultRow execution,
                               String examName,
                               String courseCode,
                               String courseName,
                               List<StudentGradeRow> rows,
                               ResultStatistics stats) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ExecutionResults {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(examName, "examName");
        Objects.requireNonNull(courseCode, "courseCode");
        Objects.requireNonNull(courseName, "courseName");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    }

    /**
     * @return the frozen statistics, or empty while grading is unfinished. The Optional is the
     *         point: a caller that maps it cannot accidentally render a null as zeros
     */
    public Optional<ResultStatistics> statistics() {
        return Optional.ofNullable(stats);
    }

    /** @return {@code true} when nobody's paper has been marked yet. */
    public boolean isUnmarked() {
        return rows.isEmpty();
    }

    /** @return {@code true} when the histogram and the stat cards have numbers to show. */
    public boolean hasStatistics() {
        return stats != null;
    }
}
