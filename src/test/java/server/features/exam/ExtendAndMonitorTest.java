package server.features.exam;

import common.dto.auth.Role;
import common.dto.exam.AttemptForm;
import common.dto.exam.AttemptStartRequest;
import common.dto.exam.AttemptState;
import common.dto.exam.ExecutionMonitor;
import common.dto.exam.ExtendTimeRequest;
import common.dto.exam.MonitorRequest;
import common.dto.exam.MonitorRow;
import common.dto.exam.SaveAnswerRequest;
import common.dto.exam.SubmitAttemptRequest;
import common.dto.exam.TimerExtended;
import common.dto.notify.NotificationType;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.hibernate.StaleObjectStateException;
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
import server.db.projections.ExecutionContext;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Extension and live monitoring (E11.1–E11.5 — F7).
 *
 * <p>The two halves are tested together because they only make sense together: an
 * extension answers with a monitor snapshot, the monitor is what a teacher extends from,
 * and the interesting assertions are about both at once — that the minutes reached every
 * live student, that the deadlines moved, and that the teacher's screen now says the same
 * thing the students' countdowns do.
 *
 * <p>All of it on a {@link TestClock}, so "extend at T minus ten seconds" (E11.4) is a
 * line rather than a wait.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExtendAndMonitorTest {

    private static final Instant T0 = Instant.parse("2026-08-20T09:00:00Z");
    private static final long EXECUTION = 5001L;
    private static final long EXAM_VERSION = 7001L;
    private static final long DANA = 1001L;
    private static final long RINA = 1002L;
    private static final long MAYA = 2001L;
    private static final long NOAM = 2002L;
    private static final String COURSE = "21";
    private static final int DURATION = 45;

    @Mock
    private ConnectionToClient danaSocket;
    @Mock
    private ConnectionToClient rinaSocket;
    @Mock
    private ConnectionToClient mayaSocket;
    @Mock
    private ConnectionToClient noamSocket;

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
        clock = new TestClock(T0);
        store = new InMemoryExamStore();
        scheduler = new ManualScheduler();
        sessions = new SessionManager();
        gateway = new RecordingGateway(sessions);
        notifier = new RecordingNotifier();

        store.execution(EXECUTION, EXAM_VERSION, "4B7Q", ExecutionStatus.LIVE,
                T0.minus(Duration.ofMinutes(5)), T0.plus(Duration.ofHours(3)), DURATION, DANA, COURSE);
        store.paper(EXAM_VERSION, 4);
        store.addUser(DANA, "Dana Cohen", "214703951");
        store.addUser(RINA, "Rina Barak", "248190639");
        store.addUser(MAYA, "Maya Levi", "374301851");
        store.addUser(NOAM, "Noam Bar", "301548202");
        store.enrol(MAYA, COURSE);
        store.enrol(NOAM, COURSE);

        attempts = new AttemptService(store, clock, scheduler, gateway, notifier,
                AttemptFinalizedListener.NO_OP);
        monitors = new MonitorService(store, attempts, gateway, clock);
        attempts.publishTo(monitors);
        extend = new ExtendService(store, attempts.timers(), monitors, gateway, notifier, clock);
    }

    // ===================== Extending (E11.1) =============================

    @Nested
    @DisplayName("extending a live execution")
    class Extending {

        @Test
        @DisplayName("the minutes go on the execution and every live deadline moves (S-20) ⚑")
        void everyDeadlineMoves() {
            long mayas = start(MAYA, "374301851");
            long noams = start(NOAM, "301548202");

            Message response = extend(DANA, 15);

            assertThat(response.isOk()).isTrue();
            assertThat(attempts.timers().deadlineOf(mayas))
                    .contains(T0.plus(Duration.ofMinutes(DURATION + 15)));
            assertThat(attempts.timers().deadlineOf(noams))
                    .contains(T0.plus(Duration.ofMinutes(DURATION + 15)));
        }

        @Test
        @DisplayName("each student is pushed the moment, naming the teacher and the new end (F7.1 ⚑)")
        void pushesTheDesignedMoment() {
            sessions.attach(MAYA, Role.STUDENT, mayaSocket);
            start(MAYA, "374301851");

            extend(DANA, 15);

            TimerExtended pushed = gateway
                    .firstPayload(MAYA, Verb.PUSH_TIMER_EXTENDED, TimerExtended.class)
                    .orElseThrow();
            assertThat(pushed.teacherName()).isEqualTo("Dana Cohen");
            assertThat(pushed.extraMinutes()).isEqualTo(15);
            assertThat(pushed.examName()).isEqualTo("Java Midterm");
            assertThat(pushed.timing().endsAt()).isEqualTo(T0.plus(Duration.ofMinutes(DURATION + 15)));
            assertThat(pushed.gained()).isEqualTo(Duration.ofMinutes(15));
        }

        @Test
        @DisplayName("a durable notification goes out too, for whoever was offline (E11.4)")
        void notifiesForOfflineStudents() {
            start(MAYA, "374301851");
            start(NOAM, "301548202");

            extend(DANA, 15);

            assertThat(notifier.of(NotificationType.TIME_EXTENDED)).hasSize(1);
            assertThat(notifier.recipients()).containsExactlyInAnyOrder(MAYA, NOAM);
            // Nobody is online, so the push reached nobody. The extension still happened.
            assertThat(gateway.of(Verb.PUSH_TIMER_EXTENDED)).isEmpty();
        }

        @Test
        @DisplayName("a student who was offline for it gains the time on resume (E11.4) ⚑")
        void appliesOnResume() {
            long attemptId = start(MAYA, "374301851");

            extend(DANA, 15);
            clock.advance(Duration.ofMinutes(50));

            // Fifty minutes into a forty-five-minute exam: without the extension she would
            // be five minutes past the bell.
            AttemptForm form = resume(MAYA);
            assertThat(form.state()).isEqualTo(AttemptState.IN_PROGRESS);
            assertThat(form.timing().remainingMillis()).isEqualTo(Duration.ofMinutes(10).toMillis());
            assertThat(form.attemptId()).isEqualTo(attemptId);
        }

        @Test
        @DisplayName("extending at T minus ten seconds keeps the attempt alive (E11.4) ⚑")
        void extendAtTheLastMoment() {
            long attemptId = start(MAYA, "374301851");
            clock.moveTo(T0.plus(Duration.ofMinutes(DURATION)).minusSeconds(10));

            extend(DANA, 15);
            scheduler.runAll();

            assertThat(store.attempt(attemptId)).get()
                    .extracting(record -> record.status().name()).isEqualTo("IN_PROGRESS");
            assertThat(attempts.timers().deadlineOf(attemptId))
                    .contains(T0.plus(Duration.ofMinutes(DURATION + 15)));
        }

        @Test
        @DisplayName("two extensions accumulate on the execution, not on the exam")
        void extensionsAccumulate() {
            long attemptId = start(MAYA, "374301851");

            extend(DANA, 10);
            extend(DANA, 5);

            assertThat(attempts.timers().deadlineOf(attemptId))
                    .contains(T0.plus(Duration.ofMinutes(DURATION + 15)));
        }

        @Test
        @DisplayName("zero and negative minutes are refused (§6)")
        void zeroAndNegativeRefused() {
            assertThat(extend(DANA, 0).getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(extend(DANA, -5).getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(extend(DANA, 0).errorMessage()).isEqualTo(ExamMessages.EXTENSION_INVALID);
        }

        @Test
        @DisplayName("an absurd amount is refused rather than turning a lesson into two days")
        void absurdAmountRefused() {
            assertThat(extend(DANA, ExtendTimeRequest.MAX_MINUTES + 1).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION);
        }

        @Test
        @DisplayName("extending after close is blocked (§6)")
        void extendAfterCloseBlocked() {
            store.addExecution(closedExecution());

            Message response = extend(DANA, 15);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.EXTENSION_NOT_LIVE);
        }

        @Test
        @DisplayName("a teacher who does not own this execution is refused")
        void ownershipEnforced() {
            Message response = extend(RINA, 15);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.NOT_YOUR_EXECUTION);
        }

        @Test
        @DisplayName("the exam's author may extend it too, even if somebody else released it (S-35)")
        void authorMayExtend() {
            store.addExecution(new ExecutionContext(EXECUTION, EXAM_VERSION, 901, COURSE,
                    "Java Programming", "Java Midterm", DURATION, "", "4B7Q", ExecutionStatus.LIVE,
                    T0.minus(Duration.ofMinutes(5)), T0.plus(Duration.ofHours(3)), 0, RINA, DANA));

            assertThat(extend(DANA, 10).isOk()).isTrue();
        }

        @Test
        @DisplayName("a student cannot extend anything")
        void studentsAreRefused() {
            assertThatThrownBy(() -> extend.extend(student(MAYA),
                    Message.request(Verb.EXECUTION_EXTEND, new ExtendTimeRequest(EXECUTION, 15))))
                    .isInstanceOf(AuthorizationException.class);
        }

        @Test
        @DisplayName("an unknown execution answers NOT_FOUND")
        void unknownExecution() {
            Message response = extend.extend(teacher(DANA),
                    Message.request(Verb.EXECUTION_EXTEND, new ExtendTimeRequest(999, 15)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("a malformed payload is a validation error")
        void malformedPayload() {
            Message response = extend.extend(teacher(DANA),
                    Message.request(Verb.EXECUTION_EXTEND, "fifteen"));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
        }

        @Test
        @DisplayName("losing the optimistic-lock race answers CONFLICT, not a stack trace")
        void concurrentExtensionConflicts() {
            store.failNextWith(new jakarta.persistence.OptimisticLockException(
                    "row was changed", new StaleObjectStateException("ExamExecution", EXECUTION)));

            Message response = extend(DANA, 15);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.EXTENSION_RACED);
        }

        @Test
        @DisplayName("any other failure is not swallowed as a conflict")
        void otherFailuresPropagate() {
            store.failNextWith(new IllegalStateException("the database is on fire"));

            assertThatThrownBy(() -> extend(DANA, 15))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("the verb is registered and needs a session")
        void registersItsVerb() {
            MessageRouter router = new MessageRouter(new SessionManager());

            extend.registerOn(router);

            assertThat(router.isRegistered(Verb.EXECUTION_EXTEND)).isTrue();
            assertThat(router.isOpen(Verb.EXECUTION_EXTEND)).isFalse();
        }
    }

    // ===================== Monitoring (E11.2) ============================

    @Nested
    @DisplayName("the live monitor")
    class Monitoring {

        @Test
        @DisplayName("it answers derived counts and a row per student (F7.2, S-21)")
        void countsAndRows() {
            long mayas = start(MAYA, "374301851");
            start(NOAM, "301548202");
            save(MAYA, mayas, 1, 2);
            submit(MAYA, mayas);

            ExecutionMonitor snapshot = monitor(DANA);

            assertThat(snapshot.counts().started()).isEqualTo(2);
            assertThat(snapshot.counts().finished()).isEqualTo(1);
            assertThat(snapshot.counts().timedOut()).isZero();
            assertThat(snapshot.counts().inProgress()).isEqualTo(1);
            assertThat(snapshot.rows()).hasSize(2);
            assertThat(snapshot.live()).isTrue();
            assertThat(snapshot.code()).isEqualTo("4B7Q");
        }

        @Test
        @DisplayName("a live row carries the same remaining time the student's own countdown shows")
        void remainingMatchesTheStudent() {
            start(MAYA, "374301851");
            clock.advance(Duration.ofMinutes(20));

            MonitorRow row = rowFor(monitor(DANA), "Maya Levi");

            assertThat(row.remainingMillis()).isEqualTo(Duration.ofMinutes(25).toMillis());
            assertThat(row.state()).isEqualTo(AttemptState.IN_PROGRESS);
        }

        @Test
        @DisplayName("a finished row shows the recorded minutes and no countdown")
        void finishedRow() {
            long attemptId = start(MAYA, "374301851");
            clock.advance(Duration.ofMinutes(12));
            submit(MAYA, attemptId);

            MonitorRow row = rowFor(monitor(DANA), "Maya Levi");

            assertThat(row.remainingMillis()).isZero();
            assertThat(row.actualMinutes()).isEqualTo(12);
            assertThat(row.state()).isEqualTo(AttemptState.SUBMITTED);
        }

        @Test
        @DisplayName("progress is counted from the answers, per row")
        void progressPerRow() {
            long mayas = start(MAYA, "374301851");
            save(MAYA, mayas, 1, 1);
            save(MAYA, mayas, 3, 4);

            MonitorRow row = rowFor(monitor(DANA), "Maya Levi");

            assertThat(row.answeredCount()).isEqualTo(2);
            assertThat(row.questionCount()).isEqualTo(4);
            assertThat(row.progressLabel()).isEqualTo("2/4");
        }

        @Test
        @DisplayName("an execution nobody has joined shows its empty state honestly")
        void emptyExecution() {
            ExecutionMonitor snapshot = monitor(DANA);

            assertThat(snapshot.isEmpty()).isTrue();
            assertThat(snapshot.counts().started()).isZero();
        }

        @Test
        @DisplayName("the C-4 integrity flag appears on the student's row (F6.8) ⚑")
        void integrityFlagOnTheRow() {
            start(MAYA, "374301851");
            attempts.reportCrossCourseBotUse(MAYA, "11", "Algebra 11");

            MonitorRow row = rowFor(monitor(DANA), "Maya Levi");

            assertThat(row.isFlagged()).isTrue();
            assertThat(row.integrity().courseName()).isEqualTo("Algebra 11");
            assertThat(monitor(DANA).flaggedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("asking subscribes, and every change is pushed as a whole snapshot")
        void askingSubscribes() {
            sessions.attach(DANA, Role.TEACHER, danaSocket);
            monitor(DANA);
            assertThat(monitors.watchersOf(EXECUTION)).containsExactly(DANA);
            gateway.clear();

            start(MAYA, "374301851");

            assertThat(gateway.countFor(DANA, Verb.PUSH_MONITOR_UPDATED)).isPositive();
            ExecutionMonitor pushed = gateway
                    .firstPayload(DANA, Verb.PUSH_MONITOR_UPDATED, ExecutionMonitor.class)
                    .orElseThrow();
            assertThat(pushed.rows()).hasSize(1);
        }

        @Test
        @DisplayName("nothing is pushed when nobody is watching")
        void noWatchersNoPush() {
            start(MAYA, "374301851");

            assertThat(gateway.of(Verb.PUSH_MONITOR_UPDATED)).isEmpty();
        }

        @Test
        @DisplayName("a dropped socket stops the pushes (E18.3's hook)")
        void disconnectUnsubscribes() {
            monitors.attachTo(sessions);
            sessions.attach(DANA, Role.TEACHER, danaSocket);
            monitor(DANA);

            sessions.detach(danaSocket);

            assertThat(monitors.watchersOf(EXECUTION)).isEmpty();
        }

        @Test
        @DisplayName("a teacher who owns nothing here is refused and learns nothing")
        void ownershipEnforced() {
            Message response = monitors.monitor(teacher(RINA),
                    Message.request(Verb.EXECUTION_MONITOR_GET, new MonitorRequest(EXECUTION)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(monitors.watchersOf(EXECUTION))
                    .as("and a refused caller is not registered as a watcher")
                    .isEmpty();
        }

        @Test
        @DisplayName("an execution that does not exist answers the same way, indistinguishably")
        void unknownExecutionLooksTheSame() {
            Message response = monitors.monitor(teacher(DANA),
                    Message.request(Verb.EXECUTION_MONITOR_GET, new MonitorRequest(999)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("a student cannot open a monitor")
        void studentsAreRefused() {
            assertThatThrownBy(() -> monitors.monitor(student(MAYA),
                    Message.request(Verb.EXECUTION_MONITOR_GET, new MonitorRequest(EXECUTION))))
                    .isInstanceOf(AuthorizationException.class);
        }

        @Test
        @DisplayName("a malformed payload is a validation error")
        void malformedPayload() {
            Message response = monitors.monitor(teacher(DANA),
                    Message.request(Verb.EXECUTION_MONITOR_GET, 5001L));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
        }

        @Test
        @DisplayName("a change to an execution nobody watches costs no database read")
        void quietWhenUnwatched() {
            monitors.executionChanged(999);

            assertThat(gateway.sent()).isEmpty();
        }

        @Test
        @DisplayName("a push that fails does not propagate into the path that triggered it ⚑")
        void pushFailureIsContained() {
            sessions.attach(DANA, Role.TEACHER, danaSocket);
            monitor(DANA);
            store.failNextWith(new IllegalStateException("connection pool exhausted"));

            // A monitor that cannot repaint must never be able to fail a student's submit.
            monitors.executionChanged(EXECUTION);

            assertThat(gateway.of(Verb.PUSH_MONITOR_UPDATED)).isEmpty();
        }

        @Test
        @DisplayName("the verb is registered and needs a session")
        void registersItsVerb() {
            MessageRouter router = new MessageRouter(new SessionManager());

            monitors.registerOn(router);

            assertThat(router.isRegistered(Verb.EXECUTION_MONITOR_GET)).isTrue();
            assertThat(router.isOpen(Verb.EXECUTION_MONITOR_GET)).isFalse();
        }

        @Test
        @DisplayName("the extension answer is a refreshed snapshot, not the teacher's arithmetic")
        void extendAnswersASnapshot() {
            start(MAYA, "374301851");

            Message response = extend(DANA, 15);

            ExecutionMonitor snapshot = (ExecutionMonitor) response.getPayload();
            assertThat(snapshot.extraMinutes()).isEqualTo(15);
            assertThat(snapshot.durationMinutes()).isEqualTo(DURATION + 15);
        }
    }

    // ===================== Closing (E11.5) ===============================

    @Nested
    @DisplayName("closing an execution")
    class Closing {

        private ExecutionCloseService closer;
        private List<Long> monitorEvents;

        @BeforeEach
        void setUp() {
            monitorEvents = new ArrayList<>();
            closer = new ExecutionCloseService(store, attempts, monitorEvents::add);
        }

        @Test
        @DisplayName("everyone still working is handed in, as if their time had run out (F5.5)")
        void strugglersAreForceSubmitted() {
            long mayas = start(MAYA, "374301851");
            long noams = start(NOAM, "301548202");
            submit(MAYA, mayas);

            closer.close(EXECUTION);

            assertThat(store.attempt(noams)).get()
                    .extracting(record -> record.status().name()).isEqualTo("TIMED_OUT");
            assertThat(store.attempt(mayas)).get()
                    .extracting(record -> record.status().name()).isEqualTo("SUBMITTED");
        }

        @Test
        @DisplayName("the counts are frozen into the documentation record (S-21, F7.3)")
        void freezesTheCounts() {
            long mayas = start(MAYA, "374301851");
            start(NOAM, "301548202");
            submit(MAYA, mayas);

            var counts = closer.close(EXECUTION).orElseThrow();

            assertThat(counts.started()).isEqualTo(2);
            assertThat(counts.finished()).isEqualTo(1);
            assertThat(counts.timedOut())
                    .as("the straggler was force-submitted before the freeze, so she is counted")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the execution is no longer live afterwards")
        void executionIsClosed() {
            closer.close(EXECUTION);

            assertThat(monitor(DANA).live()).isFalse();
        }

        @Test
        @DisplayName("closing twice is idempotent")
        void closingTwice() {
            start(MAYA, "374301851");

            closer.close(EXECUTION);
            var second = closer.close(EXECUTION).orElseThrow();

            assertThat(second.started()).isEqualTo(1);
            assertThat(second.timedOut()).isEqualTo(1);
        }

        @Test
        @DisplayName("the watchers are told it closed")
        void tellsTheMonitor() {
            closer.close(EXECUTION);

            assertThat(monitorEvents).contains(EXECUTION);
        }

        @Test
        @DisplayName("closing an execution that does not exist answers empty, not a crash")
        void unknownExecution() {
            assertThat(closer.close(999)).isEmpty();
        }
    }

    // ===================== Fixture =======================================

    private CallerContext teacher(long userId) {
        return CallerContext.authenticated(userId == DANA ? danaSocket : rinaSocket,
                userId, Role.TEACHER);
    }

    private CallerContext student(long userId) {
        return CallerContext.authenticated(userId == MAYA ? mayaSocket : noamSocket,
                userId, Role.STUDENT);
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

    private void save(long studentId, long attemptId, int ordinal, int option) {
        attempts.saveAnswer(student(studentId), Message.request(Verb.ANSWER_SAVE,
                new SaveAnswerRequest(attemptId, store.questionId(EXAM_VERSION, ordinal), option)));
    }

    private void submit(long studentId, long attemptId) {
        attempts.submit(student(studentId),
                Message.request(Verb.ATTEMPT_SUBMIT, new SubmitAttemptRequest(attemptId)));
    }

    private Message extend(long teacherId, int minutes) {
        return extend.extend(teacher(teacherId),
                Message.request(Verb.EXECUTION_EXTEND, new ExtendTimeRequest(EXECUTION, minutes)));
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

    private ExecutionContext closedExecution() {
        return new ExecutionContext(EXECUTION, EXAM_VERSION, 901, COURSE, "Java Programming",
                "Java Midterm", DURATION, "", "4B7Q", ExecutionStatus.CLOSED,
                T0.minus(Duration.ofDays(1)), T0.minus(Duration.ofHours(2)), 0, DANA, DANA);
    }
}
