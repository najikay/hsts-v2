package server.db.projections;

import java.time.Instant;

/**
 * One exam in the school's catalogue, as the principal's data browser lists it (E15.2 — F9.3).
 *
 * <p>The sibling of {@link AuthoredExam} with two differences, and both of them come from who is
 * reading. The author's list needs no author column, because every row on it is hers; this one
 * carries the writer's display name, because "who wrote it" is half of what a school-wide
 * catalogue is for. And this one carries the latest version's number and date, because an exam
 * that has been rewritten four times is a different fact from one written once and never touched
 * (F2.3), and the principal has no other surface that tells her.
 *
 * <p>Not a widening of {@code AuthoredExam}: that projection is E14's, scoped by a {@code WHERE}
 * clause on {@code exams.author}, and adding an author name to it would put a column on a screen
 * where every row already has the same value in it.
 *
 * <p>Carries no questions, no answer key, no instructions and no approval status. It is the
 * drawer's index, not its contents, and the exclusions are argued on
 * {@code common.dto.report.DataExamRow}.
 *
 * @param displayId     the six-digit display id, subject(2) + course(2) + serial(2) (S-10)
 * @param courseCode    the two-character course code
 * @param courseName    the course's display name
 * @param examName      the name on the exam's most recent version
 * @param authorName    the display name of the teacher who wrote it
 * @param versions      the most recent version number, which is also how many versions exist
 * @param lastVersionAt when that version was written, UTC
 */
public record SchoolExam(String displayId,
                         String courseCode,
                         String courseName,
                         String examName,
                         String authorName,
                         int versions,
                         Instant lastVersionAt) {
}
