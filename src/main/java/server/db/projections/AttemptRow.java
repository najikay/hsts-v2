package server.db.projections;

import server.db.entities.AttemptStatus;

import java.time.Instant;

/**
 * One line of the live execution monitor, straight from the database (E11.2 — F7.2).
 *
 * <p>{@link AttemptRecord} plus the student's name, which the monitor needs and the
 * take-exam path does not. Two projections rather than one optional field, because the
 * join to {@code users} is a cost the hot autosave path should not pay for a column it
 * never reads.
 *
 * <p>Carries no answers and no score. A teacher watching a live exam is entitled to know
 * how far along someone is, not what they picked.
 *
 * @param attemptId     the attempt
 * @param studentId     whose it is
 * @param studentName   her display name, for the row
 * @param startedAt     when she began
 * @param endedAt       when she finished, or {@code null} while she is working
 * @param actualMinutes recorded solving time once finished, else {@code null} (S-19)
 * @param status        IN_PROGRESS / SUBMITTED / TIMED_OUT
 */
public record AttemptRow(long attemptId,
                         long studentId,
                         String studentName,
                         Instant startedAt,
                         Instant endedAt,
                         Integer actualMinutes,
                         AttemptStatus status) {
}
