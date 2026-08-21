package server.features.exam;

import common.dto.auth.Role;
import common.dto.exam.AttemptForm;
import common.dto.exam.AttemptStartRequest;
import common.dto.exam.AttemptState;
import common.dto.exam.AttentionReport;
import common.dto.exam.AttentionSummary;
import common.dto.exam.ExecutionMonitor;
import common.dto.exam.MonitorRequest;
import common.dto.exam.MonitorRow;
import common.dto.exam.SubmitAttemptRequest;
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
import server.db.entities.ExecutionStatus;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Attention events end to end on the server (E11.7 — F7.1b).
 *
 * <p>{@code AttentionTrackerTest} owns the client's debounce; this owns everything after the
 * message leaves it: the verb is authenticated, a report with no live attempt is a no-op and
 * not an error, reports accumulate per attempt, the summary reaches the monitor snapshot, and
 * every report pushes that snapshot to whoever is watching.
 *
 * <p>The two negative assertions matter as much as the positive ones, because F7.1b's rules
 * are mostly about what must <b>not</b> happen: no notification is raised, and nothing is
 * pushed to the student.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttentionEventsTest {

    private static final Instant T0 = Instant.parse("2026-08-21T09:00:00Z");
    private static final long EXECUTION = 5001L;
    private static final long EXAM_VERSION = 7001L;
    private static final long DANA = 1001L;
    private static final long MAYA = 2001L;
    private static final long NOAM = 2002L;
    private static final String COURSE = "21";
    private static final int DURATION = 45;

    @Mock
    private ConnectionToClient danaSocket;
    @Mock
    private ConnectionToClient mayaSocket;
    @Mock
    private ConnectionToClient noamSocket;

    private TestClock clock;
    private InMemoryExamStore store;
    private SessionManager sessions;
    private RecordingGateway gateway;
    private RecordingNotifier notifier;
    private AttemptService attempts;
    private MonitorService monitors;

    @BeforeEach
    void setUp() {
        clock = new TestClock(T0);
        store = new InMemoryExamStore();
        sessions = new SessionManager();
        gateway = new RecordingGateway(sessions);
        notifier = new RecordingNotifier();

        store.execution(EXECUTION, EXAM_VERSION, "4B7Q", ExecutionStatus.LIVE,
                T0.minus(Duration.ofMinutes(5)), T0.plus(Duration.ofHours(3)), DURATION, DANA, COURSE);
        store.paper(EXAM_VERSION, 4);
        store.addUser(DANA, "Dana Cohen", "214703951");
        store.addUser(MAYA, "Maya Levi", "374301851");
        store.addUser(NOAM, "Noam Bar", "301548202");
        store.enrol(MAYA, COURSE);
        store.enrol(NOAM, COURSE);

        attempts = new AttemptService(store, clock, new ManualScheduler(), gateway, notifier,
                AttemptFinalizedListener.NO_OP);
        monitors = new MonitorService(store, attempts, gateway, clock);
        attempts.publishTo(monitors);
    }

    // ===================== The verb ======================================

    @Nested
    @DisplayName("the verb")
    class TheVerb {

        @Test
        @DisplayName("is registered, and is not reachable without a session")
        void isAuthenticated() {
            MessageRouter router = new MessageRouter(sessions);
            attempts.registerOn(router);

            assertThat(router.isRegistered(Verb.ATTEMPT_ATTENTION)).isTrue();
            assertThat(router.isOpen(Verb.ATTEMPT_ATTENTION))
                    .as("an anonymous socket must not be able to report attention")
                    .isFalse();
        }

        @Test
        @DisplayName("an anonymous caller is refused")
        void anonymousIsRefused() {
            assertThatThrownBy(() -> attempts.attention(CallerContext.anonymous(mayaSocket),
                    Message.request(Verb.ATTEMPT_ATTENTION, new AttentionReport(4000))))
                    .isInstanceOf(AuthorizationException.class);
        }

        @Test
        @DisplayName("a payload that is not an AttentionReport is a validation error")
        void malformedPayloadIsRefused() {
            start(MAYA, "374301851");

            Message response = attempts.attention(student(MAYA),
                    Message.request(Verb.ATTEMPT_ATTENTION, "not a report"));

            assertThat(response.isError()).isTrue();
            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
        }

        @Test
        @DisplayName("a report from a student with no live attempt answers OK and does nothing ⚑")
        void noLiveAttemptIsANoOp() {
            // The normal shape of the race: she was away when her time ran out, so the
            // refocus that ends the absence arrives after the server force-submitted her.
            Message response = report(MAYA, 12_000);

            assertThat(response.isOk())
                    .as("this is normal, not an error: a correct client must not log failures")
                    .isTrue();
            assertThat(response.getPayload()).isNull();
            assertThat(attempts.registry().attentionCount()).isZero();
        }

        @Test
        @DisplayName("a report after she has handed in is a no-op too")
        void reportAfterSubmitIsANoOp() {
            long attemptId = start(MAYA, "374301851");
            submit(MAYA, attemptId);

            assertThat(report(MAYA, 9_000).isOk()).isTrue();
            assertThat(attempts.attentionOf(attemptId)).isEmpty();
        }
    }

    // ===================== Accumulation ==================================

    @Nested
    @DisplayName("accumulation")
    class Accumulation {

        @Test
        @DisplayName("one report becomes a summary of one absence")
        void firstReport() {
            long attemptId = start(MAYA, "374301851");

            report(MAYA, 12_000);

            AttentionSummary summary = attempts.attentionOf(attemptId).orElseThrow();
            assertThat(summary.count()).isEqualTo(1);
            assertThat(summary.totalAwayMillis()).isEqualTo(12_000);
            assertThat(summary.lastAt()).isEqualTo(T0);
        }

        @Test
        @DisplayName("further reports add up, and the time of the last one wins ⚑")
        void reportsAccumulate() {
            long attemptId = start(MAYA, "374301851");

            report(MAYA, 12_000);
            clock.advance(Duration.ofMinutes(3));
            report(MAYA, 20_000);
            clock.advance(Duration.ofMinutes(1));
            report(MAYA, 8_000);

            AttentionSummary summary = attempts.attentionOf(attemptId).orElseThrow();
            assertThat(summary.count()).isEqualTo(3);
            assertThat(summary.totalAwayMillis()).isEqualTo(40_000);
            assertThat(summary.lastAt()).isEqualTo(T0.plus(Duration.ofMinutes(4)));
            assertThat(summary.label()).isEqualTo("Left the exam view 3 times · 40s total");
        }

        @Test
        @DisplayName("summaries are per attempt: one student's absences never reach another's row")
        void summariesAreIsolated() {
            long mayas = start(MAYA, "374301851");
            long noams = start(NOAM, "301548202");

            report(MAYA, 5_000);
            report(MAYA, 5_000);
            report(NOAM, 1_000);

            assertThat(attempts.attentionOf(mayas).orElseThrow().count()).isEqualTo(2);
            assertThat(attempts.attentionOf(noams).orElseThrow().count()).isEqualTo(1);
        }

        @Test
        @DisplayName("the summary survives a resume — reconnecting does not wipe the history")
        void summarySurvivesResume() {
            long attemptId = start(MAYA, "374301851");
            report(MAYA, 12_000);

            AttemptForm resumed = resume(MAYA);

            assertThat(resumed.state()).isEqualTo(AttemptState.IN_PROGRESS);
            assertThat(attempts.attentionOf(attemptId).orElseThrow().count()).isEqualTo(1);
        }

        @Test
        @DisplayName("the summary outlives the attempt, like the C-4 flag does")
        void summaryOutlivesTheAttempt() {
            long attemptId = start(MAYA, "374301851");
            report(MAYA, 12_000);

            submit(MAYA, attemptId);

            assertThat(attempts.attentionOf(attemptId))
                    .as("a teacher opening the monitor after the exam must still see it")
                    .isPresent();
        }

        @Test
        @DisplayName("clearing the registry's monitor state drops flags and summaries together")
        void clearingDropsBoth() {
            long attemptId = start(MAYA, "374301851");
            report(MAYA, 12_000);
            attempts.registry().flag(attemptId, "11", "Algebra 11", T0);

            attempts.registry().clearFlags();

            assertThat(attempts.registry().attentionCount()).isZero();
            assertThat(attempts.registry().flagCount()).isZero();
        }
    }

    // ===================== The monitor ===================================

    @Nested
    @DisplayName("the teacher's monitor")
    class Monitor {

        @Test
        @DisplayName("the snapshot carries the summary on her row, and only on hers ⚑")
        void snapshotCarriesTheSummary() {
            start(MAYA, "374301851");
            start(NOAM, "301548202");
            report(MAYA, 12_000);
            report(MAYA, 28_000);

            ExecutionMonitor snapshot = monitor(DANA);

            MonitorRow maya = rowFor(snapshot, "Maya Levi");
            assertThat(maya.hasAttentionEvents()).isTrue();
            assertThat(maya.attention().count()).isEqualTo(2);
            assertThat(maya.attention().label())
                    .isEqualTo("Left the exam view 2 times · 40s total");

            MonitorRow noam = rowFor(snapshot, "Noam Bar");
            assertThat(noam.attention())
                    .as("a student who never left her window carries nothing, not a zero")
                    .isNull();
            assertThat(noam.hasAttentionEvents()).isFalse();
        }

        @Test
        @DisplayName("every report pushes a fresh snapshot to the watching teacher (NFR-18) ⚑")
        void reportPushesTheMonitor() {
            sessions.attach(DANA, Role.TEACHER, danaSocket);
            start(MAYA, "374301851");
            monitor(DANA);
            gateway.clear();

            report(MAYA, 12_000);

            assertThat(gateway.countFor(DANA, Verb.PUSH_MONITOR_UPDATED))
                    .as("the monitor repaints without anybody pressing anything")
                    .isEqualTo(1);
            ExecutionMonitor pushed = gateway
                    .firstPayload(DANA, Verb.PUSH_MONITOR_UPDATED, ExecutionMonitor.class)
                    .orElseThrow();
            assertThat(rowFor(pushed, "Maya Levi").attention().count()).isEqualTo(1);
        }

        @Test
        @DisplayName("nothing is pushed to the student, and no notification is raised (F7.1b)")
        void theStudentIsToldNothing() {
            sessions.attach(DANA, Role.TEACHER, danaSocket);
            sessions.attach(MAYA, Role.STUDENT, mayaSocket);
            start(MAYA, "374301851");
            monitor(DANA);
            gateway.clear();

            report(MAYA, 12_000);

            assertThat(gateway.sent())
                    .as("no student-facing UI anywhere means no student-facing push either")
                    .noneMatch(sent -> sent.userId() == MAYA);
            assertThat(notifier.all())
                    .as("a signal is not an alert: nobody is notified")
                    .isEmpty();
        }

        @Test
        @DisplayName("a report with nobody watching costs nothing and still records")
        void noWatchersIsFine() {
            long attemptId = start(MAYA, "374301851");

            report(MAYA, 12_000);

            assertThat(gateway.of(Verb.PUSH_MONITOR_UPDATED)).isEmpty();
            assertThat(attempts.attentionOf(attemptId)).isPresent();
        }
    }

    // ===================== Fixture =======================================

    private CallerContext student(long userId) {
        return CallerContext.authenticated(userId == MAYA ? mayaSocket : noamSocket,
                userId, Role.STUDENT);
    }

    private CallerContext teacher(long userId) {
        return CallerContext.authenticated(danaSocket, userId, Role.TEACHER);
    }

    private long start(long studentId, String nationalId) {
        Message response = attempts.start(student(studentId),
                Message.request(Verb.ATTEMPT_START, new AttemptStartRequest(EXECUTION, nationalId)));
        assertThat(response.isOk()).as("fixture start: %s", response.errorMessage()).isTrue();
        return ((AttemptForm) response.getPayload()).attemptId();
    }

    private AttemptForm resume(long studentId) {
        Message response = attempts.resume(student(studentId),
                Message.request(Verb.ATTEMPT_RESUME,
                        new common.dto.exam.AttemptResumeRequest(EXECUTION)));
        assertThat(response.isOk()).as("fixture resume: %s", response.errorMessage()).isTrue();
        return (AttemptForm) response.getPayload();
    }

    private void submit(long studentId, long attemptId) {
        attempts.submit(student(studentId),
                Message.request(Verb.ATTEMPT_SUBMIT, new SubmitAttemptRequest(attemptId)));
    }

    private Message report(long studentId, long awayMillis) {
        return attempts.attention(student(studentId),
                Message.request(Verb.ATTEMPT_ATTENTION, new AttentionReport(awayMillis)));
    }

    private ExecutionMonitor monitor(long teacherId) {
        Message response = monitors.monitor(teacher(teacherId),
                Message.request(Verb.EXECUTION_MONITOR_GET, new MonitorRequest(EXECUTION)));
        assertThat(response.isOk()).as("fixture monitor: %s", response.errorMessage()).isTrue();
        return (ExecutionMonitor) response.getPayload();
    }

    private static MonitorRow rowFor(ExecutionMonitor snapshot, String studentName) {
        return snapshot.rows().stream()
                .filter(row -> row.studentName().equals(studentName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + studentName));
    }
}
