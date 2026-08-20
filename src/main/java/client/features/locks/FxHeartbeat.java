package client.features.locks;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

import java.util.Objects;

/**
 * The running app's {@link Heartbeat}: a JavaFX {@link Timeline} (Presentation
 * tier, E18.3).
 *
 * <p>A {@code Timeline} rather than a {@code ScheduledExecutorService} because
 * its ticks already arrive on the JavaFX Application Thread, which is where the
 * renewal's result has to be applied. A background scheduler would need a hop
 * back, and the app has exactly one of those by design (ARCHITECTURE §6).
 *
 * <p>Thin by construction: every decision about <i>when</i> to renew and what to
 * do with the answer lives in {@link LockAwareEditor} and
 * {@link EditLockState}, both of which are unit-tested without a toolkit.
 */
public final class FxHeartbeat implements Heartbeat {

    private Timeline timeline;

    @Override
    public void start(java.time.Duration period, Runnable tick) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(tick, "tick");
        stop();
        timeline = new Timeline(new KeyFrame(Duration.millis(period.toMillis()), e -> tick.run()));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    @Override
    public void stop() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
    }

    @Override
    public boolean isRunning() {
        return timeline != null && timeline.getStatus() == Animation.Status.RUNNING;
    }
}
