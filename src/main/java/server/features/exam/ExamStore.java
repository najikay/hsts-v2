package server.features.exam;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The seam between take-exam and the database (Logic tier, E10 — ADR-002).
 *
 * <p>One method, and it hands out an {@link ExamData} for the length of one transaction.
 * Services phrase every rule as "in one transaction, read the current truth and act on
 * it":
 *
 * <pre>{@code
 * return store.inTx(data -> {
 *     AttemptRecord attempt = data.attemptById(attemptId).orElse(null);
 *     if (attempt == null || attempt.studentId() != caller) return notFound();
 *     if (!attempt.isInProgress()) return rejected(attempt);
 *     if (!clock.instant().isBefore(deadlineOf(attempt, data))) return expired();
 *     data.upsertAnswer(...);
 *     return saved(...);
 * });
 * }</pre>
 *
 * <p>That shape is the whole reason this interface exists rather than the services taking
 * repositories directly. The v1 failure that this epic is a rewrite of was a client that
 * decided things the server should have; the subtler version of the same bug is a server
 * that reads state, thinks, and then writes against a world that has moved. Making the
 * transaction the unit a rule is written inside removes the gap.
 *
 * <p>It also gives every rule two homes to be tested in: a fast in-memory implementation
 * for the unit tests, and {@link JpaExamStore} driven against H2 and real MySQL for the
 * queries and the races.
 */
@FunctionalInterface
public interface ExamStore {

    /**
     * Runs one unit of work in one transaction and returns its result.
     *
     * @param work what to do; the {@link ExamData} it receives is valid only for this call
     * @param <T>  the result type
     * @return whatever the work returned
     */
    <T> T inTx(Function<ExamData, T> work);

    /**
     * Runs one unit of work in one transaction, discarding the result.
     *
     * @param work what to do
     */
    default void runInTx(Consumer<ExamData> work) {
        inTx(data -> {
            work.accept(data);
            return null;
        });
    }
}
