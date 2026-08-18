package client.events;

import javafx.application.Platform;

/**
 * Production {@link FxThreadPoster}: hands the action to
 * {@link Platform#runLater(Runnable)}.
 *
 * <p>This class is the literal one-line crossing into the JavaFX toolkit: the
 * body cannot run without a booted toolkit (TestFX/Monocle arrives with E4.24),
 * which is precisely why every other class in this package delegates the hop
 * here instead of calling {@code Platform.runLater} itself. It carries no logic
 * to get wrong, and it needs no coverage exclusion — the three uncovered
 * instructions are immaterial against the bundle gate.
 */
public final class PlatformFxThreadPoster implements FxThreadPoster {

    @Override
    public void run(Runnable action) {
        Platform.runLater(action);
    }
}
