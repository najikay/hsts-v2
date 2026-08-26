package server.features.grading;

import common.dto.grading.ExecutionGrades;
import common.dto.grading.GradeState;
import common.dto.grading.GradingQueue;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.db.entities.ExecutionStatus;
import server.db.entities.GradeStatus;
import server.db.projections.ExecutionContext;
import server.db.projections.ParticipationCounts;
import server.db.projections.StudentResultRow;
import server.db.repos.AttemptRepository;
import server.db.repos.ExecutionRepository;
import server.db.repos.GradeRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link GradingQueueService} — what is waiting to be graded (E12.5/E12.6).
 *
 * <p>The queue's value is entirely in what it <em>leaves out</em>. A queue that listed every
 * execution would never empty, and a list that never empties stops being read — so the tests
 * that matter are the exclusions: a sitting still running, a sitting nobody has marked, and a
 * sitting already signed off are all absent for three different reasons.
 *
 * <p>Fixture: the seeded pair. Execution 1 (Algebra) is closed with all eight grades approved —
 * finished, so not waiting. Execution 2 (Java) is closed with eight `AUTO` grades — the one
 * genuinely on a teacher's desk.
 */
@ExtendWith(MockitoExtension.class)
class GradingQueueServiceTest {

    private static final long TEACHER = 4;
    private static final long OTHER_TEACHER = 99;
    private static final long ALGEBRA = 4821;
    private static final long JAVA = 4822;

    @Mock
    private Session session;
    @Mock
    private ExecutionRepository executions;
    @Mock
    private GradeRepository grades;
    @Mock
    private AttemptRepository attempts;

    private GradingQueueService service;

    @BeforeEach
    void setUp() {
        service = new GradingQueueService(executions, grades, attempts);
    }

    private static ExecutionContext execution(long id, ExecutionStatus status, String name,
                                              Instant closeAt, long owner) {
        return new ExecutionContext(id, 77, 12, "21", "Java", name, 60, null,
                String.valueOf(id), status, closeAt.minusSeconds(3600), closeAt, 0, owner, owner);
    }

    private static ExecutionContext java(ExecutionStatus status) {
        return execution(JAVA, status, "Java midterm",
                Instant.parse("2026-06-02T10:00:00Z"), TEACHER);
    }

    private static ExecutionContext algebra(ExecutionStatus status) {
        return execution(ALGEBRA, status, "Algebra midterm",
                Instant.parse("2026-06-01T10:00:00Z"), TEACHER);
    }

    private static StudentResultRow resultRow(long gradeId, String name, int auto,
                                              Integer finalScore, GradeStatus status) {
        return new StudentResultRow(gradeId, 11, name, auto, finalScore, status,
                status == GradeStatus.APPROVED ? "credit for a bad question" : null,
                "well done", status == GradeStatus.APPROVED
                        ? Instant.parse("2026-06-03T10:00:00Z") : null, 45,
                server.db.entities.AttemptStatus.SUBMITTED);
    }

    // ===================== What is in the queue ===========================

    @Nested
    @DisplayName("The queue")
    class Queue {

        @Test
        @DisplayName("lists a closed sitting with grades still awaiting approval")
        void listsWaitingExecution() {
            when(executions.findContextsByExamAuthor(session, TEACHER))
                    .thenReturn(List.of(java(ExecutionStatus.CLOSED)));
            when(attempts.countAttemptsByExecution(any(), any())).thenReturn(Map.of(JAVA, 8));
            when(grades.countGradesByExecution(any(), any())).thenReturn(Map.of(JAVA, 8));
            when(grades.countApprovedByExecution(any(), any())).thenReturn(Map.of());

            GradingQueue queue = service.queue(session, TEACHER);

            assertThat(queue.executions()).hasSize(1);
            assertThat(queue.executions().get(0).executionId()).isEqualTo(JAVA);
            assertThat(queue.executions().get(0).participants()).isEqualTo(8);
            assertThat(queue.executions().get(0).gradedCount()).isEqualTo(8);
            assertThat(queue.executions().get(0).approvedCount()).isZero();
        }

        @Test
        @DisplayName("leaves out a sitting that is still running — it is not a task yet")
        void excludesLiveExecution() {
            when(executions.findContextsByExamAuthor(session, TEACHER))
                    .thenReturn(List.of(java(ExecutionStatus.LIVE)));

            // Grading an exam people are still sitting would invite a teacher to approve half a
            // class and wonder where the rest went.
            assertThat(service.queue(session, TEACHER).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("leaves out a sitting nobody has marked — there is nothing to approve")
        void excludesUngradedExecution() {
            when(executions.findContextsByExamAuthor(session, TEACHER))
                    .thenReturn(List.of(java(ExecutionStatus.CLOSED)));
            lenient().when(attempts.countAttemptsByExecution(any(), any()))
                    .thenReturn(Map.of(JAVA, 8));
            when(grades.countGradesByExecution(any(), any())).thenReturn(Map.of());
            when(grades.countApprovedByExecution(any(), any())).thenReturn(Map.of());

            assertThat(service.queue(session, TEACHER).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("leaves out a sitting already signed off — a queue that never empties is "
                + "not read")
        void excludesFullyApprovedExecution() {
            when(executions.findContextsByExamAuthor(session, TEACHER))
                    .thenReturn(List.of(algebra(ExecutionStatus.CLOSED)));
            lenient().when(attempts.countAttemptsByExecution(any(), any()))
                    .thenReturn(Map.of(ALGEBRA, 8));
            when(grades.countGradesByExecution(any(), any())).thenReturn(Map.of(ALGEBRA, 8));
            when(grades.countApprovedByExecution(any(), any())).thenReturn(Map.of(ALGEBRA, 8));

            // Execution 1 in the seed: finished, and therefore E14's history rather than a
            // to-do item.
            assertThat(service.queue(session, TEACHER).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("keeps a half-approved sitting, because it is still waiting on her")
        void keepsPartiallyApproved() {
            when(executions.findContextsByExamAuthor(session, TEACHER))
                    .thenReturn(List.of(java(ExecutionStatus.CLOSED)));
            when(attempts.countAttemptsByExecution(any(), any())).thenReturn(Map.of(JAVA, 8));
            when(grades.countGradesByExecution(any(), any())).thenReturn(Map.of(JAVA, 8));
            when(grades.countApprovedByExecution(any(), any())).thenReturn(Map.of(JAVA, 3));

            GradingQueue queue = service.queue(session, TEACHER);

            assertThat(queue.executions()).hasSize(1);
            assertThat(queue.executions().get(0).approvedCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("puts the sitting that closed longest ago first")
        void oldestClosingFirst() {
            when(executions.findContextsByExamAuthor(session, TEACHER))
                    .thenReturn(List.of(java(ExecutionStatus.CLOSED), algebra(ExecutionStatus.CLOSED)));
            when(attempts.countAttemptsByExecution(any(), any()))
                    .thenReturn(Map.of(JAVA, 8, ALGEBRA, 8));
            when(grades.countGradesByExecution(any(), any()))
                    .thenReturn(Map.of(JAVA, 8, ALGEBRA, 8));
            when(grades.countApprovedByExecution(any(), any())).thenReturn(Map.of());

            GradingQueue queue = service.queue(session, TEACHER);

            // Algebra closed a day earlier, so its students have been waiting longest. A queue
            // ordered newest-first buries them.
            assertThat(queue.executions()).extracting(row -> row.executionId())
                    .containsExactly(ALGEBRA, JAVA);
        }

        @Test
        @DisplayName("a teacher who wrote nothing gets an empty queue, not an error")
        void noExamsIsEmptyNotAnError() {
            when(executions.findContextsByExamAuthor(session, OTHER_TEACHER))
                    .thenReturn(List.of());

            assertThat(service.queue(session, OTHER_TEACHER)).isEqualTo(GradingQueue.EMPTY);
        }

        @Test
        @DisplayName("asks for counts once, not once per row")
        void countsAreReadInBulk() {
            when(executions.findContextsByExamAuthor(session, TEACHER))
                    .thenReturn(List.of(java(ExecutionStatus.CLOSED), algebra(ExecutionStatus.CLOSED)));
            when(attempts.countAttemptsByExecution(any(), any()))
                    .thenReturn(Map.of(JAVA, 8, ALGEBRA, 8));
            when(grades.countGradesByExecution(any(), any()))
                    .thenReturn(Map.of(JAVA, 8, ALGEBRA, 8));
            when(grades.countApprovedByExecution(any(), any())).thenReturn(Map.of());

            service.queue(session, TEACHER);

            // Three reads for the whole queue, whatever its length.
            org.mockito.Mockito.verify(attempts).countAttemptsByExecution(any(), any());
            org.mockito.Mockito.verify(grades).countGradesByExecution(any(), any());
            org.mockito.Mockito.verify(grades).countApprovedByExecution(any(), any());
        }
    }

    // ===================== Opening one ====================================

    @Nested
    @DisplayName("Opening one sitting")
    class Opening {

        @Test
        @DisplayName("returns every grade, approved ones included")
        void returnsEveryGrade() {
            when(executions.findContext(session, JAVA))
                    .thenReturn(Optional.of(java(ExecutionStatus.CLOSED)));
            when(grades.findResultRows(session, JAVA)).thenReturn(List.of(
                    resultRow(1, "מאיה לוי", 100, null, GradeStatus.AUTO),
                    resultRow(2, "עומר כץ", 40, 45, GradeStatus.APPROVED)));
            when(attempts.countParticipation(session, JAVA))
                    .thenReturn(new ParticipationCounts(8, 8, 0));

            ExecutionGrades opened = service.executionGrades(session, TEACHER, JAVA).orElseThrow();

            // A table that hid the approved rows would shrink as she worked and give her no way
            // to check a decision she had just made.
            assertThat(opened.rows()).hasSize(2);
            assertThat(opened.summary().approvedCount()).isEqualTo(1);
            assertThat(opened.summary().gradedCount()).isEqualTo(2);
            assertThat(opened.summary().participants()).isEqualTo(8);
        }

        @Test
        @DisplayName("carries the justification, because this is the teacher wire")
        void carriesTheJustification() {
            when(executions.findContext(session, JAVA))
                    .thenReturn(Optional.of(java(ExecutionStatus.CLOSED)));
            when(grades.findResultRows(session, JAVA)).thenReturn(List.of(
                    resultRow(2, "עומר כץ", 40, 45, GradeStatus.APPROVED)));
            when(attempts.countParticipation(session, JAVA))
                    .thenReturn(new ParticipationCounts(8, 8, 0));

            ExecutionGrades opened = service.executionGrades(session, TEACHER, JAVA).orElseThrow();

            assertThat(opened.rows().get(0).overrideReason()).isEqualTo("credit for a bad question");
            assertThat(opened.rows().get(0).state()).isEqualTo(GradeState.APPROVED);
            // v1.1's exam labels stay null on a teacher path: the summary says it once.
            assertThat(opened.rows().get(0).examName()).isNull();
        }

        @Test
        @DisplayName("refuses another teacher's execution exactly as it refuses an unknown one")
        void refusalsAreIndistinguishable() {
            when(executions.findContext(session, JAVA)).thenReturn(Optional.empty());
            Optional<ExecutionGrades> unknown = service.executionGrades(session, TEACHER, JAVA);

            when(executions.findContext(session, JAVA))
                    .thenReturn(Optional.of(execution(JAVA, ExecutionStatus.CLOSED, "Java midterm",
                            Instant.parse("2026-06-02T10:00:00Z"), OTHER_TEACHER)));
            Optional<ExecutionGrades> notMine = service.executionGrades(session, TEACHER, JAVA);

            // A queue row is not a capability: guessing an id must teach nothing.
            assertThat(notMine).isEqualTo(unknown).isEmpty();
        }

        @Test
        @DisplayName("the exam's author may open it even when a colleague ran it (S-35)")
        void authorMayOpenIt() {
            ExecutionContext ranByColleague = new ExecutionContext(JAVA, 77, 12, "21", "Java",
                    "Java midterm", 60, null, "4822", ExecutionStatus.CLOSED,
                    Instant.parse("2026-06-02T09:00:00Z"), Instant.parse("2026-06-02T10:00:00Z"),
                    0, OTHER_TEACHER, TEACHER);
            when(executions.findContext(session, JAVA)).thenReturn(Optional.of(ranByColleague));
            when(grades.findResultRows(session, JAVA)).thenReturn(List.of());
            when(attempts.countParticipation(session, JAVA))
                    .thenReturn(new ParticipationCounts(0, 0, 0));

            assertThat(service.executionGrades(session, TEACHER, JAVA)).isPresent();
        }
    }

    @Test
    @DisplayName("rejects nulls rather than answering with a queue for nobody")
    void rejectsNulls() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> service.queue(null, TEACHER));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> service.executionGrades(null, TEACHER, JAVA));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new GradingQueueService(null, grades, attempts));
    }
}
