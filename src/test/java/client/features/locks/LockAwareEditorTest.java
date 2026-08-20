package client.features.locks;

import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.events.ServerPushEvent;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.lock.EntityRef;
import common.dto.lock.LockChange;
import common.dto.lock.LockHolder;
import common.dto.lock.LockRequest;
import common.dto.lock.LockResponse;
import common.dto.lock.LockTiming;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for the reusable lock-aware editor helper (E18.3).
 *
 * <p>No toolkit anywhere: the heartbeat is a {@link ManualHeartbeat} the test
 * ticks by hand, the server is a {@link FakeClientConnection}, and the bus posts
 * synchronously. That makes the four UI states, the renewal loop and the
 * best-effort release ordinary unit tests instead of something to click through.
 */
class LockAwareEditorTest {

    private static final long ME = 1001L;
    private static final EntityRef QUESTION = EntityRef.question(42);
    private static final EntityRef OTHER = EntityRef.question(43);
    private static final LockHolder MYSELF = new LockHolder(ME, "Dana Cohen");
    private static final LockHolder RINA = new LockHolder(1002L, "Rina Barak");
    private static final Instant EXPIRY = Instant.parse("2026-08-19T09:00:40Z");

    private FakeClientConnection connection;
    private ClientEventBus eventBus;
    private ManualHeartbeat heartbeat;
    private LockAwareEditor editor;
    private List<EditLockState.Snapshot> seen;

    @BeforeEach
    void setUp() throws IOException {
        connection = new FakeClientConnection();
        connection.connect();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        eventBus = new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster());
        heartbeat = new ManualHeartbeat();
        editor = new LockAwareEditor(dispatcher, eventBus, ME, heartbeat, "question");
        seen = new ArrayList<>();
        editor.onStateChanged(seen::add);
    }

    // ===================== Opening =======================================

    @Nested
    @DisplayName("opening")
    class Opening {

        @Test
        @DisplayName("opening acquires the lock and starts the heartbeat")
        void grantedOpen() {
            grant();

            editor.open(QUESTION);

            assertThat(editor.isEditable()).isTrue();
            assertThat(editor.entity()).isEqualTo(QUESTION);
            assertThat(sentVerbs()).containsExactly(Verb.LOCK_ACQUIRE);
            assertThat(((LockRequest) connection.lastSent().getPayload()).entity())
                    .isEqualTo(QUESTION);
            assertThat(heartbeat.isRunning()).isTrue();
            assertThat(heartbeat.period()).isEqualTo(LockTiming.HEARTBEAT);
        }

        @Test
        @DisplayName("a refusal opens read-only, names the holder and sends no heartbeat")
        void refusedOpen() {
            connection.replyOk(Verb.LOCK_ACQUIRE,
                    LockResponse.refused(QUESTION, RINA, EXPIRY));

            editor.open(QUESTION);

            assertThat(editor.isEditable()).isFalse();
            assertThat(editor.state().isReadOnly()).isTrue();
            assertThat(editor.state().bannerText("question"))
                    .contains("Rina Barak is editing this question. It is read-only for you.");
            assertThat(heartbeat.isRunning())
                    .as("a read-only screen must generate no traffic at all")
                    .isFalse();
        }

        @Test
        @DisplayName("the screen sees 'checking' before it sees an answer")
        void checkingComesFirst() {
            grant();

            editor.open(QUESTION);

            assertThat(seen).extracting(EditLockState.Snapshot::mode)
                    .containsExactly(EditLockState.Mode.CHECKING, EditLockState.Mode.OWNED);
        }

        @Test
        @DisplayName("opening a different entity releases the previous one first")
        void switchingEntitiesReleases() {
            grant();
            editor.open(QUESTION);
            connection.clearSent();

            editor.open(OTHER);

            assertThat(sentVerbs()).containsExactly(Verb.LOCK_RELEASE, Verb.LOCK_ACQUIRE);
            assertThat(editor.entity()).isEqualTo(OTHER);
        }

        @Test
        @DisplayName("re-opening the same entity does not churn the lock")
        void reopeningTheSameEntity() {
            grant();
            editor.open(QUESTION);
            connection.clearSent();

            editor.open(QUESTION);

            assertThat(sentVerbs()).containsExactly(Verb.LOCK_ACQUIRE);
        }

        @Test
        @DisplayName("an entity is required")
        void entityRequired() {
            assertThatNullPointerException().isThrownBy(() -> editor.open(null));
        }
    }

    // ===================== Heartbeat =====================================

    @Nested
    @DisplayName("heartbeat")
    class Heartbeat {

        @Test
        @DisplayName("each tick renews the lock while the editor owns it")
        void tickRenews() {
            grant();
            connection.respondTo(Verb.LOCK_RENEW,
                    request -> Message.ok(request, LockResponse.granted(QUESTION, MYSELF, EXPIRY)));
            editor.open(QUESTION);
            connection.clearSent();

            heartbeat.tick();
            heartbeat.tick();

            assertThat(sentVerbs()).containsExactly(Verb.LOCK_RENEW, Verb.LOCK_RENEW);
            assertThat(editor.isEditable()).isTrue();
        }

        @Test
        @DisplayName("a renewal refused after a takeover turns the editor read-only")
        void renewalRefusedAfterTakeover() {
            grant();
            editor.open(QUESTION);
            connection.respondTo(Verb.LOCK_RENEW,
                    request -> Message.ok(request, LockResponse.refused(QUESTION, RINA, EXPIRY)));

            heartbeat.tick();

            assertThat(editor.isEditable()).isFalse();
            assertThat(editor.state().isReadOnly()).isTrue();
            assertThat(heartbeat.isRunning()).isFalse();
        }

        @Test
        @DisplayName("a renewal that finds the lock gone offers a takeover, reported as a loss")
        void renewalFindsItLapsed() {
            grant();
            editor.open(QUESTION);
            connection.respondTo(Verb.LOCK_RENEW,
                    request -> Message.ok(request, LockResponse.free(QUESTION)));

            heartbeat.tick();

            assertThat(editor.state().offersTakeover()).isTrue();
            assertThat(editor.state().reason()).isEqualTo(TakeoverReason.LOST);
            assertThat(heartbeat.isRunning()).isFalse();
        }

        @Test
        @DisplayName("a read-only editor's ticks send nothing")
        void readOnlyEditorDoesNotRenew() {
            connection.replyOk(Verb.LOCK_ACQUIRE, LockResponse.refused(QUESTION, RINA, EXPIRY));
            editor.open(QUESTION);
            connection.clearSent();

            heartbeat.tick();

            assertThat(connection.sentCount()).isZero();
        }
    }

    // ===================== Pushes ========================================

    @Nested
    @DisplayName("pushes")
    class Pushes {

        @Test
        @DisplayName("somebody taking the lock turns this editor read-only, live")
        void takenAway() {
            grant();
            editor.open(QUESTION);

            eventBus.post(new ServerPushEvent(Verb.PUSH_LOCK_CHANGED,
                    LockChange.acquired(QUESTION, RINA)));

            assertThat(editor.isEditable()).isFalse();
            assertThat(editor.state().holderName()).contains(RINA);
            assertThat(heartbeat.isRunning()).isFalse();
        }

        @Test
        @DisplayName("a release reaching a reader offers the takeover instead of grabbing it")
        void releasedElsewhere() {
            connection.replyOk(Verb.LOCK_ACQUIRE, LockResponse.refused(QUESTION, RINA, EXPIRY));
            editor.open(QUESTION);
            connection.clearSent();

            eventBus.post(new ServerPushEvent(Verb.PUSH_LOCK_CHANGED, LockChange.released(QUESTION)));

            assertThat(editor.state().offersTakeover()).isTrue();
            assertThat(editor.state().reason()).isEqualTo(TakeoverReason.AVAILABLE);
            assertThat(connection.sentCount())
                    .as("nothing is acquired until the user says so")
                    .isZero();
        }

        @Test
        @DisplayName("a push for another entity is ignored")
        void otherEntitiesAreIgnored() {
            grant();
            editor.open(QUESTION);

            eventBus.post(new ServerPushEvent(Verb.PUSH_LOCK_CHANGED,
                    LockChange.acquired(OTHER, RINA)));

            assertThat(editor.isEditable()).isTrue();
        }

        @Test
        @DisplayName("other verbs and malformed payloads are ignored, never thrown")
        void noiseIsIgnored() {
            grant();
            editor.open(QUESTION);

            eventBus.post(new ServerPushEvent(Verb.PUSH_NOTIFICATION, "anything"));
            eventBus.post(new ServerPushEvent(Verb.PUSH_LOCK_CHANGED, "not a change"));
            eventBus.post(new ServerPushEvent(Verb.PUSH_LOCK_CHANGED, null));
            editor.onServerPush(null);

            assertThat(editor.isEditable()).isTrue();
        }

        @Test
        @DisplayName("a push arriving after close changes nothing")
        void pushesStopAtClose() {
            grant();
            editor.open(QUESTION);
            editor.close();

            eventBus.post(new ServerPushEvent(Verb.PUSH_LOCK_CHANGED,
                    LockChange.acquired(QUESTION, RINA)));

            assertThat(editor.state().mode()).isEqualTo(EditLockState.Mode.IDLE);
        }
    }

    // ===================== Takeover and close ============================

    @Nested
    @DisplayName("takeover and close")
    class TakeoverAndClose {

        @Test
        @DisplayName("takeOver acquires only when a takeover is actually on offer")
        void takeOverAcquires() {
            connection.replyOk(Verb.LOCK_ACQUIRE, LockResponse.refused(QUESTION, RINA, EXPIRY));
            editor.open(QUESTION);
            eventBus.post(new ServerPushEvent(Verb.PUSH_LOCK_CHANGED, LockChange.released(QUESTION)));
            connection.clearSent();
            grant();

            editor.takeOver();

            assertThat(sentVerbs()).containsExactly(Verb.LOCK_ACQUIRE);
            assertThat(editor.isEditable()).isTrue();
        }

        @Test
        @DisplayName("a stale takeover click cannot steal a lock somebody has since taken")
        void takeOverIsIgnoredWhenNotOffered() {
            connection.replyOk(Verb.LOCK_ACQUIRE, LockResponse.refused(QUESTION, RINA, EXPIRY));
            editor.open(QUESTION);
            connection.clearSent();

            editor.takeOver();

            assertThat(connection.sentCount()).isZero();
            assertThat(editor.state().isReadOnly()).isTrue();
        }

        @Test
        @DisplayName("takeOver with nothing open does nothing")
        void takeOverWithNothingOpen() {
            editor.takeOver();

            assertThat(connection.sentCount()).isZero();
        }

        @Test
        @DisplayName("declining leaves the screen read-only")
        void decline() {
            connection.replyOk(Verb.LOCK_ACQUIRE, LockResponse.free(QUESTION));
            editor.open(QUESTION);

            editor.declineTakeover();

            assertThat(editor.state().isReadOnly()).isTrue();
            assertThat(editor.state().offersTakeover()).isFalse();
        }

        @Test
        @DisplayName("close stops the heartbeat, releases and unsubscribes")
        void closeReleases() {
            grant();
            editor.open(QUESTION);
            connection.clearSent();

            editor.close();

            assertThat(sentVerbs()).containsExactly(Verb.LOCK_RELEASE);
            assertThat(heartbeat.isRunning()).isFalse();
            assertThat(editor.entity()).isNull();
            assertThat(editor.state().mode()).isEqualTo(EditLockState.Mode.IDLE);
        }

        @Test
        @DisplayName("close is safe with nothing open, so a screen can call it unconditionally")
        void closeWithNothingOpen() {
            editor.close();

            assertThat(connection.sentCount()).isZero();
            assertThat(editor.state().mode()).isEqualTo(EditLockState.Mode.IDLE);
        }

        @Test
        @DisplayName("a release that never reaches the server does not hold the user up")
        void releaseIsBestEffort() {
            grant();
            editor.open(QUESTION);
            connection.failSendsWith(new IOException("socket closed"));

            editor.close();

            assertThat(editor.state().mode()).isEqualTo(EditLockState.Mode.IDLE);
        }
    }

    // ===================== Failures ======================================

    @Nested
    @DisplayName("failures")
    class Failures {

        @Test
        @DisplayName("a server error never leaves the editor claiming the lock")
        void serverErrorDropsTheClaim() {
            connection.replyError(Verb.LOCK_ACQUIRE, ErrorCode.INTERNAL, "boom");

            editor.open(QUESTION);

            assertThat(editor.isEditable()).isFalse();
            assertThat(editor.state().offersTakeover()).isTrue();
            assertThat(heartbeat.isRunning()).isFalse();
        }

        @Test
        @DisplayName("a dropped socket is handled the same way")
        void sendFailureDropsTheClaim() {
            connection.failSendsWith(new IOException("socket closed"));

            editor.open(QUESTION);

            assertThat(editor.isEditable()).isFalse();
            assertThat(editor.state().offersTakeover()).isTrue();
        }

        @Test
        @DisplayName("an answer of the wrong shape is treated as a failure, not as a grant")
        void unexpectedPayload() {
            connection.replyOk(Verb.LOCK_ACQUIRE, "not a lock response");

            editor.open(QUESTION);

            assertThat(editor.isEditable()).isFalse();
        }

        @Test
        @DisplayName("an answer arriving after the editor moved on is dropped")
        void lateAnswerAfterClose() {
            connection.respondTo(Verb.LOCK_ACQUIRE, request -> null);
            editor.open(QUESTION);
            Message request = connection.lastSent();
            editor.close();

            connection.deliver(Message.ok(request, LockResponse.granted(QUESTION, MYSELF, EXPIRY)));

            assertThat(editor.state().mode()).isEqualTo(EditLockState.Mode.IDLE);
            assertThat(editor.isEditable()).isFalse();
        }
    }

    @Test
    @DisplayName("the helper exposes the noun its screen gave it")
    void nounIsReadable() {
        assertThat(editor.entityNoun()).isEqualTo("question");
    }

    @Test
    @DisplayName("every collaborator is required")
    void collaboratorsAreRequired() {
        RequestDispatcher dispatcher = new RequestDispatcher(connection);

        assertThatNullPointerException().isThrownBy(() ->
                new LockAwareEditor(null, eventBus, ME, heartbeat, "question"));
        assertThatNullPointerException().isThrownBy(() ->
                new LockAwareEditor(dispatcher, null, ME, heartbeat, "question"));
        assertThatNullPointerException().isThrownBy(() ->
                new LockAwareEditor(dispatcher, eventBus, ME, null, "question"));
        assertThatNullPointerException().isThrownBy(() ->
                new LockAwareEditor(dispatcher, eventBus, ME, heartbeat, null));
        assertThatNullPointerException().isThrownBy(() -> editor.onStateChanged(null));
    }

    // ===================== Helpers =======================================

    private void grant() {
        connection.respondTo(Verb.LOCK_ACQUIRE,
                request -> Message.ok(request, LockResponse.granted(QUESTION, MYSELF, EXPIRY)));
        connection.respondTo(Verb.LOCK_RELEASE,
                request -> Message.ok(request, LockResponse.free(QUESTION)));
    }

    private List<Verb> sentVerbs() {
        return connection.sentMessages().stream().map(Message::getVerb).toList();
    }

    /** A {@link client.features.locks.Heartbeat} the test drives by hand. */
    private static final class ManualHeartbeat implements client.features.locks.Heartbeat {

        private Runnable tick;
        private Duration period;

        @Override
        public void start(Duration everyPeriod, Runnable action) {
            this.period = everyPeriod;
            this.tick = action;
        }

        @Override
        public void stop() {
            this.tick = null;
        }

        @Override
        public boolean isRunning() {
            return tick != null;
        }

        Duration period() {
            return period;
        }

        /** Fires one beat, as a Timeline would. */
        void tick() {
            if (tick != null) {
                tick.run();
            }
        }
    }
}
