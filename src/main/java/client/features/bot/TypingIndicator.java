package client.features.bot;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/**
 * The three dots that say the bot is thinking (Presentation tier, E16.13 — F12.5).
 *
 * <p>The only feedback a student gets during a provider call that can take twenty
 * seconds, so it is not decoration: NFR-21 requires every async operation to show
 * progress, and a chat that sits still after a send button reads as broken.
 *
 * <p>Three dots pulsing out of phase, 600ms each, indefinitely — a shape people
 * already read as "typing" without a label. There is a label anyway, hidden from
 * sight but present for screen readers and for the TestFX assertion, because a
 * purely visual signal is not a signal for everyone.
 *
 * <p>{@link #stop()} is called on every exit from the thinking state, including
 * the failure ones. An animation left running behind a hidden node is the classic
 * way a JavaFX screen quietly burns a frame budget forever.
 */
public final class TypingIndicator extends HBox {

    /** Style class on the container. */
    public static final String STYLE = "typing-indicator";

    /** How long one dot takes to fade down and back. */
    private static final Duration BEAT = Duration.millis(600);

    private final FadeTransition[] pulses = new FadeTransition[3];

    public TypingIndicator() {
        getStyleClass().add(STYLE);
        setSpacing(6);
        setAlignment(Pos.CENTER_LEFT);
        setVisible(false);
        setManaged(false);

        Label label = new Label(BotCopy.THINKING);
        label.getStyleClass().add("visually-hidden");
        label.setVisible(false);
        label.setManaged(false);
        getChildren().add(label);

        for (int i = 0; i < pulses.length; i++) {
            Circle dot = new Circle(4);
            dot.getStyleClass().add("typing-dot");
            getChildren().add(dot);

            FadeTransition pulse = new FadeTransition(BEAT, dot);
            pulse.setFromValue(0.25);
            pulse.setToValue(1.0);
            pulse.setAutoReverse(true);
            pulse.setCycleCount(Animation.INDEFINITE);
            // Out of phase, so the three dots read as a wave rather than a blink.
            pulse.setDelay(BEAT.divide(3).multiply(i));
            pulses[i] = pulse;
        }
    }

    /** Shows the indicator and starts the animation. */
    public void start() {
        setVisible(true);
        setManaged(true);
        for (FadeTransition pulse : pulses) {
            pulse.playFromStart();
        }
    }

    /** Hides the indicator and stops the animation. */
    public void stop() {
        for (FadeTransition pulse : pulses) {
            pulse.stop();
        }
        setVisible(false);
        setManaged(false);
    }

    /** @return {@code true} while the indicator is showing. */
    public boolean isRunning() {
        return isVisible();
    }
}
