package server.db.projections;

import server.db.entities.ExamVersionStatus;

import java.time.Instant;

/**
 * Everything about one exam version that an approval decision needs (E8, over E2.11).
 *
 * <p>Four tables' worth of facts in one row — the version, the exam identity behind it, the
 * course it belongs to and the author's name — for the reason {@link ExecutionContext}
 * joins its four: every rule in E8 needs several of them at once ("is it pending, do I
 * coordinate its subject, did I write it, what is it called"), and reading them in four
 * calls would let a service decide from a picture that changed halfway through.
 *
 * <p>{@link #subjectCode} is carried because it is the whole scoping question. A coordinator
 * owns a <em>subject</em>, an exam belongs to a <em>course</em>, and the link between them is
 * {@code courses.subject_code}; resolving it in the join means no service has to remember
 * that a course code is not a subject code.
 *
 * <p>{@link #lockVersion} is carried because the two decisions are compare-and-sets. The
 * value the coordinator's screen was rendered from travels out on the wire and back in with
 * her decision, and the write is refused if it no longer matches (ARCHITECTURE §5).
 *
 * <p><b>Deliberately no questions and no answer key.</b> This is the metadata; the paper
 * comes from {@code QuestionRepository.findForTakeExam} — the same no-correctness projection
 * a student is served from — and the key comes separately from
 * {@code QuestionRepository.findAnswerKeyForAuthoring}. Keeping the three apart is E2.12,
 * and it is what lets the approval queue list twenty exams without loading a single
 * illustration or a single correct answer.
 *
 * @param examVersionId   the version this row is about
 * @param examId          the exam identity row behind it
 * @param examDisplayId   the 6-digit id people quote (S-10)
 * @param examName        the exam's name, as its author wrote it
 * @param versionNo       the 1-based version number; approval binds to a version (S-14)
 * @param durationMinutes the stored duration
 * @param studentText     instructions for examinees (F3.1); nullable by schema decision
 * @param teacherText     instructions for staff (F3.1); nullable by schema decision
 * @param status          DRAFT / PENDING / APPROVED / REJECTED
 * @param rejectedReason  why it was sent back; {@code null} unless {@link #status} is
 *                        {@link ExamVersionStatus#REJECTED}
 * @param createdAt       when this version was created, UTC
 * @param lockVersion     the optimistic-locking value this row was read at
 * @param courseCode      the 2-character course code
 * @param courseName      the course's display name
 * @param subjectCode     the 2-character subject code the course belongs to; the scoping key
 * @param authorId        who wrote the exam; the F4.3 self-approval check compares to this
 * @param authorName      the author's display name, for the queue row and the notification
 */
public record ExamVersionContext(long examVersionId,
                                 long examId,
                                 String examDisplayId,
                                 String examName,
                                 int versionNo,
                                 int durationMinutes,
                                 String studentText,
                                 String teacherText,
                                 ExamVersionStatus status,
                                 String rejectedReason,
                                 Instant createdAt,
                                 int lockVersion,
                                 String courseCode,
                                 String courseName,
                                 String subjectCode,
                                 long authorId,
                                 String authorName) {

    /** @return {@code true} when this version is waiting on a coordinator (S-14). */
    public boolean isPending() {
        return status == ExamVersionStatus.PENDING;
    }

    /**
     * @param userId the caller
     * @return whether that caller wrote the exam this version belongs to — the F4.3 case,
     *         which is allowed and logged rather than refused
     */
    public boolean isAuthoredBy(long userId) {
        return authorId == userId;
    }
}
