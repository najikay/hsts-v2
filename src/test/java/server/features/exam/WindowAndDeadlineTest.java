package server.features.exam;

import common.dto.auth.Role;
import common.dto.exam.AttemptForm;
import common.dto.exam.AttemptStartRequest;
import common.dto.exam.AttemptState;
import common.dto.exam.ExamHeader;
import common.dto.exam.ExamJoinRequest;
import common.dto.exam.ExecutionMonitor;
import common.dto.exam.ExtendTimeRequest;
import common.dto.exam.MonitorRequest;
import common.dto.exam.MonitorRow;
import common.dto.exam.TimerExtended;
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
import server.core.CallerContext;
import server.core.SessionManager;
import server.db.entities.AttemptStatus;
import server.db.entities.ExecutionStatus;
import server.db.projections.AttemptRecord;
import server.db.projections.ExecutionContext;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two clocks, reconciled ⚑ (B-14 — E9/E10/E11, F6.1, F7.1).
 *
 * <p>Every fixture in {@code AttemptServiceTest}, {@code ExtendAndMonitorTest} and
 * {@code ExamConcurrencyIntegrationTest} builds a window generously wider than the paper, so
 * the two clocks that govern a sitting never cross and none of them could have failed for a
 * missing rule. This class exists to make them cross, and it uses the reproduction times the
 * S6-S7 acceptance probe recorded rather than convenient round numbers, so a reader can put
 * the report and the test side by side.
 *
 * <p><b>The rule under test.</b> An attempt ends at
 * {@code min(startedAt + duration + extensions, the execution's effective close)}. That was
 * always what happened — {@code ReleaseScheduler} closed the execution at the window's end and
 * force-submitted the stragglers — and nothing the client was told knew it. So the assertions
 * here are mostly about <em>agreement</em>: the countdown, the header, the monitor and the
 * force-submit all describing one moment.
 *
 * <p>Two probes, both from the report:
 *
 * <ol>
 *   <li><b>The truncated join.</b> Window {@code 08:00Z → 10:00Z}, a 75-minute paper, she
 *       joins at {@code 09:58Z}. She was told {@code endsAt 11:13Z} and 75 minutes, and closed
 *       at {@code actual_minutes 2}. Promised 75, given 2, told neither.</li>
 *   <li><b>The extension eaten by the window.</b> Same window, she joins at {@code 09:30Z},
 *       {@code dana} grants {@code +15} at {@code 09:50Z}. The toast announced the minutes,
 *       the deadline moved to {@code 11:00Z}, and the scheduler closed the execution at
 *       {@code 10:15Z} — {@code actual_minutes 45} of an allotted 90.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WindowAndDeadlineTest {

    /** The window the S6-S7 probe used, in the report's own instants. */
    private static final Instant OPENS = Instant.parse("2026-08-20T08:00:00Z");
    private static final Instant CLOSES = Instant.parse("2026-08-20T10:00:00Z");

    /** Exam 1 v2's stored duration, as the probe found it. */
    private static final int DURATION = 75;

    private static final long EXECUTION = 2075L;
    private static final long EXAM_VERSION = 101102L;
    private static final long DANA = 1001L;
    private static final long MAYA = 2001L;
    private static final String COURSE = "11";
    private static final String MAYA_ID = "374301851";

    @Mock
    private ConnectionToClient danaSocket;
    @Mock
    private ConnectionToClient mayaSocket;

    private TestClock clock;
    private InMemoryExamStore store;
    private ManualScheduler scheduler;
    private SessionManager sessions;
    private RecordingGateway gateway;
    private RecordingNotifier notifier;
    private AttemptService attempts;
    private MonitorService monitors;
    private ExtendService extend;

    @BeforeEach
    void setUp() {
        clock = new TestClock(OPENS);
        store = new InMemoryExamStore();
        scheduler = new ManualScheduler();
        sessions = new SessionManager();
        gateway = new RecordingGateway(sessions);
        notifier = new RecordingNotifier();

        store.execution(EXECUTION, EXAM_VERSION, "2075", ExecutionStatus.LIVE,
                OPENS, CLOSES, DURATION, DANA, COURSE);
        store.paper(EXAM_VERSION, 7);
        store.addUser(DANA, "Dana Cohen", "214703951");
        store.addUser(MAYA, "Maya Levi", MAYA_ID);
        store.enrol(MAYA, COURSE);

        attempts = new AttemptService(store, clock, scheduler, gateway, notifier,
                AttemptFinalizedListener.NO_OP);
        monitors = new MonitorService(store, attempts, gateway, clock);
        attempts.publishTo(monitors);
        extend = new ExtendService(store, attempts.timers(), monitors, gateway, notifier, clock);
    }

    // ===================== The derivation itself =========================

    @Nested
    @DisplayName("the one derivation point")
    class Derivation {

        @Test
        @DisplayName("takes the earlier of the allotted time and the window's close")
        void deadlineIsTheMinimum() {
            ExecutionContext ctx = context();
            AttemptRecord late = attempt(at("09:58:00Z"));
            AttemptRecord early = attempt(at("08:30:00Z"));

            assertThat(late.deadline(ctx))
                    .as("the window wins: 09:58 + 75 min would be 11:13")
                    .isEqualTo(CLOSES);
            assertThat(early.deadline(ctx))
                    .as("the allotted time wins: 09:45 is inside the window")
                    .isEqualTo(at("09:45:00Z"));
        }

        @Test
        @DisplayName("extensions widen both clocks before the minimum is taken")
        void extensionsCountOnBothSides() {
            ExecutionContext extended = context().withExtraMinutes(15);

            assertThat(extended.effectiveCloseAt()).isEqualTo(at("10:15:00Z"));
            assertThat(attempt(at("09:30:00Z")).deadline(extended))
                    .as("09:30 + 90 min is 11:00, but the window still shuts at 10:15")
                    .isEqualTo(at("10:15:00Z"));
        }

        @Test
        @DisplayName("reports the minutes a sitting really gets, floored, never negative")
        void sittingMinutesAreHonest() {
            ExecutionContext ctx = context();

            assertThat(ctx.sittingMinutesFrom(at("08:30:00Z"))).isEqualTo(DURATION);
            assertThat(ctx.windowShortensSittingFrom(at("08:30:00Z"))).isFalse();
            assertThat(ctx.sittingMinutesFrom(at("09:58:00Z"))).isEqualTo(2);
            assertThat(ctx.windowShortensSittingFrom(at("09:58:00Z"))).isTrue();
            assertThat(ctx.sittingMinutesFrom(at("09:58:30Z")))
                    .as("rounded down: promising a minute past the bell is the whole defect")
                    .isEqualTo(1);
            assertThat(ctx.sittingMinutesFrom(at("10:30:00Z")))
                    .as("past the close, which the join gate refuses anyway")
                    .isZero();
        }
    }

    // ===================== Probe 1: the truncated join ===================

    @Nested
    @DisplayName("joining two minutes before the window shuts")
    class TruncatedJoin {

        @Test
        @DisplayName("the join answer promises the two minutes she will actually get")
        void joinTellsTheTruth() {
            clock.moveTo(at("09:58:00Z"));

            ExamHeader header = join();

            assertThat(header.durationMinutes())
                    .as("the paper is still worth 75 and the screen still says so")
                    .isEqualTo(DURATION);
            assertThat(header.sittingMinutes())
                    .as("but this sitting is worth 2 - the number B-14 says was never sent")
                    .isEqualTo(2);
            assertThat(header.windowClosesAt()).isEqualTo(CLOSES);
            assertThat(header.isSittingShortened()).isTrue();
        }

        @Test
        @DisplayName("the countdown she is handed agrees with the bell that ends her")
        void startedAttemptAgreesWithTheBell() {
            clock.moveTo(at("09:58:00Z"));

            AttemptForm form = start();

            assertThat(form.timing().endsAt())
                    .as("11:13:00Z was the promise that was never kept")
                    .isEqualTo(CLOSES);
            assertThat(form.timing().remainingMillis())
                    .isEqualTo(Duration.ofMinutes(2).toMillis());
            assertThat(form.header().sittingMinutes()).isEqualTo(2);
            assertThat(form.header().isSittingShortened()).isTrue();
        }

        @Test
        @DisplayName("promised and given are now the same number")
        void promisedEqualsGiven() {
            clock.moveTo(at("09:58:00Z"));
            AttemptForm form = start();
            long promisedMinutes = form.header().sittingMinutes();

            // The bell. Force-submit already fired here before B-14 - the probe proved it -
            // and this asserts that what she was told above is what the server then did.
            clock.moveTo(CLOSES);
            scheduler.runAll();

            AttemptRecord closed = store.attempt(form.attemptId()).orElseThrow();
            assertThat(closed.status()).isEqualTo(AttemptStatus.TIMED_OUT);
            assertThat(closed.endedAt()).isEqualTo(CLOSES);
            assertThat(closed.actualMinutes())
                    .as("promised %s, given %s", promisedMinutes, closed.actualMinutes())
                    .isEqualTo((int) promisedMinutes);
        }

        @Test
        @DisplayName("a sitting the window does not touch says nothing about it")
        void theNormalCaseIsSilent() {
            clock.moveTo(at("08:30:00Z"));

            ExamHeader header = join();

            assertThat(header.sittingMinutes()).isEqualTo(DURATION);
            assertThat(header.isSittingShortened())
                    .as("the entry sentence must not become background noise")
                    .isFalse();
        }
    }

    // ===================== Probe 2: the extension eaten =================

    @Nested
    @DisplayName("an extension the window would have eaten")
    class ExtensionEatenByTheWindow {

        @Test
        @DisplayName("the window moves, so the minutes are actually delivered")
        void windowMovesAndMinutesArrive() {
            clock.moveTo(at("09:30:00Z"));
            AttemptForm form = start();
            assertThat(form.timing().endsAt())
                    .as("before the extension the window already had her at 30 minutes")
                    .isEqualTo(CLOSES);

            clock.moveTo(at("09:50:00Z"));
            assertThat(extend(15).isOk()).isTrue();

            ExecutionContext after = context();
            assertThat(after.extraMinutes()).isEqualTo(15);
            assertThat(after.effectiveCloseAt())
                    .as("max(10:15, the latest new deadline 11:00) - B-14's rule")
                    .isEqualTo(at("11:00:00Z"));

            AttemptRecord attempt = store.attempt(form.attemptId()).orElseThrow();
            assertThat(attempt.deadline(after))
                    .as("09:30 + 90 minutes, undiminished")
                    .isEqualTo(at("11:00:00Z"));
        }

        @Test
        @DisplayName("she is not timed out at the old bell any more")
        void sheIsNoLongerTimedOutAtTheOldBell() {
            clock.moveTo(at("09:30:00Z"));
            AttemptForm form = start();
            clock.moveTo(at("09:50:00Z"));
            extend(15);

            // The moment the report recorded as `actual_minutes 45 of an allotted 90`.
            clock.moveTo(at("10:15:00Z"));
            scheduler.runAll();

            assertThat(store.attempt(form.attemptId()).orElseThrow().status())
                    .as("still working, because the window moved with the grant")
                    .isEqualTo(AttemptStatus.IN_PROGRESS);

            clock.moveTo(at("11:00:00Z"));
            scheduler.runAll();

            AttemptRecord closed = store.attempt(form.attemptId()).orElseThrow();
            assertThat(closed.status()).isEqualTo(AttemptStatus.TIMED_OUT);
            assertThat(closed.actualMinutes())
                    .as("the whole allotted 90, which is what the toast promised")
                    .isEqualTo(90);
        }

        @Test
        @DisplayName("the push carries the deadline she will really be held to")
        void thePushIsObservedAndTruthful() {
            sessions.attach(MAYA, Role.STUDENT, mayaSocket);
            clock.moveTo(at("09:30:00Z"));
            start();

            clock.moveTo(at("09:50:00Z"));
            extend(15);

            TimerExtended pushed = gateway
                    .firstPayload(MAYA, Verb.PUSH_TIMER_EXTENDED, TimerExtended.class)
                    .orElseThrow(() -> new AssertionError("no PUSH_TIMER_EXTENDED reached Maya"));
            assertThat(pushed.extraMinutes()).isEqualTo(15);
            assertThat(pushed.timing().endsAt()).isEqualTo(at("11:00:00Z"));
            assertThat(pushed.timing().remainingMillis())
                    .as("70 minutes left at 09:50, and this time she gets all of them")
                    .isEqualTo(Duration.ofMinutes(70).toMillis());
        }

        @Test
        @DisplayName("a window that already outlasts every new deadline is left alone")
        void aWideWindowIsNotWidenedFurther() {
            // The ordinary case, and the one where the release's own window has to survive
            // untouched: a max, never a set.
            store.addExecution(context().withCloseAt(at("18:00:00Z")));
            clock.moveTo(at("09:30:00Z"));
            start();

            clock.moveTo(at("09:50:00Z"));
            extend(15);

            assertThat(context().closeAt())
                    .as("the teacher's own window, not one this verb invented")
                    .isEqualTo(at("18:00:00Z"));
        }

        @Test
        @DisplayName("with nobody sitting it, the window does not move")
        void anEmptyExecutionKeepsItsWindow() {
            clock.moveTo(at("09:50:00Z"));

            assertThat(extend(15).isOk()).isTrue();

            assertThat(context().closeAt()).isEqualTo(CLOSES);
            assertThat(context().effectiveCloseAt()).isEqualTo(at("10:15:00Z"));
        }
    }

    // ===================== The monitor's own consistency =================

    @Nested
    @DisplayName("the teacher's monitor")
    class MonitorConsistency {

        @Test
        @DisplayName("the rows count down to the close the header states")
        void headerAndRowsAgree() {
            clock.moveTo(at("09:30:00Z"));
            start();
            clock.moveTo(at("09:40:00Z"));

            ExecutionMonitor snapshot = monitor();
            MonitorRow maya = rowFor(snapshot, "Maya Levi");

            // ⚑ Before B-14 this row counted to 10:45 while closesAt read 10:00, and the
            // window was what actually happened - the teacher's own screen disagreeing with
            // itself, with the wrong half winning.
            assertThat(snapshot.closesAt()).isEqualTo(CLOSES);
            assertThat(at("09:40:00Z").plusMillis(maya.remainingMillis()))
                    .as("the row's countdown lands exactly on the header's close")
                    .isEqualTo(snapshot.closesAt());
        }

        @Test
        @DisplayName("after an extension both halves move together")
        void bothHalvesMoveOnExtension() {
            clock.moveTo(at("09:30:00Z"));
            start();
            clock.moveTo(at("09:50:00Z"));
            extend(15);

            ExecutionMonitor snapshot = monitor();
            MonitorRow maya = rowFor(snapshot, "Maya Levi");

            assertThat(snapshot.closesAt()).isEqualTo(at("11:00:00Z"));
            assertThat(snapshot.durationMinutes()).isEqualTo(DURATION + 15);
            assertThat(at("09:50:00Z").plusMillis(maya.remainingMillis()))
                    .isEqualTo(at("11:00:00Z"));
        }

        @Test
        @DisplayName("the watching teacher is pushed the moved window without asking")
        void theMovedWindowIsPushed() {
            sessions.attach(DANA, Role.TEACHER, danaSocket);
            clock.moveTo(at("09:30:00Z"));
            start();
            monitor();
            gateway.clear();

            clock.moveTo(at("09:50:00Z"));
            extend(15);

            ExecutionMonitor pushed = gateway
                    .firstPayload(DANA, Verb.PUSH_MONITOR_UPDATED, ExecutionMonitor.class)
                    .orElseThrow(() -> new AssertionError("no PUSH_MONITOR_UPDATED reached Dana"));
            assertThat(pushed.closesAt())
                    .as("she sent no second request and her screen is already right")
                    .isEqualTo(at("11:00:00Z"));
        }
    }

    // ===================== Fixture =======================================

    private static Instant at(String timeOfDay) {
        return Instant.parse("2026-08-20T" + timeOfDay);
    }

    private ExecutionContext context() {
        return store.inTx(data -> data.executionById(EXECUTION)).orElseThrow();
    }

    private AttemptRecord attempt(Instant startedAt) {
        return new AttemptRecord(1L, EXECUTION, MAYA, startedAt, null, null,
                AttemptStatus.IN_PROGRESS);
    }

    private CallerContext student() {
        return CallerContext.authenticated(mayaSocket, MAYA, Role.STUDENT);
    }

    private CallerContext teacher() {
        return CallerContext.authenticated(danaSocket, DANA, Role.TEACHER);
    }

    private ExamHeader join() {
        Message response = attempts.join(student(),
                Message.request(Verb.EXAM_JOIN, new ExamJoinRequest("2075")));
        assertThat(response.isOk()).as("fixture join: %s", response.errorMessage()).isTrue();
        return (ExamHeader) response.getPayload();
    }

    private AttemptForm start() {
        Message response = attempts.start(student(),
                Message.request(Verb.ATTEMPT_START, new AttemptStartRequest(EXECUTION, MAYA_ID)));
        assertThat(response.isOk()).as("fixture start: %s", response.errorMessage()).isTrue();
        AttemptForm form = (AttemptForm) response.getPayload();
        assertThat(form.state()).isEqualTo(AttemptState.IN_PROGRESS);
        return form;
    }

    private Message extend(int minutes) {
        return extend.extend(teacher(),
                Message.request(Verb.EXECUTION_EXTEND, new ExtendTimeRequest(EXECUTION, minutes)));
    }

    private ExecutionMonitor monitor() {
        Message response = monitors.monitor(teacher(),
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
