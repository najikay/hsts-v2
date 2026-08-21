package server.console;

import common.dto.auth.Role;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;
import server.core.SessionManager;
import server.db.seed.SeedOutcome;
import server.db.seed.SeedSummary;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-input interaction test for the server console (E19.2 to E19.7 ⚑).
 *
 * <p>House policy: a smoke test that only checks that nodes exist proves very
 * little. This one drives the console with the actual robot, a mouse click on the
 * start/stop button and typed text in the log filter, and asserts the consequence
 * on the other side: the fake listener actually started, the status sentence
 * changed to the one {@link ConsoleModel} promises, and the log pane narrowed to
 * the lines matching what was typed.
 *
 * <p>What only a booted toolkit can prove, and this therefore does: the window
 * assembles at all with the shared stylesheet applied and the dark class on its
 * root (E19.7), the connected-clients table is fed from the session map's
 * snapshot, and the header renders the address a room is meant to read.
 *
 * <p>Everything behind the buttons is a fake, so no socket, no database and no
 * seed run happens here. Same escape hatch as the other UI tests:
 * {@code ./mvnw verify -Dhsts.uitests=false}.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class ServerConsoleInteractionTest extends ApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private FakeServer listener;
    private SessionManager sessions;
    private LogRingBuffer buffer;
    private ConsoleView view;
    private Scene scene;

    @BeforeAll
    static void headless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("glass.platform", "Monocle");
        System.setProperty("monocle.platform", "Headless");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
    }

    @Override
    public void start(Stage stage) {
        listener = new FakeServer();
        sessions = new SessionManager(CLOCK);
        buffer = new LogRingBuffer(200);
        buffer.add(new LogLine(NOW, LogLevel.INFO, "core.HSTSServer", "Server started"));
        buffer.add(new LogLine(NOW, LogLevel.WARN, "core.SessionManager",
                "Rejected duplicate login for user 1001"));

        ConsoleModel model = new ConsoleModel(
                List.of(new NetworkAddress("192.168.1.42", "Wi-Fi", true),
                        new NetworkAddress("192.168.56.1", "VirtualBox Host-Only Network", true)),
                5555, "7f3a2b91-1111-2222-3333-444444444444");

        ConsoleSession session = new ConsoleSession(model, listener,
                (mode, confirmation) -> new SeedSummary(SeedOutcome.UNCHANGED, Map.of()),
                new ConsoleHealth(() -> true, sessions::onlineCount,
                        ConsoleHealth.MemoryGauge.RUNTIME, ConsoleHealth.ProviderHealth.NONE, CLOCK),
                new FakeDiscovery());

        ConsoleClients clients = new ConsoleClients(userId ->
                userId == 1001L ? Optional.of("Dana Cohen") : Optional.empty());

        view = new ConsoleView(session, clients, sessions, new LogTailModel(buffer), CLOCK);
        Region root = view.build();
        scene = new Scene(root, 1280, 820);
        ConsoleTheme.apply(scene);
        stage.setScene(scene);
        stage.show();
        view.start();
    }

    @AfterEach
    void stopTheView() {
        interact(() -> view.stop());
    }

    @Test
    @DisplayName("clicking the listen button starts the listener and the status sentence follows")
    void clickingListenStartsTheServer() {
        Button listen = lookupId("console-listen");

        assertThat(listen.getText()).isEqualTo("Start listening");
        assertThat(label("console-message").getText()).isEmpty();
        assertThat(listener.listening).isFalse();

        clickOn(listen);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(listener.listening)
                .as("a real click reached a real handler")
                .isTrue();
        assertThat(listen.getText()).isEqualTo("Stop listening");
        assertThat(label("console-message").getText()).contains("Clients can connect now");

        clickOn(listen);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(listener.listening).isFalse();
        assertThat(label("console-message").getText())
                .as("the promise the button makes, on screen, in the operator's own words")
                .contains("Exams already in progress keep running");
    }

    @Test
    @DisplayName("typing in the log filter narrows the pane to the matching lines")
    void typingFiltersTheLog() {
        ListView<String> log = lookupId("console-log");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(log.getItems())
                .as("both seeded lines are at INFO or above")
                .hasSize(2);

        TextField search = lookupId("console-log-search");
        clickOn(search).write("duplicate");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(search.getText()).isEqualTo("duplicate");
        assertThat(log.getItems())
                .as("a real keystroke narrowed a real pane")
                .hasSize(1);
        assertThat(log.getItems().get(0)).contains("Rejected duplicate login");
        assertThat(label("console-log-status").getText())
                .contains("1 line at INFO and above matching \"duplicate\"");
    }

    @Test
    @DisplayName("pausing freezes the pane and says how much is waiting")
    void pauseFreezesThePane() {
        Button pause = lookupId("console-log-pause");
        ListView<String> log = lookupId("console-log");

        clickOn(pause);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(pause.getText()).isEqualTo("Resume");

        buffer.add(new LogLine(NOW, LogLevel.INFO, "core.HSTSServer", "Client connected"));
        // The pane repaints on the console's own one-second tick, so wait for the
        // tick rather than for the frame the write happened on.
        waitForStatus("Paused, 1 new line waiting");

        assertThat(log.getItems())
                .as("what the operator is reading stays put")
                .hasSize(2);

        clickOn(pause);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(pause.getText()).isEqualTo("Pause");
        assertThat(log.getItems())
                .as("nothing was lost, it was held")
                .hasSize(3);
    }

    @Test
    @DisplayName("the header shows the address a room is meant to read, with its id")
    void headerShowsTheAddress() {
        assertThat(scene.getRoot().lookupAll(".console-address"))
                .as("the big header exists and is the ranked best address")
                .anySatisfy(node -> assertThat(((Label) node).getText())
                        .isEqualTo("192.168.1.42:5555"));
        assertThat(scene.getRoot().lookupAll(".label"))
                .anySatisfy(node -> assertThat(((Label) node).getText()).isEqualTo("ID 7F3A-2B91"));
    }

    @Test
    @DisplayName("a signed-in client appears in the table by name, role and address")
    void clientsTableIsLive() throws Exception {
        TableView<ConsoleClients.Row> table = lookupId("console-clients");
        assertThat(table.getItems()).isEmpty();

        ocsf.server.ConnectionToClient socket = org.mockito.Mockito.mock(
                ocsf.server.ConnectionToClient.class);
        org.mockito.Mockito.when(socket.getInetAddress())
                .thenReturn(java.net.InetAddress.getByName("192.168.1.51"));

        sessions.attach(1001L, Role.TEACHER, socket);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(table.getItems())
                .as("the session listener repainted the table off an OCSF thread")
                .containsExactly(new ConsoleClients.Row(1001L, "Dana Cohen", "TEACHER",
                        "192.168.1.51", "just now"));
    }

    @Test
    @DisplayName("the window is dark by default and carries the shared token stylesheet")
    void styledWithTheDesignSystem() {
        assertThat(scene.getRoot().getStyleClass())
                .as("E19.7: the console is the app's design system, dark by default")
                .contains(ConsoleTheme.DARK);
        assertThat(scene.getStylesheets())
                .anySatisfy(sheet -> assertThat(sheet).endsWith("hsts.css"))
                .anySatisfy(sheet -> assertThat(sheet).endsWith("accent-indigo.css"));
    }

    /** Waits for the console's refresh timer to put {@code fragment} in the status line. */
    private void waitForStatus(String fragment) {
        try {
            WaitForAsyncUtils.waitFor(5, java.util.concurrent.TimeUnit.SECONDS,
                    () -> label("console-log-status").getText().contains(fragment));
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("The log status never became \"" + fragment
                    + "\". It was: " + label("console-log-status").getText(), e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    @SuppressWarnings("unchecked")
    private <T> T lookupId(String id) {
        return (T) scene.getRoot().lookup("#" + id);
    }

    private Label label(String id) {
        return lookupId(id);
    }

    // ===================== Fakes =========================================

    private static final class FakeServer implements ConsoleSession.ServerControl {
        volatile boolean listening;

        @Override
        public void startListening() throws IOException {
            listening = true;
        }

        @Override
        public void stopListening() throws IOException {
            listening = false;
        }

        @Override
        public boolean isListening() {
            return listening;
        }
    }

    private static final class FakeDiscovery implements ConsoleSession.DiscoveryControl {
        private boolean running = true;

        @Override
        public boolean enable() {
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
