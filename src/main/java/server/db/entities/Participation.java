package server.db.entities;

/**
 * How many students did what in one execution — the {@code participation} JSON column
 * of {@code exam_executions} (V4, §5, S-21).
 *
 * <p><b>These are not counters.</b> While an execution is live the same three numbers
 * are derived on demand by counting {@code exam_attempts} grouped by status, served by
 * the {@code ix_exam_attempts_execution_status} index. Nothing increments a column, so
 * nothing can drift or race on submit. This record exists only to freeze the final
 * values into the execution's documentation record when it closes (S-21).
 *
 * @param started   attempts opened
 * @param finished  attempts the student handed in
 * @param timedOut  attempts the server closed when time ran out
 */
public record Participation(int started, int finished, int timedOut) {
}
