package server.features.reports;

import common.dto.auth.Role;
import common.dto.report.ReportDimension;
import common.dto.report.ReportRequest;
import common.dto.report.ReportResult;
import common.dto.report.ReportSubjects;
import common.dto.report.ReportSubjectsRequest;
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
 * {@link ReportService} — the principal's gate, and nothing but (E15.3 ⚑ — F9.3, S-7).
 *
 * <p>The rule this suite exists for is the role gate, and it is tested in both directions
 * because half of it is easy to get right by accident. A build that never wired the handler at
 * all would pass "a teacher is refused"; only the positive test catches that, and only the
 * negatives catch a gate that was written as {@code requireAuthenticated}.
 *
 * <p>The three refusals are separately asserted rather than parameterised into one, because
 * they are three different mistakes a future author could make: admitting the exam author,
 * admitting the coordinator who approved it, and admitting the student it is about. The last is
 * the one that matters most — a student reaching {@code REPORT_GET} with another student's id
 * would be reading school-wide statistics through a screen she was never offered.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceTest {

    private static final Instant WHEN = Instant.parse("2026-08-07T06:00:00Z");
    private static final long PRINCIPAL = 1;
    private static final long DANA = 2;
    private static final long MAYA = 11;

    /** SEED_CONTENT section 9.1's frozen record. */
    private static final ExecutionStats SEEDED = new ExecutionStats(
            72.5, 72.5, 17.5, 45, 100, 0.875, List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));

    @Mock
    private ConnectionToClient socket;

    private ReportService service;

    @BeforeEach
    void setUp() {
        InMemoryReportStore store = new InMemoryReportStore()
                .teacher(DANA, "דנה כהן", "dana.cohen")
                .student(MAYA, "מאיה לוי", "maya.levi")
                .course("11", "אלגברה")
                // A course nobody has sat: the E15.5 degenerate case, on the wire.
                .course("12", "חדו\"א")
                .sitting(1, "4821", WHEN, "מבחן אמצע: אלגברה", "11", DANA,
                        ExecutionStatus.CLOSED, SEEDED)
                .sat(1, MAYA)
                .participants(1, 8);
        service = new ReportService(new ReportEngine(store, ReportStrategies.all()));
    }

    // ===================== The gate ⚑ ====================================

    @Nested
    @DisplayName("who may ask ⚑ (F9.3, S-7)")
    class RoleGate {

        @Test
        @DisplayName("⚑ the principal gets her subjects and her report")
        void principalIsAdmitted() {
            Message subjects = service.subjects(principal(), subjectsAsk(ReportDimension.BY_COURSE));
            Message report = service.report(principal(),
                    reportAsk(ReportDimension.BY_COURSE, "11"));

            assertThat(subjects.isOk()).isTrue();
            assertThat(((ReportSubjects) subjects.getPayload()).subjects()).isNotEmpty();
            assertThat(report.isOk()).isTrue();
            assertThat(((ReportResult) report.getPayload()).rows()).hasSize(1);
        }

        @Test
        @DisplayName("⚑ a teacher is refused: FORBIDDEN, not an empty list")
        void teacherIsRefused() {
            CallerContext dana = CallerContext.authenticated(socket, DANA, Role.TEACHER);

            assertThatThrownBy(() ->
                    service.subjects(dana, subjectsAsk(ReportDimension.BY_TEACHER)))
                    .isInstanceOf(AuthorizationException.class)
                    .satisfies(failure -> assertThat(
                            ((AuthorizationException) failure).errorCode())
                            .isEqualTo(ErrorCode.FORBIDDEN));
            assertThatThrownBy(() ->
                    service.report(dana, reportAsk(ReportDimension.BY_TEACHER, "2")))
                    .isInstanceOf(AuthorizationException.class);
        }

        @Test
        @DisplayName("⚑ a student is refused, including about herself")
        void studentIsRefused() {
            CallerContext maya = CallerContext.authenticated(socket, MAYA, Role.STUDENT);

            assertThatThrownBy(() ->
                    service.subjects(maya, subjectsAsk(ReportDimension.BY_STUDENT)))
                    .isInstanceOf(AuthorizationException.class)
                    .satisfies(failure -> assertThat(
                            ((AuthorizationException) failure).errorCode())
                            .isEqualTo(ErrorCode.FORBIDDEN));
            assertThatThrownBy(() -> service.report(maya,
                    reportAsk(ReportDimension.BY_STUDENT, String.valueOf(MAYA))))
                    .as("her own id does not open the door: this is a school-wide statistics "
                            + "screen, not her grades")
                    .isInstanceOf(AuthorizationException.class);
        }

        @Test
        @DisplayName("a coordinator is refused too: coordinating a subject is not reading reports")
        void coordinatorIsRefused() {
            CallerContext rina = CallerContext.authenticated(socket, 3, Role.COORDINATOR);

            assertThatThrownBy(() ->
                    service.subjects(rina, subjectsAsk(ReportDimension.BY_COURSE)))
                    .isInstanceOf(AuthorizationException.class);
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"TEACHER", "COORDINATOR", "STUDENT"})
        @DisplayName("no role but the principal reaches either verb")
        void everyOtherRoleIsRefused(Role role) {
            CallerContext caller = CallerContext.authenticated(socket, 42, role);

            assertThatThrownBy(() ->
                    service.subjects(caller, subjectsAsk(ReportDimension.BY_COURSE)))
                    .isInstanceOf(AuthorizationException.class);
            assertThatThrownBy(() ->
                    service.report(caller, reportAsk(ReportDimension.BY_COURSE, "11")))
                    .isInstanceOf(AuthorizationException.class);
        }

        @Test
        @DisplayName("no session at all is UNAUTHORIZED rather than FORBIDDEN")
        void anonymousIsUnauthorized() {
            assertThatThrownBy(() -> service.subjects(CallerContext.anonymous(socket),
                    subjectsAsk(ReportDimension.BY_COURSE)))
                    .isInstanceOf(AuthorizationException.class)
                    .satisfies(failure -> assertThat(
                            ((AuthorizationException) failure).errorCode())
                            .isEqualTo(ErrorCode.UNAUTHORIZED));
        }
    }

    // ===================== The answers ===================================

    @Nested
    @DisplayName("the two verbs")
    class Verbs {

        @Test
        @DisplayName("a malformed payload is VALIDATION, and the sentence says what to do next")
        void malformedPayload() {
            Message subjects = service.subjects(principal(),
                    Message.request(Verb.REPORT_SUBJECTS_GET, "not a request"));
            Message report = service.report(principal(),
                    Message.request(Verb.REPORT_GET, null));

            assertThat(subjects.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(subjects.errorMessage()).isEqualTo(ReportMessages.MALFORMED_REQUEST);
            assertThat(report.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
        }

        @Test
        @DisplayName("a subject that does not exist is NOT_FOUND, and says to pick from the list")
        void unknownSubject() {
            Message answer = service.report(principal(),
                    reportAsk(ReportDimension.BY_COURSE, "zz"));

            assertThat(answer.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(answer.errorMessage()).isEqualTo(ReportMessages.NO_SUCH_SUBJECT);
            assertThat(answer.errorMessage())
                    .as("every refusal says what to do next")
                    .contains("Pick a subject");
        }

        @Test
        @DisplayName("a subject with nothing to compare is OK with no rows, not an error")
        void emptyReportIsAnAnswer() {
            Message answer = service.report(principal(), reportAsk(ReportDimension.BY_COURSE, "12"));
            ReportResult result = (ReportResult) answer.getPayload();

            assertThat(answer.isOk())
                    .as("'this course has never had a sitting close' is a true answer to what "
                            + "she asked, and refusing it would read as a fault")
                    .isTrue();
            assertThat(result.isEmpty()).isTrue();
            assertThat(result.subject().label()).isEqualTo("חדו\"א");
            assertThat(result.summary().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("the answer echoes the dimension it was asked about")
        void answerEchoesTheDimension() {
            for (ReportDimension dimension : ReportDimension.values()) {
                Message answer = service.subjects(principal(), subjectsAsk(dimension));
                assertThat(((ReportSubjects) answer.getPayload()).dimension())
                        .isEqualTo(dimension);
            }
        }

        @Test
        @DisplayName("⚑ the served figures are the stored ones, straight through the handler")
        void figuresAreTheStoredOnes() {
            Message answer = service.report(principal(),
                    reportAsk(ReportDimension.BY_COURSE, "11"));
            ReportResult result = (ReportResult) answer.getPayload();

            assertThat(result.rows().get(0).statistics().mean()).isEqualTo(72.5);
            assertThat(result.rows().get(0).statistics().standardDeviation()).isEqualTo(17.5);
            assertThat(result.summary().mean()).isEqualTo(72.5);
        }
    }

    // ===================== Registration ==================================

    @Test
    @DisplayName("both verbs are registered, and neither is open to an unauthenticated caller")
    void registration() {
        MessageRouter router = new MessageRouter(new SessionManager());

        service.registerOn(router);

        assertThat(router.isRegistered(Verb.REPORT_SUBJECTS_GET)).isTrue();
        assertThat(router.isRegistered(Verb.REPORT_GET)).isTrue();
        assertThat(router.isOpen(Verb.REPORT_SUBJECTS_GET)).isFalse();
        assertThat(router.isOpen(Verb.REPORT_GET)).isFalse();
    }

    // ===================== Fixture =======================================

    private CallerContext principal() {
        return CallerContext.authenticated(socket, PRINCIPAL, Role.PRINCIPAL);
    }

    private static Message subjectsAsk(ReportDimension dimension) {
        return Message.request(Verb.REPORT_SUBJECTS_GET, new ReportSubjectsRequest(dimension));
    }

    private static Message reportAsk(ReportDimension dimension, String subjectId) {
        return Message.request(Verb.REPORT_GET, new ReportRequest(dimension, subjectId));
    }
}
