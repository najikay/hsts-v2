package server.db.projections;

/**
 * An exam that stands between a question and its deletion (E6.4 - F2.5, T-2.7).
 *
 * <p>Maps to {@code common.dto.bank.BlockingExam}. The teacher sees these in the dialog that
 * refuses the delete, and naming them is the entire requirement: "this question cannot be
 * deleted" without a list leaves her with no next move, which is the dead end PRD §4.1 forbids.
 *
 * <h2>One row per exam, never per exam version</h2>
 *
 * <p>A question is referenced from {@code exam_version_questions}, which is keyed on the exam
 * <em>version</em>, and one exam has many versions (C-2, ADR-011). Seed exam {@code 101101}
 * pins question {@code 11005} in both v1 and v2, which {@code SeedDatasetContract} asserts,
 * so the obvious query returns that exam twice and the dialog reads "2 exams use it: Algebra
 * Midterm, Algebra Midterm". The query behind this record collapses to the exam and takes its
 * name from the latest version.
 *
 * <p>{@link #displayId} travels beside the name because two exams in a course can share a
 * name across terms, and the id is what the teacher sees on her own exam list.
 *
 * @param displayId the 6-digit exam id (S-10)
 * @param name      the latest version's name, which is the one her exam list shows
 */
public record ReferencingExam(String displayId, String name) {
}
