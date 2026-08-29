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
 * <p><b>{@code teacherId} (A7, 2026-08-29).</b> The third label the student's screens print is
 * a person, and the execution this already joins is where the id lives — so it is a column on a
 * read that was happening anyway, not a read of its own. An <b>id</b> rather than a name,
 * because names come from {@code UserRepository.findById} everywhere else in the product and a
 * second way to spell a person is a second way for two screens to disagree about one.
 *
 * @param gradeId    the {@code grades} row this labels
 * @param examName   the exam's name, as its author wrote it
 * @param courseCode the 2-character course code
 * @param teacherId  {@code exam_executions.created_by} — who released the sitting (A7)
 */
public record GradeExamLabel(long gradeId, String examName, String courseCode, long teacherId) {
}
