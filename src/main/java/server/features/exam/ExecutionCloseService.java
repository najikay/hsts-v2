package server.features.exam;

import common.dto.exam.MonitorCounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.projections.AttemptRecord;
import server.db.projections.ExecutionContext;
import server.db.projections.ParticipationCounts;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Closing an execution and writing its documentation record (Logic tier, E11.5 — S-21,
 * F7.3, F5.5).
 *
 * <p>Two jobs that have to happen together and in this order.
 *
 * <h2>1. Everybody still working is handed in</h2>
 *
 * <p>F5.5 says closing early "behaves like time expiry for active students", and that is
 * exactly what happens: each live attempt goes through the same force-submit as an expiry,
 * so a student who was mid-question ends {@code TIMED_OUT} with the answers she had saved
 * and the same push she would have got from her own timer. Reusing the expiry path rather
 * than writing a second one is the point — a close that ended attempts a slightly different
 * way would produce rows the grader treats differently for no reason anyone could name.
 *
 * <h2>2. The counts stop being derived</h2>
 *
 * <p>While an execution runs, started / finished / timed-out are a {@code COUNT} over
 * attempts, because §5 forbids counter columns and mutable counters race on exactly the
 * event this system has most of. At close they are written once into the
 * {@code participation} JSON, and from then on every reader — the execution history, the
 * report engine — sees the same three numbers forever, even if attempts are later archived.
 *
 * <p>Order matters: freezing before the force-submits would record students as still in
 * progress in a record that is supposed to be final.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>The score half of S-21's record — average, median, σ, deciles — is E12's, computed when
 * the last grade is approved and written to the {@code stats} column. This service freezes
 * what exists at close: the counts, and, in columns that were already stored, the date and
 * the actually-allotted duration including extensions. A close that tried to compute scores
 * would be computing them from grades that do not exist yet.
 *
 * <p>The verb that calls this belongs to E9's release manager; this is the seam it uses, so
 * neither epic has to know how the other decides anything.
 */
public final class ExecutionCloseService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionCloseService.class);

    private final ExamStore store;
    private final AttemptService attempts;
    private final MonitorPublisher monitor;

    /**
     * @param store    the transactional data seam
     * @param attempts the take-exam service, whose expiry path force-submits stragglers
     * @param monitor  where "this execution changed" goes; watchers see it close live
     */
    public ExecutionCloseService(ExamStore store, AttemptService attempts, MonitorPublisher monitor) {
        this.store = Objects.requireNonNull(store, "store");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.monitor = monitor == null ? MonitorPublisher.NO_OP : monitor;
    }

    /**
     * Closes one execution: hands in whoever is still working, then freezes the counts.
     *
     * <p>Idempotent. Closing an execution that is already closed force-submits nobody
     * (there is nothing in progress) and rewrites the same three numbers, so a double
     * click on the release screen and a retry after a dropped connection both do the right
     * thing.
     *
     * @param executionId the execution to close
     * @return the frozen counts, or empty when there is no such execution
     */
    public Optional<MonitorCounts> close(long executionId) {
        List<Long> stragglers = store.inTx(data -> {
            Optional<ExecutionContext> found = data.executionById(executionId);
            if (found.isEmpty()) {
                return null;
            }
            List<Long> live = new ArrayList<>();
            for (AttemptRecord attempt : data.liveAttemptsOf(executionId)) {
                live.add(attempt.attemptId());
            }
            return live;
        });
        if (stragglers == null) {
            log.warn("Asked to close execution {}, which does not exist", executionId);
            return Optional.empty();
        }

        // Outside the read transaction: each force-submit is its own transaction, its own
        // compare-and-set and its own push, exactly as a timer expiry is. A student whose
        // own submit lands in the middle of this simply wins her own race.
        for (Long attemptId : stragglers) {
            attempts.closeEarly(attemptId);
        }

        ParticipationCounts counts = store.inTx(data -> {
            ParticipationCounts frozen = data.participationOf(executionId);
            data.closeExecution(executionId, frozen);
            return frozen;
        });
        log.info("Execution {} closed: {} started, {} handed in, {} timed out ({} force-submitted now)",
                executionId, counts.started(), counts.finished(), counts.timedOut(), stragglers.size());
        monitor.executionChanged(executionId);
        return Optional.of(new MonitorCounts(counts.started(), counts.finished(), counts.timedOut()));
    }
}
