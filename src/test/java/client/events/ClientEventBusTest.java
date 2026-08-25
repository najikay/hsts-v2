package client.events;

import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.protocol.Message;
import common.protocol.Status;
import common.protocol.Verb;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for the client event layer (E1.8): {@link ClientEventBus},
 * {@link PushEventBridge}, {@link ServerPushEvent} and the
 * {@link FxThreadPoster} seam.
 *
 * <p>The point of the seam is proved here: the whole push → event → subscriber
 * chain runs with no JavaFX toolkit booted, because the tests install
 * {@link DirectFxThreadPoster}. In production the same chain runs through
 * {@code PlatformFxThreadPoster} — the single documented crossing onto the FX
 * thread (ARCHITECTURE §6).
 */
class ClientEventBusTest {

    private CountingPoster poster;
    private ClientEventBus eventBus;
    private RecordingSubscriber subscriber;

    @BeforeEach
    void setUp() {
        poster = new CountingPoster();
        eventBus = new ClientEventBus(ClientEventBus.newBus(), poster);
        subscriber = new RecordingSubscriber();
    }

    @Nested
    @DisplayName("bus")
    class Bus {

        @Test
        @DisplayName("a posted event reaches a registered subscriber, through the poster")
        void postReachesSubscribers() {
            eventBus.register(subscriber);

            eventBus.post(new ServerPushEvent(Verb.PUSH_NOTIFICATION, "hi"));

            assertThat(subscriber.events).hasSize(1);
            assertThat(subscriber.events.get(0).verb()).isEqualTo(Verb.PUSH_NOTIFICATION);
            assertThat(poster.calls).hasValue(1);
        }

        @Test
        @DisplayName("unregistering stops delivery")
        void unregisterStopsDelivery() {
            eventBus.register(subscriber);
            assertThat(eventBus.isRegistered(subscriber)).isTrue();

            eventBus.unregister(subscriber);
            eventBus.post(new ServerPushEvent(Verb.PUSH_NOTIFICATION, "hi"));

            assertThat(eventBus.isRegistered(subscriber)).isFalse();
            assertThat(subscriber.events).isEmpty();
        }

        @Test
        @DisplayName("an event with no subscribers is silently ignored (a push may outlive its screen)")
        void postWithoutSubscribersIsHarmless() {
            assertThatCode(() -> eventBus.post(new ServerPushEvent(Verb.PUSH_GRADE_PUBLISHED, null)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the wrapped bus and poster are reachable for advanced use")
        void exposesItsCollaborators() {
            assertThat(eventBus.poster()).isSameAs(poster);
            assertThat(eventBus.bus()).isInstanceOf(EventBus.class);
        }

        @Test
        @DisplayName("it refuses to be built without a bus or a poster")
        void constructorValidatesArguments() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ClientEventBus(null, poster));
            assertThatNullPointerException()
                    .isThrownBy(() -> new ClientEventBus(ClientEventBus.newBus(), null));
        }

        @Test
        @DisplayName("getInstance() is a stable singleton with a real FX poster")
        void singletonIsStable() {
            ClientEventBus first = ClientEventBus.getInstance();

            assertThat(ClientEventBus.getInstance()).isSameAs(first);
            assertThat(first.poster()).isInstanceOf(PlatformFxThreadPoster.class);
        }
    }

    @Nested
    @DisplayName("FxThreadPoster")
    class Posters {

        @Test
        @DisplayName("the direct poster runs the action inline on the calling thread")
        void directPosterIsSynchronous() {
            AtomicInteger ran = new AtomicInteger();

            new DirectFxThreadPoster().run(ran::incrementAndGet);

            assertThat(ran).hasValue(1);
        }
    }

    @Nested
    @DisplayName("PushEventBridge")
    class Bridge {

        @Test
        @DisplayName("a push becomes a ServerPushEvent carrying verb and payload")
        void pushBecomesAnEvent() {
            eventBus.register(subscriber);
            PushEventBridge bridge = new PushEventBridge(eventBus);

            bridge.onPush(Message.push(Verb.PUSH_TIMER_EXTENDED, "+10 min"));

            assertThat(subscriber.events).hasSize(1);
            assertThat(subscriber.events.get(0))
                    .isEqualTo(new ServerPushEvent(Verb.PUSH_TIMER_EXTENDED, "+10 min"));
        }

        @Test
        @DisplayName("null, verb-less and non-push messages are dropped, never thrown")
        void malformedPushesAreDropped() {
            eventBus.register(subscriber);
            PushEventBridge bridge = new PushEventBridge(eventBus);

            assertThatCode(() -> {
                bridge.onPush(null);
                bridge.onPush(new Message(null, "id", Status.PUSH, null, "x"));
                // A request verb wearing PUSH status: a server bug or a spoof.
                bridge.onPush(new Message(Verb.BANK_LIST, "id", Status.PUSH, null, "x"));
            }).doesNotThrowAnyException();

            assertThat(subscriber.events).isEmpty();
        }

        @Test
        @DisplayName("it refuses to be built without a bus")
        void constructorValidatesArguments() {
            assertThatNullPointerException().isThrownBy(() -> new PushEventBridge(null));
        }

        @Test
        @DisplayName("end to end: a socket push reaches a screen's subscriber with no FX toolkit")
        void socketToSubscriber() {
            eventBus.register(subscriber);
            FakeClientConnection connection = new FakeClientConnection();
            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            dispatcher.setPushListener(new PushEventBridge(eventBus));
            connection.setServerMessageHandler(dispatcher::dispatchIncoming);

            connection.pushToClient(Verb.PUSH_FORCE_SUBMITTED, "attempt 12");

            assertThat(subscriber.events)
                    .containsExactly(new ServerPushEvent(Verb.PUSH_FORCE_SUBMITTED, "attempt 12"));
        }
    }

    /**
     * A screen stand-in: records what it was told about. Public because
     * greenrobot invokes {@code @Subscribe} methods reflectively — the same
     * constraint real screens live under.
     */
    public static class RecordingSubscriber {
        final List<ServerPushEvent> events = new ArrayList<>();

        @Subscribe
        public void onPush(ServerPushEvent event) {
            events.add(event);
        }
    }

    /** Synchronous poster that also counts how often the crossing was used. */
    private static final class CountingPoster implements FxThreadPoster {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void run(Runnable action) {
            calls.incrementAndGet();
            action.run();
        }
    }
}
