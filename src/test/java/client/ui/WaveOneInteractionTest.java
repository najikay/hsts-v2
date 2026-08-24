package client.ui;

import client.core.ClientApp;
import client.core.FxTestHarness;
import client.core.Routes;
import client.core.ScreenManager;
import client.events.PushEventBridge;
import client.features.login.ShellBoot;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.BackLink;
import common.dto.approval.ApprovalQueue;
import common.dto.approval.ApprovalRow;
import common.dto.approval.ApprovalState;
import common.dto.approval.ExamPreview;
import common.dto.approval.PreviewAnswerRow;
import common.dto.approval.TeacherOnlyBlock;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.exam.ExamQuestion;
import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import common.dto.notify.NotificationsPage;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three wave-1 gestures that only a booted toolkit can prove (F-6, F-7, F-8).
 *
 * <p>Each of these is a finding from the lead's manual pass, and each is the kind
 * that no unit test would ever have caught, because in every case the code was
 * doing something and the something was not what a hand expected:
 *
 * <ul>
 *   <li><b>F-6.</b> The notification list opened. It opened <i>centred</i>, with no
 *       way out but the bell, which is why the bell felt like it needed two
 *       clicks. The assertions here are the three dismissals a popover owes:
 *       click outside, ESC, and the owner again.</li>
 *   <li><b>F-7.</b> Drill-in screens rendered correctly and could not be left.
 *       "Is there a control that goes back" is not a thing a session test can
 *       ask.</li>
 *   <li><b>F-8.</b> The approvals queue opened a row on <i>double</i> click.
 *       Single-clicking it did exactly nothing, which reads as a dead list. The
 *       test clicks once and asserts the screen changed.</li>
 * </ul>
 *
 * <p>Same escape hatch as the other UI tests:
 * {@code ./mvnw verify -Dhsts.uitests=false}.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class WaveOneInteractionTest extends ApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-23T09:00:00Z");
    private static final long CALCULUS_V1 = 5501;

    // A coordinator, because the approvals route is registered for that role only
    // (SessionRoutes), and the approvals queue is F-8's confirmed case.
    private static final LoginResult RINA = new LoginResult(1002, "rina.barak", "Rina Barak",
            Role.COORDINATOR, List.of(new CourseRef("12", "Calculus")), 1);

    private static final ApprovalRow PENDING = new ApprovalRow(CALCULUS_V1, "101201",
            "Calculus Midterm", "12", "Calculus", 1, "Dana Cohen", NOW, 2, 60,
            ApprovalState.PENDING, null, false, 1);

    private static final ExamQuestion QUESTION_ONE = new ExamQuestion(901, "12001", 1, 50,
            "What are the roots of x squared minus 5x plus 6?",
            "1 and 6", "2 and 3", "minus 2 and minus 3", "0 and 5", null);

    private static final ExamQuestion QUESTION_TWO = new ExamQuestion(902, "12002", 2, 50,
            "What is the derivative of x squared?",
            "2x", "x", "x cubed over 3", "2", null);

    private static final ExamPreview PREVIEW = new ExamPreview(PENDING,
            "Answer every question. Calculators are not allowed.",
            List.of(QUESTION_ONE, QUESTION_TWO),
            new TeacherOnlyBlock("Mark question 2 generously.", "Dana Cohen",
                    List.of(new PreviewAnswerRow(901, 1, (byte) 2),
                            new PreviewAnswerRow(902, 2, (byte) 2))));

    private static final NotificationDto WAITING = new NotificationDto(1L,
            NotificationType.APPROVAL_REQUESTED, "Exam waiting for your approval",
            "Dana Cohen submitted Calculus Midterm for approval.",
            NavRef.to("approvals", 55L), NOW, null);

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
    void resetGlobalState() {
        FxTestHarness.resetGlobalState();
    }

    // ===================== F-6: the notification popover =================

    @Test
    @DisplayName("⚑ F-6: the popover closes on a click outside it")
    void clickOutsideClosesThePopover() {
        ScreenManager manager = signIn(this::withNotifications);

        clickOn(manager.shell().bell());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(panel(manager)).as("the bell opened it").isNotNull();

        // The rail is as "outside" as it gets: a real, pickable part of the shell
        // that is not the panel and not the bell.
        clickOn(manager.scene().getRoot().lookup(".hsts-rail"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(panel(manager))
                .as("clicking away dismisses it; before F-6 nothing did")
                .isNull();
    }

    @Test
    @DisplayName("⚑ F-6: ESC closes the popover")
    void escapeClosesThePopover() {
        ScreenManager manager = signIn(this::withNotifications);

        clickOn(manager.shell().bell());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(panel(manager)).isNotNull();

        press(KeyCode.ESCAPE).release(KeyCode.ESCAPE);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(panel(manager)).isNull();
    }

    @Test
    @DisplayName("F-6: a second click of the bell closes it, and a third opens it again")
    void theBellIsAToggleThatStaysAToggle() {
        ScreenManager manager = signIn(this::withNotifications);

        clickOn(manager.shell().bell());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(panel(manager)).isNotNull();

        // The regression this guards: light dismissal sees the press on the bell
        // before the bell's own action does. Without excluding the anchor, the
        // filter closes and the action immediately reopens, so the control can
        // never be switched off.
        clickOn(manager.shell().bell());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(panel(manager)).as("second click closes").isNull();

        clickOn(manager.shell().bell());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(panel(manager)).as("third click opens").isNotNull();
    }

    @Test
    @DisplayName("F-6: the popover is anchored to the bell, not centred on the page")
    void thePopoverIsAnchoredToTheBell() {
        ScreenManager manager = signIn(this::withNotifications);

        clickOn(manager.shell().bell());
        WaitForAsyncUtils.waitForFxEvents();

        // Layout bounds, not bounds-in-local: the panel carries an 18px gaussian
        // drop shadow, and bounds-in-local includes the effect. Measuring the
        // shadow instead of the panel would put this assertion 18px out and send
        // the next reader hunting for a layout bug that is not there.
        Node panel = panel(manager);
        Node bell = manager.shell().bell();
        double panelRight = panel.localToScene(panel.getLayoutBounds()).getMaxX();
        double bellRight = bell.localToScene(bell.getLayoutBounds()).getMaxX();

        // This is the whole of F-6's "centred modal": the panel used to sit in the
        // middle of the content area, hundreds of pixels from the control that
        // opened it. Right edges within a few px is what "anchored" means here.
        assertThat(panelRight).isCloseTo(bellRight, org.assertj.core.data.Offset.offset(4.0));
    }

    // ===================== F-8: single click opens ========================

    @Test
    @DisplayName("⚑ F-8: one click on an approvals row opens the preview")
    void oneClickOpensAnApprovalRow() {
        ScreenManager manager = signIn(connection -> {
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, new ApprovalQueue(List.of(PENDING), true));
            connection.replyOk(Verb.EXAM_PREVIEW_GET, PREVIEW);
        });
        interact(() -> manager.navigator().navigate(Routes.APPROVALS.id()));
        WaitForAsyncUtils.waitForFxEvents();

        Node row = manager.scene().getRoot().lookupAll(".table-row-cell").stream()
                .filter(node -> !node.getStyleClass().contains("empty"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no populated row to click"));

        clickOn(row);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.navigator().currentRouteId())
                .as("one click, not two; before F-8 this row needed a double click")
                .isEqualTo(Routes.EXAM_PREVIEW.id());
    }

    // ===================== F-7: the back convention =======================

    @Test
    @DisplayName("⚑ F-7: the exam preview has a back control top-left, and it goes back")
    void theDrillInHasAWayBack() {
        ScreenManager manager = signIn(connection -> {
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, new ApprovalQueue(List.of(PENDING), true));
            connection.replyOk(Verb.EXAM_PREVIEW_GET, PREVIEW);
        });
        interact(() -> manager.navigator().navigate(Routes.APPROVALS.id()));
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> manager.navigator().navigate(Routes.EXAM_PREVIEW.id(),
                client.core.NavParams.of("examVersionId", CALCULUS_V1)));
        WaitForAsyncUtils.waitForFxEvents();

        Node back = manager.scene().getRoot().lookup("." + BackLink.STYLE_CLASS);
        assertThat(back).as("every drill-in carries the convention's control").isNotNull();
        assertThat(((Button) back).getText()).isEqualTo(BackLink.LABEL);

        clickOn(back);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.navigator().currentRouteId())
                .as("it returns to where the user came from")
                .isEqualTo(Routes.APPROVALS.id());
    }

    // ===================== Fixture =======================================

    private void withNotifications(FakeClientConnection connection) {
        connection.replyOk(Verb.NOTIFICATIONS_GET, new NotificationsPage(List.of(WAITING), 1));
    }

    private static Node panel(ScreenManager manager) {
        return manager.scene().getRoot().lookup(".hsts-notification-panel");
    }

    /** Boots the app, attaches a scripted server, and enters Rina's shell. */
    private ScreenManager signIn(Consumer<FakeClientConnection> script) {
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
            connection.replyOk(Verb.LOGIN, RINA);
            connection.replyOk(Verb.LOGOUT, null);
            script.accept(connection);

            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            connection.setServerMessageHandler(dispatcher::dispatchIncoming);
            manager.setClient(connection);
            manager.setDispatcher(dispatcher);
            dispatcher.setPushListener(new PushEventBridge(manager.eventBus()));

            ShellBoot.enter(manager, RINA);
        });
        WaitForAsyncUtils.waitForFxEvents();
        return manager;
    }
}
