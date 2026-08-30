package client.ui.components;

import client.ui.anim.Animations;
import client.ui.anim.Motion;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;

import java.util.Objects;

/**
 * The strip that appears when the socket drops mid-session (Presentation tier,
 * E4.6).
 *
 * <p>PRD §6 requires that restarting the server leaves clients showing a
 * reconnect banner and recovering — not a modal error, and certainly not a
 * frozen screen. A banner is the right shape because the app stays usable
 * underneath: a student's answers are already server-side (F6.3) and her timer
 * keeps running server-side (S-18), so what she needs is an honest status line,
 * not a wall.
 *
 * <p>Three states, and the retry-count wording is deliberate — "Reconnecting…
 * (attempt 3)" tells the user the app is still trying, which is the difference
 * between waiting and force-quitting.
 */
public final class ReconnectBanner extends HBox {

    /** What the connection is doing right now. */
    public enum State {
        /** Socket dropped, not currently retrying. Amber, with a Retry button. */
        DISCONNECTED,
        /** A reconnect attempt is in flight. Amber, with a spinner. */
        RECONNECTING,
        /** Back online. Green, then auto-hides. */
        RECONNECTED
    }

    private static final String[] TONE_CLASSES = {"danger", "ok"};

    private final Label text = new Label();
    private final Button retry = Buttons.styled("Retry now", Buttons.SECONDARY, Buttons.SMALL);
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Node icon = Icons.of(Icons.CLOUD_OFF, Icons.SIZE_DEFAULT, "banner-icon");

    private Runnable onRetry;

    public ReconnectBanner() {
        getStyleClass().add("hsts-banner");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);

        text.getStyleClass().add("banner-text");
        spinner.setPrefSize(14, 14);
        spinner.setMaxSize(14, 14);
        retry.setOnAction(e -> {
            if (onRetry != null) {
                onRetry.run();
            }
        });

        getChildren().addAll(icon, text, Buttons.spacer(), spinner, retry);
        setShown(this, false);
    }

    /** Registers the manual retry action. */
    public void setOnRetry(Runnable handler) {
        this.onRetry = Objects.requireNonNull(handler, "handler");
    }

    /** Shows the "connection lost" state with a manual retry button. */
    public void showDisconnected(String serverLabel) {
        apply(State.DISCONNECTED, "Connection to " + serverLabel + " lost. Your work is saved on the server.");
    }

    /**
     * Shows the disconnected state again after a Retry that did not get through,
     * carrying the caller's own sentence (⚑ U-52).
     *
     * <p>Separate from {@link #showDisconnected(String)} because the two say
     * different things. That one reports the drop and reassures ("your work is
     * saved on the server"); this one reports a failed attempt to undo it, and the
     * sentence comes from {@code ConnectFlow.retryFailed}, which names the address
     * and the next step without leaking a class name (B-37).
     *
     * @param message a whole sentence, already in the product's voice
     */
    public void showRetryFailed(String message) {
        apply(State.DISCONNECTED, message == null || message.isBlank()
                ? "Could not reconnect. Check this computer is on the network, then try again."
                : message);
    }

    /**
     * Shows the "trying again" state.
     *
     * @param attempt 1-based attempt number, surfaced so the user can see progress
     */
    public void showReconnecting(int attempt) {
        apply(State.RECONNECTING, "Reconnecting… (attempt " + Math.max(1, attempt) + ")");
    }

    /** Shows the success state briefly, then hides the banner. */
    public void showReconnected() {
        apply(State.RECONNECTED, "Reconnected. Everything is up to date.");
        javafx.animation.PauseTransition settle =
                new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
        settle.setOnFinished(e -> hide());
        settle.play();
    }

    /** Hides the banner and releases its layout row. */
    public void hide() {
        Animations.stop(this);
        setShown(this, false);
    }

    /** @return {@code true} when the banner is currently occupying a row. */
    public boolean isShowing() {
        return isVisible();
    }

    private void apply(State state, String message) {
        text.setText(message);
        getStyleClass().removeAll(TONE_CLASSES);
        if (state == State.RECONNECTED) {
            getStyleClass().add("ok");
        }
        setShown(spinner, state == State.RECONNECTING);
        setShown(retry, state == State.DISCONNECTED);
        setShown(icon, state != State.RECONNECTING);

        boolean wasHidden = !isVisible();
        setShown(this, true);
        if (wasHidden) {
            Animations.slideInY(this, true, 12, Motion.BASE_MS);
        }
        setAccessibleText(message);
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
