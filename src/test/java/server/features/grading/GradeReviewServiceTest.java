package server.features.grading;

import common.dto.grading.AnswerReviewRow;
import common.dto.grading.GradeReview;
import common.dto.grading.GradeState;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.db.entities.ExecutionStatus;
import server.db.entities.Grade;
import server.db.entities.QuestionVersion;
import server.db.entities.User;
import server.db.projections.AttemptRecord;
import server.db.projections.ExecutionContext;
import server.db.repos.AttemptRepository;
import server.db.repos.ExecutionRepository;
import server.db.repos.GradeRepository;
import server.db.repos.QuestionRepository;
import server.db.repos.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link GradeReviewService} — assembling a marked paper (E12.6, and the read half of E12.3).
 *
 * <p>The tests worth reading twice are the ones about where the numbers come from. A review is
 * two different kinds of fact glued together — the ticks, recomputed from the pinned questions
 * and the stored answers, and the total, remembered from the grade row — and the whole value of
 * the class is that it never confuses the two. {@code headerKeepsTheStoredScoreWhileTicksAre
 * Recomputed} is the one that would catch a "simplification" that made the header add up the
 * ticks instead.
 *
 * <p>Fixture: a three-question paper worth 40/35/25, sat by one student who got the first two
 * right and left the third blank — 75 by the machine.
 */
@ExtendWith(MockitoExtension.class)
class GradeReviewServiceTest {

    private static final long GRADE_ID = 900;
    private static final long ATTEMPT_ID = 500;
    private static final long EXECUTION_ID = 4821;
    private static final long EXAM_VERSION_ID = 77;
    private static final long STUDENT_ID = 11;
    private static final long TEACHER_ID = 3;
    private static final long AUTHOR_ID = 4;
    private static final long OTHER_TEACHER = 99;

    @Mock
    private Session session;
    @Mock
    private GradeRepository grades;
    @Mock
    private AttemptRepository attempts;
    @Mock
    private ExecutionRepository executions;
    @Mock
    private GradingReads reads;
    @Mock
    private QuestionRepository questions;
    @Mock
    private UserRepository users;

    private GradeReviewService service;

    @BeforeEach
    void setUp() {
        service = new GradeReviewService(grades, attempts, executions, reads, questions, users);
    }

    // ===================== Fixture builders ==============================

    private static Grade grade(int autoScore) {
        Grade grade = new Grade(ATTEMPT_ID, autoScore);
        setId(grade, GRADE_ID);
        return grade;
    }

    private static void setId(Object entity, long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("fixture could not set an id", e);
        }
    }

    private static AttemptRecord attemptRecord() {
        return new AttemptRecord(ATTEMPT_ID, EXECUTION_ID, STUDENT_ID,
                Instant.parse("2026-06-01T08:00:00Z"), Instant.parse("2026-06-01T09:00:00Z"),
                60, server.db.entities.AttemptStatus.SUBMITTED);
    }

    private static ExecutionContext executionContext() {
        return new ExecutionContext(EXECUTION_ID, EXAM_VERSION_ID, 12, "01", "Java",
                "Java midterm", 60, null, "4821", ExecutionStatus.CLOSED,
                Instant.parse("2026-06-01T08:00:00Z"), Instant.parse("2026-06-01T10:00:00Z"),
                0, TEACHER_ID, AUTHOR_ID);
    }

    private static QuestionVersion version(long versionId, long questionId, String text,
                                           byte correct) {
        QuestionVersion version = new QuestionVersion(questionId, 1, text,
                "option one", "option two", "option three", "option four", correct,
                "topic", null, null, 1, Instant.parse("2026-01-01T00:00:00Z"));
        setId(version, versionId);
        return version;
    }

    /** The three-question paper, with the student's two right answers and one blank. */
    private void givenThePaper() {
        lenient().when(grades.findByIdUnscoped(session, GRADE_ID)).thenReturn(Optional.of(grade(75)));
        lenient().when(attempts.findRecordById(session, ATTEMPT_ID))
                .thenReturn(Optional.of(attemptRecord()));
        lenient().when(executions.findContext(session, EXECUTION_ID))
                .thenReturn(Optional.of(executionContext()));

        lenient().when(reads.pinnedQuestions(session, EXAM_VERSION_ID)).thenReturn(List.of(
                new AutoGrader.PinnedQuestion(101, 40, (byte) 1),
                new AutoGrader.PinnedQuestion(102, 35, (byte) 3),
                new AutoGrader.PinnedQuestion(103, 25, (byte) 2)));
        lenient().when(reads.selectedAnswers(session, ATTEMPT_ID))
                .thenReturn(Map.of(101L, (byte) 1, 102L, (byte) 3));

        lenient().when(questions.findVersionsForGrading(any(), any())).thenReturn(List.of(
                version(101, 11, "What is a JVM?", (byte) 1),
                version(102, 12, "What is a class?", (byte) 3),
                version(103, 13, "What is a record?", (byte) 2)));
        lenient().when(questions.findDisplayIds(any(), any()))
                .thenReturn(Map.of(11L, "01001", 12L, "01002", 13L, "01003"));

        lenient().when(users.findById(session, STUDENT_ID))
                .thenReturn(Optional.of(student()));
    }

    private static User student() {
        User user = new User("maya.levi", "hash", "מאיה לוי",
                server.db.entities.UserRole.STUDENT, "312345678");
        setId(user, STUDENT_ID);
        return user;
    }

    // ===================== Resolving ======================================

    @Nested
    @DisplayName("Resolving a grade")
    class Resolving {

        @Test
        @DisplayName("gives the grade, its attempt and its execution together")
        void resolvesTheWholeContext() {
            givenThePaper();

            Optional<GradeReviewService.ReviewContext> context =
                    service.contextOf(session, GRADE_ID);

            assertThat(context).isPresent();
            assertThat(context.get().execution().executionId()).isEqualTo(EXECUTION_ID);
            assertThat(context.get().attempt().studentId()).isEqualTo(STUDENT_ID);
        }

        @Test
        @DisplayName("is empty for a grade that does not exist")
        void emptyForUnknownGrade() {
            when(grades.findByIdUnscoped(session, 404)).thenReturn(Optional.empty());

            assertThat(service.contextOf(session, 404)).isEmpty();
        }

        @Test
        @DisplayName("is empty when the attempt behind the grade has gone")
        void emptyWhenAttemptMissing() {
            when(grades.findByIdUnscoped(session, GRADE_ID)).thenReturn(Optional.of(grade(75)));
            when(attempts.findRecordById(session, ATTEMPT_ID)).thenReturn(Optional.empty());

            assertThat(service.contextOf(session, GRADE_ID)).isEmpty();
        }

        @Test
        @DisplayName("is empty when the execution has gone — same answer as a missing grade")
        void emptyWhenExecutionMissing() {
            when(grades.findByIdUnscoped(session, GRADE_ID)).thenReturn(Optional.of(grade(75)));
            when(attempts.findRecordById(session, ATTEMPT_ID))
                    .thenReturn(Optional.of(attemptRecord()));
            when(executions.findContext(session, EXECUTION_ID)).thenReturn(Optional.empty());

            assertThat(service.contextOf(session, GRADE_ID)).isEmpty();
        }
    }

    // ===================== Ownership ======================================

    @Nested
    @DisplayName("Ownership")
    class Ownership {

        @Test
        @DisplayName("the executing teacher owns it")
        void executingTeacherOwnsIt() {
            givenThePaper();
            assertThat(service.contextOf(session, GRADE_ID).orElseThrow().isOwnedBy(TEACHER_ID))
                    .isTrue();
        }

        @Test
        @DisplayName("so does the exam's author, even though she did not release it (S-35)")
        void authorOwnsItToo() {
            givenThePaper();
            assertThat(service.contextOf(session, GRADE_ID).orElseThrow().isOwnedBy(AUTHOR_ID))
                    .isTrue();
        }

        @Test
        @DisplayName("nobody else does")
        void otherTeacherDoesNot() {
            givenThePaper();
            assertThat(service.contextOf(session, GRADE_ID).orElseThrow().isOwnedBy(OTHER_TEACHER))
                    .isFalse();
        }
    }

    // ===================== The paper ======================================

    @Nested
    @DisplayName("The marked paper")
    class ThePaper {

        @Test
        @DisplayName("has one row per pinned question, in exam order, numbered from 1")
        void rowsInExamOrder() {
            givenThePaper();

            List<AnswerReviewRow> rows =
                    service.answers(session, service.contextOf(session, GRADE_ID).orElseThrow());

            assertThat(rows).hasSize(3);
            assertThat(rows).extracting(AnswerReviewRow::ordinal).containsExactly(1, 2, 3);
            assertThat(rows).extracting(AnswerReviewRow::displayId)
                    .containsExactly("01001", "01002", "01003");
            assertThat(rows.get(0).questionText()).isEqualTo("What is a JVM?");
        }

        @Test
        @DisplayName("marks each question with the grader's own result, not a second rule")
        void marksComeFromTheGrader() {
            givenThePaper();

            List<AnswerReviewRow> rows =
                    service.answers(session, service.contextOf(session, GRADE_ID).orElseThrow());

            assertThat(rows).extracting(AnswerReviewRow::isCorrect)
                    .containsExactly(true, true, false);
            assertThat(rows).extracting(AnswerReviewRow::pointsAwarded)
                    .containsExactly(40, 35, 0);
            // And the ticks add up to what the machine scored.
            assertThat(rows.stream().mapToInt(AnswerReviewRow::pointsAwarded).sum()).isEqualTo(75);
        }

        @Test
        @DisplayName("shows an unanswered question as blank rather than as a wrong choice")
        void unansweredIsNull() {
            givenThePaper();

            List<AnswerReviewRow> rows =
                    service.answers(session, service.contextOf(session, GRADE_ID).orElseThrow());

            assertThat(rows.get(2).chosen()).isNull();
            assertThat(rows.get(2).isUnanswered()).isTrue();
            // Still carries what the right answer was: a blank question is reviewable.
            assertThat(rows.get(2).correct()).isEqualTo((byte) 2);
        }

        @Test
        @DisplayName("carries what the student chose alongside what was right")
        void carriesBothAnswers() {
            givenThePaper();

            List<AnswerReviewRow> rows =
                    service.answers(session, service.contextOf(session, GRADE_ID).orElseThrow());

            assertThat(rows.get(0).chosen()).isEqualTo((byte) 1);
            assertThat(rows.get(0).correct()).isEqualTo((byte) 1);
            assertThat(rows.get(0).points()).isEqualTo(40);
        }

        @Test
        @DisplayName("is empty when the exam version pinned nothing, rather than throwing")
        void emptyPaper() {
            when(grades.findByIdUnscoped(session, GRADE_ID)).thenReturn(Optional.of(grade(0)));
            when(attempts.findRecordById(session, ATTEMPT_ID))
                    .thenReturn(Optional.of(attemptRecord()));
            when(executions.findContext(session, EXECUTION_ID))
                    .thenReturn(Optional.of(executionContext()));
            when(reads.pinnedQuestions(session, EXAM_VERSION_ID)).thenReturn(List.of());

            assertThat(service.answers(session, service.contextOf(session, GRADE_ID).orElseThrow()))
                    .isEmpty();
        }

        @Test
        @DisplayName("skips a question whose version did not load rather than rendering it blank")
        void skipsMissingVersion() {
            givenThePaper();
            // The middle question's version fails to come back.
            lenient().when(questions.findVersionsForGrading(any(), any())).thenReturn(List.of(
                    version(101, 11, "What is a JVM?", (byte) 1),
                    version(103, 13, "What is a record?", (byte) 2)));

            List<AnswerReviewRow> rows =
                    service.answers(session, service.contextOf(session, GRADE_ID).orElseThrow());

            assertThat(rows).hasSize(2);
            // Ordinals stay the paper's, so the gap is visible rather than silently renumbered.
            assertThat(rows).extracting(AnswerReviewRow::ordinal).containsExactly(1, 3);
        }

        @Test
        @DisplayName("falls back to a placeholder display id rather than dropping the question")
        void missingDisplayIdFallsBack() {
            givenThePaper();
            lenient().when(questions.findDisplayIds(any(), any()))
                    .thenReturn(Map.of(11L, "01001"));

            List<AnswerReviewRow> rows =
                    service.answers(session, service.contextOf(session, GRADE_ID).orElseThrow());

            assertThat(rows).hasSize(3);
            assertThat(rows.get(1).displayId()).isEqualTo("?????");
            assertThat(rows.get(1).questionText()).isEqualTo("What is a class?");
        }
    }

    // ===================== The header =====================================

    @Nested
    @DisplayName("The header row")
    class Header {

        @Test
        @DisplayName("keeps the stored score while the ticks are recomputed")
        void headerKeepsTheStoredScoreWhileTicksAreRecomputed() {
            givenThePaper();
            // A teacher moved 75 up to 80. The ticks still add to 75; the header must say 80.
            Grade overridden = grade(75);
            overridden.override(80, "credit for a badly worded question");
            lenient().when(grades.findByIdUnscoped(session, GRADE_ID)).thenReturn(Optional.of(overridden));

            GradeReview review =
                    service.review(session, service.contextOf(session, GRADE_ID).orElseThrow());

            assertThat(review.grade().autoScore()).isEqualTo(75);
            assertThat(review.grade().finalScore()).isEqualTo(80);
            assertThat(review.grade().effectiveScore()).isEqualTo(80);
            assertThat(review.answers().stream().mapToInt(AnswerReviewRow::pointsAwarded).sum())
                    .isEqualTo(75);
        }

        @Test
        @DisplayName("carries the justification, because this is the teacher wire")
        void justificationIsPresentForTeachers() {
            givenThePaper();
            Grade overridden = grade(75);
            overridden.override(80, "credit for a badly worded question");
            lenient().when(grades.findByIdUnscoped(session, GRADE_ID)).thenReturn(Optional.of(overridden));

            GradeReview review =
                    service.review(session, service.contextOf(session, GRADE_ID).orElseThrow());

            assertThat(review.grade().overrideReason())
                    .isEqualTo("credit for a badly worded question");
        }

        @Test
        @DisplayName("leaves examName and courseCode null — v1.1 populates them student-side only")
        void noExamLabelsOnTheTeacherPath() {
            givenThePaper();

            GradeReview review =
                    service.review(session, service.contextOf(session, GRADE_ID).orElseThrow());

            assertThat(review.grade().examName()).isNull();
            assertThat(review.grade().courseCode()).isNull();
        }

        @Test
        @DisplayName("says AUTO until the grade is approved")
        void stateIsAutoBeforeApproval() {
            givenThePaper();

            GradeReview review =
                    service.review(session, service.contextOf(session, GRADE_ID).orElseThrow());

            assertThat(review.grade().state()).isEqualTo(GradeState.AUTO);
            assertThat(review.grade().approvedAt()).isNull();
        }

        @Test
        @DisplayName("names the student, so a teacher knows whose paper this is")
        void namesTheStudent() {
            givenThePaper();

            GradeReview review =
                    service.review(session, service.contextOf(session, GRADE_ID).orElseThrow());

            assertThat(review.grade().studentName()).isEqualTo("מאיה לוי");
            assertThat(review.grade().studentId()).isEqualTo(STUDENT_ID);
        }

        @Test
        @DisplayName("still renders when the student row is missing, rather than failing the "
                + "whole review")
        void unknownStudentDoesNotBreakTheReview() {
            givenThePaper();
            lenient().when(users.findById(session, STUDENT_ID)).thenReturn(Optional.empty());

            GradeReview review =
                    service.review(session, service.contextOf(session, GRADE_ID).orElseThrow());

            assertThat(review.grade().studentName()).isEqualTo("(unknown student)");
            assertThat(review.answers()).hasSize(3);
        }
    }

    @Test
    @DisplayName("a review is the header and the paper together")
    void reviewIsHeaderPlusAnswers() {
        givenThePaper();

        GradeReview review =
                service.review(session, service.contextOf(session, GRADE_ID).orElseThrow());

        assertThat(review.grade().gradeId()).isEqualTo(GRADE_ID);
        assertThat(review.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("rejects a null session rather than failing later inside a repository")
    void rejectsNullSession() {
        org.assertj.core.api.Assertions
                .assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> service.contextOf(null, GRADE_ID));
    }
}
