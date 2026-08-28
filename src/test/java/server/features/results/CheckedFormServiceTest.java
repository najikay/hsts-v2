package server.features.results;

import common.dto.exam.AttemptState;
import common.dto.grading.AnswerReviewRow;
import common.dto.grading.CheckedForm;
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
import server.db.entities.User;
import server.db.entities.UserRole;
import server.db.projections.AttemptRecord;
import server.db.projections.ExecutionContext;
import server.db.repos.AttemptRepository;
import server.db.repos.ExecutionRepository;
import server.db.repos.UserRepository;
import server.features.grading.GradeReviewService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CheckedFormService} — the only path by which correctness reaches a student (E13.4 ⚑).
 *
 * <p>Defence-critical, and the negative tests are the point of the file. A student reaching
 * another student's marked paper, or her own before her teacher approved it, or anyone's while
 * the sitting is still open, are three different ways to end a demo — and the third is the one
 * that is easy to forget, because it is the only gate that is not about the person asking.
 *
 * <p>{@code allFourRefusalsAreIndistinguishable} is the test that carries the most weight and
 * the one that is easiest to write badly. Asserting that each of the four cases refuses would
 * also pass for an implementation that refused them with four different answers, which is a
 * membership oracle. It compares the results to each other instead.
 *
 * <p>Fixtures are the seeded execution 4821 rows: {@code maya.levi}'s approved 71 in a closed
 * sitting is the paper that should open, and everything else is a variation that must not.
 */
@ExtendWith(MockitoExtension.class)
class CheckedFormServiceTest {

    private static final long MAYA = 11;
    /** The teacher who wrote, released and approved the seeded Algebra sitting (A6). */
    private static final long DANA = 2;
    private static final long OTHER_STUDENT = 13;
    private static final long GRADE_ID = 900;
    private static final long ATTEMPT_ID = 500;
    private static final long EXECUTION_ID = 4821;

    @Mock
    private Session session;
    @Mock
    private ResultsService results;
    @Mock
    private GradeReviewService reviews;
    @Mock
    private AttemptRepository attempts;
    @Mock
    private ExecutionRepository executions;
    @Mock
    private UserRepository users;

    private CheckedFormService service;

    @BeforeEach
    void setUp() {
        service = new CheckedFormService(results, reviews, attempts, executions, users);
    }

    // ===================== Fixtures =======================================

    private static void setId(Object entity, long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("fixture could not set an id", e);
        }
    }

    private static Grade approvedGrade() {
        Grade grade = new Grade(ATTEMPT_ID, 71);
        setId(grade, GRADE_ID);
        grade.approve(3, Instant.parse("2026-08-06T09:00:00Z"));
        return grade;
    }

    private static Grade autoGrade() {
        Grade grade = new Grade(ATTEMPT_ID, 71);
        setId(grade, GRADE_ID);
        return grade;
    }

    private static AttemptRecord attempt(AttemptStatus status, Integer minutes) {
        return new AttemptRecord(ATTEMPT_ID, EXECUTION_ID, MAYA,
                Instant.parse("2026-06-01T08:00:00Z"), Instant.parse("2026-06-01T09:00:00Z"),
                minutes, status);
    }

    private static ExecutionContext execution(ExecutionStatus status) {
        // executingTeacherId and authorId are both DANA, which is the seeded execution 4821:
        // exam_executions.created_by is the author of the released version (SEED_CONTENT §9).
        return new ExecutionContext(EXECUTION_ID, 77, 12, "11", "אלגברה", "מבחן אמצע — אלגברה",
                60, null, "4821", status, Instant.parse("2026-06-01T08:00:00Z"),
                Instant.parse("2026-06-01T10:00:00Z"), 0, DANA, DANA);
    }

    private static AnswerReviewRow answerRow() {
        return new AnswerReviewRow(1, "11001", "שאלה", "א", "ב", "ג", "ד",
                15, (byte) 1, (byte) 1, true, 15);
    }

    /** The paper that should open: hers, approved, sitting closed. */
    private void givenEverythingPasses() {
        givenEverythingPasses(AttemptStatus.SUBMITTED, 70);
    }

    private void givenEverythingPasses(AttemptStatus status, Integer minutes) {
        lenient().when(results.findOwnGrade(session, GRADE_ID, MAYA))
                .thenReturn(Optional.of(approvedGrade()));
        lenient().when(attempts.findRecordById(session, ATTEMPT_ID))
                .thenReturn(Optional.of(attempt(status, minutes)));
        lenient().when(executions.findContext(session, EXECUTION_ID))
                .thenReturn(Optional.of(execution(ExecutionStatus.CLOSED)));
        lenient().when(reviews.answers(any(), any())).thenReturn(List.of(answerRow()));
        User maya = new User("maya.levi", "hash", "מאיה לוי", UserRole.STUDENT, "312345678");
        setId(maya, MAYA);
        lenient().when(users.findById(session, MAYA)).thenReturn(Optional.of(maya));
        User dana = new User("dana.cohen", "hash", "Dana Cohen", UserRole.TEACHER, "214703951");
        setId(dana, DANA);
        lenient().when(users.findById(session, DANA)).thenReturn(Optional.of(dana));
    }

    // ===================== It opens =======================================

    @Nested
    @DisplayName("When all three conditions hold")
    class Opens {

        @Test
        @DisplayName("her own approved paper in a closed sitting opens")
        void opensTheForm() {
            givenEverythingPasses();

            Optional<CheckedForm> form = service.checkedForm(session, MAYA, GRADE_ID);

            assertThat(form).isPresent();
            assertThat(form.get().grade().effectiveScore()).isEqualTo(71);
            assertThat(form.get().answers()).hasSize(1);
        }

        @Test
        @DisplayName("ownership is the query, never a check applied afterwards")
        void ownershipIsTheQuery() {
            givenEverythingPasses();

            service.checkedForm(session, MAYA, GRADE_ID);

            // The scoped read, with the caller's own id. An unscoped read followed by a
            // comparison would be a check somebody could later forget.
            verify(results).findOwnGrade(session, GRADE_ID, MAYA);
        }

        @Test
        @DisplayName("carries the exam label, since a student may arrive from a notification")
        void carriesTheExamLabel() {
            givenEverythingPasses();

            CheckedForm form = service.checkedForm(session, MAYA, GRADE_ID).orElseThrow();

            assertThat(form.examName()).isEqualTo("מבחן אמצע — אלגברה");
            assertThat(form.courseCode()).isEqualTo("11");
        }

        @Test
        @DisplayName("the justification never reaches the student wire, twice over")
        void justificationIsStrippedTwice() {
            Grade overridden = approvedGrade();
            overridden.override(55, "teacher-only audit text");
            givenEverythingPasses();
            when(results.findOwnGrade(session, GRADE_ID, MAYA))
                    .thenReturn(Optional.of(overridden));

            CheckedForm form = service.checkedForm(session, MAYA, GRADE_ID).orElseThrow();

            // The service passes null, and CheckedForm strips it structurally as well. Two
            // independent defences, so neither is the only one.
            assertThat(form.grade().overrideReason()).isNull();
            assertThat(form.grade().teacherComment()).isNotEqualTo("teacher-only audit text");
        }

        @Test
        @DisplayName("reuses the same assembler the teacher's review uses")
        void reusesTheOneAssembler() {
            givenEverythingPasses();

            service.checkedForm(session, MAYA, GRADE_ID);

            // One place in the product turns an answer key into rows, with two gates in front
            // of it. Two assemblers would be two places, and one would drift.
            verify(reviews).answers(any(), any());
        }
    }

    // ===================== The checked-form amendment =====================

    @Nested
    @DisplayName("Attempt status and solving time (9.5)")
    class TimedOut {

        @Test
        @DisplayName("a timed-out paper says so, with the minutes actually recorded")
        void timedOutIsVisible() {
            givenEverythingPasses(AttemptStatus.TIMED_OUT, 75);

            CheckedForm form = service.checkedForm(session, MAYA, GRADE_ID).orElseThrow();

            // omer.katz's seeded row: auto-submitted at expiry, 75 minutes recorded (S-19).
            assertThat(form.attemptStatus()).isEqualTo(AttemptState.TIMED_OUT);
            assertThat(form.wasTimedOut()).isTrue();
            assertThat(form.actualMinutes()).isEqualTo(75);
        }

        @Test
        @DisplayName("a submitted paper says that instead")
        void submittedIsVisible() {
            givenEverythingPasses(AttemptStatus.SUBMITTED, 45);

            CheckedForm form = service.checkedForm(session, MAYA, GRADE_ID).orElseThrow();

            assertThat(form.attemptStatus()).isEqualTo(AttemptState.SUBMITTED);
            assertThat(form.wasTimedOut()).isFalse();
        }

        @Test
        @DisplayName("an unrecorded solving time stays null rather than becoming a zero")
        void unrecordedMinutesStayNull() {
            givenEverythingPasses(AttemptStatus.SUBMITTED, null);

            CheckedForm form = service.checkedForm(session, MAYA, GRADE_ID).orElseThrow();

            // "Not recorded" and "took no time at all" are different facts.
            assertThat(form.actualMinutes()).isNull();
        }
    }

    // ===================== A6: whose exam it was ==========================

    @Nested
    @DisplayName("The releasing teacher's name (A6, 2026-08-28)")
    class TeacherName {

        @Test
        @DisplayName("the seeded Algebra paper carries Dana Cohen, who released the sitting")
        void carriesTheReleasingTeacher() {
            givenEverythingPasses();

            CheckedForm form = service.checkedForm(session, MAYA, GRADE_ID).orElseThrow();

            // SEED_CONTENT §9: exam_executions.created_by for executions 1 and 4 is
            // 2 dana.cohen, the author of the released version.
            assertThat(form.teacherName()).isEqualTo("Dana Cohen");
        }

        @Test
        @DisplayName("the name comes from the execution's releasing teacher, not the student")
        void resolvesTheExecutionsTeacher() {
            givenEverythingPasses();

            CheckedForm form = service.checkedForm(session, MAYA, GRADE_ID).orElseThrow();

            // Two different people are looked up on this path and they must not be confused:
            // the header names the student, the new line names her teacher.
            verify(users).findById(session, DANA);
            assertThat(form.grade().studentName()).isEqualTo("מאיה לוי");
        }

        @Test
        @DisplayName("an unresolvable teacher is the empty string, never null and never a name")
        void unresolvableTeacherIsEmpty() {
            givenEverythingPasses();
            when(users.findById(session, DANA)).thenReturn(Optional.empty());

            CheckedForm form = service.checkedForm(session, MAYA, GRADE_ID).orElseThrow();

            // A missing join must not cost a student her marked paper, and it must not print
            // a placeholder onto it either. The client drops the line.
            assertThat(form.teacherName()).isEmpty();
        }
    }

    // ===================== The three gates ================================

    @Nested
    @DisplayName("The three conditions, each refusing on its own")
    class Gates {

        @Test
        @DisplayName("gate 1 — somebody else's grade is refused, and nothing is read after it")
        void notHers() {
            when(results.findOwnGrade(session, GRADE_ID, OTHER_STUDENT))
                    .thenReturn(Optional.empty());

            assertThat(service.checkedForm(session, OTHER_STUDENT, GRADE_ID)).isEmpty();

            // The answer key was never fetched, let alone assembled.
            verify(reviews, never()).answers(any(), any());
            verify(attempts, never()).findRecordById(any(), anyLong());
        }

        @Test
        @DisplayName("gate 2 — her own but unapproved paper is refused (C-3, S-24)")
        void notApproved() {
            when(results.findOwnGrade(session, GRADE_ID, MAYA))
                    .thenReturn(Optional.of(autoGrade()));

            assertThat(service.checkedForm(session, MAYA, GRADE_ID)).isEmpty();

            // Auto-grading publishes nothing. Serving this would show her a score her teacher
            // has not stood behind, complete with the answer key.
            verify(reviews, never()).answers(any(), any());
        }

        @Test
        @DisplayName("gate 3 — a sitting still open is refused, whoever is asking")
        void executionNotClosed() {
            for (ExecutionStatus open : List.of(ExecutionStatus.LIVE, ExecutionStatus.SCHEDULED)) {
                givenEverythingPasses();
                when(executions.findContext(session, EXECUTION_ID))
                        .thenReturn(Optional.of(execution(open)));

                assertThat(service.checkedForm(session, MAYA, GRADE_ID))
                        .as("execution %s", open)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("gate 3 is the one not about the person asking — hers, approved, still open")
        void ownApprovedButStillOpen() {
            givenEverythingPasses();
            when(executions.findContext(session, EXECUTION_ID))
                    .thenReturn(Optional.of(execution(ExecutionStatus.LIVE)));

            // Both of the first two gates pass. Handing her the key while others are still
            // sitting the same paper hands it to the room.
            assertThat(service.checkedForm(session, MAYA, GRADE_ID)).isEmpty();
            verify(reviews, never()).answers(any(), any());
        }

        @Test
        @DisplayName("a cancelled sitting is refused too — closed means closed, not merely over")
        void cancelledIsNotClosed() {
            givenEverythingPasses();
            when(executions.findContext(session, EXECUTION_ID))
                    .thenReturn(Optional.of(execution(ExecutionStatus.CANCELLED)));

            assertThat(service.checkedForm(session, MAYA, GRADE_ID)).isEmpty();
        }
    }

    // ===================== No oracle ======================================

    @Test
    @DisplayName("all four refusals are indistinguishable from each other")
    void allFourRefusalsAreIndistinguishable() {
        // Not "each of them refuses" — that is also true of an implementation returning four
        // different reasons, which is exactly the membership oracle E13.1 forbids.
        when(results.findOwnGrade(session, GRADE_ID, MAYA)).thenReturn(Optional.empty());
        Optional<CheckedForm> unknown = service.checkedForm(session, MAYA, GRADE_ID);

        when(results.findOwnGrade(session, GRADE_ID, MAYA)).thenReturn(Optional.of(autoGrade()));
        Optional<CheckedForm> unapproved = service.checkedForm(session, MAYA, GRADE_ID);

        givenEverythingPasses();
        when(executions.findContext(session, EXECUTION_ID))
                .thenReturn(Optional.of(execution(ExecutionStatus.LIVE)));
        Optional<CheckedForm> stillOpen = service.checkedForm(session, MAYA, GRADE_ID);

        when(results.findOwnGrade(session, GRADE_ID, MAYA)).thenReturn(Optional.empty());
        Optional<CheckedForm> notMine = service.checkedForm(session, MAYA, GRADE_ID);

        assertThat(unknown).isEqualTo(unapproved).isEqualTo(stillOpen).isEqualTo(notMine);
        assertThat(unknown).isEmpty();
    }

    @Test
    @DisplayName("a grade whose attempt or execution has gone is refused, not half-rendered")
    void missingJoinsRefuse() {
        when(results.findOwnGrade(session, GRADE_ID, MAYA))
                .thenReturn(Optional.of(approvedGrade()));
        when(attempts.findRecordById(session, ATTEMPT_ID)).thenReturn(Optional.empty());

        assertThat(service.checkedForm(session, MAYA, GRADE_ID)).isEmpty();
    }

    @Test
    @DisplayName("rejects null arguments rather than serving a paper to nobody")
    void rejectsNulls() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> service.checkedForm(null, MAYA, GRADE_ID));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new CheckedFormService(null, reviews, attempts, executions, users));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new CheckedFormService(results, null, attempts, executions, users));
    }
}
