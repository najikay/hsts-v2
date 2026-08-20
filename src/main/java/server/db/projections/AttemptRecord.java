package server.db.projections;

import server.db.entities.AttemptStatus;

import java.time.Instant;

/**
 * One attempt as the take-exam service reasons about it (E10).
 *
 * <p>A read-only snapshot rather than the {@code ExamAttempt} entity, and that is the
 * point: the entity has no status setter <em>on purpose</em> (§5 — finalisation is a
 * status-guarded atomic UPDATE, never load-mutate-flush), and handing a managed entity to
 * a service is an invitation to reintroduce exactly the last-write-wins bug that guard
 * exists to prevent. A service holding one of these can only change an attempt by asking
 * for the compare-and-set.
 *
 * <p><b>No deadline field, deliberately.</b> The deadline is derived — started plus the
 * execution's allotted duration plus whatever extensions it has been granted — so an
 * extension that lands while a student is offline applies the moment she resumes, with
 * nothing to reschedule and nothing that can go stale (E11.4). Storing it would mean
 * having to remember to update every row on every extension, which is the kind of thing
 * that works until the one time it matters.
 *
 * @param attemptId     the attempt's id
 * @param executionId   which execution it belongs to
 * @param studentId     whose it is
 * @param startedAt     when the clock started (S-18)
 * @param endedAt       when it closed, or {@code null} while live
 * @param actualMinutes recorded solving time, or {@code null} while live (S-19)
 * @param status        IN_PROGRESS / SUBMITTED / TIMED_OUT
 */
public record AttemptRecord(long attemptId,
                            long executionId,
                            long studentId,
                            Instant startedAt,
                            Instant endedAt,
                            Integer actualMinutes,
                            AttemptStatus status) {

    /** @return {@code true} while answers may still be saved. */
    public boolean isInProgress() {
        return status == AttemptStatus.IN_PROGRESS;
    }

    /**
     * The derived deadline (ADR-010).
     *
     * @param allottedMinutes the execution's duration plus its extensions
     * @return when this attempt runs out
     */
    public Instant deadline(int allottedMinutes) {
        return startedAt.plusSeconds(Math.max(0, allottedMinutes) * 60L);
    }
}
