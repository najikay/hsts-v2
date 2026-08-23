package server.features.release;

/**
 * "This release changed, tell whoever is looking at it" (Logic tier, E9.2 — F5.4).
 *
 * <p>One method, and it exists so the scheduled check does not have to know how a release
 * row is assembled or who the recipients are. {@link ReleaseScheduler} decides <em>when</em>
 * a release opens and closes; {@link ReleaseService} decides what a row looks like and whose
 * screen it belongs on. Neither has to hold the other whole.
 *
 * <p>The same shape as {@code MonitorPublisher} in the take-exam feature, and for the same
 * reason: it is the one edge that runs backwards, from the thing that changes state to the
 * thing that renders it, and a functional interface keeps it from turning into a
 * constructor cycle.
 *
 * <p>Implementations must not throw. Callers are the timer thread and the middle of a verb,
 * and neither may fail because a screen could not be repainted.
 */
@FunctionalInterface
public interface ReleaseAnnouncer {

    /** Does nothing, for tests and for a server assembled without a push channel. */
    ReleaseAnnouncer NO_OP = executionId -> { };

    /**
     * Rebuilds this release's row and pushes it to its owners.
     *
     * @param executionId the release that changed
     */
    void executionChanged(long executionId);
}
