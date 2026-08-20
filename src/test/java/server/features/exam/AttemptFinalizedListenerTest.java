package server.features.exam;

import common.dto.exam.AttemptState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The seam E12's grading service will register on (E10.4 → E12).
 *
 * <p>Small, and tested anyway, because it is a promise made to another epic written by
 * another person: the default has to be safe, the composite has to isolate a broken
 * listener, and the record has to carry the pinned exam version, which is what §6's
 * "auto-grading always checks against the PINNED question version" depends on.
 */
class AttemptFinalizedListenerTest {

    private static final Instant ENDED = Instant.parse("2026-08-20T09:45:00Z");

    private static final AttemptFinalizedListener.FinalizedAttempt SUBMITTED =
            new AttemptFinalizedListener.FinalizedAttempt(42, 5001, 7001, 2001,
                    AttemptState.SUBMITTED, ENDED, 45);

    @Test
    @DisplayName("the no-op default accepts an attempt and does not throw")
    void noOpIsSafe() {
        // A server with no grader wired still has to be able to finish an exam.
        assertThatCode(() -> AttemptFinalizedListener.NO_OP.attemptFinalized(SUBMITTED))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a composite calls every listener, in order")
    void compositeCallsAll() {
        List<String> heard = new ArrayList<>();

        AttemptFinalizedListener.composite(
                        attempt -> heard.add("first:" + attempt.attemptId()),
                        attempt -> heard.add("second:" + attempt.attemptId()))
                .attemptFinalized(SUBMITTED);

        assertThat(heard).containsExactly("first:42", "second:42");
    }

    @Test
    @DisplayName("one broken listener does not stop the others ⚑")
    void compositeIsolatesFailures() {
        List<String> heard = new ArrayList<>();

        AttemptFinalizedListener.composite(
                        attempt -> {
                            throw new IllegalStateException("grader is down");
                        },
                        attempt -> heard.add("still ran"))
                .attemptFinalized(SUBMITTED);

        // A broken grader must never turn a successful submission into a failure at the
        // student, which is why the call site logs rather than propagates.
        assertThat(heard).containsExactly("still ran");
    }

    @Test
    @DisplayName("a composite of nothing is a working no-op")
    void emptyComposite() {
        assertThatCode(() -> AttemptFinalizedListener.composite().attemptFinalized(SUBMITTED))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a null listener array is rejected at the boundary")
    void nullListenersRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> AttemptFinalizedListener.composite((AttemptFinalizedListener[]) null));
    }

    @Test
    @DisplayName("the record carries the pinned exam version E12 has to mark against (§6)")
    void carriesThePinnedVersion() {
        assertThat(SUBMITTED.examVersionId()).isEqualTo(7001);
        assertThat(SUBMITTED.executionId()).isEqualTo(5001);
        assertThat(SUBMITTED.studentId()).isEqualTo(2001);
        assertThat(SUBMITTED.actualMinutes()).isEqualTo(45);
        assertThat(SUBMITTED.endedAt()).isEqualTo(ENDED);
    }

    @Test
    @DisplayName("a forced submit arrives the same way as a voluntary one")
    void forcedAndVoluntaryLookAlike() {
        AttemptFinalizedListener.FinalizedAttempt forced =
                new AttemptFinalizedListener.FinalizedAttempt(43, 5001, 7001, 2002,
                        AttemptState.TIMED_OUT, ENDED, 45);

        // Grading marks unanswered questions zero either way (§6), so a forced submit is
        // not a special case for the implementer.
        assertThat(forced.state()).isEqualTo(AttemptState.TIMED_OUT);
        assertThat(forced.state().isFinished()).isTrue();
        assertThat(SUBMITTED.state().isFinished()).isTrue();
    }
}
