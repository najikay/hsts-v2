package server.console;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import server.db.seed.Confirmation;
import server.db.seed.SeedMode;
import server.db.seed.SeedOutcome;
import server.db.seed.SeedSummary;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * What every console button does, and what it says afterwards (E19.2 / E19.6 /
 * E19.8).
 *
 * <p>All four collaborators are fakes, which is what lets a test assert "the seed
 * button asked before deleting anything" without a database and "stopping the
 * listener told the operator that live exams survive" without a socket.
 */
class ConsoleSessionTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");

    private FakeServer server;
    private FakeSeed seed;
    private FakeDiscovery discovery;
    private ConsoleModel model;
    private ConsoleSession session;

    @BeforeEach
    void setUp() {
        server = new FakeServer();
        seed = new FakeSeed();
        discovery = new FakeDiscovery();
        model = new ConsoleModel(List.of(new NetworkAddress("192.168.1.42", "Wi-Fi", true)),
                5555, "7f3a2b91-0000-0000-0000-000000000000");
        session = newSession();
    }

    private ConsoleSession newSession() {
        return new ConsoleSession(model, server, seed, health(true, 3), discovery);
    }

    private static ConsoleHealth health(boolean databaseUp, int clients) {
        return new ConsoleHealth(() -> databaseUp, () -> clients,
                new ConsoleHealth.MemoryGauge() {
                    @Override
                    public long usedBytes() {
                        return 128L * 1024 * 1024;
                    }

                    @Override
                    public long maxBytes() {
                        return 1024L * 1024 * 1024;
                    }
                },
                ConsoleHealth.ProviderHealth.NONE,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("the listener")
    class Listener {

        @Test
        @DisplayName("the model is seeded from the real state, not assumed")
        void constructorReadsRealState() {
            server.listening = true;
            discovery.running = false;

            ConsoleSession fresh = newSession();

            assertThat(fresh.model().isListening()).isTrue();
            assertThat(fresh.model().isDiscoveryEnabled()).isFalse();
        }

        @Test
        @DisplayName("starting says clients can connect now")
        void start() {
            ConsoleSession.Outcome outcome = session.startListening();

            assertThat(outcome.ok()).isTrue();
            assertThat(outcome.message()).contains("Listening on port 5555");
            assertThat(server.listening).isTrue();
            assertThat(model.isListening()).isTrue();
        }

        @Test
        @DisplayName("stopping promises that exams in progress survive")
        void stop() {
            session.startListening();

            ConsoleSession.Outcome outcome = session.stopListening();

            assertThat(outcome.ok()).isTrue();
            assertThat(outcome.message())
                    .as("the whole meaning of the button, said out loud")
                    .contains("Exams already in progress keep running");
            assertThat(server.listening).isFalse();
        }

        @Test
        @DisplayName("the toggle goes both ways")
        void toggle() {
            assertThat(session.toggleListening().message()).contains("Clients can connect now");
            assertThat(session.toggleListening().message()).contains("Stopped listening");
        }

        @Test
        @DisplayName("starting an already-started listener is not an error")
        void startWhenAlreadyListening() {
            server.listening = true;

            ConsoleSession.Outcome outcome = session.startListening();

            assertThat(outcome.ok()).isTrue();
            assertThat(outcome.message()).contains("Already listening");
        }

        @Test
        @DisplayName("stopping an already-stopped listener is not an error")
        void stopWhenNotListening() {
            ConsoleSession.Outcome outcome = session.stopListening();

            assertThat(outcome.ok()).isTrue();
            assertThat(outcome.message()).contains("already stopped");
        }

        @Test
        @DisplayName("a port in use names the port and the way out")
        void startFails() {
            server.failStart = new IOException("Address already in use");

            ConsoleSession.Outcome outcome = session.startListening();

            assertThat(outcome.ok()).isFalse();
            assertThat(outcome.message())
                    .contains("Could not listen on port 5555")
                    .contains("Address already in use")
                    .contains("--port");
            assertThat(model.isListening())
                    .as("the model must not claim a listener that did not start")
                    .isFalse();
        }

        @Test
        @DisplayName("a failed stop says the server is still accepting clients")
        void stopFails() {
            server.listening = true;
            server.failStop = new IOException("socket wedged");
            session = newSession();

            ConsoleSession.Outcome outcome = session.stopListening();

            assertThat(outcome.ok()).isFalse();
            assertThat(outcome.message())
                    .contains("still accepting clients")
                    .contains("Close the window");
            assertThat(model.isListening()).isTrue();
        }

        @Test
        @DisplayName("a failure with no message still says something")
        void failureWithoutMessage() {
            server.failStart = new IOException();

            assertThat(session.startListening().message()).contains("IOException");
        }
    }

    @Nested
    @DisplayName("the seed button")
    class Seed {

        @Test
        @DisplayName("load-if-missing destroys nothing, so it asks nothing")
        void loadIfMissing() {
            seed.summary = new SeedSummary(SeedOutcome.LOADED, Map.of("users", 18));

            ConsoleSession.Outcome outcome = session.loadSeedIfMissing();

            assertThat(seed.modes).containsExactly(SeedMode.LOAD_IF_MISSING);
            assertThat(outcome.ok()).isTrue();
            assertThat(outcome.message())
                    .as("the loader's own text, not a second description of the same run")
                    .isEqualTo(seed.summary.toText());
        }

        @Test
        @DisplayName("reseeding passes the console's confirmation through to the loader")
        void reseedAsks() {
            seed.summary = new SeedSummary(SeedOutcome.RESEEDED, Map.of("users", 18));
            List<String> prompts = new ArrayList<>();

            ConsoleSession.Outcome outcome = session.reseed(prompt -> {
                prompts.add(prompt);
                return true;
            });

            assertThat(seed.modes).containsExactly(SeedMode.RESEED);
            assertThat(seed.confirmed)
                    .as("the loader is what asks, using its own prompt")
                    .isTrue();
            assertThat(prompts).hasSize(1);
            assertThat(prompts.get(0)).contains("DELETE every row");
            assertThat(outcome.ok()).isTrue();
        }

        @Test
        @DisplayName("declining is reported as not ok, so the console does not look successful")
        void reseedDeclined() {
            seed.summary = new SeedSummary(SeedOutcome.CANCELLED, Map.of());
            seed.honourConfirmation = true;

            ConsoleSession.Outcome outcome = session.reseed(Confirmation.refused());

            assertThat(outcome.ok()).isFalse();
            assertThat(outcome.message()).contains("Nothing was deleted");
        }

        @Test
        @DisplayName("a seed that throws says nothing was changed and where to look")
        void seedThrows() {
            seed.failure = new IllegalStateException("connection closed");

            ConsoleSession.Outcome outcome = session.reseed(Confirmation.preApproved());

            assertThat(outcome.ok()).isFalse();
            assertThat(outcome.message())
                    .contains("Nothing was changed")
                    .contains("connection closed")
                    .contains("database is up");
        }

        @Test
        @DisplayName("a confirmation is required")
        void confirmationRequired() {
            assertThatNullPointerException().isThrownBy(() -> session.reseed(null));
        }
    }

    @Nested
    @DisplayName("the discovery toggle")
    class Discovery {

        @Test
        @DisplayName("turning it off says what students will need instead")
        void off() {
            ConsoleSession.Outcome outcome = session.disableDiscovery();

            assertThat(outcome.ok()).isTrue();
            assertThat(outcome.message()).contains("Give students the address above");
            assertThat(model.isDiscoveryEnabled()).isFalse();
        }

        @Test
        @DisplayName("turning it on says clients can find the server")
        void on() {
            session.disableDiscovery();

            ConsoleSession.Outcome outcome = session.enableDiscovery();

            assertThat(outcome.ok()).isTrue();
            assertThat(outcome.message()).contains("find this server by themselves");
            assertThat(model.isDiscoveryEnabled()).isTrue();
        }

        @Test
        @DisplayName("the toggle goes both ways")
        void toggle() {
            assertThat(session.toggleDiscovery().message()).contains("Discovery is off");
            assertThat(session.toggleDiscovery().message()).contains("Discovery is on");
        }

        @Test
        @DisplayName("enabling an already-enabled responder says so rather than lying")
        void alreadyOn() {
            assertThat(session.enableDiscovery().message()).isEqualTo("Discovery was already on.");
        }

        @Test
        @DisplayName("a responder that cannot start reports failure with the fallback")
        void cannotStart() {
            discovery.refuseToStart = true;
            discovery.running = false;
            session = newSession();

            ConsoleSession.Outcome outcome = session.enableDiscovery();

            assertThat(outcome.ok()).isFalse();
            assertThat(outcome.message()).contains("typed in by hand");
        }

        @Test
        @DisplayName("a server started with no responder at all reports it off")
        void disabledControl() {
            ConsoleSession noDiscovery = new ConsoleSession(model, server, seed,
                    health(true, 0), ConsoleSession.DiscoveryControl.DISABLED);

            assertThat(noDiscovery.model().isDiscoveryEnabled()).isFalse();
            assertThat(noDiscovery.enableDiscovery().ok()).isFalse();
            assertThat(noDiscovery.disableDiscovery().ok()).isTrue();
            assertThat(ConsoleSession.DiscoveryControl.DISABLED.isRunning()).isFalse();
        }
    }

    @Nested
    @DisplayName("cards and address")
    class Cards {

        @Test
        @DisplayName("refreshing reads the probes")
        void refreshingReadsTheProbes() {
            HealthSnapshot snapshot = session.refreshHealth();

            assertThat(snapshot.databaseUp()).isTrue();
            assertThat(snapshot.connectedClients()).isEqualTo(3);
            assertThat(snapshot.at()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("a good address is applied and confirmed")
        void addressApplied() {
            ConsoleSession.Outcome outcome = session.selectAddress("10.1.2.3");

            assertThat(outcome.ok()).isTrue();
            assertThat(outcome.message()).contains("10.1.2.3:5555");
        }

        @Test
        @DisplayName("a bad address is refused with its reason")
        void addressRefused() {
            ConsoleSession.Outcome outcome = session.selectAddress("10.1.2.3:5555");

            assertThat(outcome.ok()).isFalse();
            assertThat(outcome.message()).isEqualTo(ConsoleModel.ADDRESS_HAS_PORT);
        }

        @Test
        @DisplayName("collaborators are all required")
        void required() {
            assertThatNullPointerException().isThrownBy(() ->
                    new ConsoleSession(null, server, seed, health(true, 0), discovery));
            assertThatNullPointerException().isThrownBy(() ->
                    new ConsoleSession(model, null, seed, health(true, 0), discovery));
            assertThatNullPointerException().isThrownBy(() ->
                    new ConsoleSession(model, server, null, health(true, 0), discovery));
            assertThatNullPointerException().isThrownBy(() ->
                    new ConsoleSession(model, server, seed, null, discovery));
            assertThatNullPointerException().isThrownBy(() ->
                    new ConsoleSession(model, server, seed, health(true, 0), null));
            assertThatNullPointerException().isThrownBy(() ->
                    new ConsoleSession.Outcome(true, null));
        }
    }

    // ===================== Fakes ========================================

    private static final class FakeServer implements ConsoleSession.ServerControl {
        boolean listening;
        IOException failStart;
        IOException failStop;

        @Override
        public void startListening() throws IOException {
            if (failStart != null) {
                throw failStart;
            }
            listening = true;
        }

        @Override
        public void stopListening() throws IOException {
            if (failStop != null) {
                throw failStop;
            }
            listening = false;
        }

        @Override
        public boolean isListening() {
            return listening;
        }
    }

    private static final class FakeSeed implements ConsoleSession.SeedRunner {
        final List<SeedMode> modes = new ArrayList<>();
        SeedSummary summary = new SeedSummary(SeedOutcome.UNCHANGED, Map.of());
        RuntimeException failure;
        boolean confirmed;
        boolean honourConfirmation;

        @Override
        public SeedSummary load(SeedMode mode, Confirmation confirmation) {
            modes.add(mode);
            if (failure != null) {
                throw failure;
            }
            // The real loader only asks for RESEED; this mirrors that so a test can
            // assert the console did not smuggle a pre-approval into the destructive path.
            confirmed = mode != SeedMode.RESEED
                    || confirmation.confirm("Reseed will DELETE every row in all 20 HSTS tables "
                            + "and load the demo dataset again. Any data entered since the last "
                            + "seed will be lost. Continue?");
            if (honourConfirmation && !confirmed) {
                return new SeedSummary(SeedOutcome.CANCELLED, Map.of());
            }
            return summary;
        }
    }

    private static final class FakeDiscovery implements ConsoleSession.DiscoveryControl {
        boolean running = true;
        boolean refuseToStart;

        @Override
        public boolean enable() {
            if (refuseToStart) {
                return false;
            }
            boolean changed = !running;
            running = true;
            return changed;
        }

        @Override
        public boolean disable() {
            boolean changed = running;
            running = false;
            return changed;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
