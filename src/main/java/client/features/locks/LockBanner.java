package client.features.locks;

import client.ui.anim.Animations;
import client.ui.anim.Motion;
import client.ui.components.Buttons;
import client.ui.components.Icons;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import java.util.Objects;

/**
 * The strip an editor shows when it is not the one holding the lock
 * (Presentation tier, E18.3).
 *
 * <p>A banner rather than a dialog, for the same reason as
 * {@code ReconnectBanner}: the screen underneath stays useful. A teacher who
 * cannot edit a question can still read it, and blocking that behind a modal
 * would be worse than the situation it reports.
 *
 * <p>It renders an {@link EditLockState.Snapshot} and nothing else, so what it
 * says is decided by the state machine and the copy class, both unit-tested.
 * Its own job is three lines of layout and one button.
 */
public final class LockBanner extends HBox {

    private final Label text = new Label();
    private final Node lockIcon = Icons.of(Icons.WARNING, Icons.SIZE_DEFAULT, "banner-icon");
    private final Button takeOver = Buttons.styled(LockCopy.TAKEOVER_CONFIRM,
            Buttons.SECONDARY, Buttons.SMALL);

    private Runnable onTakeOver;

    public LockBanner() {
        getStyleClass().add("hsts-banner");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);
        text.getStyleClass().add("banner-text");
        text.setWrapText(true);
        takeOver.setOnAction(e -> {
            if (onTakeOver != null) {
                onTakeOver.run();
            }
        });
        getChildren().addAll(lockIcon, text, Buttons.spacer(), takeOver);
        setShown(this, false);
    }

    /** Registers what the "Take over" button does. */
    public void setOnTakeOver(Runnable handler) {
        this.onTakeOver = Objects.requireNonNull(handler, "handler");
    }

    /**
     * Renders a lock state.
     *
     * @param state      the snapshot from {@link LockAwareEditor#state()}
     * @param entityNoun what is being edited, lower case singular
     */
    public void show(EditLockState.Snapshot state, String entityNoun) {
        Objects.requireNonNull(state, "state");
        String message = state.bannerText(entityNoun).orElse(null);
        if (message == null) {
            hide();
            return;
        }
        text.setText(message);
        // The takeover affordance appears only where taking over is the next step;
        // on a read-only banner there is nothing to take.
        setShown(takeOver, state.offersTakeover());
        boolean wasHidden = !isVisible();
        setShown(this, true);
        if (wasHidden) {
            Animations.slideInY(this, true, 12, Motion.BASE_MS);
        }
    }

    /** Hides the banner and releases its layout row. */
    public void hide() {
        Animations.stop(this);
        setShown(this, false);
    }

    /** @return {@code true} while the banner occupies a row. */
    public boolean isShowing() {
        return isVisible();
    }

    /** @return the banner's current sentence, for tests. */
    public String message() {
        return text.getText();
    }

    /** @return the "Take over" button, for tests and keyboard wiring. */
    public Button takeOverButton() {
        return takeOver;
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
