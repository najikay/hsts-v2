package client.ui;

import client.core.AppArgs;
import client.core.ClientApp;
import client.core.Routes;
import client.core.ScreenManager;
import client.features.login.ShellBoot;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import common.dto.notify.NotificationsPage;
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
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-input interaction test for the notification bell (E17.6).
 *
 * <p>House policy: a smoke test that only checks that nodes exist proves very
 * little. This one drives the actual affordance with the actual robot — a mouse
 * click on the navbar bell — and asserts the consequence: the panel appears, it
 * has fetched, and the pushed notification is rendered with its own text.
 *
 * <p>What only a booted toolkit can prove, and this therefore does: the panel is
 * reachable from the shell's popover layer, the bell's badge renders the count
 * that arrived with {@code LoginResult}, and a push that lands while the panel is
 * open shows up in the open list (E17.6) rather than waiting for a reopen.
 *
 * <p>Same escape hatch as the other UI tests:
 * {@code ./mvnw verify -Dhsts.uitests=false}.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class NotificationsInteractionTest extends ApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-19T09:00:00Z");

    private static final LoginResult DANA = new LoginResult(1001, "dana.cohen", "Dana Cohen",
            Role.TEACHER, List.of(new CourseRef("11", "Algebra 11")), 2);

    private static final NotificationDto WAITING = new NotificationDto(1L,
            NotificationType.APPROVAL_REQUESTED, "Exam waiting for your approval",
            "Dana Cohen submitted Algebra Midterm for approval.",
            NavRef.to("approvals", 55L), NOW, null);

    private static final NotificationDto PUSHED = new NotificationDto(2L,
            NotificationType.GRADE_PUBLISHED, "Your grade is ready",
            "Your grade for Algebra Midterm has been published.",
            NavRef.to("grades", 7L), NOW.plusSeconds(30), null);

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
    @DisplayName("clicking the bell opens the panel and renders the waiting notification")
    void clickingTheBellOpensThePanel() {
        ScreenManager manager = signIn();

        assertThat(badgeText(manager.scene()))
                .as("the badge is right on the first frame, from LoginResult (E17.5)")
                .isEqualTo("2");
        assertThat(manager.scene().getRoot().lookup(".hsts-notification-panel"))
                .as("the panel is closed until the bell is clicked")
                .isNull();

        clickOn(manager.shell().bell());
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = manager.scene();
        assertThat(scene.getRoot().lookup(".hsts-notification-panel"))
                .as("the click opened the panel")
                .isNotNull();
        assertThat(scene.getRoot().lookupAll(".panel-row"))
                .as("the panel fetched and rendered the waiting notification")
                .hasSize(1);
        assertThat(labelTexts(scene)).contains("Exam waiting for your approval");
        assertThat(labelTexts(scene)).contains("Dana Cohen submitted Algebra Midterm for approval.");
        assertThat(scene.getRoot().lookupAll(".panel-row.unread"))
                .as("an unread row is visibly unread")
                .hasSize(1);

        // Clicking again closes it: the bell is a toggle, not a one-way door.
        clickOn(manager.shell().bell());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(manager.scene().getRoot().lookup(".hsts-notification-panel")).isNull();
    }

    @Test
    @DisplayName("a push that lands while the panel is open updates the open list (E17.6)")
    void pushUpdatesTheOpenPanel() {
        ScreenManager manager = signIn();
        FakeClientConnection connection = (FakeClientConnection) manager.getClient();

        clickOn(manager.shell().bell());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(manager.scene().getRoot().lookupAll(".panel-row")).hasSize(1);

        interact(() -> connection.pushToClient(Verb.PUSH_NOTIFICATION, PUSHED));
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = manager.scene();
        assertThat(scene.getRoot().lookupAll(".panel-row"))
                .as("the open panel grew without being reopened (NFR-18: no manual refresh)")
                .hasSize(2);
        assertThat(labelTexts(scene)).contains("Your grade is ready");
        assertThat(badgeText(scene))
                .as("and the badge counted it")
                .isEqualTo("3");
        assertThat(scene.getRoot().lookup(".hsts-toast"))
                .as("a foreground push also raises a toast (E17.5, F11.3)")
                .isNotNull();
    }

    // ===================== Fixture =======================================

    /** Boots the app, attaches a scripted server, and enters Dana's shell. */
    private ScreenManager signIn() {
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
            connection.replyOk(Verb.NOTIFICATIONS_GET, new NotificationsPage(List.of(WAITING), 2));
            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            connection.setServerMessageHandler(dispatcher::dispatchIncoming);
            manager.setClient(connection);
            manager.setDispatcher(dispatcher);
            // The one wire a real connect would install: pushes become bus events.
            dispatcher.setPushListener(new client.events.PushEventBridge(manager.eventBus()));

            ShellBoot.enter(manager, DANA);
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.navigator().currentRouteId()).isEqualTo(Routes.HOME_TEACHER.id());
        return manager;
    }

    /** @return the navbar bell's badge text, or empty when no badge is showing. */
    private static String badgeText(Scene scene) {
        Node badge = scene.getRoot().lookup(".hsts-badge .badge-text");
        return badge instanceof Label label ? label.getText() : "";
    }

    private static Set<String> labelTexts(Scene scene) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .collect(java.util.stream.Collectors.toSet());
    }
}
