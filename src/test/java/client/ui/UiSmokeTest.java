package client.ui;

import client.core.AppArgs;
import client.core.ClientApp;
import client.core.FxTestHarness;
import client.core.Routes;
import client.core.ScreenManager;
import client.core.ServerEndpoint;
import client.events.ConnectionLostEvent;
import client.features.connect.ConnectFlow;
import client.features.connect.ConnectWiring;
import client.features.login.ShellBoot;
import client.net.FakeClientConnection;
import client.net.IClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.ReconnectBanner;
import client.ui.shell.NavItem;
import common.dto.approval.ApprovalQueue;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI smoke test: the app boots (E4.24).
 *
 * <p>Deliberately shallow. Screen <i>logic</i> is unit-tested without a toolkit
 * (that is the whole point of {@code Navigator} / {@code ThemeState} /
 * {@code CountdownLogic} being FX-free); what only a booted toolkit can prove is
 * that the wiring holds together — FXML resolves, the stylesheets are on the
 * classpath, and {@code ClientApp} reaches its first screen without throwing.
 * That is what this checks, for both entry paths.
 *
 * <p><b>Runs by default.</b> Headless Monocle was verified working on this
 * project (JavaFX 21.0.4 + {@code org.testfx:openjfx-monocle:21.0.2}), so the
 * smoke test is part of {@code mvn verify} rather than an opt-in — it has
 * already earned its place by catching two real defects that no unit test could
 * see: a screen receiving {@code onShow} before {@code build()} had created its
 * nodes, and radius values written as looked-up tokens (JavaFX resolves those
 * for colours only, so every rounded corner in the app was silently failing).
 *
 * <p>Escape hatch for a machine where the software pipeline is unavailable:
 * {@code ./mvnw verify -Dhsts.uitests=false} skips this class only. It costs
 * roughly 45s, almost all of it JavaFX toolkit start-up.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class UiSmokeTest extends ApplicationTest {

    @BeforeAll
    static void headless() {
        // Monocle's software pipeline: no display server, no window manager.
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
        // Each test supplies its own args by re-launching through ClientApp below.
    }

    @AfterEach
    void resetGlobalState() throws Exception {
        // The manager is a Singleton and the gallery flag is a system property:
        // both would otherwise leak from one test into the next.
        FxTestHarness.resetGlobalState();
    }

    @Test
    @DisplayName("boots to the connect screen")
    void bootsToConnect() {
        Stage stage = launchApp(AppArgs.none());

        assertThat(stage.isShowing()).isTrue();
        assertThat(stage.getTitle()).contains(Routes.CONNECT.title());

        Scene scene = ScreenManager.getInstance().scene();
        assertThat(scene).isNotNull();
        assertThat(scene.getStylesheets()).anySatisfy(sheet ->
                assertThat(sheet).contains("hsts.css"));
        assertThat(scene.getStylesheets()).anySatisfy(sheet ->
                assertThat(sheet).contains("accent-"));

        // The connect card's two fields and its primary button exist and are wired.
        assertThat(lookupOne(scene, ".hsts-field")).isNotNull();
        assertThat(lookupOne(scene, ".button.primary")).isNotNull();
    }

    @Test
    @DisplayName("--gallery boots straight into the component gallery")
    void galleryFlagBootsTheGallery() {
        Stage stage = launchApp(new AppArgs(true));

        assertThat(stage.isShowing()).isTrue();
        Scene scene = ScreenManager.getInstance().scene();
        assertThat(scene).isNotNull();

        // The gallery is identifiable by the live theme controls it pins on top.
        assertThat(lookupOne(scene, ".hsts-segmented")).isNotNull();
        assertThat(lookupOne(scene, ".hsts-swatch")).isNotNull();
        // …and by the fact that every component family rendered.
        assertThat(lookupOne(scene, ".hsts-chip")).isNotNull();
        assertThat(lookupOne(scene, ".hsts-countdown")).isNotNull();
        assertThat(lookupOne(scene, ".hsts-rail")).isNotNull();
        // E14.3's histogram, including the bars: a chart that constructed but painted
        // nothing would still pass a lookup on the container alone.
        assertThat(lookupOne(scene, ".hsts-stat-chart")).isNotNull();
        assertThat(scene.getRoot().lookupAll(".stat-bar")).isNotEmpty();
        // E6.10's two components, including their contents: a radio group that built no
        // options and a picker that drew no frame would both survive a lookup on the shell.
        assertThat(lookupOne(scene, ".hsts-radio-group")).isNotNull();
        assertThat(scene.getRoot().lookupAll(".radio-option")).isNotEmpty();
        assertThat(lookupOne(scene, ".hsts-image-picker")).isNotNull();
        assertThat(lookupOne(scene, ".picker-frame")).isNotNull();
    }

    @Test
    @DisplayName("the login screen renders once a connection exists (E5.3)")
    void loginScreenRenders() {
        Stage stage = launchApp(AppArgs.none());
        ScreenManager manager = ScreenManager.getInstance();

        interact(() -> {
            attachFakeConnection(manager);
            manager.navigator().replace(Routes.LOGIN.id());
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(stage.getTitle()).contains(Routes.LOGIN.title());
        Scene scene = ScreenManager.getInstance().scene();
        // The artboard: brand panel, two fields, a primary button and the
        // connection chip that keeps "wrong password" apart from "no server".
        assertThat(lookupOne(scene, ".hsts-brand-panel")).isNotNull();
        assertThat(scene.getRoot().lookupAll(".hsts-field")).hasSize(2);
        assertThat(lookupOne(scene, ".button.primary")).isNotNull();
        assertThat(lookupOne(scene, ".hsts-chip")).isNotNull();
    }

    @Test
    @DisplayName("\u26a1 the login screen stops saying Connected when the server dies (E4.6)")
    void loginReactsToTheServerDying() {
        launchApp(AppArgs.none());
        ScreenManager manager = ScreenManager.getInstance();

        interact(() -> {
            attachFakeConnection(manager);
            manager.navigator().replace(Routes.LOGIN.id());
        });
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = manager.scene();
        assertThat(labelTexts(scene))
                .as("a reachable server is named, which is the state the bug froze")
                .contains("Connected");
        typeCredentials(scene, "dana.cohen", "demo123");
        assertThat(buttonNamed(scene, ".button.primary").isDisabled()).isFalse();

        // 2026-08-28, manual round 1: kill the server in front of a signed-out client. The
        // status row was computed once in build(), so it went on saying Connected for as
        // long as the screen lived.
        interact(() -> manager.eventBus().post(
                new ConnectionLostEvent("demo-server:5555", "socket closed")));
        WaitForAsyncUtils.waitForFxEvents();

        Set<String> labels = labelTexts(manager.scene());
        assertThat(labels)
                .as("the chip flips and the line stops naming a server that is not there")
                .contains("Disconnected", "Not connected");
        assertThat(labels).doesNotContain("Connected");
        assertThat(buttonNamed(manager.scene(), ".button.primary").isDisabled())
                .as("a form that cannot reach anything must not look ready")
                .isTrue();
        assertThat(linkNamed(manager.scene(), "Reconnect"))
                .as("and there is one thing to do about it")
                .isNotNull();

        clickOn(linkNamed(manager.scene(), "Reconnect"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(manager.navigator().currentRouteId()).isEqualTo(Routes.CONNECT.id());
    }

    @Test
    @DisplayName("\u26a1 signing in after a reconnect goes down the new socket (U-17)")
    void loginUsesTheConnectionItReconnectedOn() {
        launchApp(AppArgs.none());
        ScreenManager manager = ScreenManager.getInstance();
        LoginResult dana = new LoginResult(1001, "dana.cohen", "Dana Cohen", Role.TEACHER,
                List.of(new CourseRef("11", "Algebra 11")));

        // The first server. She reaches Login, which is built here and cached from now on:
        // LoginSession captures the dispatcher that exists at build time.
        FakeClientConnection first = new FakeClientConnection("demo-server", 5555);
        interact(() -> {
            connectThrough(manager, first);
            first.replyOk(Verb.LOGIN, dana);
            manager.navigator().replace(Routes.LOGIN.id());
        });
        WaitForAsyncUtils.waitForFxEvents();
        int builtOnce = manager.screens().builtCount();

        // 2026-08-29, manual round 2: the server is stopped and restarted, and she
        // reconnects from the connect screen. A restart is a brand-new socket.
        FakeClientConnection second = new FakeClientConnection("demo-server", 5555);
        interact(() -> {
            first.disconnect();
            manager.navigator().replace(Routes.CONNECT.id());
            connectThrough(manager, second);
            manager.navigator().replace(Routes.LOGIN.id());
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.screens().builtCount())
                .as("the login screen is the cached one, which is the whole point")
                .isEqualTo(builtOnce);
        assertThat(labelTexts(manager.scene()))
                .as("the status row reads the live connection and says so")
                .contains("Connected");

        signIn(manager.scene(), "dana.cohen", "demo123");
        WaitForAsyncUtils.waitForFxEvents();

        // The defect: the row said Connected while LOGIN went down the dead first socket,
        // so sign-in answered "could not reach the server" until the window was restarted.
        assertThat(sentVerbs(second))
                .as("the credentials go to the server she is actually connected to")
                .contains(Verb.LOGIN);
        assertThat(sentVerbs(first))
                .as("and never to the one that was stopped")
                .doesNotContain(Verb.LOGIN);
    }

    /**
     * Connects {@code connection} the way {@code ConnectView} does: through
     * {@link ConnectWiring}, so the reconnect decision under test is the
     * production one and not a rehearsal of it.
     */
    private void connectThrough(ScreenManager manager, FakeClientConnection connection) {
        try {
            connection.connect();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        connection.replyOk(Verb.LOGOUT, null);
        ConnectWiring.Wiring wiring = ConnectWiring.attach(connection,
                new ServerEndpoint(connection.getHost(), connection.getPort()),
                manager.eventBus(), manager.getDispatcher());
        manager.setClient(wiring.client());
        manager.setDispatcher(wiring.dispatcher());
    }

    private static List<Verb> sentVerbs(FakeClientConnection connection) {
        return connection.sentMessages().stream()
                .map(common.protocol.Message::getVerb)
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("\u26a1 U-52: a client that lost the network is dead even while it claims to be open")
    void loginTellsTheTruthAfterTheClientLostTheNetwork() {
        launchApp(AppArgs.none());
        ScreenManager manager = ScreenManager.getInstance();
        LoginResult dana = new LoginResult(1001, "dana.cohen", "Dana Cohen", Role.TEACHER,
                List.of(new CourseRef("11", "Algebra 11")));

        // A client that goes on reporting an open socket after it died. Not a contrivance:
        // HSTSClient answers isConnectionOpen() from OCSF's isConnected(), which only flips
        // once a read fails, so a laptop that slept on battery comes back exactly like this.
        FakeClientConnection stubborn = new FakeClientConnection("demo-server", 5555) {
            @Override
            public boolean isConnectionOpen() {
                return true;
            }
        };
        interact(() -> {
            attachFakeConnection(manager, stubborn).replyOk(Verb.LOGIN, dana);
            manager.navigator().replace(Routes.LOGIN.id());
        });
        WaitForAsyncUtils.waitForFxEvents();
        signIn(manager.scene(), "dana.cohen", "demo123");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(manager.shell()).as("she is signed in when the network goes").isNotNull();

        // 2026-08-30, Findings.txt, U-52: the client machine loses the network.
        interact(() -> manager.eventBus().post(
                new ConnectionLostEvent("demo-server:5555", "no route to host")));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(stubborn.isConnectionOpen())
                .as("the adapter is unrepentant, which is the whole trouble")
                .isTrue();
        assertThat(manager.isConnectionAlive())
                .as("but the app has retired it, so nothing may use it again")
                .isFalse();

        // He signs out, which is what he actually did. The status row said Connected here
        // while every sign-in was refused with "check the server".
        interact(() -> ShellBoot.logout(manager));
        WaitForAsyncUtils.waitForFxEvents();

        Set<String> labels = labelTexts(manager.scene());
        assertThat(labels).contains("Disconnected", "Not connected");
        assertThat(labels).doesNotContain("Connected");
        assertThat(linkNamed(manager.scene(), "Reconnect"))
                .as("and the one thing to do about it is offered")
                .isNotNull();
    }

    @Test
    @DisplayName("\u26a1 U-52: the shell banner's Retry re-dials, and Login asks her to sign in again")
    void theShellBannerRetryReconnectsAndLandsOnLogin() {
        launchApp(AppArgs.none());
        ScreenManager manager = ScreenManager.getInstance();
        LoginResult dana = new LoginResult(1001, "dana.cohen", "Dana Cohen", Role.TEACHER,
                List.of(new CourseRef("11", "Algebra 11")));

        interact(() -> {
            attachFakeConnection(manager).replyOk(Verb.LOGIN, dana);
            manager.navigator().replace(Routes.LOGIN.id());
        });
        WaitForAsyncUtils.waitForFxEvents();
        signIn(manager.scene(), "dana.cohen", "demo123");
        WaitForAsyncUtils.waitForFxEvents();

        interact(() -> manager.eventBus().post(
                new ConnectionLostEvent("demo-server:5555", "no route to host")));
        WaitForAsyncUtils.waitForFxEvents();

        ReconnectBanner banner = manager.shell().reconnectBanner();
        assertThat(banner.isShowing()).as("the amber strip, with its Retry").isTrue();

        IClientConnection dead = manager.getClient();
        RequestDispatcher correlator = manager.getDispatcher();
        interact(() -> retryButton(banner).fire());
        WaitForAsyncUtils.waitForFxEvents();

        // The defect: this button had no action wired to it at all, so pressing it did
        // nothing whatsoever. It now builds a fresh client for the endpoint this computer
        // already knows, around the dispatcher every cached screen is holding (U-17).
        assertThat(manager.getClient())
                .as("Retry re-dials rather than reusing the socket that died")
                .isNotSameAs(dead);
        assertThat(manager.getDispatcher())
                .as("through the correlator the screens already hold")
                .isSameAs(correlator);

    }

    @Test
    @DisplayName("\u26a1 U-52: a reconnect lands on Login, pre-filled, saying why")
    void aReconnectLandsOnLoginAskingHerToSignInAgain() {
        launchApp(AppArgs.none());
        ScreenManager manager = ScreenManager.getInstance();
        LoginResult dana = new LoginResult(1001, "dana.cohen", "Dana Cohen", Role.TEACHER,
                List.of(new CourseRef("11", "Algebra 11")));

        interact(() -> {
            attachFakeConnection(manager).replyOk(Verb.LOGIN, dana);
            manager.navigator().replace(Routes.LOGIN.id());
        });
        WaitForAsyncUtils.waitForFxEvents();
        signIn(manager.scene(), "dana.cohen", "demo123");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(manager.shell()).isNotNull();

        // The socket came back. The server freed the session with the old one (F1.4), so
        // the shell signs her out locally and Login says why, with her name already there.
        interact(() -> ShellBoot.afterReconnect(manager));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.shell()).isNull();
        assertThat(manager.signedInUser()).isNull();
        assertThat(manager.navigator().currentRouteId()).isEqualTo(Routes.LOGIN.id());
        assertThat(usernameField(manager.scene()).getText())
                .as("she did not ask to sign out, so she does not retype her name")
                .isEqualTo("dana.cohen");
        assertThat(labelTexts(manager.scene()))
                .contains(ConnectFlow.RECONNECTED_SIGN_IN_AGAIN);
    }

    /** The banner's one button, found by type rather than by a style class it shares. */
    private static Button retryButton(ReconnectBanner banner) {
        return banner.getChildrenUnmodifiable().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the reconnect banner has no Retry button"));
    }

    @Test
    @DisplayName("signing in installs the role's shell; signing out tears it down (E5.4/E5.7)")
    void shellBootsAndTearsDown() {
        launchApp(AppArgs.none());
        ScreenManager manager = ScreenManager.getInstance();
        LoginResult dana = new LoginResult(1001, "dana.cohen", "Dana Cohen", Role.TEACHER,
                List.of(new CourseRef("11", "Algebra 11")));

        interact(() -> {
            FakeClientConnection connection = attachFakeConnection(manager);
            connection.replyOk(Verb.LOGIN, dana);
            manager.navigator().replace(Routes.LOGIN.id());
        });
        WaitForAsyncUtils.waitForFxEvents();

        // Drive the real form, not ShellBoot directly: the point of this test is
        // that connect → login → dashboard holds end to end.
        signIn(manager.scene(), "dana.cohen", "demo123");
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = manager.scene();
        assertThat(manager.shell()).isNotNull();
        assertThat(manager.signedInUser()).isEqualTo(dana);
        assertThat(manager.navigator().currentRouteId()).isEqualTo(Routes.HOME_TEACHER.id());
        assertThat(lookupOne(scene, ".hsts-rail")).isNotNull();
        // The dashboard rendered, and every item on the rail is one she can press.
        assertThat(lookupOne(scene, ".hsts-stat-card")).isNotNull();
        assertThat(scene.getRoot().lookupAll(".nav-item")).isNotEmpty();
        // ⚑ U-1. This asserted the opposite until batch C: "the not-yet-built rail items are
        // visibly muted", which was true of Take Exam and Live Monitor long after their screens
        // shipped. Nothing on any rail is disabled now, and the assertion is inverted rather
        // than deleted so that the next placeholder has to come here and say so.
        assertThat(scene.getRoot().lookupAll(".nav-item.disabled")).isEmpty();

        interact(() -> ShellBoot.logout(manager));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.shell()).isNull();
        assertThat(manager.signedInUser()).isNull();
        assertThat(manager.navigator().currentRouteId()).isEqualTo(Routes.LOGIN.id());
        assertThat(manager.screens().builtCount())
                .as("only the freshly rebuilt login screen survives a logout")
                .isEqualTo(1);
        assertThat(lookupOne(manager.scene(), ".hsts-brand-panel")).isNotNull();
    }

    @Test
    @DisplayName("\u2691 U-41: a coordinator who teaches nothing gets four rail items, not ten")
    void pureCoordinatorGetsTheNarrowRail() {
        launchApp(AppArgs.none());
        ScreenManager manager = ScreenManager.getInstance();
        // rina.barak coordinates Mathematics and has zero course_teachers rows, so the
        // sign-in answer carries an empty course list. That empty list is the whole signal
        // (F1.2: role AND course relations); nothing here asks the server a second question.
        LoginResult rina = new LoginResult(3, "rina.barak", "Rina Barak", Role.COORDINATOR,
                List.of());

        interact(() -> {
            FakeClientConnection connection = attachFakeConnection(manager);
            connection.replyOk(Verb.LOGIN, rina);
            // Her dashboard's two cards are one read of the queue she signs in for.
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, ApprovalQueue.empty());
            manager.navigator().replace(Routes.LOGIN.id());
        });
        WaitForAsyncUtils.waitForFxEvents();

        signIn(manager.scene(), "rina.barak", "demo123");
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = manager.scene();
        assertThat(manager.navigator().currentRouteId()).isEqualTo(Routes.HOME_COORDINATOR.id());
        // Read from the shell's own state rather than from a .nav-label lookup: a rail
        // collapsed by the window width renders icons only, and the assertion would then
        // be measuring the breakpoint instead of the ruling.
        assertThat(manager.shell().state().items()).extracting(NavItem::label).containsExactly(
                // The six that are gone were every rail item scoped to a course she
                // teaches, and each of them opened an empty screen. Question Bank stays:
                // the bank's read scope is her whole coordinated subject (BANK 7.3).
                "Dashboard", "Question Bank", "Approvals", "Settings");
        assertThat(scene.getRoot().lookupAll(".nav-item")).hasSize(4);
        // And the dashboard drops its courses card on the same rule: "No courses are
        // assigned to you yet." reads as a missing seed rather than as the truth about
        // her job.
        assertThat(labelTexts(scene)).doesNotContain("Your courses");
    }

    /**
     * Gives the manager a connected {@code FakeClientConnection} + dispatcher —
     * everything the login screen and the logout verb need, without a server.
     */
    private FakeClientConnection attachFakeConnection(ScreenManager manager) {
        return attachFakeConnection(manager, new FakeClientConnection("demo-server", 5555));
    }

    /** @see #attachFakeConnection(ScreenManager) with a connection of the caller's own */
    private FakeClientConnection attachFakeConnection(ScreenManager manager,
                                                      FakeClientConnection connection) {
        try {
            connection.connect();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        connection.replyOk(Verb.LOGOUT, null);
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        manager.setClient(connection);
        manager.setDispatcher(dispatcher);
        return connection;
    }

    /** @return the login form's username control. */
    private static TextField usernameField(Scene scene) {
        return scene.getRoot().lookupAll(".text-input").stream()
                .filter(node -> node instanceof TextField && !(node instanceof PasswordField))
                .map(TextField.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no username field on the login screen"));
    }

    /** Fills the login form and presses its primary button, as a user would. */
    private void signIn(Scene scene, String username, String password) {
        PasswordField passwordField = (PasswordField) scene.getRoot().lookup(".password-field");
        TextField usernameField = usernameField(scene);
        Button signIn = (Button) scene.getRoot().lookup(".button.primary");

        interact(() -> {
            usernameField.setText(username);
            passwordField.setText(password);
        });
        assertThat(signIn.isDisabled()).as("the button enables once both fields are filled").isFalse();
        interact(signIn::fire);
    }

    /** Starts {@link ClientApp} on the FX thread with the given switches. */
    private Stage launchApp(AppArgs args) {
        Stage[] stage = new Stage[1];
        interact(() -> {
            stage[0] = new Stage();
            if (args.gallery()) {
                System.setProperty(AppArgs.PROP_GALLERY, "true");
            } else {
                System.clearProperty(AppArgs.PROP_GALLERY);
            }
            new ClientApp().start(stage[0]);
        });
        WaitForAsyncUtils.waitForFxEvents();
        return stage[0];
    }

    private Node lookupOne(Scene scene, String selector) {
        return scene.getRoot().lookup(selector);
    }

    /** Fills the login form without pressing anything. */
    private void typeCredentials(Scene scene, String username, String password) {
        PasswordField passwordField = (PasswordField) scene.getRoot().lookup(".password-field");
        TextField usernameField = scene.getRoot().lookupAll(".text-input").stream()
                .filter(node -> node instanceof TextField && !(node instanceof PasswordField))
                .map(TextField.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no username field on the login screen"));
        interact(() -> {
            usernameField.setText(username);
            passwordField.setText(password);
        });
    }

    private Button buttonNamed(Scene scene, String selector) {
        Node node = scene.getRoot().lookup(selector);
        assertThat(node).as("a button matching " + selector).isInstanceOf(Button.class);
        return (Button) node;
    }

    private Hyperlink linkNamed(Scene scene, String text) {
        return scene.getRoot().lookupAll(".hyperlink").stream()
                .filter(Hyperlink.class::isInstance)
                .map(Hyperlink.class::cast)
                .filter(link -> text.equals(link.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no link labelled " + text));
    }

    private static Set<String> labelTexts(Scene scene) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }
}
