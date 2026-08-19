package server.db.entities;

/**
 * State of one student's sitting — {@code exam_attempts.status} (V4, §5, F6).
 *
 * <p>The live participation counts of S-21 are a {@code COUNT} over this column while
 * the execution runs; there are deliberately no counter columns to drift out of step
 * (ADR-011 discussion, §5).
 *
 * <p>Transitions out of {@link #IN_PROGRESS} are racy by nature — the server's time-up
 * force-submit (ADR-010, F6) can fire at the same moment the student presses submit.
 * The lead settled this in the E2 PR 1 review: a status-guarded atomic
 * {@code UPDATE … WHERE status = 'IN_PROGRESS'} decides the winner, rather than an
 * optimistic-lock column. Whichever update changes a row wins; the other sees zero
 * rows affected and stands down.
 */
public enum AttemptStatus {

    /** Started, still inside the window; answers may be saved. */
    IN_PROGRESS,

    /** Handed in by the student (F6.9). */
    SUBMITTED,

    /** Closed by the server when time ran out — with or without a client present (F6.4). */
    TIMED_OUT
}
