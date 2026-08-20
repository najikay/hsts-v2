package server.features.exam;

/**
 * "Something about this execution changed; whoever is watching should be told" (Logic
 * tier, E11.2 — F7.2).
 *
 * <p>The one-way seam between take-exam and the teacher's live monitor. Every event a
 * monitor cares about — a student starting, an answer landing, a submit, an expiry, an
 * integrity flag — happens inside {@link AttemptService}, and every one of them ends with a
 * call to this. What the monitor then does (rebuild the snapshot, find its watchers, push)
 * is {@link MonitorService}'s business and none of take-exam's.
 *
 * <p>It is one method taking one id, rather than a rich event, on purpose: the monitor
 * answers with a whole snapshot rather than a delta (see
 * {@code common.dto.exam.ExecutionMonitor}), so a description of <em>what</em> changed
 * would be information nobody uses and one more thing to keep truthful.
 *
 * <p>The direction matters. Take-exam depends on this interface and never on the monitor;
 * the monitor depends on take-exam's data and never the other way. Without that, the two
 * services could not be constructed at all without one of them being half-built.
 */
@FunctionalInterface
public interface MonitorPublisher {

    /**
     * Announces that a watched execution's state has moved.
     *
     * <p>Must not throw and must not block for long: it is called from inside request
     * handling and from the timer thread, and a monitor that is slow or broken must not be
     * able to delay a student's submit or stop an expiry.
     *
     * @param executionId the execution that changed
     */
    void executionChanged(long executionId);

    /**
     * The default when no monitor is wired: does nothing.
     *
     * <p>Silent rather than logged, unlike {@link AttemptFinalizedListener#NO_OP}: this one
     * fires on every autosave, and a log line per keystroke would bury the log that matters
     * during a demo.
     */
    MonitorPublisher NO_OP = executionId -> {
    };
}
