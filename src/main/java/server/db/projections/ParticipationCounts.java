package server.db.projections;

/**
 * Live participation for an execution, counted from {@code exam_attempts} (S-21).
 *
 * <p>§5 forbids counter columns on {@code exam_executions}: "participation counts are
 * DERIVED from exam_attempts (COUNT by status) while live — no mutable counters, no
 * increment races — and frozen into stats JSON at close". This record is that derivation.
 *
 * @param started   attempts that exist at all
 * @param finished  attempts the student submitted
 * @param timedOut  attempts the server force-submitted on expiry
 */
public record ParticipationCounts(long started, long finished, long timedOut) {
}
