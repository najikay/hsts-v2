package client.ui.components;

import client.ui.anim.Animations;
import client.ui.anim.Motion;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * A scrim + spinner laid over a region while a blocking operation runs
 * (Presentation tier, E4.16).
 *
 * <p>Used where a skeleton makes no sense because the content already exists and
 * is being <i>changed</i> rather than loaded: saving an exam, submitting an
 * attempt, running auto-generation. Covering the content is deliberate — it also
 * prevents the double-submit that a disabled button alone does not (a second
 * Enter press while the first request is in flight).
 *
 * <p>Always shows a message. "Please wait" over a frozen screen is exactly the
 * mystery state PRD §4.1 forbids; "Generating exam…" is not.
 */
public final class ProgressOverlay extends StackPane {

    private final Label message = new Label();

    public ProgressOverlay(String messageText) {
        getStyleClass().add("hsts-progress-overlay");
        setAlignment(Pos.CENTER);

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(38, 38);

        message.setText(messageText);
        message.getStyleClass().add("progress-message");
        message.setWrapText(true);

        VBox card = new VBox(14, spinner, message);
        card.getStyleClass().add("progress-card");
        card.setAlignment(Pos.CENTER);
        // Keep the card at its natural size instead of filling the scrim.
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        getChildren().add(card);
        setVisible(false);
        setManaged(false);
    }

    /** Updates the message while the overlay is up ("Saving…" → "Checking answers…"). */
    public void setMessage(String text) {
        message.setText(text);
    }

    /** Fades the overlay in and starts blocking input to what is underneath. */
    public void show() {
        setVisible(true);
        setManaged(true);
        Animations.fadeIn(this, Motion.FAST_MS);
    }

    /** Shows the overlay with a new message. */
    public void show(String text) {
        setMessage(text);
        show();
    }

    /** Hides the overlay and releases input. */
    public void hide() {
        Animations.stop(this);
        setVisible(false);
        setManaged(false);
        setOpacity(1);
    }

    /** @return {@code true} when the overlay is currently blocking. */
    public boolean isShowing() {
        return isVisible();
    }

    /**
     * Wraps content in a {@link StackPane} with this overlay on top.
     *
     * @return the stack to add to the scene graph in place of {@code content}
     */
    public StackPane over(Node content) {
        Objects.requireNonNull(content, "content");
        return new StackPane(content, this);
    }
}
