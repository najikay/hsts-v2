package server.features.reports;

import common.dto.auth.Role;
import common.dto.report.ReportDimension;
import common.dto.report.ReportRequest;
import common.dto.report.ReportResult;
import common.dto.report.ReportSubject;
import common.dto.report.ReportSubjects;
import common.dto.report.ReportSubjectsRequest;
import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.core.CallerContext;
import server.db.RepositoryTestBase;
import server.db.entities.Exam;
import server.db.entities.ExamAttempt;
import server.db.entities.ExamExecution;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionStatus;
import server.db.entities.ExecutionStats;
import server.db.entities.ExecutionStatus;
import server.db.projections.CourseSummary;
import server.db.projections.ExecutionReport;
import server.db.projections.PersonRef;
import server.db.repos.ExecutionRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The five reads E15 added to the shared repositories, plus the store in front of them (E15.3).
 *
 * <p>Added under TEAM_SPLIT rule 5 — repositories grow with their callers — so each new query is
 * contract-tested on both engines here, with its consumer named in its javadoc.
 *
 * <p>The E15 unit tests all run against {@link InMemoryReportStore}, which is what lets the
 * strategies and the summary arithmetic be proven in milliseconds. This suite is the other half
 * of that bargain: without it the production data path would be reasoned about rather than
 * executed, and a strategy wired to the wrong query would pass everything. It ends by running
 * {@link ReportService} itself against a real database.
 *
 * <p>The rule it exists for above all others is <b>H15.2 ⚑</b>. Every fixture below builds a
 * cancelled sitting <em>with statistics frozen on it</em> — something the seed does not contain
 * and something the ordinary flow would never produce — because that is the only fixture that
 * can tell "cancelled is excluded" apart from "cancelled sittings have no statistics anyway".
 */
abstract class JpaReportStoreContract extends RepositoryTestBase {

    private static final Instant SPRING = Instant.parse("2026-03-10T07:00:00Z");
    private static final Instant SUMMER = Instant.parse("2026-08-07T06:00:00Z");

    /** SEED_CONTENT section 9.1's frozen record: finals 45, 55, 60, 70, 75, 85, 90, 100. */
    private static final ExecutionStats SEEDED = new ExecutionStats(
            72.5, 72.5, 17.5, 45, 100, 0.875, List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));

    /** A second, quieter sitting: 50, 60, 70, 80 - mean 65, population sigma root-125. */
    private static final ExecutionStats QUIET = new ExecutionStats(
            65, 65, Math.sqrt(125), 50, 80, 0.75, List.of(0, 0, 0, 0, 0, 1, 1, 1, 1, 0));

    private final ExecutionRepository executions = new ExecutionRepository();

    private ReportStore store;

    private ReportStore store() {
        if (store == null) {
            store = new JpaReportStore(factory());
        }
        return store;
    }

    // ===================== The store's whole surface ======================

    @Test
    @DisplayName("the store hands out every read a report needs, from one transaction")
    void everyReadIsWiredToTheRightQuery() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן אמצע");
        long executionId = newExecution(examId, "4821", ExecutionStatus.CLOSED, rinaId, SUMMER);
        newAttempt(executionId, mayaId);
        freeze(executionId, SEEDED);

        record Snapshot(List<PersonRef> teachers, Optional<PersonRef> dana,
                        List<ExecutionReport> byAuthor, List<CourseSummary> courses,
                        Optional<CourseSummary> algebra, List<ExecutionReport> byCourse,
                        List<PersonRef> students, Optional<PersonRef> maya,
                        List<ExecutionReport> byStudent, Map<String, Integer> authorCounts,
                        Map<Long, Integer> participants) { }

        Snapshot read = store().inTx(data -> new Snapshot(
                data.teachers(),
                data.teacher(danaId),
                data.executionsByAuthor(danaId),
                data.courses(),
                data.course(COURSE_ALGEBRA),
                data.executionsByCourse(COURSE_ALGEBRA),
                data.students(),
                data.student(mayaId),
                data.executionsByStudent(mayaId),
                data.reportableCounts(ExecutionRepository.ReportGrouping.AUTHOR),
                data.participantsByExecution(List.of(executionId))));

        assertThat(read.teachers()).extracting(PersonRef::username)
                .contains("dana.cohen", "rina.barak");
        assertThat(read.dana()).isPresent();
        assertThat(read.dana().orElseThrow().fullName()).isEqualTo("דנה כהן");
        assertThat(read.byAuthor()).extracting(ExecutionReport::executionId)
                .containsExactly(executionId);
        assertThat(read.courses()).extracting(CourseSummary::code)
                .containsExactly(COURSE_ALGEBRA, COURSE_CALCULUS, COURSE_JAVA, COURSE_DATABASES);
        assertThat(read.algebra()).isPresent();
        assertThat(read.byCourse()).extracting(ExecutionReport::executionId)
                .containsExactly(executionId);
        assertThat(read.students()).extracting(PersonRef::username).containsExactly("maya.levi");
        assertThat(read.maya()).isPresent();
        assertThat(read.byStudent()).extracting(ExecutionReport::executionId)
                .containsExactly(executionId);
        assertThat(read.authorCounts()).containsEntry(String.valueOf(danaId), 1);
        assertThat(read.participants()).containsEntry(executionId, 1);
    }

    @Test
    @DisplayName("⚑ the frozen record makes the round trip through the JSON column untouched")
    void frozenStatisticsSurviveTheColumn() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן אמצע");
        long executionId = newExecution(examId, "4821", ExecutionStatus.CLOSED, danaId, SUMMER);
        freeze(executionId, SEEDED);

        ExecutionStats stored = store().inTx(data ->
                data.executionsByAuthor(danaId).get(0).stats());

        assertThat(stored.average()).isEqualTo(72.5);
        assertThat(stored.median()).isEqualTo(72.5);
        assertThat(stored.stdDev())
                .as("population sigma, divisor n, exactly as section 9.1 wrote it")
                .isEqualTo(17.5);
        assertThat(stored.min()).isEqualTo(45);
        assertThat(stored.max()).isEqualTo(100);
        assertThat(stored.passRate()).isEqualTo(0.875);
        assertThat(stored.deciles()).containsExactly(0, 0, 0, 0, 1, 1, 1, 2, 1, 2);
    }

    // ===================== The reportable filter ⚑ ========================

    @Test
    @DisplayName("⚑ a cancelled sitting is excluded from all three populations, statistics or not")
    void cancelledIsExcludedEverywhere() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן אמצע");
        long good = newExecution(examId, "4821", ExecutionStatus.CLOSED, danaId, SUMMER);
        newAttempt(good, mayaId);
        freeze(good, SEEDED);
        // The contrived row: cancelled, and carrying statistics anyway.
        long cancelled = newExecution(examId, "9999", ExecutionStatus.CANCELLED, danaId, SPRING);
        newAttempt(cancelled, mayaId);
        freeze(cancelled, SEEDED);

        store().inTx(data -> {
            assertThat(data.executionsByAuthor(danaId)).extracting(ExecutionReport::executionId)
                    .containsExactly(good);
            assertThat(data.executionsByCourse(COURSE_ALGEBRA))
                    .extracting(ExecutionReport::executionId).containsExactly(good);
            assertThat(data.executionsByStudent(mayaId))
                    .extracting(ExecutionReport::executionId).containsExactly(good);
            assertThat(data.reportableCounts(ExecutionRepository.ReportGrouping.AUTHOR))
                    .containsEntry(String.valueOf(danaId), 1);
            assertThat(data.reportableCounts(ExecutionRepository.ReportGrouping.COURSE))
                    .containsEntry(COURSE_ALGEBRA, 1);
            assertThat(data.reportableCounts(ExecutionRepository.ReportGrouping.STUDENT))
                    .containsEntry(String.valueOf(mayaId), 1);
            return null;
        });
    }

    @Test
    @DisplayName("a live or scheduled sitting is excluded, and so is a closed unmarked one")
    void onlyClosedAndFrozenSittingsAreReportable() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן אמצע");
        newExecution(examId, "2075", ExecutionStatus.LIVE, danaId, SUMMER);
        newExecution(examId, "5164", ExecutionStatus.SCHEDULED, danaId, SUMMER);
        // Closed and awaiting grading: no stats column, so nothing to compare.
        newExecution(examId, "7390", ExecutionStatus.CLOSED, danaId, SUMMER);

        Map<String, Integer> counts = store().inTx(data ->
                data.reportableCounts(ExecutionRepository.ReportGrouping.AUTHOR));

        assertThat(byAuthor(danaId)).isEmpty();
        assertThat(counts).isEmpty();
    }

    @Test
    @DisplayName("the teacher is the exam's author, not whoever released the sitting (S-35)")
    void scopedOnTheAuthor() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן אמצע");
        // Rina releases Dana's exam.
        long executionId = newExecution(examId, "4821", ExecutionStatus.CLOSED, rinaId, SUMMER);
        freeze(executionId, SEEDED);

        assertThat(byAuthor(danaId)).hasSize(1);
        assertThat(byAuthor(rinaId))
                .as("she ran the room; she did not write the paper")
                .isEmpty();
    }

    @Test
    @DisplayName("a student's population is her attempts, marked or not")
    void studentPopulationIsAttempts() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן אמצע");
        long executionId = newExecution(examId, "4821", ExecutionStatus.CLOSED, danaId, SUMMER);
        newAttempt(executionId, mayaId);
        freeze(executionId, SEEDED);

        assertThat(byStudent(mayaId)).hasSize(1);
        assertThat(byStudent(danaId))
                .as("she wrote it; she did not sit it")
                .isEmpty();
    }

    @Test
    @DisplayName("rows come back oldest first, across two sittings of the same exam")
    void oldestFirst() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן אמצע");
        long later = newExecution(examId, "4822", ExecutionStatus.CLOSED, danaId, SUMMER);
        long earlier = newExecution(examId, "4821", ExecutionStatus.CLOSED, danaId, SPRING);
        freeze(later, QUIET);
        freeze(earlier, SEEDED);

        assertThat(byAuthor(danaId))
                .extracting(ExecutionReport::executionId)
                .containsExactly(earlier, later);
    }

    @Test
    @DisplayName("a person id resolves only under her own role")
    void rolesDoNotResolveEachOther() {
        store().inTx(data -> {
            assertThat(data.teacher(mayaId))
                    .as("Maya is a student; asking for teacher %s must not find her", mayaId)
                    .isEmpty();
            assertThat(data.student(danaId)).isEmpty();
            assertThat(data.teacher(999_999L)).isEmpty();
            assertThat(data.course("zz")).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("a row carries the released version's name and its course's display name")
    void rowsCarryTheirLabels() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן אמצע: אלגברה");
        long executionId = newExecution(examId, "4821", ExecutionStatus.CLOSED, danaId, SUMMER);
        freeze(executionId, SEEDED);

        ExecutionReport row = store().inTx(data -> data.executionsByAuthor(danaId).get(0));

        assertThat(row.examName()).isEqualTo("מבחן אמצע: אלגברה");
        assertThat(row.courseCode()).isEqualTo(COURSE_ALGEBRA);
        assertThat(row.courseName()).isEqualTo("אלגברה");
        assertThat(row.code()).isEqualTo("4821");
        assertThat(row.openAt()).isEqualTo(SUMMER);
    }

    @Test
    @DisplayName("an empty id list is answered without an `in ()` reaching either engine")
    void emptyIdListIsSafe() {
        Map<Long, Integer> nobody =
                store().inTx(data -> data.participantsByExecution(List.of()));
        List<ExecutionReport> blankCode =
                inTx(session -> executions.findReportRowsByCourse(session, "  "));
        List<ExecutionReport> nullCode =
                inTx(session -> executions.findReportRowsByCourse(session, null));

        assertThat(nobody).isEmpty();
        assertThat(blankCode).isEmpty();
        assertThat(nullCode).isEmpty();
    }

    // ===================== Typed shorthands ==============================
    //
    // The store's inTx is generic, and AssertJ has an assertThat overload for almost every
    // type, so `assertThat(store().inTx(...))` leaves the compiler choosing an inference that
    // does not exist. Naming the type once here is cheaper than a type witness per assertion.

    private List<ExecutionReport> byAuthor(long teacherId) {
        return store().inTx(data -> data.executionsByAuthor(teacherId));
    }

    private List<ExecutionReport> byStudent(long studentId) {
        return store().inTx(data -> data.executionsByStudent(studentId));
    }

    // ===================== End to end ⚑ ===================================

    @Test
    @DisplayName("⚑ end to end on a real database: the principal compares two sittings")
    void serviceOverRealData() {
        long examId = newExam(COURSE_ALGEBRA, (byte) 1, danaId, "מבחן אמצע: אלגברה");
        long earlier = newExecution(examId, "4821", ExecutionStatus.CLOSED, danaId, SPRING);
        long later = newExecution(examId, "4822", ExecutionStatus.CLOSED, rinaId, SUMMER);
        newAttempt(earlier, mayaId);
        newAttempt(later, mayaId);
        freeze(earlier, SEEDED);
        freeze(later, QUIET);
        // Excluded, on the production path, with statistics on it.
        long cancelled = newExecution(examId, "9999", ExecutionStatus.CANCELLED, danaId, SUMMER);
        freeze(cancelled, SEEDED);

        ReportService service = new ReportService(
                new ReportEngine(store(), ReportStrategies.all()));
        CallerContext principal = CallerContext.authenticated(SOCKET, principalId, Role.PRINCIPAL);

        Message list = service.subjects(principal, Message.request(Verb.REPORT_SUBJECTS_GET,
                new ReportSubjectsRequest(ReportDimension.BY_TEACHER)));
        ReportSubjects subjects = (ReportSubjects) list.getPayload();
        ReportSubject dana = subjects.subjects().stream()
                .filter(subject -> subject.id().equals(String.valueOf(danaId)))
                .findFirst()
                .orElseThrow();
        assertThat(dana.executions())
                .as("two reportable sittings; the cancelled one is not one of them")
                .isEqualTo(2);

        Message answer = service.report(principal, Message.request(Verb.REPORT_GET,
                new ReportRequest(ReportDimension.BY_TEACHER, dana.id())));
        ReportResult result = (ReportResult) answer.getPayload();

        assertThat(result.rows()).extracting(row -> row.code4()).containsExactly("4821", "4822");
        assertThat(result.rows().get(0).statistics().standardDeviation())
                .as("read out of the JSON column, not recomputed from anything beside it")
                .isEqualTo(17.5);
        assertThat(result.summary().executions()).isEqualTo(2);
        assertThat(result.summary().scored()).isEqualTo(12);
        assertThat(result.summary().mean())
                .as("840 points over 12 papers: the weighted mean, hand-computed")
                .isEqualTo(70.0);
        assertThat(result.summary().deciles()).containsExactly(0, 0, 0, 0, 1, 2, 2, 3, 2, 2);
        assertThat(result.summary().passCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("⚑ end to end: a teacher asking for a report is refused by the role gate")
    void nonPrincipalIsRefusedOverRealData() {
        ReportService service = new ReportService(
                new ReportEngine(store(), ReportStrategies.all()));
        CallerContext dana = CallerContext.authenticated(SOCKET, danaId, Role.TEACHER);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.subjects(dana, Message.request(Verb.REPORT_SUBJECTS_GET,
                                new ReportSubjectsRequest(ReportDimension.BY_TEACHER))))
                .isInstanceOf(server.core.AuthorizationException.class);
    }

    // ===================== Fixture =======================================

    /**
     * A stand-in connection. {@code CallerContext} only ever hands it back for logging, and
     * these tests never reach a push, so a bare instance is enough and Mockito is not needed in
     * a suite that is already paying for a database.
     */
    private static final ConnectionToClient SOCKET = null;

    private long newExam(String courseCode, byte serial, long authorId, String name) {
        return inTx(session -> {
            Exam exam = new Exam(courseCode, serial,
                    SUBJECT_MATH + courseCode + String.format("%02d", serial), authorId);
            session.persist(exam);
            session.flush();
            ExamVersion version = new ExamVersion(exam.getId(), 1, name, 60, null, null,
                    ExamVersionStatus.APPROVED, SUMMER);
            session.persist(version);
            session.flush();
            return exam.getId();
        });
    }

    private long newExecution(long examId, String code, ExecutionStatus status, long releasedBy,
                              Instant openAt) {
        return inTx(session -> {
            long versionId = versionOf(session, examId);
            ExamExecution execution = new ExamExecution(versionId, code, openAt,
                    openAt.plusSeconds(7200), status, releasedBy);
            session.persist(execution);
            session.flush();
            return execution.getId();
        });
    }

    private static long versionOf(Session session, long examId) {
        return session.createQuery(
                        "select v.id from ExamVersion v where v.examId = :examId", Long.class)
                .setParameter("examId", examId)
                .getSingleResult();
    }

    private void newAttempt(long executionId, long studentId) {
        runInTx(session -> session.persist(new ExamAttempt(executionId, studentId, SUMMER)));
    }

    /** Writes a record into the JSON column, as approval completion does (F8.5). */
    private void freeze(long executionId, ExecutionStats stats) {
        runInTx(session -> session.get(ExamExecution.class, executionId).setStats(stats));
    }
}
