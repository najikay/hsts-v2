package server.db.projections;

import server.db.entities.AttemptStatus;
import server.db.entities.GradeStatus;

import java.time.Instant;

/**
 * One marked student in an execution's results table (E14.1 — F9.2, T-10).
 *
 * <p>A {@code grades} row joined to the attempt it belongs to and to the student's name, which
 * is the shape the teacher's table needs and which neither the grade entity nor
 * {@link AttemptRow} can supply alone. Ordered by name at the query, for the same reason the
 * live monitor is: a teacher looking one student up scans alphabetically.
 *
 * <p>One row per <b>grade</b>, so a student whose paper has not been marked is absent rather
 * than present with nulls. The screen states the gap explicitly by comparing this list against
 * the execution's participant count.
 *
 * <p>Carries no answers and no correctness data: which options a student chose belongs to the
 * grade-review path (E12.3) and its own gate, not to a results table.
 *
 * @param gradeId        the {@code grades} row
 * @param studentId      whose result this is
 * @param studentName    her display name, for the row
 * @param autoScore      what the machine computed, kept even after an override (S-23)
 * @param finalScore     the teacher's score, or {@code null} when nobody overrode it
 * @param status         AUTO or APPROVED, as stored
 * @param overrideReason why the teacher changed it, or {@code null}; teacher-path only
 * @param teacherComment the note written for the student, or {@code null}
 * @param approvedAt     when it was approved, UTC, or {@code null} while unapproved
 * @param actualMinutes  recorded solving time (S-19), or {@code null} when it was not recorded
 * @param attemptStatus  how the attempt ended — SUBMITTED when she handed in, TIMED_OUT when
 *                       the server did it for her at the bell (B-16, T-10.2)
 */
public record StudentResultRow(long gradeId,
                               long studentId,
                               String studentName,
                               int autoScore,
                               Integer finalScore,
                               GradeStatus status,
                               String overrideReason,
                               String teacherComment,
                               Instant approvedAt,
                               Integer actualMinutes,
                               AttemptStatus attemptStatus) {

    /**
     * @return the score that counts — the teacher's when she set one, the machine's otherwise.
     *         Computed once here so no screen re-derives it and gets the null check backwards
     */
    public int effectiveScore() {
        return finalScore != null ? finalScore : autoScore;
    }

    /** @return {@code true} when a teacher changed the machine's score (S-23). */
    public boolean wasAdjusted() {
        return finalScore != null && finalScore != autoScore;
    }
}
