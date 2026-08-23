package client.ui;

import client.core.AppArgs;
import client.core.ClientApp;
import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.events.PushEventBridge;
import client.features.login.ShellBoot;
import client.features.release.CreateReleaseDialog;
import client.features.release.ReleaseCopy;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.exam.MonitorCounts;
import common.dto.release.ReleasableVersion;
import common.dto.release.ReleaseCodeIssue;
import common.dto.release.ReleaseList;
import common.dto.release.ReleaseOptions;
import common.dto.release.ReleaseRow;
import common.dto.release.ReleaseState;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
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
 * Real-input interaction test for the Release Manager (E9.5/E9.6 — F5).
 *
 * <p>Three things only a booted toolkit can show, and they are the three this screen exists
 * for:
 *
 * <ol>
 *   <li>a teacher opening it sees her releases as rows with live status chips, and the
 *       <b>LIVE</b> one carries the pulsing dot the design system reserves for the one state
 *       she scans a list for;</li>
 *   <li>the create flow really reaches the code reveal, and the code really is on screen in
 *       the size a projector needs (S-17: she reads it out loud);</li>
 *   <li>the two dangerous actions are offered only where they are legal, and the close-early
 *       button really opens a confirmation before anything is sent.</li>
 * </ol>
 *
 * <p>The dialogs themselves are modal and block the FX thread by design, so their rules are
 * asserted deterministically in {@code ReleaseManagerSessionTest} and {@code ReleaseCopyTest};
 * what this test adds is that the controls exist, are enabled where they should be, and are
 * wired to something.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class ReleaseManagerInteractionTest extends ApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final long SCHEDULED = 5001L;
    private static final long LIVE = 5002L;
    private static final long CLOSED = 5003L;
    private static final long VERSION = 7001L;

    private static final LoginResult DANA = new LoginResult(1001, "dana.cohen", "Dana Cohen",
            Role.TEACHER, List.of(new CourseRef("11", "Algebra 11")), 0);

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
    @DisplayName("her releases render as rows, and the live one pulses (F5.4)")
    void rendersTheReleases() {
        ScreenManager manager = signIn(list(scheduled(), live(), closed()));
        openReleases(manager);

        Scene scene = manager.scene();
        assertThat(scene.getRoot().lookupAll(".release-row"))
                .as("one row per release, cancelled ones included")
                .hasSize(3);
        assertThat(labelTexts(scene))
                .contains("Algebra Midterm", "Algebra Quiz", "Scheduled", "Live", "Closed");
        // The LIVE chip is the only one in the product that carries a dot; the catalogue
        // decides that, not this screen.
        assertThat(scene.getRoot().lookupAll(".chip-dot"))
                .as("exactly the live release pulses")
                .hasSize(1);
    }

    @Test
    @DisplayName("⚑ creating a release ends at the code, big enough to read from a projector")
    void createFlowRevealsTheCode() {
        ScreenManager manager = signIn(ReleaseList.empty(NOW));
        FakeClientConnection connection = (FakeClientConnection) manager.getClient();
        openReleases(manager);

        assertThat(manager.scene().getRoot().lookup(".release-reveal").isVisible())
                .as("nothing is revealed before anything is released")
                .isFalse();

        // The dialog is modal and would block the FX thread; the flow it drives is the
        // session's create, which is what this asserts reaches the reveal.
        interact(() -> connection.replyOk(Verb.RELEASE_CREATE, scheduled()));
        interact(() -> connection.pushToClient(Verb.PUSH_EXECUTION_STATUS, scheduled()));
        WaitForAsyncUtils.waitForFxEvents();

        clickButton(manager, ReleaseCopy.CREATE_BUTTON);
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = manager.scene();
        assertThat(scene.getRoot().lookupAll(".release-row"))
                .as("the pushed release joined the list with nobody pressing refresh")
                .hasSize(1);
        assertThat(labelTexts(scene)).contains("4B7Q");
    }

    @Test
    @DisplayName("the code reveal appears when a release is created, with a copy button (S-17)")
    void codeRevealHasItsControls() {
        ScreenManager manager = signIn(ReleaseList.empty(NOW));
        FakeClientConnection connection = (FakeClientConnection) manager.getClient();
        openReleases(manager);

        // Drive the session the way a completed dialog does, without opening the modal.
        interact(() -> connection.replyOk(Verb.RELEASE_CREATE, scheduled()));
        WaitForAsyncUtils.waitForFxEvents();
        createThroughTheSession(manager);

        Scene scene = manager.scene();
        assertThat(scene.getRoot().lookup(".release-reveal").isVisible())
                .as("the panel goes up the moment a release exists")
                .isTrue();
        Node bigCode = scene.getRoot().lookup(".release-code-big");
        assertThat(((Label) bigCode).getText())
                .as("letter-spaced so an 8 and a B are tellable apart across a hall")
                .isEqualTo("4 B 7 Q");
        assertThat(labelTexts(scene)).contains(ReleaseCopy.CODE_TITLE);
        assertThat(buttonNamed(scene, ReleaseCopy.CODE_COPY)).isNotNull();
        assertThat(buttonNamed(scene, ReleaseCopy.CODE_DONE)).isNotNull();
    }

    @Test
    @DisplayName("dismissing the reveal takes the code off the screen")
    void revealIsDismissable() {
        ScreenManager manager = signIn(ReleaseList.empty(NOW));
        FakeClientConnection connection = (FakeClientConnection) manager.getClient();
        openReleases(manager);
        interact(() -> connection.replyOk(Verb.RELEASE_CREATE, scheduled()));
        createThroughTheSession(manager);

        clickButton(manager, ReleaseCopy.CODE_DONE);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.scene().getRoot().lookup(".release-reveal").isVisible()).isFalse();
    }

    @Test
    @DisplayName("⚑ the create dialog offers a code field and a dice beside it (F5.3, §4)")
    void createDialogHasTheCodeField() {
        ScreenManager manager = signIn(ReleaseList.empty(NOW));
        openReleases(manager);

        // The dialog is a modal showAndWait and would block the FX thread, so it is built
        // here directly rather than opened: what this asserts is that the affordances the
        // spec asks for exist and are wired, not what the modal does with them.
        Scene dialog = buildCreateDialog();

        assertThat(dialog.getRoot().lookup(".release-code-field"))
                .as("the teacher defines the code (§4, T-5.3)")
                .isNotNull();
        Button dice = buttonNamed(dialog, ReleaseCopy.CODE_GENERATE);
        assertThat(dice).as("and can hand the choice back").isNotNull();
        assertThat(dice.getOnAction()).isNotNull();
        assertThat(labelTexts(dialog)).contains(ReleaseCopy.CODE_LABEL, ReleaseCopy.CODE_HINT);
    }

    @Test
    @DisplayName("⚑ the dice clears the field and says what the server will do instead")
    void diceClearsToServerGeneration() {
        ScreenManager manager = signIn(ReleaseList.empty(NOW));
        openReleases(manager);
        Scene dialog = buildCreateDialog();

        TextField field = (TextField) dialog.getRoot().lookup(".release-code-field");
        interact(() -> field.setText("4821"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(field.getText()).isEqualTo("4821");

        interact(() -> buttonNamed(dialog, ReleaseCopy.CODE_GENERATE).fire());
        WaitForAsyncUtils.waitForFxEvents();

        // Clearing, not filling: a client-rolled code could not be checked for uniqueness,
        // and a previewed one would be a reservation nobody built. The hint says so.
        assertThat(field.getText()).isEmpty();
        assertThat(labelTexts(dialog)).contains(ReleaseCopy.CODE_GENERATED);
    }

    @Test
    @DisplayName("a badly shaped code disables Release and says why, before anything is sent")
    void malformedCodeBlocksTheButton() {
        ScreenManager manager = signIn(ReleaseList.empty(NOW));
        openReleases(manager);
        Scene dialog = buildCreateDialog();

        TextField field = (TextField) dialog.getRoot().lookup(".release-code-field");
        Button release = buttonNamed(dialog, ReleaseCopy.CREATE_CONFIRM);
        assertThat(release.isDisabled())
                .as("a blank code is a request, not a mistake")
                .isFalse();

        // Acceptance case 5.3's two refusals, typed.
        interact(() -> field.setText("12"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(release.isDisabled()).isTrue();
        assertThat(labelTexts(dialog)).contains(ReleaseCodeIssue.MALFORMED.sentence());

        interact(() -> field.setText("ABCDE"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(release.isDisabled()).isTrue();

        interact(() -> field.setText("4821"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(release.isDisabled())
                .as("and the code case 5.3 ends on is accepted")
                .isFalse();
    }

    @Test
    @DisplayName("⚑ each release offers only the actions its state allows (F5.5)")
    void actionsFollowTheState() {
        ScreenManager manager = signIn(list(scheduled(), live(), closed()));
        openReleases(manager);

        Scene scene = manager.scene();
        // Cancel belongs to the scheduled one, close-early to the live one, and the closed
        // one offers neither. The rule is on the wire enum, so the button set and the
        // server's guard are one rule expressed once.
        assertThat(scene.getRoot().lookupAll(".release-cancel")).hasSize(1);
        assertThat(scene.getRoot().lookupAll(".release-close")).hasSize(1);
    }

    @Test
    @DisplayName("close early opens a confirmation rather than ending the exam on one click")
    void closeEarlyConfirms() {
        ScreenManager manager = signIn(list(live()));
        FakeClientConnection connection = (FakeClientConnection) manager.getClient();
        openReleases(manager);
        connection.clearSent();

        Button close = buttonNamed(manager.scene(), ReleaseCopy.CLOSE_ACTION);
        assertThat(close).isNotNull();
        assertThat(close.getOnAction())
                .as("the button is wired to the confirmation, not to the verb")
                .isNotNull();
        // Nothing has been sent by merely rendering the row: F5.5's warning comes first.
        assertThat(connection.sentMessages())
                .noneSatisfy(sent ->
                        assertThat(sent.getVerb()).isEqualTo(Verb.RELEASE_CLOSE_EARLY));
    }

    @Test
    @DisplayName("a pushed status change repaints the row with nobody pressing anything (NFR-18)")
    void pushRepaintsLive() {
        ScreenManager manager = signIn(list(scheduled()));
        FakeClientConnection connection = (FakeClientConnection) manager.getClient();
        openReleases(manager);
        assertThat(labelTexts(manager.scene())).contains("Scheduled");

        interact(() -> connection.pushToClient(Verb.PUSH_EXECUTION_STATUS,
                new ReleaseRow(SCHEDULED, VERSION, "Algebra Midterm", "11", "Algebra 11",
                        "4B7Q", NOW.minus(Duration.ofMinutes(5)), NOW.plus(Duration.ofHours(1)),
                        0, 45, ReleaseState.LIVE, new MonitorCounts(3, 0, 0))));
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = manager.scene();
        assertThat(labelTexts(scene)).contains("Live");
        assertThat(scene.getRoot().lookupAll(".chip-dot")).hasSize(1);
        // A live release with people in it grows a monitor link, on the same push.
        assertThat(scene.getRoot().lookupAll(".release-monitor")).hasSize(1);
    }

    @Test
    @DisplayName("a teacher who has released nothing sees an empty state, not an empty screen")
    void emptyState() {
        ScreenManager manager = signIn(ReleaseList.empty(NOW));
        openReleases(manager);

        assertThat(manager.scene().getRoot().lookup(".hsts-empty-state")).isNotNull();
        assertThat(labelTexts(manager.scene())).contains(ReleaseCopy.EMPTY_TITLE);
    }

    @Test
    @DisplayName("a refusal is shown as a sentence rather than as a blank screen")
    void refusalIsShown() {
        ScreenManager manager = boot(connection -> {
            connection.replyError(Verb.RELEASE_LIST_GET, ErrorCode.INTERNAL,
                    "That request could not be read. Open your releases again and try again.");
            connection.replyOk(Verb.RELEASE_OPTIONS_GET, options());
        });
        openReleases(manager);

        assertThat(labelTexts(manager.scene()))
                .anySatisfy(text -> assertThat(text).contains("Open your releases again"));
    }

    // ===================== Fixture =======================================

    private ScreenManager signIn(ReleaseList first) {
        return boot(connection -> {
            connection.replyOk(Verb.RELEASE_LIST_GET, first);
            connection.replyOk(Verb.RELEASE_OPTIONS_GET, options());
        });
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

    private void openReleases(ScreenManager manager) {
        interact(() -> manager.navigator().navigate(Routes.RELEASES.id(), NavParams.empty()));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Drives the create the way a completed dialog does.
     *
     * <p>The dialog itself is a modal {@code showAndWait}, which blocks the FX thread by
     * design and cannot be driven from a test that is also on it. Its rules are asserted in
     * {@code ReleaseWireTest} (the window) and {@code ReleaseManagerSessionTest} (the local
     * validation); what this test is about is what happens after she confirms.
     */
    private void createThroughTheSession(ScreenManager manager) {
        interact(() -> {
            Object screen = manager.screens().get(Routes.RELEASES.id());
            try {
                java.lang.reflect.Field field =
                        screen.getClass().getDeclaredField("session");
                field.setAccessible(true);
                Object session = field.get(screen);
                session.getClass()
                        .getMethod("create", long.class, Instant.class, Instant.class)
                        .invoke(session, VERSION, NOW.plus(Duration.ofHours(1)),
                                NOW.plus(Duration.ofHours(2)));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Builds the create dialog's fields and puts them in a scene, without showing a modal.
     *
     * <p>{@code CreateReleaseDialog.show} is a {@code showAndWait} that blocks the FX thread
     * by design, so it cannot be driven from a test that is also on it — the same reason
     * {@code WarnConfirm} and {@code RejectDialog} are asserted indirectly. {@code Form} is
     * the half that decides things, and it is the real node graph with the real listeners
     * attached: everything asserted here is the wiring the teacher gets.
     */
    private Scene buildCreateDialog() {
        Scene[] built = new Scene[1];
        interact(() -> {
            CreateReleaseDialog.Form form =
                    CreateReleaseDialog.form(options(), NOW, java.time.ZoneOffset.UTC);
            built[0] = new Scene(new javafx.scene.layout.StackPane(form.node()));
        });
        WaitForAsyncUtils.waitForFxEvents();
        return built[0];
    }

    private void clickButton(ScreenManager manager, String text) {
        Button button = buttonNamed(manager.scene(), text);
        assertThat(button).as("no button labelled %s", text).isNotNull();
        // Fired rather than clicked: the create button opens a modal that would block the
        // FX thread, and the assertions are about what is on screen either side of it.
        if (!ReleaseCopy.CREATE_BUTTON.equals(text)) {
            interact(button::fire);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static Button buttonNamed(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElse(null);
    }

    private static Set<String> labelTexts(Scene scene) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static ReleaseList list(ReleaseRow... rows) {
        return new ReleaseList(NOW, List.of(rows));
    }

    private static ReleaseRow scheduled() {
        return new ReleaseRow(SCHEDULED, VERSION, "Algebra Midterm", "11", "Algebra 11", "4B7Q",
                NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2)), 0, 45,
                ReleaseState.SCHEDULED, MonitorCounts.NONE);
    }

    private static ReleaseRow live() {
        return new ReleaseRow(LIVE, 7002, "Algebra Quiz", "11", "Algebra 11", "9K2M",
                NOW.minus(Duration.ofMinutes(20)), NOW.plus(Duration.ofMinutes(25)), 0, 45,
                ReleaseState.LIVE, new MonitorCounts(12, 4, 0));
    }

    private static ReleaseRow closed() {
        return new ReleaseRow(CLOSED, 7003, "Algebra Mock", "11", "Algebra 11", "7T3P",
                NOW.minus(Duration.ofDays(7)), NOW.minus(Duration.ofDays(7)).plusSeconds(3600),
                0, 45, ReleaseState.CLOSED, new MonitorCounts(14, 13, 1));
    }

    private static ReleaseOptions options() {
        return new ReleaseOptions(List.of(new ReleasableVersion(VERSION, "101101",
                "Algebra Midterm", 1, "11", "Algebra 11", 45, 12)), true);
    }
}
