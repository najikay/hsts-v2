package server.features.approval;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The seam between the approval workflow and the database (Logic tier, E8 — ADR-002).
 *
 * <p>One method, handing out an {@link ApprovalData} for the length of one transaction. Every
 * rule in {@link ApprovalService} is written inside one:
 *
 * <pre>{@code
 * return store.inTx(data -> {
 *     ExamVersionContext version = data.versionContext(id).orElse(null);
 *     if (version == null) return notFound();
 *     Authorization.requireCoordinatorOf(caller, version.subjectCode(), data::coordinates);
 *     if (!version.isPending()) return alreadyDecided(version);
 *     if (version.lockVersion() != expected) return raced();
 *     data.versionForUpdate(id).orElseThrow().approve();
 *     data.flush();
 *     return decided(data.versionContext(id).orElseThrow());
 * });
 * }</pre>
 *
 * <p>The shape matters more here than almost anywhere else in the product, because the thing
 * being guarded is a two-value race: approve and reject both write the same column of the
 * same row, and the loser must be told rather than overwritten. Reading the status, deciding,
 * and writing in three separate transactions would leave a gap wide enough for a supersede to
 * land in the middle of.
 *
 * <p>It also gives every rule two homes to be tested in: a fast in-memory implementation for
 * the unit tests, and {@link JpaApprovalStore} driven against H2 and real MySQL for the
 * queries and the optimistic-lock behaviour.
 */
@FunctionalInterface
public interface ApprovalStore {

    /**
     * Runs one unit of work in one transaction and returns its result.
     *
     * @param work what to do; the {@link ApprovalData} it receives is valid only for this call
     * @param <T>  the result type
     * @return whatever the work returned
     */
    <T> T inTx(Function<ApprovalData, T> work);

    /**
     * Runs one unit of work in one transaction, discarding the result.
     *
     * @param work what to do
     */
    default void runInTx(Consumer<ApprovalData> work) {
        inTx(data -> {
            work.accept(data);
            return null;
        });
    }
}
