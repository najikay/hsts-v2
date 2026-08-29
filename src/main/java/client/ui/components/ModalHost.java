package client.ui.components;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.Objects;

/**
 * The transparent stage every modal in the app is shown on (Presentation tier,
 * E4.13).
 *
 * <p>Three dialogs are built the same way — {@link WarnConfirm}, the approval
 * tier's reject dialog and the release tier's create dialog — and each of them
 * used to carry its own copy of the plumbing: a scrim {@code StackPane}, a
 * transparent scene, and the six lines that copy the owner's stylesheets and
 * {@code dark} class across so a modal does not flash up light-themed over a
 * dark app. One copy each is three places for the same defect to live, which is
 * how the one below survived three dialogs.
 *
 * <h2>2026-08-28, manual round 1: the scrim has to dim the window</h2>
 *
 * <p>A scrim is the dark wash over everything the dialog is interrupting. Ours
 * was not: the stage was left to size itself to its scene, so it came out as the
 * dialog plus 40px of padding, and the scrim painted that rectangle and nothing
 * else. What testers saw on every modal was a dark border hugging the dialog —
 * reported as a "weird shadow" — rather than a dimmed app behind it.
 *
 * <p>So {@link #mount} sizes and positions the stage to the <b>owner window's</b>
 * bounds. The scrim then covers the app, the dialog sits centred in it, and the
 * soft drop shadow on {@code .hsts-dialog} is the only shadow in the picture.
 * With no owner — or an owner that has no bounds yet — the stage sizes to its
 * scene as before, because there is no window to cover.
 */
public final class ModalHost {

    /** Smallest gap between the dialog and the edge of the window it dims, in px. */
    public static final double MARGIN = 40;

    private ModalHost() {
    }

    /**
     * Puts a dialog on a stage: scrim, transparent scene, inherited styles, and
     * the owner's bounds.
     *
     * @param stage  the transparent stage to show the dialog on
     * @param owner  the window being interrupted; may be {@code null}
     * @param dialog the dialog node, centred in the scrim
     * @return the scrim, for the call site that fades it in
     */
    public static StackPane mount(Stage stage, Window owner, Node dialog) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(dialog, "dialog");

        StackPane scrim = new StackPane(dialog);
        // 2026-08-29, manual round 3: with the stage covering the owner window, a
        // StackPane hands its child the whole area, so the card stretched to the full
        // height of the window ("unreasonably long"). The card keeps its preferred
        // height and sits in the middle; its width still follows the stylesheet's
        // min/max so long explanations wrap instead of widening it.
        StackPane.setAlignment(dialog, javafx.geometry.Pos.CENTER);
        if (dialog instanceof javafx.scene.layout.Region region) {
            region.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        }
        scrim.getStyleClass().add("hsts-scrim");
        scrim.setPadding(new Insets(MARGIN));

        Scene scene = new Scene(scrim);
        scene.setFill(Color.TRANSPARENT);
        inheritStyles(owner, scene);
        stage.setScene(scene);
        coverOwner(stage, owner);
        return scrim;
    }

    /**
     * Sizes and positions the stage over the whole owner window.
     *
     * <p>Setting an explicit width and height is also what stops JavaFX applying
     * {@code sizeToScene} on show, which is the behaviour that produced the
     * dialog-sized scrim in the first place.
     *
     * @return {@code true} when the stage was moved; {@code false} when there was
     *         no owner to cover, which leaves the stage to size to its scene
     */
    public static boolean coverOwner(Stage stage, Window owner) {
        Objects.requireNonNull(stage, "stage");
        if (!hasBounds(owner)) {
            return false;
        }
        stage.setX(owner.getX());
        stage.setY(owner.getY());
        stage.setWidth(owner.getWidth());
        stage.setHeight(owner.getHeight());
        return true;
    }

    /**
     * A window that has never been laid out reports {@code NaN} for its position
     * and zero for its size, and handing either to a stage is a dialog nobody can
     * find. Checked rather than assumed, because a dialog opened from a screen
     * that is still being built is a real call.
     */
    private static boolean hasBounds(Window owner) {
        return owner != null
                && Double.isFinite(owner.getX())
                && Double.isFinite(owner.getY())
                && owner.getWidth() > 0
                && owner.getHeight() > 0;
    }

    /**
     * A dialog opens in its own {@link Stage} with its own {@link Scene}, so it
     * does not inherit the owner's stylesheets or the {@code dark} root class.
     * Copying both across is what keeps a modal from flashing up unstyled and
     * light-themed over a dark app.
     */
    private static void inheritStyles(Window owner, Scene scene) {
        if (owner == null || owner.getScene() == null) {
            return;
        }
        Scene ownerScene = owner.getScene();
        scene.getStylesheets().setAll(ownerScene.getStylesheets());
        if (ownerScene.getRoot() != null
                && ownerScene.getRoot().getStyleClass().contains("dark")) {
            scene.getRoot().getStyleClass().add("dark");
        }
    }
}
