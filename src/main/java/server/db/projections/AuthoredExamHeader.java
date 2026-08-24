package server.db.projections;

/**
 * One exam on its author's exam list, without its versions (E7.10 — F3.5).
 *
 * <p>The header half of {@code EXAM_LIST}. Its versions are a second read for the same reason
 * {@link ExamCompositionHeader} splits from {@link PinnedQuestion}: one exam has many versions,
 * and one joined query would repeat every header once per version.
 *
 * <p>Deliberately <b>not</b> {@link AuthoredExam}, which E14.1's results screen uses. That record
 * carries the latest version's name but not its number, and this screen needs the number: the
 * list groups versions under their exam and has to know which one is current. Widening
 * {@code AuthoredExam} would change a frozen results read to serve a screen it does not serve,
 * and the two questions are genuinely different — E14 asks "which exams did she write", E7 asks
 * "what is the state of each version of each of them".
 *
 * <p>Carries no questions and no answer key.
 *
 * @param examId          the {@code exams} row
 * @param displayId6      subject(2) + course(2) + serial(2), S-10
 * @param courseCode      the two-character course code
 * @param courseName      the course's display name
 * @param name            the name on the exam's highest version, which is what the list shows
 * @param latestVersionNo that version's number
 */
public record AuthoredExamHeader(long examId,
                                 String displayId6,
                                 String courseCode,
                                 String courseName,
                                 String name,
                                 int latestVersionNo) {
}
