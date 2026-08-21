package server.features.results;

import common.dto.auth.Role;
import common.dto.results.ExecutionResults;
import common.dto.results.ExecutionResultsRequest;
import common.dto.results.ResultStatistics;
import common.dto.results.TeacherResults;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.core.CallerContext;
import server.db.RepositoryTestBase;
import server.db.entities.AttemptStatus;
import server.db.entities.Exam;
import server.db.entities.ExamAttempt;
import server.db.entities.ExamExecution;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionStatus;
import server.db.entities.ExecutionStats;
import server.db.entities.ExecutionStatus;
import server.db.entities.Grade;
import server.db.projections.AuthoredExam;
import server.db.projections.ExecutionContext;
import server.db.projections.StudentResultRow;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JpaTeacherResultsStore} driven through the seam the service actually uses (E14.1).
 *
 * <p>{@code ResultsRepositoryContract} proves the six queries; this proves the thing in front
 * of them. The distinction is the same one {@code JpaExamStoreContract} exists for: the unit
 * tests of every E14 rule run against an in-memory double, so without this suite the
 * production data path would be reasoned about rather than executed, and a method wired to the
 * wrong repository would pass everything.
 *
 * <p>It ends by running {@link TeacherResultsService} itself against a real database, which is
 * where S-35 stops being a query and becomes an answer on the wire: Rina releases Dana's exam,
 * Dana reads its results, and Rina cannot.
 */
abstract class JpaTeacherResultsStoreContract extends RepositoryTestBase {

    private static final Instant OPENED = Instant.parse("2026-08-07T06:00:00Z");

    /** §9.1's frozen record: finals 45, 55, 60, 70, 75, 85, 90, 100. */
    private static final ExecutionStats FROZEN = new ExecutionStats(
            72.5, 72.5, 17.5, 45, 100, 0.875, List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));

    private TeacherResultsStore store;

    private TeacherResultsStore store() {
        if (store == null) {
            store = new JpaTeacherResultsStore(factory());
        }
        return store;
    }

    @Test
    @DisplayName("the store hands out every read the service needs, from one transaction")
    void everyReadIsWiredToTheRightQuery() {
        long examId = newExam(danaId, "מבחן אמצע");
        long executionId = newExecution(examId, "4821", ExecutionStatus.CLOSED, rinaId);
        long attemptId = newAttempt(executionId, mayaId);
        markGrade(attemptId, 45, 55, "ניתן ניקוד חלקי.");
        freeze(executionId);

        record Snapshot(List<AuthoredExam> exams, List<ExecutionContext> executions,
                        Set<Long> withStats, Map<Long, Integer> participants,
                        Map<Long, Integer> graded, Optional<ExecutionStats> stats,
                        List<StudentResultRow> rows, Optional<ExecutionContext> byId) { }

        Snapshot read = store().inTx(data -> new Snapshot(
                data.examsAuthoredBy(danaId),
                data.executionsOfExamsAuthoredBy(danaId),
                data.executionsWithStatistics(List.of(executionId)),
                data.participantsByExecution(List.of(executionId)),
                data.gradedByExecution(List.of(executionId)),
                data.statisticsOf(executionId),
                data.resultRowsOf(executionId),
                data.executionById(executionId)));

        assertThat(read.exams()).extracting(AuthoredExam::examName).containsExactly("מבחן אמצע");
        assertThat(read.executions()).extracting(ExecutionContext::executionId)
                .containsExactly(executionId);
        assertThat(read.withStats()).containsExactly(executionId);
        assertThat(read.participants()).containsEntry(executionId, 1);
        assertThat(read.graded()).containsEntry(executionId, 1);
        assertThat(read.stats()).isPresent();
        assertThat(read.stats().orElseThrow().stdDev()).isEqualTo(17.5);
        assertThat(read.rows()).extracting(StudentResultRow::studentName).containsExactly("מאיה לוי");
        assertThat(read.rows().get(0).effectiveScore()).isEqualTo(55);
        assertThat(read.byId()).isPresent();
        assertThat(read.byId().orElseThrow().authorId()).isEqualTo(danaId);
    }

    @Test
    @DisplayName("an execution with no frozen statistics reads as absent, not as zeros")
    void unfrozenStatisticsAreAbsent() {
        long examId = newExam(danaId, "מבחן");
        long executionId = newExecution(examId, "2075", ExecutionStatus.LIVE, danaId);

        Optional<ExecutionStats> stats = store().inTx(data -> data.statisticsOf(executionId));
        Set<Long> withStats =
                store().inTx(data -> data.executionsWithStatistics(List.of(executionId)));

        assertThat(stats).isEmpty();
        assertThat(withStats).isEmpty();
    }

    @Test
    @DisplayName("an execution that does not exist reads as empty rather than throwing")
    void unknownExecution() {
        Optional<ExecutionContext> missing = store().inTx(data -> data.executionById(999_999L));
        Optional<ExecutionStats> stats = store().inTx(data -> data.statisticsOf(999_999L));

        assertThat(missing).isEmpty();
        assertThat(stats).isEmpty();
    }

    @Test
    @DisplayName("⚑ end to end on a real database: the author reads a sitting her colleague ran")
    void serviceOverRealData() {
        long examId = newExam(danaId, "מבחן אמצע: אלגברה");
        // Rina releases Dana's exam. S-35, on the production data path.
        long executionId = newExecution(examId, "4821", ExecutionStatus.CLOSED, rinaId);
        markGrade(newAttempt(executionId, mayaId), 60, null, null);
        freeze(executionId);

        TeacherResultsService service = new TeacherResultsService(store());

        Message list = service.exams(teacher(danaId),
                Message.request(Verb.RESULTS_EXAMS_GET, null));
        TeacherResults results = (TeacherResults) list.getPayload();
        assertThat(results.exams()).hasSize(1);
        assertThat(results.exams().get(0).executions()).hasSize(1);
        assertThat(results.exams().get(0).executions().get(0).releasedByAnotherTeacher()).isTrue();

        Message detail = service.execution(teacher(danaId), ask(executionId));
        ExecutionResults answer = (ExecutionResults) detail.getPayload();
        ResultStatistics stats = answer.statistics().orElseThrow();
        assertThat(stats.mean()).isEqualTo(72.5);
        assertThat(stats.standardDeviation())
                .as("read out of the JSON column, not recomputed from the one row beside it")
                .isEqualTo(17.5);
        assertThat(stats.count()).isEqualTo(8);
        assertThat(stats.passCount()).isEqualTo(7);
        assertThat(answer.rows()).hasSize(1);
    }

    @Test
    @DisplayName("⚑ end to end: the teacher who ran it, but did not write it, gets NOT_FOUND")
    void nonAuthorIsRefusedOverRealData() {
        long examId = newExam(danaId, "מבחן");
        long executionId = newExecution(examId, "4821", ExecutionStatus.CLOSED, rinaId);

        TeacherResultsService service = new TeacherResultsService(store());

        Message refused = service.execution(teacher(rinaId), ask(executionId));
        Message neverExisted = service.execution(teacher(rinaId), ask(999_999L));
        Message list = service.exams(teacher(rinaId),
                Message.request(Verb.RESULTS_EXAMS_GET, null));

        assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(refused.errorMessage()).isEqualTo(neverExisted.errorMessage());
        assertThat(((TeacherResults) list.getPayload()).isEmpty())
                .as("she released it; she did not write it, so it is not in her results at all")
                .isTrue();
    }

    // ===================== Fixture =======================================

    private CallerContext teacher(long userId) {
        return CallerContext.authenticated(SOCKET, userId, Role.TEACHER);
    }

    /**
     * A stand-in connection. {@code CallerContext} only ever hands it back for logging, and
     * these tests never reach a push, so a bare instance is enough and Mockito is not needed
     * in a suite that is already paying for a database.
     */
    private static final ConnectionToClient SOCKET = null;

    private static Message ask(long executionId) {
        return Message.request(Verb.RESULTS_EXECUTION_GET,
                new ExecutionResultsRequest(executionId));
    }

    private long newExam(long authorId, String name) {
        return inTx(session -> {
            byte serial = (byte) (countExams(session) + 1);
            Exam exam = new Exam(COURSE_ALGEBRA, serial,
                    SUBJECT_MATH + COURSE_ALGEBRA + String.format("%02d", serial), authorId);
            session.persist(exam);
            session.flush();
            ExamVersion version = new ExamVersion(exam.getId(), 1, name, 60, null, null,
                    ExamVersionStatus.APPROVED, OPENED);
            session.persist(version);
            session.flush();
            return exam.getId();
        });
    }

    private static int countExams(org.hibernate.Session session) {
        return session.createQuery("select count(e) from Exam e", Long.class)
                .getSingleResult()
                .intValue();
    }

    private long newExecution(long examId, String code, ExecutionStatus status, long releasedBy) {
        return inTx(session -> {
            long versionId = session.createQuery(
                            "select v.id from ExamVersion v where v.examId = :examId", Long.class)
                    .setParameter("examId", examId)
                    .getSingleResult();
            ExamExecution execution = new ExamExecution(versionId, code, OPENED,
                    OPENED.plusSeconds(7200), status, releasedBy);
            session.persist(execution);
            session.flush();
            return execution.getId();
        });
    }

    private long newAttempt(long executionId, long studentId) {
        return inTx(session -> {
            ExamAttempt attempt = new ExamAttempt(executionId, studentId, OPENED);
            session.persist(attempt);
            session.flush();
            return attempt.getId();
        });
    }

    private void markGrade(long attemptId, int auto, Integer overrideTo, String reason) {
        runInTx(session -> {
            session.createMutationQuery(
                            "update ExamAttempt set status = :status, endedAt = :endedAt,"
                                    + " actualMinutes = 30 where id = :id and status = :inProgress")
                    .setParameter("status", AttemptStatus.SUBMITTED)
                    .setParameter("endedAt", OPENED.plusSeconds(1800))
                    .setParameter("id", attemptId)
                    .setParameter("inProgress", AttemptStatus.IN_PROGRESS)
                    .executeUpdate();
            Grade grade = new Grade(attemptId, auto);
            if (overrideTo != null) {
                grade.override(overrideTo, reason);
            }
            grade.approve(danaId, OPENED.plusSeconds(3600));
            session.persist(grade);
        });
    }

    /** Writes §9.1's record into the JSON column, as approval completion does (F8.5). */
    private void freeze(long executionId) {
        runInTx(session -> session.get(ExamExecution.class, executionId).setStats(FROZEN));
    }
}
