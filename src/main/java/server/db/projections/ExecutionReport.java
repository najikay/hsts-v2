package server.db.projections;

import server.db.entities.ExecutionStats;

import java.time.Instant;

/**
 * One reportable sitting: its identity, its window and its frozen statistics (E15.3 — F9.4).
 *
 * <p>The row shape every {@code ReportDimension} strategy produces, whatever it filtered on.
 * That uniformity is what lets {@code ReportEngine} be dimension-agnostic: the strategies differ
 * in their {@code WHERE} clause and in nothing else, so the mapping onto the wire, the
 * participant counts and the summary arithmetic are written once.
 *
 * <p>Deliberately <b>not</b> {@link ExecutionContext}. That projection carries the pinned
 * version id, the duration, the student instructions, the extra minutes and the two teacher ids,
 * because E10 and E11 decide things from them. A report decides nothing; it labels rows and
 * compares stored numbers, and a projection carrying a student-facing instruction text into the
 * principal's report would be five fields nobody reads and one more thing to keep true.
 *
 * <p>{@code stats} is the stored JSON column exactly as written when the sitting's last grade
 * was approved (F8.5). Every query producing this projection filters {@code stats is not null},
 * so it is never null here; it is mapped onto the wire through
 * {@code server.features.results.FrozenStatistics}, which is the one place stored statistics
 * become wire statistics.
 *
 * @param executionId the sitting
 * @param code        its four-character code
 * @param openAt      when the window opened, UTC
 * @param closeAt     when the window closed, UTC
 * @param examName    the released version's name, so the row is labelled with what the students
 *                    saw even after the exam has been renamed
 * @param courseCode  the two-character course code
 * @param courseName  the course's display name
 * @param stats       the frozen statistics, never null
 */
public record ExecutionReport(long executionId,
                              String code,
                              Instant openAt,
                              Instant closeAt,
                              String examName,
                              String courseCode,
                              String courseName,
                              ExecutionStats stats) {
}
