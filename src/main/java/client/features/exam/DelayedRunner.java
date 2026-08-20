package client.features.exam;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * "Run this in a moment" (Presentation tier, E10.11 — F6.3).
 *
 * <p>The seam under the debounced autosave. Production hands it to the JDK's shared
 * delayed executor, exactly as {@code RequestDispatcher} does for its timeouts, so nothing
 * here owns a thread. Tests hand it a manual implementation and fire the pending task
 * themselves, which turns "the save is sent 400 ms after the last click, once, not four
 * times" from a flaky sleep into a deterministic assertion.
 *
 * <p>An interface rather than a {@code ScheduledExecutorService} parameter because the
 * only capability the autosave needs is this one method, and a test double for it is one
 * line.
 */
@FunctionalInterface
public interface DelayedRunner {

    /**
     * Runs {@code task} after {@code delay}.
     *
     * @param delay how long to wait
     * @param task  what to run; must not throw
     */
    void runAfter(Duration delay, Runnable task);

    /**
     * @return a runner backed by the JDK's shared delayed executor, owning no threads of
     *         its own
     */
    static DelayedRunner shared() {
        return (delay, task) -> CompletableFuture.runAsync(task,
                CompletableFuture.delayedExecutor(Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS));
    }

    /** @return a runner that runs everything immediately; for tests that do not care. */
    static DelayedRunner immediate() {
        return (delay, task) -> task.run();
    }
}
