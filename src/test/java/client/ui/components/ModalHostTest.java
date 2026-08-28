package client.ui.components;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ModalHost} — the scrim covers the window, not the dialog ⚑
 * (2026-08-28, manual round 1).
 *
 * <p>The defect these assertions exist to stop is one everybody saw and nobody
 * could name. A modal was shown on a transparent stage that sized itself to its
 * scene, so the stage came out as the dialog plus its 40px padding and the
 * scrim — a colour meant to wash over the whole app — painted that rectangle
 * instead. Testers reported a "weird shadow" around every dialog in the app,
 * because a dark band hugging a card is what a shadow looks like.
 *
 * <p>The size of a stage is not something a copy test or a session test can see,
 * which is why three dialogs carried it for as long as they did. Asserted here
 * once, on the class all three now share.
 *
 * <p>Same escape hatch as the other UI tests:
 * {@code ./mvnw verify -Dhsts.uitests=false}.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class ModalHostTest extends ApplicationTest {

    private static final String SHEET = "data:text/css,.marker%20%7B%7D";

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
        // Every test builds the stages it needs; none of them is shown.
    }

    @Test
    @DisplayName("⚑ the modal stage takes the owner window's bounds, so the scrim dims the app")
    void theStageCoversTheOwner() {
        Stage owner = ownerAt(120, 60, 1280, 800);
        Stage modal = modalStage();
        VBox dialog = dialog();

        StackPane scrim = mount(modal, owner, dialog);

        assertThat(modal.getX()).isEqualTo(120);
        assertThat(modal.getY()).isEqualTo(60);
        assertThat(modal.getWidth())
                .as("a stage sized to its scene is a scrim the size of the dialog, "
                        + "which reads as a dark box around it")
                .isEqualTo(1280);
        assertThat(modal.getHeight()).isEqualTo(800);
        assertThat(scrim.getStyleClass()).contains("hsts-scrim");
        assertThat(scrim.getChildren()).containsExactly(dialog);
        assertThat(scrim.getPadding().getTop()).isEqualTo(ModalHost.MARGIN);
        assertThat(modal.getScene().getFill()).isEqualTo(Color.TRANSPARENT);
    }

    @Test
    @DisplayName("with no owner the stage still sizes to its scene, as it always did")
    void noOwnerLeavesTheStageAlone() {
        Stage modal = modalStage();

        StackPane scrim = mount(modal, null, dialog());

        assertThat(ModalHost.coverOwner(modal, null)).isFalse();
        assertThat(modal.getWidth())
                .as("untouched: JavaFX sizes it to the scene on show")
                .isNaN();
        assertThat(scrim.getStyleClass()).contains("hsts-scrim");
        assertThat(modal.getScene().getStylesheets()).isEmpty();
    }

    @Test
    @DisplayName("an owner that has not been laid out yet is not a set of bounds")
    void anUnlaidOwnerIsRefused() {
        // A dialog opened from a screen that is still being built is a real call,
        // and a stage moved to NaN is a dialog nobody can find.
        Stage modal = modalStage();
        Stage unlaid = interactStage(() -> new Stage());

        assertThat(ModalHost.coverOwner(modal, unlaid)).isFalse();
        assertThat(ModalHost.coverOwner(modal, ownerAt(0, 0, 0, 0))).isFalse();
        assertThat(ModalHost.coverOwner(modal, ownerAt(Double.NaN, 0, 900, 700))).isFalse();
        assertThat(ModalHost.coverOwner(modal, ownerAt(0, Double.NaN, 900, 700))).isFalse();
        assertThat(ModalHost.coverOwner(modal, ownerAt(10, 10, 900, 700))).isTrue();
    }

    @Test
    @DisplayName("the modal inherits the owner's stylesheets and its dark class")
    void stylesCrossOver() {
        // A modal has its own Scene, so it starts with neither. Inheriting both is
        // what stops a dialog flashing up unstyled and light over a dark app.
        Stage owner = ownerAt(0, 0, 900, 700);
        interact(() -> {
            owner.getScene().getStylesheets().add(SHEET);
            owner.getScene().getRoot().getStyleClass().add("dark");
        });
        Stage modal = modalStage();

        StackPane scrim = mount(modal, owner, dialog());

        assertThat(modal.getScene().getStylesheets()).containsExactly(SHEET);
        assertThat(scrim.getStyleClass()).contains("dark");
    }

    @Test
    @DisplayName("a light owner hands over no dark class, and a windowless one hands over nothing")
    void theOtherTwoStyleCases() {
        Stage light = ownerAt(0, 0, 900, 700);
        interact(() -> light.getScene().getStylesheets().add(SHEET));
        Stage modal = modalStage();

        StackPane scrim = mount(modal, light, dialog());
        assertThat(scrim.getStyleClass()).doesNotContain("dark");
        assertThat(modal.getScene().getStylesheets()).containsExactly(SHEET);

        // An owner with no scene of its own: nothing to copy, and no exception.
        Stage bare = interactStage(() -> {
            Stage stage = new Stage();
            stage.setX(0);
            stage.setY(0);
            stage.setWidth(640);
            stage.setHeight(480);
            return stage;
        });
        Stage second = modalStage();
        assertThat(mount(second, bare, dialog()).getStyleClass()).contains("hsts-scrim");
        assertThat(second.getScene().getStylesheets()).isEmpty();
        assertThat(second.getWidth()).isEqualTo(640);
    }

    // ===================== Fixtures ======================================

    private StackPane mount(Stage stage, Stage owner, VBox dialog) {
        StackPane[] scrim = new StackPane[1];
        interact(() -> scrim[0] = ModalHost.mount(stage, owner, dialog));
        WaitForAsyncUtils.waitForFxEvents();
        return scrim[0];
    }

    private VBox dialog() {
        VBox[] built = new VBox[1];
        interact(() -> {
            VBox dialog = new VBox(new Label("Sign out?"));
            dialog.getStyleClass().add("hsts-dialog");
            built[0] = dialog;
        });
        return built[0];
    }

    private Stage modalStage() {
        return interactStage(() -> new Stage(StageStyle.TRANSPARENT));
    }

    private Stage ownerAt(double x, double y, double width, double height) {
        return interactStage(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(new Label("app"))));
            stage.setX(x);
            stage.setY(y);
            stage.setWidth(width);
            stage.setHeight(height);
            return stage;
        });
    }

    private Stage interactStage(java.util.function.Supplier<Stage> build) {
        Stage[] made = new Stage[1];
        interact(() -> made[0] = build.get());
        WaitForAsyncUtils.waitForFxEvents();
        return made[0];
    }
}
