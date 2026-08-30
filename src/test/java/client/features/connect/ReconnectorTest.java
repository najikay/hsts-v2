package client.features.connect;

import client.core.ConnectPrefs;
import client.core.InMemoryPropertiesStore;
import client.core.ScreenManager;
import client.core.ServerEndpoint;
import client.events.ClientEventBus;
import client.events.ConnectionLostEvent;
import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.protocol.Verb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client that lost the network, and the Retry that gets it back (⚑ U-52).
 *
 * <p>2026-08-30, Findings.txt, U-52. Two separate failures were reported as one, and
 * they are tested here as two: a dropped connection that nothing retired, so the app
 * went on believing an unusable socket; and a Retry button with no action behind it.
 *
 * <p>No toolkit and no socket. The bus posts on the calling thread
 * ({@link DirectFxThreadPoster}), the connect runs on the calling thread
 * ({@code Runnable::run}), and the stack is built around a
 * {@link FakeClientConnection} through the production {@link ConnectWiring} rather
 * than around a rehearsal of it.
 */
class ReconnectorTest {

    private static final ServerEndpoint ENDPOINT = new ServerEndpoint("10.0.0.5", 5555);

    /** A long enough id that pinning can log its short form. */
    private static final String FINGERPRINT = "a1b2c3d4e5f60718293a4b5c6d7e8f90";

    private ClientEventBus bus;
    private ScreenManager manager;
    private ConnectPrefs prefs;
    private FakeClientConnection live;
    private RequestDispatcher dispatcher;

    @BeforeEach
    void setUp() throws IOException {
        bus = new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster());
        manager = ScreenManager.getInstance();
        // The production subscription, registered the way init() registers it.
        manager.watchForConnectionLoss(bus);
        prefs = new ConnectPrefs(new InMemoryPropertiesStore());

        live = new FakeClientConnection(ENDPOINT.host(), ENDPOINT.port());
        live.connect();
        dispatcher = new RequestDispatcher(live);
        live.setServerMessageHandler(dispatcher::dispatchIncoming);
        manager.setClient(live);
        manager.setDispatcher(dispatcher);
    }

    @AfterEach
    void tearDown() {
        // The manager is a Singleton: nothing of this test may reach the next one.
        manager.setClient(null);
        manager.setDispatcher(null);
    }

    @Nested
    @DisplayName("a drop retires the client")
    class MarkingItDead {

        @Test
        @DisplayName("⚑ U-52: a ConnectionLostEvent closes the client and records the loss")
        void aDropMarksTheClientDead() {
            assertThat(manager.isConnectionAlive())
                    .as("the state this test starts from")
                    .isTrue();

            bus.post(new ConnectionLostEvent(ENDPOINT.display(), "no route to host"));

            assertThat(live.isConnectionOpen())
                    .as("nothing may trust the old socket again")
                    .isFalse();
            assertThat(manager.isConnectionAlive()).isFalse();
            assertThat(manager.lastLoss()).map(ConnectionLostEvent::serverLabel)
                    .contains(ENDPOINT.display());
        }

        @Test
        @DisplayName("⚑ U-52: an adapter that goes on claiming to be open is dead anyway")
        void aLyingAdapterIsStillDead() {
            // This is the real shape of the defect. HSTSClient answers isConnectionOpen()
            // from OCSF's isConnected(), which only flips once a read actually fails, so a
            // laptop that slept on battery came back with a client insisting it was fine.
            FakeClientConnection stubborn = new FakeClientConnection(ENDPOINT.host(), ENDPOINT.port()) {
                @Override
                public boolean isConnectionOpen() {
                    return true;
                }
            };
            manager.setClient(stubborn);

            bus.post(new ConnectionLostEvent(ENDPOINT.display(), "no route to host"));

            assertThat(stubborn.isConnectionOpen())
                    .as("the adapter is unrepentant, exactly as OCSF is")
                    .isTrue();
            assertThat(manager.isConnectionAlive())
                    .as("and the recorded loss is what the status row reads instead")
                    .isFalse();
        }

        @Test
        @DisplayName("installing a new client is what clears the loss, not showing a screen")
        void aNewClientClearsTheLoss() throws IOException {
            bus.post(new ConnectionLostEvent(ENDPOINT.display(), "no route to host"));
            assertThat(manager.isConnectionAlive()).isFalse();

            FakeClientConnection fresh = new FakeClientConnection(ENDPOINT.host(), ENDPOINT.port());
            fresh.connect();
            manager.setClient(fresh);

            assertThat(manager.lastLoss()).isEmpty();
            assertThat(manager.isConnectionAlive()).isTrue();
        }

        @Test
        @DisplayName("a connect that failed leaves a closed client, which is still dead")
        void aFailedConnectIsNotAlive() {
            manager.setClient(new FakeClientConnection(ENDPOINT.host(), ENDPOINT.port()));

            assertThat(manager.lastLoss()).isEmpty();
            assertThat(manager.isConnectionAlive()).isFalse();
        }
    }

    @Nested
    @DisplayName("Retry re-dials")
    class Redialling {

        @Test
        @DisplayName("⚑ U-52: the re-dial rebinds the dispatcher the app already holds")
        void redialKeepsTheDispatcher() {
            prefs.remember(ENDPOINT);
            bus.post(new ConnectionLostEvent(ENDPOINT.display(), "no route to host"));

            FakeClientConnection fresh = new FakeClientConnection(ENDPOINT.host(), ENDPOINT.port());
            List<String> outcomes = new ArrayList<>();

            Optional<ServerEndpoint> dialled = reconnectorFor(fresh)
                    .redial(() -> outcomes.add("open"), failure -> outcomes.add("failed"));

            assertThat(dialled).contains(ENDPOINT);
            assertThat(outcomes).containsExactly("open");
            assertThat(manager.getDispatcher())
                    .as("the same correlator, so every cached screen keeps working (U-17)")
                    .isSameAs(dispatcher);
            assertThat(manager.getClient())
                    .as("but a new client: a client cannot be re-pointed")
                    .isSameAs(fresh);
            assertThat(manager.isConnectionAlive()).isTrue();

            // And the rebind really did happen: traffic goes down the new socket.
            manager.getDispatcher().send(Verb.LOGIN, null);
            assertThat(fresh.sentCount()).isEqualTo(1);
            assertThat(live.sentCount()).isZero();
        }

        @Test
        @DisplayName("a re-dial that does not get through reports the reason, not the class name")
        void aFailedRedialReportsWhy() {
            prefs.remember(ENDPOINT);
            FakeClientConnection fresh = new FakeClientConnection(ENDPOINT.host(), ENDPOINT.port())
                    .failConnectWith(new ConnectException("Connection refused"));
            List<Throwable> failures = new ArrayList<>();

            reconnectorFor(fresh).redial(
                    () -> {
                        throw new AssertionError("the socket must not have opened");
                    },
                    failures::add);

            assertThat(failures).hasSize(1);
            String sentence = ConnectFlow.retryFailed(ENDPOINT, failures.get(0));
            assertThat(sentence)
                    .contains(ENDPOINT.display())
                    .contains(ConnectFlow.UNREACHABLE_REFUSED)
                    // B-37: a Java class name is an error code, and users never meet one.
                    .doesNotContain("ConnectException")
                    .doesNotContain("—");
        }

        @Test
        @DisplayName("with nothing to dial it starts nothing, so the caller can ask")
        void nothingToDialStartsNothing() {
            manager.setClient(null);
            List<String> outcomes = new ArrayList<>();

            Optional<ServerEndpoint> dialled = new Reconnector(manager, bus, prefs)
                    .redial(() -> outcomes.add("open"), failure -> outcomes.add("failed"));

            assertThat(dialled).isEmpty();
            assertThat(outcomes).isEmpty();
        }
    }

    @Nested
    @DisplayName("where a re-dial goes")
    class Where {

        @Test
        @DisplayName("the pinned server first: it is the one this computer trusts")
        void thePinWins() {
            prefs.remember(new ServerEndpoint("10.0.0.9", 5555));
            prefs.pin(ENDPOINT, FINGERPRINT, "Room 12");

            assertThat(new Reconnector(manager, bus, prefs).endpoint()).contains(ENDPOINT);
        }

        @Test
        @DisplayName("then the remembered endpoint")
        void thenWhatIsRemembered() {
            prefs.remember(new ServerEndpoint("10.0.0.9", 6000));

            assertThat(new Reconnector(manager, bus, prefs).endpoint())
                    .contains(new ServerEndpoint("10.0.0.9", 6000));
        }

        @Test
        @DisplayName("and finally the address the dead client was bound to")
        void thenTheDeadClientsAddress() {
            assertThat(new Reconnector(manager, bus, prefs).endpoint()).contains(ENDPOINT);
        }

        @Test
        @DisplayName("nothing at all when this client has never connected")
        void nothingWhenNothingIsKnown() {
            manager.setClient(null);

            assertThat(new Reconnector(manager, bus, prefs).endpoint()).isEmpty();
        }
    }

    /** A reconnector that attaches {@code fresh} instead of opening a socket. */
    private Reconnector reconnectorFor(FakeClientConnection fresh) {
        return new Reconnector(manager, bus, prefs,
                (endpoint, eventBus, existing) ->
                        ConnectWiring.attach(fresh, endpoint, eventBus, existing),
                Runnable::run);
    }
}
