package client.features.locks;

import java.time.Duration;

/**
 * The repeating tick that keeps an edit lock alive (Presentation tier, E18.3).
 *
 * <p>A seam, for one reason: {@link LockAwareEditor} is otherwise entirely
 * FX-free and therefore entirely unit-testable, and a JavaFX {@code Timeline}
 * would drag a toolkit into every test of the renew/expiry/takeover logic. Tests
 * pass a manual implementation and fire ticks on demand; the running app passes
 * {@link FxHeartbeat}.
 *
 * <p>Implementations must deliver ticks on the JavaFX Application Thread, since
 * a tick ends in a model update the panel renders from.
 */
public interface Heartbeat {

    /**
     * Starts ticking. Calling this while already running replaces the previous
     * schedule rather than adding a second one.
     *
     * @param period how often to tick
     * @param tick   what to run each time
     */
    void start(Duration period, Runnable tick);

    /** Stops ticking. Safe to call when not running. */
    void stop();

    /** @return {@code true} while ticks are being delivered. */
    boolean isRunning();
}
