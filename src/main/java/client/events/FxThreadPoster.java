package client.events;

/**
 * The one crossing point between background threads and the JavaFX Application
 * Thread (Presentation tier, ARCHITECTURE §6).
 *
 * <p>Network callbacks arrive on OCSF's read thread. Everything that ends up
 * touching the scene graph goes through an implementation of this interface
 * instead of calling {@code Platform.runLater} scattered across screens — which
 * is what makes {@link ClientEventBus} and every session class unit-testable
 * with no FX toolkit booted: tests install {@link DirectFxThreadPoster}, the app
 * installs {@link PlatformFxThreadPoster}.
 */
@FunctionalInterface
public interface FxThreadPoster {

    /** Runs {@code action} on the FX Application Thread (or inline, in tests). */
    void run(Runnable action);
}
