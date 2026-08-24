package server.db.projections;

import server.db.entities.ExamVersionStatus;

import java.time.Instant;

/**
 * One exam version's metadata, without its questions (E7 — F3.1, F3.5).
 *
 * <p>Everything {@code common.dto.authoring.ExamComposition} carries except the composition
 * itself, which is a second read because it is a second cardinality: one header row, many
 * question rows. Joining them would repeat the header once per question and leave the caller
 * to de-duplicate it.
 *
 * <p><b>{@code authorId} is on here for the guard, not for the screen.</b> The exam-builder
 * contract's ruling 2 is author-only: a co-teacher of the same course may not open, edit or
 * submit another teacher's exam, and the refusal is {@code NOT_FOUND} rather than
 * {@code FORBIDDEN} so that "not yours" and "not there" are indistinguishable. That check needs
 * the stored author, so it is read here rather than trusted from the request. It is dropped when
 * the service maps into the wire record, which carries {@code authorName} and no id.
 *
 * <p>Carries no answer key and no questions at all: the header of the paper, not the paper.
 *
 * @param examId          the {@code exams} row
 * @param displayId6      subject(2) + course(2) + serial(2), S-10
 * @param courseCode      the two-character course code
 * @param courseName      the course's display name
 * @param authorId        the recorded author, for the author-only guard
 * @param authorName      the author's display name, which is what the screen shows
 * @param examVersionId   the {@code exam_versions} row
 * @param versionNo       its version number, 1-based
 * @param status          the entity-side status; the service maps it to the wire enum
 * @param name            the exam's name on this version
 * @param durationMinutes the sitting length
 * @param studentText     the student-facing block, or {@code null}
 * @param teacherText     the teacher-only block, or {@code null}
 * @param rejectedReason  the coordinator's reason, or {@code null} when not rejected; the
 *                        service folds {@code null} to {@code ""}, which is what the wire record
 *                        requires
 * @param createdAt       when this version was created
 * @param lockVersion     the optimistic token the next write must send back
 */
public record ExamCompositionHeader(long examId,
                                    String displayId6,
                                    String courseCode,
                                    String courseName,
                                    long authorId,
                                    String authorName,
                                    long examVersionId,
                                    int versionNo,
                                    ExamVersionStatus status,
                                    String name,
                                    int durationMinutes,
                                    String studentText,
                                    String teacherText,
                                    String rejectedReason,
                                    Instant createdAt,
                                    int lockVersion) {
}
