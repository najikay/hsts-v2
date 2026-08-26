package client.features.exam;

import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.events.PushEventBridge;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.exam.AttemptState;
import common.dto.exam.ExecutionMonitor;
import common.dto.exam.ExtendTimeRequest;
import common.dto.exam.IntegrityFlag;
import common.dto.exam.MonitorCounts;
import common.dto.exam.MonitorRequest;
import common.dto.exam.MonitorRow;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The teacher's live monitor, with no JavaFX toolkit (E11.2/E11.3 — F7.2).
 *
 * <p>The two behaviours worth pinning are the ones a screenshot would not show: that a
 * pushed snapshot <b>replaces</b> the state rather than patching rows into it, and that
 * remaining times age between pushes against an injected clock rather than freezing or
 * inventing their own.
 */
class ExecutionMonitorSessionTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final long EXECUTION = 5001L;
    private static final long OTHER_EXECUTION = 5002L;

    private FakeClientConnection connection;
    private ClientEventBus eventBus;
    private ExecutionMonitorSession session;
    private List<ExecutionMonitor> updates;

    @BeforeEach
    void setUp() throws IOException {
        connection = new FakeClientConnection();
        connection.connect();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        eventBus = new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster());
        dispatcher.setPushListener(new PushEventBridge(eventBus));
        session = new ExecutionMonitorSession(dispatcher, eventBus);
        session.useClock(Clock.fixed(NOW, ZoneId.of("UTC")));
        updates = new ArrayList<>();
        session.onUpdate(updates::add);
    }

    @Nested
    @DisplayName("opening")
    class Opening {

        @Test
        @DisplayName("opening fetches the snapshot and subscribes")
        void opensAndSubscribes() {
            connection.replyOk(Verb.EXECUTION_MONITOR_GET, snapshot(2, 1, 0));

            session.start(EXECUTION).join();

            assertThat(session.isStarted()).isTrue();
            assertThat(eventBus.isRegistered(session)).isTrue();
            assertThat(session.snapshot()).isPresent();
            assertThat(session.snapshot().get().counts().started()).isEqualTo(2);
            assertThat(updates).hasSize(1);
            assertThat(((MonitorRequest) connection.lastSent().getPayload()).executionId())
                    .isEqualTo(EXECUTION);
        }

        /**
         * ⚑ The generation-guard sweep. {@code ExecutionMonitorView.onShow} calls
         * {@code start(executionId)} on every navigation and the view is built once, so the
         * teacher can leave sitting A and open sitting B while A's snapshot is still in flight.
         *
         * <p>The <b>push</b> path has always checked {@code pushed.executionId()} against the
         * field. The <b>request</b> path did not, so the sitting she left could repaint the
         * sitting she opened — wrong students, wrong counts, wrong countdowns, and a live-looking
         * screen with nothing on it to say so. The two paths now apply the same rule.
         */
        @Test
        @DisplayName("⚑ a snapshot for the sitting she left never repaints the one she opened")
        void aLateSnapshotForAnotherExecutionIsDropped() {
            // No responder, so both futures stay pending and the answers are delivered by hand.
            session.start(EXECUTION);
            session.start(OTHER_EXECUTION);

            connection.deliver(Message.ok(connection.sentMessages().get(1),
                    otherSnapshot()));
            connection.deliver(Message.ok(connection.sentMessages().get(0),
                    snapshot(2, 1, 0)));

            assertThat(session.snapshot()).isPresent();
            assertThat(session.snapshot().orElseThrow().executionId())
                    .as("the request path must apply the same target check the push path does")
                    .isEqualTo(OTHER_EXECUTION);
        }

        @Test
        @DisplayName("a refusal is surfaced as a sentence rather than as an empty screen")
        void refusalIsSurfaced() {
            connection.replyError(Verb.EXECUTION_MONITOR_GET, ErrorCode.FORBIDDEN,
                    "This exam is not yours to manage. Ask the teacher who released it.");

            session.start(EXECUTION).join();

            assertThat(session.snapshot()).isEmpty();
            assertThat(session.lastError()).startsWith("This exam is not yours");
        }

        @Test
        @DisplayName("a dead connection says so")
        void connectionFailure() throws IOException {
            connection.failSendsWith(new IOException("socket closed"));

            session.start(EXECUTION).join();

            assertThat(session.lastError()).isEqualTo(ExamCopy.OFFLINE);
        }

        @Test
        @DisplayName("a nonsense payload is treated as a failure")
        void unexpectedPayload() {
            connection.replyOk(Verb.EXECUTION_MONITOR_GET, "not a monitor");

            session.start(EXECUTION).join();

            assertThat(session.snapshot()).isEmpty();
            assertThat(session.lastError()).isEqualTo(ExamCopy.OFFLINE);
        }

        @Test
        @DisplayName("stopping unsubscribes and drops the snapshot")
        void stopping() {
            connection.replyOk(Verb.EXECUTION_MONITOR_GET, snapshot(1, 0, 0));
            session.start(EXECUTION).join();

            session.stop();

            assertThat(session.isStarted()).isFalse();
            assertThat(eventBus.isRegistered(session)).isFalse();
            assertThat(session.snapshot()).isEmpty();
        }

        @Test
        @DisplayName("its collaborators are all required")
        void constructorGuards() {
            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            assertThatNullPointerException()
                    .isThrownBy(() -> new ExecutionMonitorSession(null, eventBus));
            assertThatNullPointerException()
                    .isThrownBy(() -> new ExecutionMonitorSession(dispatcher, null));
            assertThatNullPointerException().isThrownBy(() -> session.onUpdate(null));
            assertThatNullPointerException().isThrownBy(() -> session.useClock(null));
        }
    }

    @Nested
    @DisplayName("live pushes")
    class Pushes {

        @BeforeEach
        void open() {
            connection.replyOk(Verb.EXECUTION_MONITOR_GET, snapshot(1, 0, 0));
            session.start(EXECUTION).join();
            updates.clear();
        }

        @Test
        @DisplayName("a pushed snapshot replaces the state outright")
        void pushReplaces() {
            connection.pushToClient(Verb.PUSH_MONITOR_UPDATED, snapshot(3, 2, 1));

            assertThat(session.snapshot().get().counts().started()).isEqualTo(3);
            assertThat(session.snapshot().get().counts().finished()).isEqualTo(2);
            assertThat(updates).hasSize(1);
        }

        @Test
        @DisplayName("a push for another execution is ignored")
        void otherExecutionsPushIsIgnored() {
            connection.pushToClient(Verb.PUSH_MONITOR_UPDATED,
                    new ExecutionMonitor(9999, "Other", "11", "ZZZZ", true, NOW,
                            NOW.plus(Duration.ofHours(1)), 0, 45, MonitorCounts.NONE, List.of()));

            assertThat(session.snapshot().get().executionId()).isEqualTo(EXECUTION);
            assertThat(updates).isEmpty();
        }

        @Test
        @DisplayName("a push of another verb passes through")
        void otherPushesAreIgnored() {
            connection.pushToClient(Verb.PUSH_NOTIFICATION, "something else");

            assertThat(updates).isEmpty();
        }

        @Test
        @DisplayName("a push with the wrong payload type is ignored rather than crashing")
        void malformedPushIsIgnored() {
            connection.pushToClient(Verb.PUSH_MONITOR_UPDATED, 42);

            assertThat(updates).isEmpty();
        }

        @Test
        @DisplayName("a push clears a previous error")
        void pushClearsTheError() {
            connection.replyError(Verb.EXECUTION_MONITOR_GET, ErrorCode.FORBIDDEN, "no");
            session.refresh().join();
            assertThat(session.lastError()).isNotBlank();

            connection.pushToClient(Verb.PUSH_MONITOR_UPDATED, snapshot(1, 0, 0));

            assertThat(session.lastError()).isEmpty();
        }
    }

    @Nested
    @DisplayName("remaining time between pushes")
    class Ageing {

        @Test
        @DisplayName("a live row's countdown ages against the local clock")
        void countdownAges() {
            java.util.concurrent.atomic.AtomicReference<Instant> now =
                    new java.util.concurrent.atomic.AtomicReference<>(NOW);
            session.useClock(new MovingClock(now));
            connection.replyOk(Verb.EXECUTION_MONITOR_GET, snapshot(1, 0, 0));
            session.start(EXECUTION).join();
            MonitorRow row = session.snapshot().get().rows().get(0);

            assertThat(session.remainingFor(row)).isEqualTo(Duration.ofMinutes(30));

            now.set(NOW.plus(Duration.ofMinutes(5)));
            assertThat(session.remainingFor(row)).isEqualTo(Duration.ofMinutes(25));
        }

        @Test
        @DisplayName("it never goes negative")
        void neverNegative() {
            java.util.concurrent.atomic.AtomicReference<Instant> now =
                    new java.util.concurrent.atomic.AtomicReference<>(NOW);
            session.useClock(new MovingClock(now));
            connection.replyOk(Verb.EXECUTION_MONITOR_GET, snapshot(1, 0, 0));
            session.start(EXECUTION).join();
            MonitorRow row = session.snapshot().get().rows().get(0);

            now.set(NOW.plus(Duration.ofHours(2)));

            assertThat(session.remainingFor(row)).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("a finished row has nothing to count")
        void finishedRowShowsNothing() {
            connection.replyOk(Verb.EXECUTION_MONITOR_GET, snapshot(2, 1, 0));
            session.start(EXECUTION).join();
            MonitorRow submitted = session.snapshot().get().rows().stream()
                    .filter(row -> row.state() == AttemptState.SUBMITTED)
                    .findFirst().orElseThrow();

            assertThat(session.remainingFor(submitted)).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("before any snapshot there is nothing to age")
        void beforeAnySnapshot() {
            assertThat(session.remainingFor(liveRow())).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("a null row is rejected at the boundary")
        void nullRowRejected() {
            assertThatNullPointerException().isThrownBy(() -> session.remainingFor(null));
        }
    }

    @Nested
    @DisplayName("extending (F7.1)")
    class Extending {

        @BeforeEach
        void open() {
            connection.replyOk(Verb.EXECUTION_MONITOR_GET, snapshot(1, 0, 0));
            session.start(EXECUTION).join();
            updates.clear();
        }

        @Test
        @DisplayName("a grant sends the request and adopts the refreshed snapshot")
        void extendAdoptsTheAnswer() {
            connection.replyOk(Verb.EXECUTION_EXTEND, snapshotWithExtra(15));

            session.extend(15).join();

            ExtendTimeRequest sent = (ExtendTimeRequest) connection.lastSent().getPayload();
            assertThat(sent.extraMinutes()).isEqualTo(15);
            assertThat(session.snapshot().get().extraMinutes())
                    .as("the numbers on screen are the server's, not the client's arithmetic")
                    .isEqualTo(15);
        }

        @Test
        @DisplayName("an impossible amount never leaves the client (§6)")
        void impossibleAmountSendsNothing() {
            connection.clearSent();

            session.extend(0).join();
            session.extend(-5).join();
            session.extend(ExtendTimeRequest.MAX_MINUTES + 1).join();

            assertThat(connection.sentCount()).isZero();
            assertThat(session.lastError()).contains("between 1 and");
        }

        @Test
        @DisplayName("a refused grant is surfaced with the server's sentence")
        void refusedGrant() {
            connection.replyError(Verb.EXECUTION_EXTEND, ErrorCode.CONFLICT,
                    "Only a live exam can be extended. This one is not running.");

            session.extend(15).join();

            assertThat(session.lastError()).startsWith("Only a live exam can be extended");
        }

        @Test
        @DisplayName("a failed grant says so rather than silently doing nothing")
        void failedGrant() throws IOException {
            connection.failSendsWith(new IOException("socket closed"));

            session.extend(15).join();

            assertThat(session.lastError()).isEqualTo(ExamCopy.OFFLINE);
        }
    }

    // ===================== Fixture =======================================

    /**
     * A clock a test moves by setting an instant.
     *
     * <p>The ageing between pushes is the one thing here that depends on wall time, and a
     * column of countdowns that ticks is worth proving without waiting a minute for a
     * minute to pass.
     */
    private static final class MovingClock extends Clock {

        private final java.util.concurrent.atomic.AtomicReference<Instant> now;

        MovingClock(java.util.concurrent.atomic.AtomicReference<Instant> now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private static ExecutionMonitor snapshot(int started, int finished, int timedOut) {
        List<MonitorRow> rows = new ArrayList<>();
        rows.add(liveRow());
        if (finished > 0) {
            rows.add(new MonitorRow(2002, "Noam Bar", AttemptState.SUBMITTED, NOW,
                    NOW.plus(Duration.ofMinutes(20)), 0, 3, 3, 20, null));
        }
        if (started > rows.size()) {
            rows.add(new MonitorRow(2003, "Ori Katz", AttemptState.IN_PROGRESS, NOW, null,
                    Duration.ofMinutes(10).toMillis(), 1, 3, null,
                    new IntegrityFlag("11", "Algebra 11", NOW)));
        }
        return new ExecutionMonitor(EXECUTION, "Java Midterm", "21", "4B7Q", true, NOW,
                NOW.plus(Duration.ofHours(2)), 0, 45,
                new MonitorCounts(started, finished, timedOut), rows);
    }

    /** A snapshot of a different sitting, so two in-flight answers can be told apart. */
    private static ExecutionMonitor otherSnapshot() {
        ExecutionMonitor base = snapshot(1, 0, 0);
        return new ExecutionMonitor(OTHER_EXECUTION, "Databases Final", "22", "5164",
                base.live(), base.serverNow(), base.closesAt(), base.extraMinutes(),
                base.durationMinutes(), base.counts(), base.rows());
    }

    private static ExecutionMonitor snapshotWithExtra(int extraMinutes) {
        ExecutionMonitor base = snapshot(1, 0, 0);
        return new ExecutionMonitor(base.executionId(), base.examName(), base.courseCode(),
                base.code(), true, NOW, base.closesAt(), extraMinutes, 45 + extraMinutes,
                base.counts(), base.rows());
    }

    private static MonitorRow liveRow() {
        return new MonitorRow(2001, "Maya Levi", AttemptState.IN_PROGRESS, NOW, null,
                Duration.ofMinutes(30).toMillis(), 2, 3, null, null);
    }
}
