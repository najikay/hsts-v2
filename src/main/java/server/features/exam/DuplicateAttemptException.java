package server.features.exam;

/**
 * Two starts for the same student and execution reached the database (E10.1 — F6.7).
 *
 * <p>Thrown when {@code UNIQUE(execution_id, student_id)} refuses the second insert. It is
 * not an error at the student: it means she double-clicked, or her client retried, and the
 * right answer is the attempt that <em>did</em> get created.
 *
 * <h2>Why an exception and not an empty {@code Optional}</h2>
 *
 * <p>Because of what a constraint violation does to the transaction it happens in. Once
 * Hibernate has flushed a failed insert the session is poisoned: it is marked for rollback,
 * and any further read or write in it is undefined at best. Returning "empty" and carrying
 * on inside the same transaction would look like it worked and would eventually commit
 * something nobody intended.
 *
 * <p>So the violation ends its transaction, properly rolled back, and
 * {@code AttemptService.start} catches this and opens a <b>second, clean</b> transaction to
 * read the attempt that won. The student sees the resumable state of her own attempt, which
 * is exactly what F6.7 asks for and is indistinguishable from having clicked once.
 */
public class DuplicateAttemptException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long executionId;
    private final long studentId;

    /**
     * @param executionId the execution
     * @param studentId   the student who already has an attempt at it
     * @param cause       the constraint violation underneath
     */
    public DuplicateAttemptException(long executionId, long studentId, Throwable cause) {
        super("Student " + studentId + " already has an attempt at execution " + executionId, cause);
        this.executionId = executionId;
        this.studentId = studentId;
    }

    /** @return the execution whose unique key refused the insert. */
    public long executionId() {
        return executionId;
    }

    /** @return the student who already had an attempt. */
    public long studentId() {
        return studentId;
    }
}
