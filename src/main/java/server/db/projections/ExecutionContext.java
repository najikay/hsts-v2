package server.db.projections;

import server.db.entities.ExecutionStatus;

import java.time.Instant;

/**
 * Everything about one execution that a take-exam or monitoring decision needs (E10/E11).
 *
 * <p>Four tables' worth of facts in one row — the execution, its pinned exam version, the
 * exam identity behind it and the course's name — because every single one of E10's rules
 * needs several of them at once and reading them separately is how a service ends up
 * making a decision from a half-loaded picture. Joining once also means the whole
 * "can this student sit this?" question is answered from one consistent read.
 *
 * <p><b>Deliberately no questions and no answer key.</b> This is the metadata; the paper
 * comes from {@code QuestionRepository.findForTakeExam}, whose projection has nowhere to
 * put a correct answer (E2.12). Keeping them apart is what lets the join screen show a
 * student what she is about to sit without the paper existing on her machine yet.
 *
 * @param executionId        the execution's id
 * @param examVersionId      the pinned version being sat (S-14: approval binds to a version)
 * @param examId             the exam identity row behind that version
 * @param courseCode         the 2-character course code
 * @param courseName         the course's display name
 * @param examName           the exam's name, as the author wrote it
 * @param durationMinutes    the stored duration, <b>without</b> extensions; callers add
 *                           {@link #extraMinutes} themselves so the two are never confused
 * @param generalText        instructions for examinees (F3.1); nullable by schema decision
 * @param code               the 4-character entry code (C-1)
 * @param status             SCHEDULED / LIVE / CLOSED / CANCELLED
 * @param openAt             window opens (S-15)
 * @param closeAt            window closes, extensions <b>not</b> included
 * @param extraMinutes       minutes granted by extensions so far (S-20)
 * @param executingTeacherId who released it; the owner for E11's teacher verbs
 * @param authorId           who wrote the exam; also an owner for E11's teacher verbs (S-35)
 */
public record ExecutionContext(long executionId,
                               long examVersionId,
                               long examId,
                               String courseCode,
                               String courseName,
                               String examName,
                               int durationMinutes,
                               String generalText,
                               String code,
                               ExecutionStatus status,
                               Instant openAt,
                               Instant closeAt,
                               int extraMinutes,
                               long executingTeacherId,
                               long authorId) {

    /**
     * The same execution after an extension has been granted (F7.1, S-20).
     *
     * <p>Used by the extend path, which has just written the new total and needs the
     * context it is holding to agree with the database before it recomputes deadlines from
     * it. Re-reading would also work; copying is one fewer round trip on a path that is
     * already inside a transaction, and it cannot answer with a row somebody else changed
     * in between.
     *
     * @param newExtraMinutes the new total of granted minutes
     * @return a copy with that total
     */
    public ExecutionContext withExtraMinutes(int newExtraMinutes) {
        return new ExecutionContext(executionId, examVersionId, examId, courseCode, courseName,
                examName, durationMinutes, generalText, code, status, openAt, closeAt,
                newExtraMinutes, executingTeacherId, authorId);
    }

    /** @return the duration one student actually gets, extensions included (S-20). */
    public int allottedMinutes() {
        return durationMinutes + extraMinutes;
    }

    /** @return when the entry window closes, extensions included. */
    public Instant effectiveCloseAt() {
        return closeAt.plusSeconds(extraMinutes * 60L);
    }

    /** @return {@code true} when students may join right now (S-15). */
    public boolean isOpenAt(Instant now) {
        return status == ExecutionStatus.LIVE
                && !now.isBefore(openAt)
                && now.isBefore(effectiveCloseAt());
    }

    /**
     * @param userId a caller
     * @return {@code true} when this execution is theirs to extend or monitor — the
     *         teacher who released it, or the author of the exam being sat (S-35). Role is
     *         checked separately; this is the ownership half
     */
    public boolean isOwnedBy(long userId) {
        return executingTeacherId == userId || authorId == userId;
    }
}
