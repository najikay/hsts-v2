package client.ui;

import client.core.AppArgs;
import client.core.ClientApp;
import client.core.Routes;
import client.core.ScreenManager;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

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
