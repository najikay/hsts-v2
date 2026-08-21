package server.features.grading;

import common.dto.exam.AttemptState;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.db.MockSessions;
import server.db.entities.ExamAttempt;
import server.db.repos.AttemptRepository;
import server.features.exam.AttemptFinalizedListener;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GradingOnSubmit} — the wire that makes submission and marking one pipeline
 * (E10.4 → E12.1, F8.1).
 *
 * <p>Almost every test here is about failure, and that is the right proportion. The happy path
 * is two lines and the seam's contract is almost entirely about what must <em>not</em> happen:
 * this listener runs after a student has already been told their submission succeeded, so
 * anything it throws would be a failure attributed to a submission that actually worked.
 *
 * <p>{@code neverThrowsAtItsCaller} is the test that protects a room full of students from one
 * bad row.
 */
@ExtendWith(MockitoExtension.class)
class GradingOnSubmitTest {

    private static final long ATTEMPT_ID = 500;
    private static final long EXECUTION_ID = 4821;
    private static final long STUDENT_ID = 11;
    private static final long EXAM_VERSION_ID = 77;

    @Mock
    private Session session;
    @Mock
    private GradingService grading;
    @Mock
    private AttemptRepository attempts;

    private GradingOnSubmit listener;
    private MockSessions.Wiring wiring;

    @BeforeEach
    void setUp() {
        wiring = MockSessions.commitsOn(session);
        listener = new GradingOnSubmit(wiring.factory(), grading, attempts);
    }

    private static AttemptFinalizedListener.FinalizedAttempt finalized(AttemptState state) {
        return new AttemptFinalizedListener.FinalizedAttempt(ATTEMPT_ID, EXECUTION_ID,
                EXAM_VERSION_ID, STUDENT_ID, state, Instant.parse("2026-06-01T09:00:00Z"), 60);
    }

    @Test
    @DisplayName("grades the attempt that just closed, in its own transaction")
    void gradesOnSubmit() {
        ExamAttempt attempt = new ExamAttempt(EXECUTION_ID, STUDENT_ID,
                Instant.parse("2026-06-01T08:00:00Z"));
        when(attempts.findByExecutionAndStudent(session, EXECUTION_ID, STUDENT_ID))
                .thenReturn(Optional.of(attempt));

        listener.attemptFinalized(finalized(AttemptState.SUBMITTED));

        verify(grading).autoGrade(session, attempt);
        assertThat(wiring.tx().committed()).isTrue();
    }

    @Test
    @DisplayName("grades a timed-out attempt too — it was sat and failed, not absent (H12.4)")
    void gradesOnTimeout() {
        ExamAttempt attempt = new ExamAttempt(EXECUTION_ID, STUDENT_ID,
                Instant.parse("2026-06-01T08:00:00Z"));
        when(attempts.findByExecutionAndStudent(session, EXECUTION_ID, STUDENT_ID))
                .thenReturn(Optional.of(attempt));

        listener.attemptFinalized(finalized(AttemptState.TIMED_OUT));

        verify(grading).autoGrade(session, attempt);
    }

    @Test
    @DisplayName("does not grade when the attempt row cannot be found, and does not throw")
    void missingAttemptRow() {
        when(attempts.findByExecutionAndStudent(session, EXECUTION_ID, STUDENT_ID))
                .thenReturn(Optional.empty());

        assertThatCode(() -> listener.attemptFinalized(finalized(AttemptState.SUBMITTED)))
                .doesNotThrowAnyException();

        verify(grading, never()).autoGrade(any(), any());
        // The transaction still ended cleanly rather than being left open.
        assertThat(wiring.tx().committed()).isTrue();
    }

    @Test
    @DisplayName("never throws at its caller — a broken grader must not break handing in")
    void neverThrowsAtItsCaller() {
        when(attempts.findByExecutionAndStudent(session, EXECUTION_ID, STUDENT_ID))
                .thenReturn(Optional.of(new ExamAttempt(EXECUTION_ID, STUDENT_ID,
                        Instant.parse("2026-06-01T08:00:00Z"))));
        when(grading.autoGrade(any(), any()))
                .thenThrow(new IllegalStateException("exam version pins a question that is gone"));

        assertThatCode(() -> listener.attemptFinalized(finalized(AttemptState.SUBMITTED)))
                .doesNotThrowAnyException();

        // And the failed transaction rolled back rather than committing a half-written grade.
        assertThat(wiring.tx().rolledBack()).isTrue();
        assertThat(wiring.tx().committed()).isFalse();
    }

    @Test
    @DisplayName("swallows a failure in the lookup itself, not only in the grading")
    void swallowsLookupFailure() {
        when(attempts.findByExecutionAndStudent(session, EXECUTION_ID, STUDENT_ID))
                .thenThrow(new RuntimeException("connection reset"));

        assertThatCode(() -> listener.attemptFinalized(finalized(AttemptState.SUBMITTED)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ignores a null event rather than logging a spurious failure")
    void ignoresNull() {
        assertThatCode(() -> listener.attemptFinalized(null)).doesNotThrowAnyException();

        verify(grading, never()).autoGrade(any(), any());
    }

    @Test
    @DisplayName("publishes nothing to the student: grading produces AUTO, approval publishes")
    void gradingPublishesNothing() {
        ExamAttempt attempt = new ExamAttempt(EXECUTION_ID, STUDENT_ID,
                Instant.parse("2026-06-01T08:00:00Z"));
        when(attempts.findByExecutionAndStudent(session, EXECUTION_ID, STUDENT_ID))
                .thenReturn(Optional.of(attempt));

        listener.attemptFinalized(finalized(AttemptState.SUBMITTED));

        // The listener knows nothing that could notify anybody — no notifier, no gateway. That
        // is the C-3 guarantee expressed as a dependency list rather than as a rule.
        verify(grading).autoGrade(session, attempt);
        verify(attempts).findByExecutionAndStudent(session, EXECUTION_ID, STUDENT_ID);
        org.mockito.Mockito.verifyNoMoreInteractions(grading, attempts);
    }

    @Test
    @DisplayName("rejects null collaborators at construction")
    void rejectsNulls() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new GradingOnSubmit(null, grading, attempts));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new GradingOnSubmit(wiring.factory(), null, attempts));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new GradingOnSubmit(wiring.factory(), grading, null));
    }

    @Test
    @DisplayName("is what HSTSServer can put where the no-op was")
    void isAnAttemptFinalizedListener() {
        assertThat(listener).isInstanceOf(AttemptFinalizedListener.class);
        // The seam takes any implementation; the no-op is still there for tests that want it.
        assertThatCode(() -> AttemptFinalizedListener.NO_OP
                .attemptFinalized(finalized(AttemptState.SUBMITTED)))
                .doesNotThrowAnyException();
    }
}
