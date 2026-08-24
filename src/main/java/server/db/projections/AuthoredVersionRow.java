package server.db.projections;

import server.db.entities.ExamVersionStatus;

import java.time.Instant;

/**
 * One version of one exam, as the exam list expands it (E7.10 — C-2, F4.2).
 *
 * <p>Every version, drafts included. That is what distinguishes this list from the approval-status
 * list it replaces: {@code MY_APPROVALS_GET} showed non-draft versions only, because a coordinator
 * never sees a draft, whereas the author's own list would be missing the row she is working on.
 *
 * <p>{@code examId} is on the row so the service can group versions under their headers without a
 * second lookup. It is not on the wire record; grouping is what it is for.
 *
 * <p>{@code questionCount} is deliberately <b>not</b> here. It is one aggregate over a different
 * table, and {@code ExamRepository.countQuestionsByVersion} already answers it in a single grouped
 * read for a whole screenful. Adding a correlated count to this query would be a second expression
 * of one fact, and the first time the two disagreed the list would show a count no other screen
 * agreed with.
 *
 * <p>Carries no questions and no answer key.
 *
 * @param examId          the exam this version belongs to, for grouping
 * @param examVersionId   the {@code exam_versions} row
 * @param versionNo       its version number, 1-based
 * @param status          the entity-side status; the service maps it to the wire enum
 * @param rejectedReason  the coordinator's reason, or {@code null} when not rejected; the service
 *                        folds {@code null} to {@code ""} as the wire record requires
 * @param durationMinutes the sitting length
 * @param createdAt       when this version was created; the screen shows it, and it is <b>not</b>
 *                        the ordering key - the read orders by {@code versionNo} descending, which
 *                        stays right even if two versions are created out of clock order
 * @param lockVersion     the optimistic token the next write must send back
 */
public record AuthoredVersionRow(long examId,
                                 long examVersionId,
                                 int versionNo,
                                 ExamVersionStatus status,
                                 String rejectedReason,
                                 int durationMinutes,
                                 Instant createdAt,
                                 int lockVersion) {
}
