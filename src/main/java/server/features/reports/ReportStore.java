package server.features.reports;

import java.util.function.Function;

/**
 * The transactional seam the report engine reads through (Logic tier, E15.3).
 *
 * <p>Same shape as {@code TeacherResultsStore}: one method, which opens a transaction and hands
 * the unit of work a {@link ReportData} bound to it. The engine holds the rules and no session,
 * which is what lets every one of them be tested against an in-memory double and what keeps the
 * whole of a report - its subject label, its rows and its participant counts - read from one
 * consistent moment.
 */
@FunctionalInterface
public interface ReportStore {

    /**
     * Runs a unit of work inside one read transaction.
     *
     * @param work what to do with the transaction's reads
     * @param <T>  whatever the work produces
     * @return the work's result
     */
    <T> T inTx(Function<ReportData, T> work);
}
