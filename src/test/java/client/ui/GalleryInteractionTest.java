package client.ui;

import client.core.AppArgs;
import client.core.ClientApp;
import client.core.FxTestHarness;
import client.core.ScreenManager;
import client.ui.theme.AccentPalette;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for real mouse interaction in the gallery (and, by shared
 * plumbing, the whole app): a transparent CSS background on the full-scene
 * ToastStack layer once made it swallow every click and wheel event —
 * pickOnBounds(false) does not help when the region carries a background fill,
 * transparent included. Found by manual testing on 2026-08-19.
 */
class GalleryInteractionTest extends ApplicationTest {

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
    }

    @AfterEach
    void reset() {
        FxTestHarness.resetGlobalState();
    }

    @Test
    @DisplayName("mouse click on a palette swatch changes the accent stylesheet")
    void swatchClickWorks() {
        Stage[] stage = new Stage[1];
        interact(() -> {
            stage[0] = new Stage();
            System.setProperty(AppArgs.PROP_GALLERY, "true");
            new ClientApp().start(stage[0]);
        });
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = ScreenManager.getInstance().scene();
        assertThat(scene).isNotNull();

        Set<Node> swatches = scene.getRoot().lookupAll(".hsts-swatch");
        Node emerald = swatches.stream()
                .filter(n -> n.getUserData() == AccentPalette.EMERALD)
                .findFirst().orElseThrow();

        System.out.println("PROBE: emerald swatch bounds in scene = "
                + emerald.localToScene(emerald.getBoundsInLocal()));

        clickOn(emerald);
        WaitForAsyncUtils.waitForFxEvents();

        boolean emeraldApplied = scene.getStylesheets().stream()
                .anyMatch(s -> s.contains("accent-emerald"));
        System.out.println("PROBE: stylesheets after click = " + scene.getStylesheets());
        assertThat(emeraldApplied)
                .as("clicking the emerald swatch should apply accent-emerald.css")
                .isTrue();
    }

    @Test
    @DisplayName("mouse wheel scrolls the gallery")
    void wheelScrollWorks() {
        Stage[] stage = new Stage[1];
        interact(() -> {
            stage[0] = new Stage();
            System.setProperty(AppArgs.PROP_GALLERY, "true");
            new ClientApp().start(stage[0]);
        });
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = ScreenManager.getInstance().scene();
        ScrollPane scroll = (ScrollPane) scene.getRoot().lookup(".scroll-pane");
        assertThat(scroll).isNotNull();
        double before = scroll.getVvalue();

        // Deliver the wheel event through the scene graph (Monocle's headless
        // robot cannot synthesize wheel input): target a node INSIDE the
        // viewport, exactly like a real wheel event picked at the cursor. This
        // is what regressed when the toast layer was pickable — the event was
        // picked on the overlay and never reached the ScrollPane.
        Node inViewport = scene.getRoot().lookup(".hsts-chip");
        assertThat(inViewport).isNotNull();
        interact(() -> {
            javafx.scene.input.ScrollEvent wheel = new javafx.scene.input.ScrollEvent(
                    javafx.scene.input.ScrollEvent.SCROLL,
                    0, 0, 0, 0, false, false, false, false, true, false,
                    0, -120, 0, -120,
                    javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0,
                    0, null);
            javafx.event.Event.fireEvent(inViewport, wheel);
        });
        WaitForAsyncUtils.waitForFxEvents();

        System.out.println("PROBE: vvalue before=" + before + " after=" + scroll.getVvalue());
        assertThat(scroll.getVvalue())
                .as("wheel scroll should move the gallery viewport")
                .isGreaterThan(before);
    }
}
