package server.features.exam;

import common.dto.exam.AttemptState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * <b>The integration point between take-exam and grading</b> (Logic tier, E10.4 → E12).
 *
 * <p>F8.1 says auto-checking happens "on submission". E10 is where submission happens and
 * E12 is where checking happens, and they are written by different people at different
 * times — so the join is this interface, declared now, with a documented no-op default
 * wired in {@code HSTSServer}. E10 ships complete and the grader is simply not there yet;
 * E12 registers its {@code GradingService} here and nothing in take-exam changes.
 *
 * <h2>How E12 uses it</h2>
 *
 * <pre>{@code
 * // 1. implement it
 * class GradingService implements AttemptFinalizedListener {
 *     public void attemptFinalized(FinalizedAttempt attempt) { autoCheck(attempt.attemptId()); }
 * }
 *
 * // 2. wire it where the server is assembled, in place of the no-op
 * AttemptService attempts = new AttemptService(..., gradingService, ...);
 * }</pre>
 *
 * <h2>Two rules the implementer needs to know</h2>
 *
 * <ul>
 *   <li><b>It is called exactly once per attempt.</b> The call sits behind the
 *       compare-and-set that decides the submit-versus-expiry race (§5), so only the
 *       winner reaches here. A timed-out attempt and a submitted one both arrive; grading
 *       marks unanswered questions as zero either way (§6), which is why a forced submit is
 *       not a special case.</li>
 *   <li><b>It is called after the transaction that closed the attempt has committed</b>,
 *       not inside it. Auto-grading reads the answers and writes a grade, and joining it to
 *       the finalisation would mean a slow or failing grader could roll back a submission
 *       a student has already been told succeeded. A grade that has not been computed yet
 *       is recoverable; a submission that silently vanished is not.</li>
 * </ul>
 */
@FunctionalInterface
public interface AttemptFinalizedListener {

    Logger LOG = LoggerFactory.getLogger(AttemptFinalizedListener.class);

    /**
     * What an implementer is told about a closed attempt.
     *
     * <p>Enough to grade it without a second lookup of the things that are awkward to find
     * from an attempt id alone — which exam version was pinned, and therefore which
     * question versions and which points the marking must use (§6: "auto-grading always
     * checks against the exam's PINNED question version, never the latest").
     *
     * @param attemptId     the attempt that just closed
     * @param executionId   the execution it belongs to
     * @param examVersionId the pinned exam version it was sat against
     * @param studentId     whose it is
     * @param state         SUBMITTED (she handed in) or TIMED_OUT (the server did)
     * @param endedAt       when it closed
     * @param actualMinutes recorded solving time (S-19)
     */
    record FinalizedAttempt(long attemptId,
                            long executionId,
                            long examVersionId,
                            long studentId,
                            AttemptState state,
                            Instant endedAt,
                            int actualMinutes) {
    }

    /**
     * An attempt has been closed and is ready to be marked.
     *
     * <p>Must not throw at its caller in a way that matters: take-exam wraps this call and
     * logs anything that escapes, because a grader that is broken must not stop students
     * from handing in.
     *
     * @param attempt what just closed
     */
    void attemptFinalized(FinalizedAttempt attempt);

    /**
     * The default until E12 lands: records the event and does nothing else.
     *
     * <p>Deliberately not a silent lambda. The line in the log is what tells whoever is
     * watching a demo that the submission arrived and that grading is simply not wired yet
     * — which is a different situation from grading being wired and broken, and the two
     * would otherwise look identical.
     */
    AttemptFinalizedListener NO_OP = attempt ->
            LOG.info("Attempt {} finalised as {} ({} min); no grader registered yet (E12)",
                    attempt.attemptId(), attempt.state(), attempt.actualMinutes());

    /**
     * @param listeners the listeners to call, in order
     * @return one listener that calls them all, isolating each from the others' failures
     */
    static AttemptFinalizedListener composite(AttemptFinalizedListener... listeners) {
        List<AttemptFinalizedListener> all = List.of(Objects.requireNonNull(listeners, "listeners"));
        return attempt -> {
            for (AttemptFinalizedListener listener : all) {
                try {
                    listener.attemptFinalized(attempt);
                } catch (RuntimeException e) {
                    LOG.error("Listener {} failed on attempt {}",
                            listener.getClass().getName(), attempt.attemptId(), e);
                }
            }
        };
    }
}
