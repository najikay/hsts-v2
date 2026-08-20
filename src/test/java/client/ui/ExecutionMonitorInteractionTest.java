package client.ui;

import client.core.AppArgs;
import client.core.ClientApp;
import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.events.PushEventBridge;
import client.features.login.ShellBoot;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.exam.AttemptState;
import common.dto.exam.ExecutionMonitor;
import common.dto.exam.IntegrityFlag;
import common.dto.exam.MonitorCounts;
import common.dto.exam.MonitorRow;
import common.protocol.ErrorCode;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-input interaction test for the live execution monitor (E11.2/E11.3 — F7.2).
 *
 * <p>The two things only a booted toolkit can show: that a teacher opening the screen sees
 * her class rendered as rows with counters, and that a <b>pushed</b> snapshot repaints those
 * rows without anybody pressing anything (NFR-18: no user-initiated refresh anywhere).
 *
 * <p>The extension itself is confirmed through a modal {@code WarnConfirm}, which blocks the
 * FX thread by design; its rules are asserted deterministically in
 * {@code ExecutionMonitorSessionTest} and end to end in {@code ExtendAndMonitorTest}. What
 * this test adds is that the control exists, is enabled while the execution is live, and is
 * disabled once it is not.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class ExecutionMonitorInteractionTest extends ApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final long EXECUTION = 5001L;

    private static final LoginResult DANA = new LoginResult(1001, "dana.cohen", "Dana Cohen",
            Role.TEACHER, List.of(new CourseRef("21", "Java Programming")), 0);

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
        // Each test boots the app itself, as UiSmokeTest does.
    }

    @AfterEach
    void resetGlobalState() throws Exception {
        java.lang.reflect.Method reset = ScreenManager.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
        System.clearProperty(AppArgs.PROP_GALLERY);
    }

    @Test
    @DisplayName("the monitor renders counters, rows and the C-4 integrity flag (F7.2)")
    void rendersTheClass() {
        ScreenManager manager = signIn(snapshot(true, 3, 1, 0));
        openMonitor(manager);

        Scene scene = manager.scene();
        assertThat(scene.getRoot().lookupAll(".monitor-row"))
                .as("one row per student who has started")
                .hasSize(3);
        assertThat(labelTexts(scene))
                .contains("Maya Levi", "Noam Bar", "Ori Katz", "Java Midterm");
        assertThat(labelTexts(scene))
                .as("the three derived counters (S-21)")
                .contains("3", "1", "0", "Started", "Handed in", "Timed out");
        assertThat(scene.getRoot().lookupAll(".integrity-flag"))
                .as("and the one student who tripped the C-4 net is flagged")
                .hasSize(1);
        assertThat(labelTexts(scene)).contains("used Algebra 11 bot");
    }

    @Test
    @DisplayName("a pushed snapshot repaints the rows with nobody pressing anything (NFR-18) ⚑")
    void pushRepaintsLive() {
        ScreenManager manager = signIn(snapshot(true, 3, 1, 0));
        FakeClientConnection connection = (FakeClientConnection) manager.getClient();
        openMonitor(manager);
        assertThat(labelTexts(manager.scene())).contains("2/3");

        interact(() -> connection.pushToClient(Verb.PUSH_MONITOR_UPDATED, snapshot(true, 3, 2, 0)));
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = manager.scene();
        assertThat(labelTexts(scene))
                .as("the counters moved without a refresh button existing anywhere")
                .contains("2");
        assertThat(scene.getRoot().lookupAll(".monitor-row")).hasSize(3);
    }

    @Test
    @DisplayName("the extension control is offered while it is live and withdrawn when it is not")
    void extensionControlFollowsTheExecution() {
        ScreenManager manager = signIn(snapshot(true, 1, 0, 0));
        FakeClientConnection connection = (FakeClientConnection) manager.getClient();
        openMonitor(manager);

        Node addTime = buttonNamed(manager.scene(), "Add time");
        assertThat(addTime.isDisabled()).as("live, so time can be added").isFalse();

        interact(() -> connection.pushToClient(Verb.PUSH_MONITOR_UPDATED, snapshot(false, 1, 1, 0)));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(buttonNamed(manager.scene(), "Add time").isDisabled())
                .as("closed, so it cannot (§6: extension after close blocked)")
                .isTrue();
    }

    @Test
    @DisplayName("an execution nobody has joined shows its empty state, not an empty screen")
    void emptyExecutionHasAnEmptyState() {
        ScreenManager manager = signIn(new ExecutionMonitor(EXECUTION, "Java Midterm", "21",
                "4B7Q", true, NOW, NOW.plus(Duration.ofHours(2)), 0, 45,
                MonitorCounts.NONE, List.of()));
        openMonitor(manager);

        assertThat(manager.scene().getRoot().lookup(".hsts-empty-state")).isNotNull();
        assertThat(labelTexts(manager.scene())).contains("Nobody has started yet");
    }

    @Test
    @DisplayName("a refusal is shown as a sentence rather than as a blank screen")
    void refusalIsShown() {
        ScreenManager manager = signInWithError();
        openMonitor(manager);

        assertThat(labelTexts(manager.scene()))
                .anySatisfy(text -> assertThat(text).contains("not yours to manage"));
    }

    // ===================== Fixture =======================================

    private ScreenManager signIn(ExecutionMonitor first) {
        return boot(connection -> connection.replyOk(Verb.EXECUTION_MONITOR_GET, first));
    }

    private ScreenManager signInWithError() {
        return boot(connection -> connection.replyError(Verb.EXECUTION_MONITOR_GET,
                ErrorCode.FORBIDDEN,
                "This exam is not yours to manage. Ask the teacher who released it."));
    }

    private ScreenManager boot(java.util.function.Consumer<FakeClientConnection> script) {
        interact(() -> new ClientApp().start(new Stage()));
        WaitForAsyncUtils.waitForFxEvents();

        ScreenManager manager = ScreenManager.getInstance();
        interact(() -> {
            FakeClientConnection connection = new FakeClientConnection("demo-server", 5555);
            try {
                connection.connect();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            connection.replyOk(Verb.LOGIN, DANA);
            connection.replyOk(Verb.LOGOUT, null);
            script.accept(connection);

            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            connection.setServerMessageHandler(dispatcher::dispatchIncoming);
            manager.setClient(connection);
            manager.setDispatcher(dispatcher);
            dispatcher.setPushListener(new PushEventBridge(manager.eventBus()));

            ShellBoot.enter(manager, DANA);
        });
        WaitForAsyncUtils.waitForFxEvents();
        return manager;
    }

    private void openMonitor(ScreenManager manager) {
        interact(() -> manager.navigator().navigate(Routes.MONITOR.id(),
                NavParams.of("executionId", EXECUTION)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private javafx.scene.control.Button buttonNamed(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(javafx.scene.control.Button.class::isInstance)
                .map(javafx.scene.control.Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button labelled " + text));
    }

    private static Set<String> labelTexts(Scene scene) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static ExecutionMonitor snapshot(boolean live, int started, int finished, int timedOut) {
        List<MonitorRow> rows = List.of(
                new MonitorRow(2001, "Maya Levi", AttemptState.IN_PROGRESS, NOW, null,
                        Duration.ofMinutes(25).toMillis(), 2, 3, null, null),
                new MonitorRow(2002, "Noam Bar", AttemptState.SUBMITTED, NOW,
                        NOW.plus(Duration.ofMinutes(20)), 0, 3, 3, 20, null),
                new MonitorRow(2003, "Ori Katz", AttemptState.IN_PROGRESS, NOW, null,
                        Duration.ofMinutes(10).toMillis(), 1, 3, null,
                        new IntegrityFlag("11", "Algebra 11", NOW)));
        return new ExecutionMonitor(EXECUTION, "Java Midterm", "21", "4B7Q", live, NOW,
                NOW.plus(Duration.ofHours(2)), 0, 45,
                new MonitorCounts(started, finished, timedOut), rows);
    }
}
