package server.features.grading;

import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.db.entities.AttemptStatus;
import server.db.entities.ExamAttempt;
import server.db.entities.Grade;
import server.db.entities.GradeStatus;
import server.db.repos.GradeRepository;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GradingService} — E12.1's persistence half.
 *
 * <p>The scoring rules are {@link AutoGrader}'s and are proven in {@code AutoGraderTest}; what
 * is proven here is everything around them: which attempts are gradeable, that grading twice
 * does not overwrite a grade a teacher may already have overridden, and that a timed-out
 * attempt is graded rather than skipped.
 *
 * <p>Reads come through the {@link GradingReads} port, mocked here — TEAM_SPLIT §3.2's
 * "services against mocked repositories". The real adapter is one class whose method naming
 * waits on the correctness-suffix decision; none of the behaviour below depends on it.
 */
@ExtendWith(MockitoExtension.class)
class GradingServiceTest {

    private static final long EXECUTION_ID = 4821;
    private static final long EXAM_VERSION_ID = 1011;
    private static final long ATTEMPT_ID = 77;
    private static final long STUDENT_ID = 11;

    /** Two questions worth 60 and 40 — a 100-point exam small enough to reason about. */
    private static final List<AutoGrader.PinnedQuestion> PINNED = List.of(
            new AutoGrader.PinnedQuestion(501, 60, (byte) 2),
            new AutoGrader.PinnedQuestion(502, 40, (byte) 3));

    @Mock
    private Session session;
    @Mock
    private GradingReads reads;
    @Mock
    private GradeRepository grades;

    private GradingService service;

    @BeforeEach
    void setUp() {
        service = new GradingService(reads, grades);
    }

    private static ExamAttempt attempt(AttemptStatus status) {
        ExamAttempt attempt = new ExamAttempt(EXECUTION_ID, STUDENT_ID, Instant.now());
        setId(attempt, ATTEMPT_ID);
        setStatus(attempt, status);
        return attempt;
    }

    private void stubReads(Map<Long, Byte> selected) {
        when(reads.examVersionOf(session, EXECUTION_ID)).thenReturn(EXAM_VERSION_ID);
        when(reads.pinnedQuestions(session, EXAM_VERSION_ID)).thenReturn(PINNED);
        when(reads.selectedAnswers(session, ATTEMPT_ID)).thenReturn(selected);
    }

    @Test
    @DisplayName("a submitted attempt is scored and persisted as an AUTO grade")
    void gradesASubmittedAttempt() {
        when(grades.findByAttempt(session, ATTEMPT_ID)).thenReturn(Optional.empty());
        stubReads(Map.of(501L, (byte) 2, 502L, (byte) 1));   // first right, second wrong

        Grade grade = service.autoGrade(session, attempt(AttemptStatus.SUBMITTED));

        assertThat(grade.getAutoScore()).isEqualTo(60);
        assertThat(grade.getAttemptId()).isEqualTo(ATTEMPT_ID);
        assertThat(grade.getStatus()).isEqualTo(GradeStatus.AUTO);
        assertThat(grade.getFinalScore()).isNull();
        verify(session).persist(grade);
    }

    @Test
    @DisplayName("H12.4 — a timed-out attempt with nothing answered is graded 0, not skipped")
    void gradesATimedOutAttemptWithNoAnswers() {
        when(grades.findByAttempt(session, ATTEMPT_ID)).thenReturn(Optional.empty());
        stubReads(Map.of());

        Grade grade = service.autoGrade(session, attempt(AttemptStatus.TIMED_OUT));

        assertThat(grade.getAutoScore()).isZero();
        assertThat(grade.getStatus()).isEqualTo(GradeStatus.AUTO);
        verify(session).persist(grade);
    }

    @Test
    @DisplayName("H12.7 — an attempt still in progress cannot be graded")
    void refusesAnAttemptInProgress() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.autoGrade(session, attempt(AttemptStatus.IN_PROGRESS)))
                .withMessageContaining("still in progress");

        verify(session, never()).persist(any());
    }

    @Test
    @DisplayName("re-grading returns the existing grade and never overwrites it")
    void gradingIsIdempotent() {
        Grade existing = new Grade(ATTEMPT_ID, 42);
        when(grades.findByAttempt(session, ATTEMPT_ID)).thenReturn(Optional.of(existing));

        Grade grade = service.autoGrade(session, attempt(AttemptStatus.SUBMITTED));

        assertThat(grade).isSameAs(existing);
        assertThat(grade.getAutoScore()).isEqualTo(42);
        verify(session, never()).persist(any());
        // Nothing was even read: re-grading must not depend on the exam still being loadable.
        verify(reads, never()).pinnedQuestions(any(), anyLong());
    }

    @Test
    @DisplayName("an overridden grade survives a re-grade — the justification is not stranded")
    void reGradingDoesNotDiscardAnOverride() {
        Grade overridden = new Grade(ATTEMPT_ID, 51);
        overridden.override(55, "partial credit on Q7");
        when(grades.findByAttempt(session, ATTEMPT_ID)).thenReturn(Optional.of(overridden));

        Grade grade = service.autoGrade(session, attempt(AttemptStatus.SUBMITTED));

        assertThat(grade.getEffectiveScore()).isEqualTo(55);
        assertThat(grade.getOverrideReason()).isEqualTo("partial credit on Q7");
        verify(session, never()).persist(any());
    }

    @Test
    @DisplayName("an unpersisted attempt is a caller bug, not a data condition")
    void refusesAnAttemptWithNoId() {
        ExamAttempt unsaved = new ExamAttempt(EXECUTION_ID, STUDENT_ID, Instant.now());
        setStatus(unsaved, AttemptStatus.SUBMITTED);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.autoGrade(session, unsaved))
                .withMessageContaining("not been persisted");
    }

    @Test
    @DisplayName("the pinned questions are read for the execution's exam version, not the latest")
    void readsThePinnedExamVersion() {
        when(grades.findByAttempt(session, ATTEMPT_ID)).thenReturn(Optional.empty());
        stubReads(Map.of(501L, (byte) 2, 502L, (byte) 3));

        service.autoGrade(session, attempt(AttemptStatus.SUBMITTED));

        verify(reads).examVersionOf(session, EXECUTION_ID);
        verify(reads).pinnedQuestions(session, EXAM_VERSION_ID);
    }

    // ===== helpers ========================================================
    // ExamAttempt's id and status are managed by Hibernate; the entity exposes no setters for
    // them, so the fixtures set them reflectively rather than the production code growing
    // test-only mutators.

    private static void setId(ExamAttempt attempt, long id) {
        set(attempt, "id", id);
    }

    private static void setStatus(ExamAttempt attempt, AttemptStatus status) {
        set(attempt, "status", status);
    }

    private static void set(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set " + fieldName, e);
        }
    }
}
