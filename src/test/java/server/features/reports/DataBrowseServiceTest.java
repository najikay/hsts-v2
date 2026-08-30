package server.features.reports;

import common.dto.auth.Role;
import common.dto.report.DataExamRow;
import common.dto.report.DataExams;
import common.dto.report.DataResults;
import common.dto.report.ReportRow;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DataBrowseService} — the principal's browse, and her gate (E15.2 ⚑ — F9.3, S-7, T-11).
 *
 * <p>The rule this suite exists for is the role gate, tested in both directions for the reason
 * {@code ReportServiceTest} gives: a build that never wired the handler would pass "a teacher is
 * refused", and only the positive test catches that.
 *
 * <p>The second rule it exists for is that the browse and the reports must agree about what a
 * sitting is. Both fixtures below carry a <b>cancelled sitting with statistics frozen on it</b>,
 * which is the only fixture that can tell "cancelled is excluded" apart from "cancelled sittings
 * have no statistics anyway" (H15.2 ⚑), and both assert the same exclusion the reports assert.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataBrowseServiceTest {

    private static final Instant SPRING = Instant.parse("2026-03-10T07:00:00Z");
    private static final Instant SUMMER = Instant.parse("2026-08-07T06:00:00Z");

    private static final long PRINCIPAL = 1;
    private static final long DANA = 2;
    private static final long MAYA = 11;

    /** SEED_CONTENT section 9.1's frozen record. */
    private static final ExecutionStats SEEDED = new ExecutionStats(
            72.5, 72.5, 17.5, 45, 100, 0.875, List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));

    /** A second, quieter sitting: 50, 60, 70, 80. */
    private static final ExecutionStats QUIET = new ExecutionStats(
            65, 65, Math.sqrt(125), 50, 80, 0.75, List.of(0, 0, 0, 0, 0, 1, 1, 1, 1, 0));

    @Mock
    private ConnectionToClient socket;

    private DataBrowseService service;

    @BeforeEach
    void setUp() {
        InMemoryReportStore store = new InMemoryReportStore()
                .teacher(DANA, "דנה כהן", "dana.cohen")
                .student(MAYA, "מאיה לוי", "maya.levi")
                .course("11", "אלגברה")
                .course("12", "חדו\"א")
                .exam("101101", "11", "מבחן אמצע: אלגברה", "דנה כהן", 2, SUMMER, 1102L)
                // An exam nobody has ever released: it belongs in a catalogue all the same.
                .exam("101201", "12", "בוחן: גבולות", "דנה כהן", 1, SPRING, 1201L)
                .sitting(1, "4821", SPRING, "מבחן אמצע: אלגברה", "11", DANA,
                        ExecutionStatus.CLOSED, SEEDED)
                .sitting(2, "5150", SUMMER, "בוחן: אי-שוויונות", "11", DANA,
                        ExecutionStatus.CLOSED, QUIET)
                // The contrived row: cancelled, and carrying statistics anyway (H15.2 ⚑).
                .sitting(3, "9999", SUMMER, "מבחן שבוטל", "11", DANA,
                        ExecutionStatus.CANCELLED, SEEDED)
                // Live, and therefore not a result yet.
                .sitting(4, "2075", SUMMER, "מבחן חי", "11", DANA, ExecutionStatus.LIVE, null)
                .sat(1, MAYA)
                .sat(2, MAYA)
                .participants(1, 8)
                .participants(2, 4);
        service = new DataBrowseService(store);
    }

    // ===================== The gate ⚑ ====================================

    @Nested
    @DisplayName("who may browse ⚑ (F9.3, S-7)")
    class RoleGate {

        @Test
        @DisplayName("⚑ the principal gets the catalogue and the closed sittings")
        void principalIsAdmitted() {
            Message exams = service.exams(principal(), ask(Verb.DATA_EXAMS_GET));
            Message results = service.results(principal(), ask(Verb.DATA_RESULTS_GET));

            assertThat(exams.isOk()).isTrue();
            assertThat(((DataExams) exams.getPayload()).exams()).isNotEmpty();
            assertThat(results.isOk()).isTrue();
            assertThat(((DataResults) results.getPayload()).sittings()).isNotEmpty();
        }

        @Test
        @DisplayName("⚑ a teacher is refused: FORBIDDEN, not her own exams")
        void teacherIsRefused() {
            CallerContext dana = CallerContext.authenticated(socket, DANA, Role.TEACHER);

            assertThatThrownBy(() -> service.exams(dana, ask(Verb.DATA_EXAMS_GET)))
                    .as("she has RESULTS_EXAMS_GET for her own exams; this verb is not a "
                            + "wider version of it, it is not hers at all")
                    .isInstanceOf(AuthorizationException.class)
                    .satisfies(failure -> assertThat(
                            ((AuthorizationException) failure).errorCode())
                            .isEqualTo(ErrorCode.FORBIDDEN));
            assertThatThrownBy(() -> service.results(dana, ask(Verb.DATA_RESULTS_GET)))
                    .isInstanceOf(AuthorizationException.class);
        }

        @Test
        @DisplayName("⚑ a student is refused, including from the sittings she sat")
        void studentIsRefused() {
            CallerContext maya = CallerContext.authenticated(socket, MAYA, Role.STUDENT);

            assertThatThrownBy(() -> service.results(maya, ask(Verb.DATA_RESULTS_GET)))
                    .as("her own sittings are not the point: this list carries every class's "
                            + "statistics, which is not a student's to read")
                    .isInstanceOf(AuthorizationException.class)
                    .satisfies(failure -> assertThat(
                            ((AuthorizationException) failure).errorCode())
                            .isEqualTo(ErrorCode.FORBIDDEN));
            assertThatThrownBy(() -> service.exams(maya, ask(Verb.DATA_EXAMS_GET)))
                    .isInstanceOf(AuthorizationException.class);
        }

        @Test
        @DisplayName("a coordinator is refused too: approving exams is not browsing the school")
        void coordinatorIsRefused() {
            CallerContext rina = CallerContext.authenticated(socket, 3, Role.COORDINATOR);

            assertThatThrownBy(() -> service.exams(rina, ask(Verb.DATA_EXAMS_GET)))
                    .isInstanceOf(AuthorizationException.class);
            assertThatThrownBy(() -> service.results(rina, ask(Verb.DATA_RESULTS_GET)))
                    .isInstanceOf(AuthorizationException.class);
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEACHER", "COORDINATOR", "STUDENT"})
        @DisplayName("no role but the principal reaches either verb")
        void everyOtherRoleIsRefused(Role role) {
            CallerContext caller = CallerContext.authenticated(socket, 42, role);

            assertThatThrownBy(() -> service.exams(caller, ask(Verb.DATA_EXAMS_GET)))
                    .isInstanceOf(AuthorizationException.class);
            assertThatThrownBy(() -> service.results(caller, ask(Verb.DATA_RESULTS_GET)))
                    .isInstanceOf(AuthorizationException.class);
        }

        @Test
        @DisplayName("no session at all is UNAUTHORIZED rather than FORBIDDEN")
        void anonymousIsUnauthorized() {
            CallerContext nobody = CallerContext.anonymous(socket);

            assertThatThrownBy(() -> service.exams(nobody, ask(Verb.DATA_EXAMS_GET)))
                    .isInstanceOf(AuthorizationException.class)
                    .satisfies(failure -> assertThat(
                            ((AuthorizationException) failure).errorCode())
                            .isEqualTo(ErrorCode.UNAUTHORIZED));
            assertThatThrownBy(() -> service.results(nobody, ask(Verb.DATA_RESULTS_GET)))
                    .isInstanceOf(AuthorizationException.class)
                    .satisfies(failure -> assertThat(
                            ((AuthorizationException) failure).errorCode())
                            .isEqualTo(ErrorCode.UNAUTHORIZED));
        }
    }

    // ===================== The exam catalogue ============================

    @Nested
    @DisplayName("DATA_EXAMS_GET")
    class Exams {

        @Test
        @DisplayName("every exam, ordered by display id, released or not")
        void everyExam() {
            DataExams answer = (DataExams) service.exams(principal(),
                    ask(Verb.DATA_EXAMS_GET)).getPayload();

            assertThat(answer.exams()).extracting(DataExamRow::displayId6)
                    .as("the Calculus quiz has never been released and is listed all the same")
                    .containsExactly("101101", "101201");
            assertThat(answer.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("⚑ every row carries a version to open, or the catalogue is a dead end (A2)")
        void everyRowIsOpenable() {
            DataExams answer = (DataExams) service.exams(principal(),
                    ask(Verb.DATA_EXAMS_GET)).getPayload();

            assertThat(answer.exams()).extracting(DataExamRow::latestVersionId)
                    .as("the latest version's id, carried through from SchoolExam so the "
                            + "principal's Exams row can open EXAM_PREVIEW_GET, which is "
                            + "addressed by version (REPORTS amendment A2, 2026-08-30)")
                    .containsExactly(1102L, 1201L);
            assertThat(answer.exams()).allSatisfy(row ->
                    assertThat(row.isOpenable()).isTrue());
        }

        @Test
        @DisplayName("a row carries its course, its author and its version count and no more")
        void rowContents() {
            DataExams answer = (DataExams) service.exams(principal(),
                    ask(Verb.DATA_EXAMS_GET)).getPayload();
            DataExamRow row = answer.exams().get(0);

            assertThat(row.examName()).isEqualTo("מבחן אמצע: אלגברה");
            assertThat(row.courseCode()).isEqualTo("11");
            assertThat(row.courseName()).isEqualTo("אלגברה");
            assertThat(row.authorName()).isEqualTo("דנה כהן");
            assertThat(row.versions()).isEqualTo(2);
            assertThat(row.hasBeenRevised()).isTrue();
            assertThat(row.lastVersionAt()).isEqualTo(SUMMER);
            assertThat(answer.exams().get(1).hasBeenRevised())
                    .as("written once and never rewritten")
                    .isFalse();
        }

        @Test
        @DisplayName("a payload that arrives anyway is ignored rather than refused")
        void payloadIsIgnored() {
            Message answer = service.exams(principal(),
                    Message.request(Verb.DATA_EXAMS_GET, "something nobody asked for"));

            assertThat(answer.isOk())
                    .as("there is no field on this request, so there is nothing to validate and "
                            + "nothing a client could widen")
                    .isTrue();
        }

        @Test
        @DisplayName("a school with nothing written yet is OK and empty, never an error")
        void emptyCatalogue() {
            DataBrowseService bare = new DataBrowseService(new InMemoryReportStore());

            Message answer = bare.exams(principal(), ask(Verb.DATA_EXAMS_GET));

            assertThat(answer.isOk()).isTrue();
            assertThat(((DataExams) answer.getPayload()).isEmpty()).isTrue();
        }
    }

    // ===================== The closed sittings ===========================

    @Nested
    @DisplayName("DATA_RESULTS_GET")
    class Results {

        @Test
        @DisplayName("⚑ cancelled, live and unmarked sittings are all absent (H15.2)")
        void onlyClosedAndFrozen() {
            DataResults answer = served();

            assertThat(answer.sittings()).extracting(ReportRow::code4)
                    .as("the cancelled sitting carries statistics and is still excluded")
                    .containsExactly("5150", "4821");
        }

        @Test
        @DisplayName("newest first, which is the opposite of a report's ordering and deliberate")
        void newestFirst() {
            assertThat(served().sittings())
                    .extracting(ReportRow::openAt)
                    .containsExactly(SUMMER, SPRING);
        }

        @Test
        @DisplayName("⚑ the figures are the stored ones, through the same mapping the reports use")
        void figuresAreTheStoredOnes() {
            ReportRow older = served().sittings().get(1);

            assertThat(older.statistics().mean()).isEqualTo(72.5);
            assertThat(older.statistics().median()).isEqualTo(72.5);
            assertThat(older.statistics().standardDeviation())
                    .as("population sigma, divisor n, exactly as it was frozen (H14.4 ⚑)")
                    .isEqualTo(17.5);
            assertThat(older.statistics().deciles()).containsExactly(0, 0, 0, 0, 1, 1, 1, 2, 1, 2);
            assertThat(older.statistics().passCount()).isEqualTo(7);
        }

        @Test
        @DisplayName("participants are the attempts, so an unmarked paper still counts a student")
        void participantsAreAttempts() {
            ReportRow older = served().sittings().get(1);

            assertThat(older.participants()).isEqualTo(8);
            assertThat(older.statistics().count())
                    .as("eight sat and eight were marked here; the two numbers are still "
                            + "different questions")
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("a school where nothing has finished is OK and empty, never an error")
        void nothingFinished() {
            DataBrowseService bare = new DataBrowseService(new InMemoryReportStore());

            Message answer = bare.results(principal(), ask(Verb.DATA_RESULTS_GET));

            assertThat(answer.isOk()).isTrue();
            assertThat(((DataResults) answer.getPayload()).isEmpty()).isTrue();
        }

        private DataResults served() {
            return (DataResults) service.results(principal(),
                    ask(Verb.DATA_RESULTS_GET)).getPayload();
        }
    }

    // ===================== Registration ==================================

    @Test
    @DisplayName("both verbs are registered, and neither is open to an unauthenticated caller")
    void registration() {
        MessageRouter router = new MessageRouter(new SessionManager());

        service.registerOn(router);

        assertThat(router.isRegistered(Verb.DATA_EXAMS_GET)).isTrue();
        assertThat(router.isRegistered(Verb.DATA_RESULTS_GET)).isTrue();
        assertThat(router.isOpen(Verb.DATA_EXAMS_GET)).isFalse();
        assertThat(router.isOpen(Verb.DATA_RESULTS_GET)).isFalse();
    }

    // ===================== Fixture =======================================

    private CallerContext principal() {
        return CallerContext.authenticated(socket, PRINCIPAL, Role.PRINCIPAL);
    }

    private static Message ask(Verb verb) {
        return Message.request(verb, null);
    }
}
