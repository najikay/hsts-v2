package server.db.projections;

/**
 * Which exam a grade was for (E13.3 — contract amendment v1.1).
 *
 * <p>Two strings, four joins away. A {@code grades} row knows its attempt, an attempt knows
 * its execution, an execution knows the exam version it pinned, and only there is there a
 * name — so a student's grade list, where every row is a different exam, cannot label itself
 * without this. The teacher's table does not need it: one execution is one exam, and
 * {@code ExecutionGradingSummary} says so once above the table.
 *
 * <p><b>Carries nothing but labels.</b> No score, no answers, no correctness, nothing about
 * anybody else's grade — which is why it is safe on the student path that motivated it, and
 * why it needs none of {@code CorrectnessLeakGuardTest}'s sanctioned suffixes.
 *
 * @param gradeId    the {@code grades} row this labels
 * @param examName   the exam's name, as its author wrote it
 * @param courseCode the 2-character course code
 */
public record GradeExamLabel(long gradeId, String examName, String courseCode) {
}
