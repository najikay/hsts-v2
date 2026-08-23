package common.dto.report;

import common.dto.results.ResultStatistics;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One closed execution as a report compares it (Common tier, E15.3 — F9.4, F8.5).
 *
 * <p>A row exists only when the sitting is <b>closed and its statistics are frozen</b>. A live
 * sitting has no figures to compare, a scheduled one has no figures at all, and a cancelled one
 * was never sat (H15.2 ⚑) — none of them belongs in a comparison, and putting them in with
 * blanks would produce a table whose gaps a reader has to interpret.
 *
 * <p>{@code statistics} is {@link ResultStatistics}, <b>reused unchanged from E14</b> rather
 * than restated here. RESULTS_WIRE_CONTRACT's closing section named this: "that is F9.4 / E15's
 * report engine, and its dimension strategies will consume {@code ResultStatistics} rather than
 * redefine it". Every figure in it was frozen into {@code exam_executions.stats} when the
 * sitting's last grade was approved — population σ, pass mark 55, ten stored buckets — and
 * nothing on this path recomputes any of it.
 *
 * @param executionId the sitting, so a screen can key a selection on something stable
 * @param code4       its four-character code, which is how a sitting is named out loud
 * @param examName    the <b>released version's</b> name, so a sitting is labelled with what the
 *                    students actually saw even after the exam has been renamed
 * @param courseCode  the two-character course code
 * @param courseName  the course's display name
 * @param openAt      when the window opened, UTC
 * @param closeAt     when the window closed, UTC
 * @param participants how many students sat it: a {@code COUNT} over attempts, so it counts the
 *                    student whose paper was never marked as well. It is deliberately not
 *                    {@code statistics.count()}, and the gap between the two is a fact a
 *                    principal should be able to see
 * @param statistics  the frozen figures, never null on a row: a row without them would not have
 *                    been built
 */
public record ReportRow(long executionId,
                        String code4,
                        String examName,
                        String courseCode,
                        String courseName,
                        Instant openAt,
                        Instant closeAt,
                        int participants,
                        ResultStatistics statistics) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * @throws NullPointerException when the statistics or either instant is missing, which
     *                              would mean a row was built for a sitting that has nothing to
     *                              compare
     */
    public ReportRow {
        Objects.requireNonNull(statistics, "statistics");
        Objects.requireNonNull(openAt, "openAt");
        Objects.requireNonNull(closeAt, "closeAt");
        examName = examName == null ? "" : examName;
        courseCode = courseCode == null ? "" : courseCode;
        courseName = courseName == null ? "" : courseName;
        code4 = code4 == null ? "" : code4;
    }

    /**
     * @return how many of the students who sat it have no marked paper behind these figures.
     *         Never negative: a grade cannot exist without an attempt
     */
    public int unmarked() {
        return Math.max(0, participants - statistics.count());
    }
}
