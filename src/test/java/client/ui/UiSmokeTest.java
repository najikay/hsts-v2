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
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
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
        java.lang.reflect.Method reset = ScreenManager.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
        System.clearProperty(AppArgs.PROP_GALLERY);
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
        // The dashboard rendered, and the not-yet-built rail items are visibly muted.
        assertThat(lookupOne(scene, ".hsts-stat-card")).isNotNull();
        assertThat(scene.getRoot().lookupAll(".nav-item.disabled")).isNotEmpty();

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

    /**
     * Gives the manager a connected {@code FakeClientConnection} + dispatcher —
     * everything the login screen and the logout verb need, without a server.
     */
    private FakeClientConnection attachFakeConnection(ScreenManager manager) {
        FakeClientConnection connection = new FakeClientConnection("demo-server", 5555);
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

    /** Fills the login form and presses its primary button, as a user would. */
    private void signIn(Scene scene, String username, String password) {
        PasswordField passwordField = (PasswordField) scene.getRoot().lookup(".password-field");
        TextField usernameField = scene.getRoot().lookupAll(".text-input").stream()
                .filter(node -> node instanceof TextField && !(node instanceof PasswordField))
                .map(TextField.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no username field on the login screen"));
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
}
