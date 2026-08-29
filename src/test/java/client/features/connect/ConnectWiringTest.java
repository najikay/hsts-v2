package client.features.connect;

import client.core.ServerEndpoint;
import client.events.ClientEventBus;
import client.events.ConnectionLostEvent;
import client.events.ConnectionWatcher;
import client.events.DirectFxThreadPoster;
import client.events.ServerPushEvent;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.protocol.Message;
import common.protocol.Verb;
import org.greenrobot.eventbus.Subscribe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link ConnectWiring} and the connection-lost path it feeds (E4.5 / E5.7).
 *
 * <p>Nothing here opens a socket: the wiring deliberately builds a client without
 * connecting it, which is what makes "who hears about a dropped connection"
 * testable at all.
 */
class ConnectWiringTest {

    private static final ServerEndpoint ENDPOINT = new ServerEndpoint("10.0.0.5", 5555);

    private ClientEventBus eventBus;
    private Collector collector;

    @BeforeEach
    void setUp() {
        eventBus = new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster());
        collector = new Collector();
        eventBus.register(collector);
    }

    @Nested
    @DisplayName("building the stack")
    class Building {

        @Test
        @DisplayName("binds a client to the chosen endpoint, unconnected")
        void bindsTheEndpoint() {
            ConnectWiring.Wiring wiring = ConnectWiring.forEndpoint(ENDPOINT, eventBus);

            assertThat(wiring.client().getHost()).isEqualTo("10.0.0.5");
            assertThat(wiring.client().getPort()).isEqualTo(5555);
            assertThat(wiring.client().isConnectionOpen()).isFalse();
            assertThat(wiring.dispatcher()).isNotNull();
        }

        @Test
        @DisplayName("a push arriving on the dispatcher reaches the event bus")
        void pushesReachTheBus() {
            ConnectWiring.Wiring wiring = ConnectWiring.forEndpoint(ENDPOINT, eventBus);

            wiring.dispatcher().dispatchIncoming(Message.push(Verb.PUSH_NOTIFICATION, "hello"));

            assertThat(collector.pushes).hasSize(1);
            assertThat(collector.pushes.get(0).verb()).isEqualTo(Verb.PUSH_NOTIFICATION);
        }

        @Test
        @DisplayName("a second connection rebinds the dispatcher instead of replacing it")
        void reconnectingKeepsTheDispatcher() {
            // ⚑ U-17, 2026-08-29, manual round 2. Screens capture the dispatcher when
            // they are built and they are built once, so the second connection has to
            // arrive through the object they are already holding.
            ConnectWiring.Wiring first = ConnectWiring.forEndpoint(ENDPOINT, eventBus);

            ConnectWiring.Wiring second = ConnectWiring.forEndpoint(
                    new ServerEndpoint("10.0.0.9", 5555), eventBus, first.dispatcher());

            assertThat(second.dispatcher())
                    .as("the same correlator, so a cached screen is not left behind")
                    .isSameAs(first.dispatcher());
            assertThat(second.client())
                    .as("but a new client: a client cannot be re-pointed")
                    .isNotSameAs(first.client());
            assertThat(second.client().getHost()).isEqualTo("10.0.0.9");
        }

        @Test
        @DisplayName("the rebound dispatcher sends on the new connection and still feeds the bus")
        void theReboundDispatcherUsesTheNewConnection() {
            FakeClientConnection firstSocket = new FakeClientConnection("first", 5555);
            FakeClientConnection secondSocket = new FakeClientConnection("second", 5555);

            ConnectWiring.Wiring first =
                    ConnectWiring.attach(firstSocket, ENDPOINT, eventBus, null);
            ConnectWiring.Wiring second =
                    ConnectWiring.attach(secondSocket, ENDPOINT, eventBus, first.dispatcher());

            assertThat(second.dispatcher()).isSameAs(first.dispatcher());

            second.dispatcher().send(Verb.LOGIN, null);
            assertThat(secondSocket.sentMessages()).hasSize(1);
            assertThat(firstSocket.sentMessages()).isEmpty();

            // And the inbound half was re-registered too, in both directions.
            secondSocket.pushToClient(Verb.PUSH_NOTIFICATION, "hello");
            assertThat(collector.pushes).hasSize(1);
        }

        @Test
        @DisplayName("a connection is required to attach one")
        void attachNeedsAConnection() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ConnectWiring.attach(null, ENDPOINT, eventBus, null));
        }

        @Test
        @DisplayName("an endpoint is required")
        void theEndpointIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ConnectWiring.forEndpoint(null, eventBus));
        }

        @Test
        @DisplayName("a missing bus degrades to a detached one instead of throwing")
        void aMissingBusIsSurvivable() {
            // UI wave 1 item 0: this call is reachable from a discovery sweep
            // that lands after the app was torn down, on the FX thread with no
            // test in the stack. Throwing there fails an unrelated test, so the
            // wiring is built against a bus nobody listens to instead.
            ConnectWiring.Wiring wiring = ConnectWiring.forEndpoint(ENDPOINT, null);

            assertThat(wiring.client().getHost()).isEqualTo("10.0.0.5");
            assertThat(wiring.client().isConnectionOpen())
                    .as("still no socket: a torn-down app must not open one")
                    .isFalse();

            // And the detached bus really is detached: a push goes nowhere.
            wiring.dispatcher().dispatchIncoming(Message.push(Verb.PUSH_NOTIFICATION, "hello"));
            assertThat(collector.pushes).isEmpty();
        }
    }

    @Nested
    @DisplayName("a dropped connection")
    class ConnectionLost {

        private FakeClientConnection connection;
        private RequestDispatcher dispatcher;

        @BeforeEach
        void wire() {
            connection = new FakeClientConnection();
            dispatcher = new RequestDispatcher(connection, Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("fails every in-flight request instead of leaving screens hanging")
        void failsPendingRequests() {
            CompletableFuture<Message> pending = dispatcher.send(Verb.BANK_LIST, null);
            assertThat(dispatcher.pendingCount()).isEqualTo(1);

            ConnectWiring.connectionLostHandler(ENDPOINT, eventBus, dispatcher)
                    .accept(new IOException("cable"));

            assertThat(pending).isCompletedExceptionally();
            assertThat(dispatcher.pendingCount()).isZero();
        }

        @Test
        @DisplayName("announces itself on the bus, naming the server")
        void postsTheEvent() {
            ConnectWiring.connectionLostHandler(ENDPOINT, eventBus, dispatcher)
                    .accept(new IOException("cable"));

            assertThat(collector.losses).hasSize(1);
            assertThat(collector.losses.get(0).serverLabel()).isEqualTo(ENDPOINT.display());
            assertThat(collector.losses.get(0).detail()).isEqualTo("cable");
        }

        @Test
        @DisplayName("a cause with no message still produces a usable detail")
        void handlesAMessagelessCause() {
            ConnectWiring.connectionLostHandler(ENDPOINT, eventBus, dispatcher)
                    .accept(new IllegalStateException());

            assertThat(collector.losses.get(0).detail()).isEqualTo("IllegalStateException");
        }

        @Test
        @DisplayName("even a null cause is reported rather than swallowed")
        void handlesANullCause() {
            ConnectWiring.connectionLostHandler(ENDPOINT, eventBus, dispatcher).accept(null);

            assertThat(collector.losses).hasSize(1);
            assertThat(collector.losses.get(0).detail()).isEmpty();
        }

        @Test
        @DisplayName("the shell's watcher turns the event into one banner call")
        void watcherRaisesTheBanner() {
            List<String> banners = new ArrayList<>();
            ConnectionWatcher watcher = new ConnectionWatcher(
                    event -> banners.add(event.serverLabel()));
            eventBus.register(watcher);

            ConnectWiring.connectionLostHandler(ENDPOINT, eventBus, dispatcher)
                    .accept(new IOException("cable"));

            assertThat(banners).containsExactly(ENDPOINT.display());

            // Unregistered — as logout does — it must go quiet.
            eventBus.unregister(watcher);
            ConnectWiring.connectionLostHandler(ENDPOINT, eventBus, dispatcher)
                    .accept(new IOException("again"));
            assertThat(banners).hasSize(1);
        }

        @Test
        @DisplayName("the watcher ignores a null event and demands an action")
        void watcherEdgeCases() {
            List<String> banners = new ArrayList<>();
            new ConnectionWatcher(event -> banners.add(event.serverLabel()))
                    .onConnectionLost(null);

            assertThat(banners).isEmpty();
            assertThatNullPointerException().isThrownBy(() -> new ConnectionWatcher(null));
        }

        @Test
        @DisplayName("the event normalises its own missing fields")
        void eventNormalisation() {
            ConnectionLostEvent event = new ConnectionLostEvent(null, null);

            assertThat(event.serverLabel()).isEqualTo("the server");
            assertThat(event.detail()).isEmpty();
        }
    }

    /** Stands in for the screens and the shell subscribing to the bus. */
    public static class Collector {

        final List<ServerPushEvent> pushes = new ArrayList<>();
        final List<ConnectionLostEvent> losses = new ArrayList<>();

        @Subscribe
        public void onPush(ServerPushEvent event) {
            pushes.add(event);
        }

        @Subscribe
        public void onLost(ConnectionLostEvent event) {
            losses.add(event);
        }
    }
}
