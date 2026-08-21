package server.features.results;

import common.dto.auth.Role;
import common.dto.grading.GradeState;
import common.dto.grading.StudentGradeRow;
import common.dto.results.ExamResultRow;
import common.dto.results.ExecutionResultRow;
import common.dto.results.ExecutionResults;
import common.dto.results.ExecutionResultsRequest;
import common.dto.results.ExecutionState;
import common.dto.results.ResultStatistics;
import common.dto.results.TeacherResults;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import server.core.AuthorizationException;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.core.SessionManager;
import server.db.entities.ExecutionStats;
import server.db.entities.ExecutionStatus;
import server.db.entities.GradeStatus;
import server.db.projections.StudentResultRow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TeacherResultsService} — E14.1's scoping and its one unbreakable rule about numbers.
 *
 * <p>Every rule is proven against {@link InMemoryTeacherResultsStore}, with no database, which
 * is the point of the store seam existing (TEAM_SPLIT §3.2). Two things here are
 * defence-critical and are marked as such:
 *
 * <ul>
 *   <li><b>S-35 ⚑.</b> Dana wrote exam 101101; Michal released one of its sittings. Dana must
 *       still see that sitting's results, and Avi — who wrote a different exam — must see
 *       nothing at all, in a way that is indistinguishable from an id that never existed.</li>
 *   <li><b>Stored, never recomputed ⚑ (H14.4).</b> One test hands the service statistics that
 *       flatly contradict the rows beside them and asserts the <em>stored</em> figures reach
 *       the wire. A service that recomputed would pass every other test in this file.</li>
 * </ul>
 *
 * <p>The fixture is the seeded world: execution 4821 with the frozen mean 72.5, median 72.5,
 * σ 17.5 and pass rate 7/8, so any number below can be checked against §9.1 of the seed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeacherResultsServiceTest {

    private static final long DANA = 1001;
    private static final long AVI = 1002;
    private static final long MICHAL = 1003;
    private static final long MAYA = 2001;

    private static final long ALGEBRA_EXAM = 1;
    private static final long DRAWER_EXAM = 2;
    private static final long JAVA_EXAM = 3;

    /** Closed, fully graded, and released by MICHAL rather than by its author: the S-35 case. */
    private static final long EXECUTION_4821 = 10;
    /** Live, released by Dana herself, nothing marked: the "grading unfinished" case. */
    private static final long EXECUTION_2075 = 11;
    /** Avi's own exam's sitting: Dana must never see it. */
    private static final long EXECUTION_7390 = 12;
    /** Called off. Never appears anywhere (H15.2). */
    private static final long EXECUTION_CANCELLED = 13;

    private static final Instant CLOSED_AT = Instant.parse("2026-08-07T08:00:00Z");

    /** §9.1's frozen statistics: finals 45, 55, 60, 70, 75, 85, 90, 100. */
    private static final ExecutionStats FROZEN = new ExecutionStats(
            72.5, 72.5, 17.5, 45, 100, 0.875, List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));

    @Mock
    private ConnectionToClient socket;

    private InMemoryTeacherResultsStore store;
    private TeacherResultsService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryTeacherResultsStore();
        store.exam(ALGEBRA_EXAM, "101101", "11", "אלגברה", "מבחן אמצע: אלגברה", DANA);
        store.exam(DRAWER_EXAM, "101102", "11", "אלגברה", "בוחן: אי-שוויונות", DANA);
        store.exam(JAVA_EXAM, "202101", "21", "תכנות מונחה עצמים", "Java Fundamentals", AVI);

        // Released by Michal. Dana wrote the exam, so this sitting is hers to read (S-35).
        store.execution(EXECUTION_4821, ALGEBRA_EXAM, "4821", ExecutionStatus.CLOSED,
                CLOSED_AT.minus(Duration.ofHours(2)), CLOSED_AT, MICHAL);
        store.participants(EXECUTION_4821, 8);
        store.statistics(EXECUTION_4821, FROZEN);
        seedEightGrades();

        store.execution(EXECUTION_2075, ALGEBRA_EXAM, "2075", ExecutionStatus.LIVE,
                CLOSED_AT.plus(Duration.ofDays(14)), CLOSED_AT.plus(Duration.ofDays(14)).plusSeconds(7200),
                DANA);
        store.participants(EXECUTION_2075, 3);

        store.execution(EXECUTION_CANCELLED, ALGEBRA_EXAM, "9999", ExecutionStatus.CANCELLED,
                CLOSED_AT.plus(Duration.ofDays(20)), CLOSED_AT.plus(Duration.ofDays(20)), DANA);

        store.execution(EXECUTION_7390, JAVA_EXAM, "7390", ExecutionStatus.CLOSED,
                CLOSED_AT.minus(Duration.ofDays(3)), CLOSED_AT.minus(Duration.ofDays(3)).plusSeconds(5400),
                AVI);
        store.participants(EXECUTION_7390, 8);

        service = new TeacherResultsService(store);
    }

    // ===================== The list ======================================

    @Nested
    @DisplayName("RESULTS_EXAMS_GET")
    class Listing {

        @Test
        @DisplayName("a teacher sees the exams she wrote and nobody else's")
        void listsOnlyHerOwn() {
            TeacherResults results = payloadOf(service.exams(teacher(DANA), request(null)));

            assertThat(results.exams()).extracting(ExamResultRow::displayId)
                    .containsExactly("101101", "101102");
            assertThat(results.exams()).extracting(ExamResultRow::displayId)
                    .doesNotContain("202101");
        }

        @Test
        @DisplayName("⚑ a sitting run by another teacher still belongs to the exam's author (S-35)")
        void sittingsRunByOthersAreListed() {
            TeacherResults results = payloadOf(service.exams(teacher(DANA), request(null)));

            ExecutionResultRow foreign = executionOf(results, "101101", EXECUTION_4821);
            assertThat(foreign.code4()).isEqualTo("4821");
            assertThat(foreign.releasedByAnotherTeacher())
                    .as("Michal released it, Dana wrote the exam: she sees it, and it says so")
                    .isTrue();
        }

        @Test
        @DisplayName("a sitting the author released herself is not flagged as somebody else's")
        void herOwnSittingIsNotFlagged() {
            TeacherResults results = payloadOf(service.exams(teacher(DANA), request(null)));

            assertThat(executionOf(results, "101101", EXECUTION_2075).releasedByAnotherTeacher())
                    .isFalse();
        }

        @Test
        @DisplayName("cancelled sittings never appear (H15.2)")
        void cancelledSittingsAreExcluded() {
            TeacherResults results = payloadOf(service.exams(teacher(DANA), request(null)));

            assertThat(results.exams().get(0).executions())
                    .extracting(ExecutionResultRow::executionId)
                    .containsExactly(EXECUTION_2075, EXECUTION_4821)
                    .doesNotContain(EXECUTION_CANCELLED);
        }

        @Test
        @DisplayName("an exam that was never released keeps its place with an empty list")
        void neverReleasedExamStays() {
            TeacherResults results = payloadOf(service.exams(teacher(DANA), request(null)));

            ExamResultRow drawer = results.exams().get(1);
            assertThat(drawer.displayId()).isEqualTo("101102");
            assertThat(drawer.neverReleased()).isTrue();
        }

        @Test
        @DisplayName("each sitting carries its counts and whether its statistics are frozen")
        void countsAndStatisticsFlag() {
            TeacherResults results = payloadOf(service.exams(teacher(DANA), request(null)));

            ExecutionResultRow graded = executionOf(results, "101101", EXECUTION_4821);
            assertThat(graded.participants()).isEqualTo(8);
            assertThat(graded.gradedCount()).isEqualTo(8);
            assertThat(graded.hasStatistics()).isTrue();
            assertThat(graded.state()).isEqualTo(ExecutionState.CLOSED);

            ExecutionResultRow live = executionOf(results, "101101", EXECUTION_2075);
            assertThat(live.participants()).isEqualTo(3);
            assertThat(live.gradedCount()).as("nothing marked yet").isZero();
            assertThat(live.hasStatistics()).isFalse();
            assertThat(live.state()).isEqualTo(ExecutionState.LIVE);
        }

        @Test
        @DisplayName("a teacher who has written nothing gets an empty answer, not an error")
        void teacherWithNoExams() {
            TeacherResults results = payloadOf(service.exams(teacher(MICHAL), request(null)));

            assertThat(results.isEmpty()).isTrue();
            assertThat(results.totalExecutions()).isZero();
        }

        @Test
        @DisplayName("a student is refused by the role gate")
        void studentsAreRefused() {
            CallerContext maya = CallerContext.authenticated(socket, MAYA, Role.STUDENT);

            assertThatThrownBy(() -> service.exams(maya, request(null)))
                    .isInstanceOf(AuthorizationException.class)
                    .satisfies(failure -> assertThat(
                            ((AuthorizationException) failure).errorCode())
                            .isEqualTo(ErrorCode.FORBIDDEN));
        }

        @Test
        @DisplayName("a coordinator is a teacher and may ask")
        void coordinatorsMayAsk() {
            CallerContext coordinator = CallerContext.authenticated(socket, DANA, Role.COORDINATOR);

            assertThat(service.exams(coordinator, request(null)).isOk()).isTrue();
        }

        @Test
        @DisplayName("an unauthenticated caller is refused before anything is read")
        void anonymousIsRefused() {
            assertThatThrownBy(() -> service.exams(CallerContext.anonymous(socket), request(null)))
                    .isInstanceOf(AuthorizationException.class);
        }
    }

    // ===================== One execution =================================

    @Nested
    @DisplayName("RESULTS_EXECUTION_GET")
    class Detail {

        @Test
        @DisplayName("⚑ the author opens a sitting another teacher ran, and gets its results (S-35)")
        void authorOpensSomebodyElsesSitting() {
            Message response = service.execution(teacher(DANA), ask(EXECUTION_4821));

            ExecutionResults results = payloadOf(response);
            assertThat(results.rows()).hasSize(8);
            assertThat(results.examName()).isEqualTo("מבחן אמצע: אלגברה");
            assertThat(results.execution().code4()).isEqualTo("4821");
            assertThat(results.execution().releasedByAnotherTeacher()).isTrue();
            assertThat(results.hasStatistics()).isTrue();
        }

        @Test
        @DisplayName("⚑ a teacher who did not write the exam sees nothing, and cannot tell it exists")
        void nonAuthorGetsNothing() {
            Message refused = service.execution(teacher(AVI), ask(EXECUTION_4821));
            Message neverExisted = service.execution(teacher(AVI), ask(999_999L));

            assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(refused.errorMessage())
                    .as("the same sentence for both, so the verb is not a membership oracle")
                    .isEqualTo(neverExisted.errorMessage())
                    .isEqualTo(ResultsMessages.NO_SUCH_EXECUTION);
            assertThat(refused.getErrorCode()).isEqualTo(neverExisted.getErrorCode());
        }

        @Test
        @DisplayName("a cancelled sitting answers NOT_FOUND even to its exam's author (H15.2)")
        void cancelledIsNotFound() {
            Message response = service.execution(teacher(DANA), ask(EXECUTION_CANCELLED));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("rows carry the override justification on the teacher path (S-23)")
        void justificationIsVisibleToTheTeacher() {
            ExecutionResults results = payloadOf(service.execution(teacher(DANA), ask(EXECUTION_4821)));

            StudentGradeRow adjusted = results.rows().stream()
                    .filter(row -> row.overrideReason() != null)
                    .findFirst()
                    .orElseThrow();
            assertThat(adjusted.autoScore()).as("the machine's score survives an override").isEqualTo(45);
            assertThat(adjusted.finalScore()).isEqualTo(55);
            assertThat(adjusted.effectiveScore()).isEqualTo(55);
            assertThat(adjusted.state()).isEqualTo(GradeState.APPROVED);
        }

        @Test
        @DisplayName("rows arrive in the order the read produced, by student name")
        void rowsKeepTheirOrder() {
            ExecutionResults results = payloadOf(service.execution(teacher(DANA), ask(EXECUTION_4821)));

            assertThat(results.rows()).extracting(StudentGradeRow::studentName)
                    .startsWith("Daniel Shapira", "Itay Regev");
        }

        @Test
        @DisplayName("an unmarked sitting answers OK with no statistics rather than an error")
        void gradingUnfinishedIsAState() {
            ExecutionResults results = payloadOf(service.execution(teacher(DANA), ask(EXECUTION_2075)));

            assertThat(results.hasStatistics()).isFalse();
            assertThat(results.statistics()).isEmpty();
            assertThat(results.isUnmarked()).isTrue();
            assertThat(results.execution().participants())
                    .as("she still learns three students sat it")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a malformed payload is a validation error, not an exception")
        void malformedPayload() {
            Message response = service.execution(teacher(DANA),
                    Message.request(Verb.RESULTS_EXECUTION_GET, "4821"));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage()).isEqualTo(ResultsMessages.MALFORMED_REQUEST);
        }

        @Test
        @DisplayName("a student is refused by the role gate before any id is read")
        void studentsAreRefused() {
            CallerContext maya = CallerContext.authenticated(socket, MAYA, Role.STUDENT);

            assertThatThrownBy(() -> service.execution(maya, ask(EXECUTION_4821)))
                    .isInstanceOf(AuthorizationException.class);
        }
    }

    // ===================== The statistics rule ===========================

    @Nested
    @DisplayName("statistics are read, never recomputed")
    class Frozen {

        @Test
        @DisplayName("⚑ the stored figures are served even when the rows say otherwise (H14.4)")
        void storedValuesWinOverAnyRecomputation() {
            // Every row scores 100. A service that recomputed would answer mean 100, sigma 0,
            // and pass rate 1.0 — all plausible, all wrong. The stored record says otherwise
            // and the stored record is what F8.5 froze.
            InMemoryTeacherResultsStore contradictory = new InMemoryTeacherResultsStore();
            contradictory.exam(ALGEBRA_EXAM, "101101", "11", "אלגברה", "Algebra", DANA);
            contradictory.execution(EXECUTION_4821, ALGEBRA_EXAM, "4821", ExecutionStatus.CLOSED,
                    CLOSED_AT.minus(Duration.ofHours(2)), CLOSED_AT, DANA);
            contradictory.participants(EXECUTION_4821, 8);
            contradictory.statistics(EXECUTION_4821, FROZEN);
            for (int i = 0; i < 8; i++) {
                contradictory.row(EXECUTION_4821, new StudentResultRow(100 + i, 2000 + i,
                        "Student " + i, 100, null, GradeStatus.APPROVED, null, null, CLOSED_AT, 40));
            }

            ExecutionResults answer = payloadOf(new TeacherResultsService(contradictory)
                    .execution(teacher(DANA), ask(EXECUTION_4821)));
            ResultStatistics served = answer.statistics().orElseThrow();

            assertThat(served.mean()).as("stored, not the rows' 100").isEqualTo(72.5);
            assertThat(served.median()).isEqualTo(72.5);
            assertThat(served.standardDeviation())
                    .as("population sigma as frozen; a recomputation over these rows gives 0")
                    .isEqualTo(17.5);
            assertThat(served.min()).isEqualTo(45);
            assertThat(served.max()).isEqualTo(100);
            assertThat(served.passRate()).isEqualTo(0.875);
            assertThat(served.deciles()).containsExactly(0, 0, 0, 0, 1, 1, 1, 2, 1, 2);
        }

        @Test
        @DisplayName("the population and the pass numerator come from the stored record, not the rows")
        void countAndPassCountAreReconstituted() {
            ExecutionResults answer = payloadOf(service.execution(teacher(DANA), ask(EXECUTION_4821)));
            ResultStatistics served = answer.statistics().orElseThrow();

            assertThat(served.count())
                    .as("the deciles account for exactly the population the figures cover")
                    .isEqualTo(8);
            assertThat(served.passCount())
                    .as("7 of 8, from the stored rate — never by re-applying the pass mark")
                    .isEqualTo(7);
            assertThat(served.passPercent()).isEqualTo(87.5);
        }

        @Test
        @DisplayName("a stored record that is not ten buckets is treated as absent, calmly")
        void malformedStoredRecordFallsBackToUnfinished() {
            store.statistics(EXECUTION_4821,
                    new ExecutionStats(72.5, 72.5, 17.5, 45, 100, 0.875, List.of(1, 2, 3)));

            ExecutionResults results = payloadOf(service.execution(teacher(DANA), ask(EXECUTION_4821)));

            assertThat(results.statistics()).isEmpty();
            assertThat(results.rows()).as("the marked papers are still shown").hasSize(8);
        }
    }

    @Test
    @DisplayName("both verbs are registered")
    void registersBothVerbs() {
        MessageRouter router = new MessageRouter(new SessionManager());

        service.registerOn(router);

        assertThat(router.isRegistered(Verb.RESULTS_EXAMS_GET)).isTrue();
        assertThat(router.isRegistered(Verb.RESULTS_EXECUTION_GET)).isTrue();
        assertThat(router.isOpen(Verb.RESULTS_EXAMS_GET))
                .as("results are never open to an unauthenticated caller")
                .isFalse();
    }

    // ===================== Fixture =======================================

    /** §9.1's eight grades, including the one override that moves the pass rate to 7/8. */
    private void seedEightGrades() {
        store.row(EXECUTION_4821, grade(1, 2001, "Daniel Shapira", 75, null, null));
        store.row(EXECUTION_4821, grade(2, 2002, "Itay Regev", 70, null, null));
        store.row(EXECUTION_4821, grade(3, 2003, "Lior Gabay", 100, null, null));
        store.row(EXECUTION_4821, grade(4, 2004, "Maya Levi", 60, null, null));
        store.row(EXECUTION_4821, grade(5, 2005, "Noa Friedman", 90, null, null));
        store.row(EXECUTION_4821, grade(6, 2006, "Omer Katz", 45, null, null));
        store.row(EXECUTION_4821, grade(7, 2007, "Shira Dahan", 85, null, null));
        store.row(EXECUTION_4821, grade(8, 2008, "Yael Azulay", 45, 55,
                "בשאלה 11011 ניתן ניקוד חלקי."));
    }

    private static StudentResultRow grade(long gradeId, long studentId, String name,
                                          int auto, Integer finalScore, String reason) {
        return new StudentResultRow(gradeId, studentId, name, auto, finalScore,
                GradeStatus.APPROVED, reason, null, CLOSED_AT.plus(Duration.ofDays(2)), 55);
    }

    private CallerContext teacher(long userId) {
        return CallerContext.authenticated(socket, userId, Role.TEACHER);
    }

    private static Message request(Object payload) {
        return Message.request(Verb.RESULTS_EXAMS_GET, payload);
    }

    private static Message ask(long executionId) {
        return Message.request(Verb.RESULTS_EXECUTION_GET,
                new ExecutionResultsRequest(executionId));
    }

    @SuppressWarnings("unchecked")
    private static <T> T payloadOf(Message response) {
        assertThat(response.isOk())
                .as("expected OK but got %s: %s", response.getErrorCode(), response.errorMessage())
                .isTrue();
        return (T) response.getPayload();
    }

    private static ExecutionResultRow executionOf(TeacherResults results, String displayId,
                                                  long executionId) {
        Optional<ExamResultRow> exam = results.exams().stream()
                .filter(row -> row.displayId().equals(displayId))
                .findFirst();
        assertThat(exam).as("exam %s is in the list", displayId).isPresent();
        return exam.orElseThrow().executions().stream()
                .filter(row -> row.executionId() == executionId)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "execution " + executionId + " is missing from exam " + displayId));
    }
}
