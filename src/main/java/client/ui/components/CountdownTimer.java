package client.ui.components;

import client.ui.anim.Animations;
import client.ui.anim.Motion;
import client.ui.components.logic.CountdownLogic;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The exam countdown chip (Presentation tier, E4.18, F6.2).
 *
 * <p>Thin: every decision — remaining time, the amber/red thresholds, the
 * {@code mm:ss} formatting, drift correction — belongs to {@link CountdownLogic}
 * and is unit-tested there. This class runs a one-second {@link Timeline} that
 * asks the logic what to display, and applies the resulting style class.
 *
 * <p>The two designed moments the mockups call for:
 * <ul>
 *   <li><b>red</b> (≤5 min): the chip pulses, so a student not looking at it
 *       still catches the movement;</li>
 *   <li><b>extension granted</b> (F7.1): {@link #showExtension} flashes the chip
 *       green with a glow and floats a "+15:00" off it — time added is never
 *       silent.</li>
 * </ul>
 */
public final class CountdownTimer extends HBox {

    private static final String[] THRESHOLD_CLASSES = {"normal", "amber", "red", "expired"};

    private final CountdownLogic logic;
    private final Label value = new Label("--:--");
    private final Label caption = new Label("remaining");
    private final Timeline ticker;

    private CountdownLogic.Threshold lastThreshold;
    private Consumer<CountdownTimer> onExpired;

    public CountdownTimer() {
        this(CountdownLogic.systemClock());
    }

    /** @param logic the countdown to display; inject a fake-clock one in tests */
    public CountdownTimer(CountdownLogic logic) {
        this.logic = Objects.requireNonNull(logic, "logic");
        getStyleClass().addAll("hsts-countdown", "normal");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(8);

        value.getStyleClass().add("countdown-value");
        caption.getStyleClass().add("countdown-label");

        getChildren().addAll(
                Icons.of(Icons.CLOCK, Icons.SIZE_DEFAULT, "countdown-icon"),
                value, caption);

        ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> refresh()));
        ticker.setCycleCount(Animation.INDEFINITE);
    }

    /** @return the decision layer — call {@code syncTo}/{@code extendBy} on it. */
    public CountdownLogic logic() {
        return logic;
    }

    /**
     * Anchors to a server-stated deadline and starts ticking (S-18).
     *
     * @param endsAt        the attempt's end instant, from the server
     * @param totalDuration the full allotted duration, for the 25% amber threshold
     */
    public void start(Instant endsAt, java.time.Duration totalDuration) {
        logic.syncTo(endsAt, totalDuration);
        refresh();
        ticker.playFromStart();
    }

    /**
     * Re-anchors to a corrected deadline without restarting the display — the
     * drift-correction path used on reconnect (F6.3).
     */
    public void resync(Instant endsAt, java.time.Duration totalDuration) {
        logic.syncTo(endsAt, totalDuration);
        refresh();
    }

    /** Stops ticking. Screens call this in {@code onHide}. */
    public void stop() {
        ticker.stop();
        Animations.stop(this);
    }

    /** Runs when the countdown reaches zero — the client's cue that the server force-submitted. */
    public void setOnExpired(Consumer<CountdownTimer> handler) {
        this.onExpired = handler;
    }

    /** Replaces the trailing caption ("remaining", "until close"). */
    public void setCaption(String text) {
        caption.setText(text);
    }

    /**
     * The F7.1 designed moment: flash green, glow, and float the gained time off
     * the chip. Call after {@code logic().extendBy(extra)}.
     */
    public void showExtension(java.time.Duration extra) {
        refresh();
        getStyleClass().add("extended");
        Animations.glow(this, Color.web("#3fb950"));

        Label gain = new Label(CountdownLogic.formatGain(extra));
        gain.getStyleClass().add("countdown-gain");
        gain.setMouseTransparent(true);
        getChildren().add(gain);
        Animations.slideInY(gain, false, 18, Motion.SLOW_MS);

        Timeline settle = new Timeline(new KeyFrame(Duration.millis(1600), e -> {
            getChildren().remove(gain);
            getStyleClass().remove("extended");
            refresh();
        }));
        settle.play();
    }

    /** Reads the logic and repaints. Called every second and after every sync. */
    public void refresh() {
        if (!logic.isRunning()) {
            return;
        }
        value.setText(logic.formatted());
        CountdownLogic.Threshold threshold = logic.threshold();
        if (threshold != lastThreshold) {
            applyThreshold(threshold);
            lastThreshold = threshold;
        }
        if (threshold == CountdownLogic.Threshold.EXPIRED) {
            ticker.stop();
            if (onExpired != null) {
                onExpired.accept(this);
            }
        }
        setAccessibleText(logic.formatted() + " remaining");
    }

    private void applyThreshold(CountdownLogic.Threshold threshold) {
        getStyleClass().removeAll(THRESHOLD_CLASSES);
        getStyleClass().add(threshold.styleClass());
        if (threshold == CountdownLogic.Threshold.RED) {
            Animations.pulse(this, 0);
        } else {
            Animations.reset(this);
        }
    }
}
