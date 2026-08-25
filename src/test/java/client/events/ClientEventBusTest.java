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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
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

    // ===================== P-10: reflective reachability ==================

    /**
     * {@code register} refuses a subscriber the bus could never deliver to.
     *
     * <p>The defect this closes has the worst possible shape: greenrobot invokes
     * {@code @Subscribe} methods reflectively, so a public method on a
     * package-private class registers happily and then throws
     * {@link IllegalAccessException} on every delivery — which
     * {@code RequestDispatcher} swallows on purpose so one bad subscriber cannot
     * drop the socket. The screen paints once, never updates, and the suite stays
     * green because tests post to their subscribers directly.
     *
     * <p>{@link #aPackagePrivateSubscriberIsRefused} is the plant: deleting the
     * check in {@code register} is what it exists to fail on.
     */
    @Nested
    @DisplayName("P-10: @Subscribe classes must be reflectively reachable")
    class SubscriberReachability {

        @Test
        @DisplayName("a package-private subscriber is refused, by name and with the reason")
        void aPackagePrivateSubscriberIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> eventBus.register(new HiddenSubscriber()))
                    .withMessageContaining("@Subscribe classes must be public")
                    .withMessageContaining(HiddenSubscriber.class.getName())
                    .withMessageContaining("delivers nothing, silently")
                    .withMessageContaining("P-10");
        }

        @Test
        @DisplayName("the refusal happens at registration, before the bus is touched")
        void nothingIsRegisteredWhenItIsRefused() {
            HiddenSubscriber hidden = new HiddenSubscriber();

            assertThatIllegalArgumentException().isThrownBy(() -> eventBus.register(hidden));

            assertThat(eventBus.isRegistered(hidden))
                    .as("registration is where the developer is looking; delivery is not")
                    .isFalse();
        }

        @Test
        @DisplayName("a public subscriber registers and receives, exactly as before")
        void aPublicSubscriberIsUnaffected() {
            assertThatCode(() -> eventBus.register(subscriber)).doesNotThrowAnyException();

            eventBus.post(new ServerPushEvent(Verb.PUSH_NOTIFICATION, "hi"));

            assertThat(subscriber.events).hasSize(1);
        }

        @Test
        @DisplayName("an anonymous subscriber is refused: it is genuinely unreachable")
        void anAnonymousSubscriberIsRefused() {
            Object anonymous = new Object() {
                @Subscribe
                public void onPush(ServerPushEvent event) {
                    // never called: greenrobot cannot reach this class from its own package
                }
            };

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> eventBus.register(anonymous));
        }

        @Test
        @DisplayName("a public class nested in a package-private one is ACCEPTED, and delivers")
        void aPublicNestedClassIsReachable() {
            // The measured correction to the obvious guard. Walking enclosing classes would
            // refuse this - and would refuse RecordingSubscriber below, which is exactly this
            // shape and has been delivering events through the real bus since E1.8. A nested
            // class carries its own ACC_PUBLIC in its own class file and the JVM's access
            // check never looks at the outer one.
            RecordingSubscriber nested = new RecordingSubscriber();

            eventBus.register(nested);
            eventBus.post(new ServerPushEvent(Verb.PUSH_NOTIFICATION, "reaches it"));

            assertThat(nested.events)
                    .as("the proof is the delivery, not the modifier")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a protected nested subscriber is accepted, because the JVM accepts it")
        void aProtectedNestedClassIsReachable() {
            // The one case where every Java-level API disagrees with the JVM: getModifiers()
            // and accessFlags() both report PROTECTED, from the InnerClasses attribute, while
            // the class file itself carries ACC_PUBLIC and the access check passes. Refusing
            // this would break a subscriber that works.
            assertThatCode(() -> eventBus.register(new ProtectedSubscriber()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a null subscriber is refused before anything is asked of it")
        void nullIsRefused() {
            assertThatNullPointerException().isThrownBy(() -> eventBus.register(null));
        }
    }

    /**
     * The P-10 shape itself: a perfectly public {@code @Subscribe} method on a class the bus
     * cannot see. Package-private on purpose — this is the bug, held still.
     */
    static class HiddenSubscriber {
        @Subscribe
        public void onPush(ServerPushEvent event) {
            // Never called in production: greenrobot's invoke throws IllegalAccessException,
            // RequestDispatcher logs it, and the screen quietly stops updating.
        }
    }

    /** Protected rather than public, to pin the ACC_PUBLIC case the guard must accept. */
    protected static class ProtectedSubscriber {
        @Subscribe
        public void onPush(ServerPushEvent event) {
            // reachable: a protected member class is ACC_PUBLIC in its own class file
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
