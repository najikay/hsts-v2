package server.features.release;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The seam between the release manager and the database (Logic tier, E9 — ADR-002).
 *
 * <p>The same one-method seam {@code ExamStore} is, and deliberately the same: E9 and E10
 * act on the same table from opposite ends, and two features that reached for their data in
 * two different shapes would be two places to look when a release behaves oddly.
 *
 * <p>One rule here really does need the transaction to be the unit a decision is written
 * inside. Code uniqueness has no constraint behind it (§5 makes it a service rule, because
 * MySQL has no partial unique index), so "this code is free" and "insert a release using it"
 * must be one transaction. Written any other way, two teachers pressing Create in the same
 * second are both told yes, and thirty students in one hall type a code that identifies two
 * exams.
 *
 * <p>It also gives every rule two homes to be tested in: a fast in-memory implementation for
 * the unit tests of the rules, and {@link JpaReleaseStore} driven against H2 and real MySQL
 * for the queries and the transitions.
 */
@FunctionalInterface
public interface ReleaseStore {

    /**
     * Runs one unit of work in one transaction and returns its result.
     *
     * @param work what to do; the {@link ReleaseData} it receives is valid only for this call
     * @param <T>  the result type
     * @return whatever the work returned
     */
    <T> T inTx(Function<ReleaseData, T> work);

    /**
     * Runs one unit of work in one transaction, discarding the result.
     *
     * @param work what to do
     */
    default void runInTx(Consumer<ReleaseData> work) {
        inTx(data -> {
            work.accept(data);
            return null;
        });
    }
}
