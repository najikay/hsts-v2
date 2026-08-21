package server.features.grading;

import common.dto.grading.GradeOverrideRequest;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.db.entities.AttemptStatus;
import server.db.entities.ExecutionStatus;
import server.db.entities.Grade;
import server.db.projections.AttemptRecord;
import server.db.projections.ExecutionContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OverrideService} — moving a score by hand (E12.3 — F8.3, S-23).
 *
 * <p>Three rules, and the tests are arranged so each one fails for a different reason if it is
 * broken. The one that matters most for the audit trail is
 * {@code keepsTheAutoScoreAlongsideTheNewOne}: an implementation that assigned the new score to
 * {@code autoScore} would pass every other test here and would quietly destroy the evidence
 * that a mark was ever changed.
 *
 * <p>{@code notFoundAndNotMineAreTheSameAnswer} is asserted by comparing the two outcomes to
 * each other rather than by checking both are refusals, because "both refuse" is also true of
 * an implementation that refuses them with two distinguishable answers.
 */
@ExtendWith(MockitoExtension.class)
class OverrideServiceTest {

    private static final long GRADE_ID = 900;
    private static final long ATTEMPT_ID = 500;
    private static final long EXECUTION_ID = 4821;
    private static final long TEACHER_ID = 3;
    private static final long OTHER_TEACHER = 99;

    @Mock
    private Session session;
    @Mock
    private GradeReviewService reviews;

    private OverrideService service;

    @BeforeEach
    void setUp() {
        service = new OverrideService(reviews);
    }

    private static Grade grade(int autoScore) {
        Grade grade = new Grade(ATTEMPT_ID, autoScore);
        try {
            var field = Grade.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(grade, GRADE_ID);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return grade;
    }

    private static GradeReviewService.ReviewContext contextFor(Grade grade) {
        return new GradeReviewService.ReviewContext(
                grade,
                new AttemptRecord(ATTEMPT_ID, EXECUTION_ID, 11,
                        Instant.parse("2026-06-01T08:00:00Z"),
                        Instant.parse("2026-06-01T09:00:00Z"), 60, AttemptStatus.SUBMITTED),
                new ExecutionContext(EXECUTION_ID, 77, 12, "01", "Java", "Java midterm",
                        60, null, "4821", ExecutionStatus.CLOSED,
                        Instant.parse("2026-06-01T08:00:00Z"),
                        Instant.parse("2026-06-01T10:00:00Z"), 0, TEACHER_ID, TEACHER_ID));
    }

    private static GradeOverrideRequest ask(int newScore) {
        return new GradeOverrideRequest(GRADE_ID, newScore, "question 3 was ambiguous");
    }

    // ===================== Overriding =====================================

    @Nested
    @DisplayName("Overriding")
    class Overriding {

        @Test
        @DisplayName("sets the teacher's score and the reason together")
        void setsScoreAndReason() {
            Grade grade = grade(75);
            when(reviews.contextOf(session, GRADE_ID))
                    .thenReturn(Optional.of(contextFor(grade)));
            lenient().when(reviews.review(any(), any()))
                    .thenReturn(new common.dto.grading.GradeReview(
                            new common.dto.grading.StudentGradeRow(GRADE_ID, 11, "מאיה לוי",
                                    75, 80, 80, common.dto.grading.GradeState.AUTO,
                                    "question 3 was ambiguous", null, null),
                            List.of()));

            OverrideService.OverrideOutcome outcome = service.override(session, TEACHER_ID, ask(80));

            assertThat(outcome.outcome()).isEqualTo(OverrideService.Outcome.OVERRIDDEN);
            assertThat(grade.getFinalScore()).isEqualTo(80);
            assertThat(grade.getOverrideReason()).isEqualTo("question 3 was ambiguous");
        }

        @Test
        @DisplayName("keeps the auto score alongside the new one, so the change stays visible")
        void keepsTheAutoScoreAlongsideTheNewOne() {
            Grade grade = grade(75);
            when(reviews.contextOf(session, GRADE_ID))
                    .thenReturn(Optional.of(contextFor(grade)));
            lenient().when(reviews.review(any(), any())).thenReturn(null);

            service.override(session, TEACHER_ID, ask(80));

            assertThat(grade.getAutoScore()).isEqualTo(75);
            assertThat(grade.getEffectiveScore()).isEqualTo(80);
        }

        @Test
        @DisplayName("answers with the paper read back, not with an echo of the request")
        void answersWithARefreshedReview() {
            Grade grade = grade(75);
            GradeReviewService.ReviewContext context = contextFor(grade);
            common.dto.grading.GradeReview refreshed = new common.dto.grading.GradeReview(
                    new common.dto.grading.StudentGradeRow(GRADE_ID, 11, "מאיה לוי",
                            75, 80, 80, common.dto.grading.GradeState.AUTO,
                            "question 3 was ambiguous", null, null),
                    List.of());
            when(reviews.contextOf(session, GRADE_ID)).thenReturn(Optional.of(context));
            when(reviews.review(session, context)).thenReturn(refreshed);

            OverrideService.OverrideOutcome outcome = service.override(session, TEACHER_ID, ask(80));

            assertThat(outcome.review()).isSameAs(refreshed);
            // Read back through the assembler, after the write.
            verify(reviews).review(session, context);
        }

        @Test
        @DisplayName("the exam's author may override too, not just whoever released it (S-35)")
        void authorMayOverride() {
            Grade grade = grade(75);
            GradeReviewService.ReviewContext asAuthor = new GradeReviewService.ReviewContext(
                    grade,
                    new AttemptRecord(ATTEMPT_ID, EXECUTION_ID, 11,
                            Instant.parse("2026-06-01T08:00:00Z"),
                            Instant.parse("2026-06-01T09:00:00Z"), 60, AttemptStatus.SUBMITTED),
                    new ExecutionContext(EXECUTION_ID, 77, 12, "01", "Java", "Java midterm",
                            60, null, "4821", ExecutionStatus.CLOSED,
                            Instant.parse("2026-06-01T08:00:00Z"),
                            Instant.parse("2026-06-01T10:00:00Z"), 0, OTHER_TEACHER, TEACHER_ID));
            when(reviews.contextOf(session, GRADE_ID)).thenReturn(Optional.of(asAuthor));
            lenient().when(reviews.review(any(), any())).thenReturn(null);

            assertThat(service.override(session, TEACHER_ID, ask(80)).outcome())
                    .isEqualTo(OverrideService.Outcome.OVERRIDDEN);
        }
    }

    // ===================== Refusal ========================================

    @Nested
    @DisplayName("Refusal")
    class Refusal {

        @Test
        @DisplayName("a grade that does not exist is NOT_FOUND")
        void unknownGrade() {
            when(reviews.contextOf(session, GRADE_ID)).thenReturn(Optional.empty());

            OverrideService.OverrideOutcome outcome = service.override(session, TEACHER_ID, ask(80));

            assertThat(outcome.outcome()).isEqualTo(OverrideService.Outcome.NOT_FOUND);
            assertThat(outcome.review()).isNull();
        }

        @Test
        @DisplayName("somebody else's grade is the same answer as one that does not exist")
        void notFoundAndNotMineAreTheSameAnswer() {
            when(reviews.contextOf(session, GRADE_ID)).thenReturn(Optional.empty());
            OverrideService.OverrideOutcome missing =
                    service.override(session, TEACHER_ID, ask(80));

            when(reviews.contextOf(session, GRADE_ID))
                    .thenReturn(Optional.of(contextFor(grade(75))));
            OverrideService.OverrideOutcome notMine =
                    service.override(session, OTHER_TEACHER, ask(80));

            assertThat(notMine).isEqualTo(missing);
        }

        @Test
        @DisplayName("refusing writes nothing at all")
        void refusalDoesNotWrite() {
            Grade grade = grade(75);
            when(reviews.contextOf(session, GRADE_ID))
                    .thenReturn(Optional.of(contextFor(grade)));

            service.override(session, OTHER_TEACHER, ask(80));

            assertThat(grade.getFinalScore()).isNull();
            assertThat(grade.getOverrideReason()).isNull();
            verify(reviews, never()).review(any(), any());
        }

        @Test
        @DisplayName("an approved grade may not be changed — CONFLICT, not a silent rewrite")
        void approvedGradeIsConflict() {
            Grade grade = grade(75);
            grade.approve(TEACHER_ID, Instant.parse("2026-06-02T10:00:00Z"));
            when(reviews.contextOf(session, GRADE_ID))
                    .thenReturn(Optional.of(contextFor(grade)));

            OverrideService.OverrideOutcome outcome = service.override(session, TEACHER_ID, ask(80));

            assertThat(outcome.outcome()).isEqualTo(OverrideService.Outcome.ALREADY_APPROVED);
            // The published score is untouched.
            assertThat(grade.getEffectiveScore()).isEqualTo(75);
            assertThat(grade.getOverrideReason()).isNull();
        }
    }

    @Test
    @DisplayName("rejects null arguments rather than half-applying an override")
    void rejectsNulls() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> service.override(null, TEACHER_ID, ask(80)));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> service.override(session, TEACHER_ID, null));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new OverrideService(null));
    }
}
