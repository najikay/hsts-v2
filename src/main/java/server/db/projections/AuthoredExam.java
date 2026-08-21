package server.db.projections;

/**
 * One exam a teacher wrote, as the results screen lists it (E14.1 — F9.2, S-35).
 *
 * <p>The identity row joined to its course and to the name of its most recent version. Three
 * tables, one read, because the list shows all three facts on every line and reading them
 * separately would be one query per exam on a screen that opens with the whole drawer.
 *
 * <p>The <b>name</b> deliberately comes from the latest version rather than from the version a
 * particular execution released: this record answers "which exams did she write", which is a
 * question about the exam, and an author who renamed her exam in v3 expects to find it under
 * that name. The per-execution answer carries the released version's name instead
 * ({@link ExecutionContext#examName()}), so a sitting is always labelled with what the
 * students actually saw.
 *
 * <p>Carries no questions and no answer key: this is the drawer's index, not its contents.
 *
 * @param examId     the {@code exams} row
 * @param displayId  the six-digit display id, subject(2) + course(2) + serial(2) (S-10)
 * @param courseCode the two-character course code
 * @param courseName the course's display name
 * @param examName   the name on the exam's most recent version
 */
public record AuthoredExam(long examId,
                           String displayId,
                           String courseCode,
                           String courseName,
                           String examName) {
}
