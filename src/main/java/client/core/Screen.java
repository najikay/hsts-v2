package client.core;

/**
 * Lifecycle contract every screen implements (Presentation tier, E4.3).
 *
 * <p>Intentionally free of JavaFX types: it is the half of a screen that
 * {@link ScreenLifecycle} drives and that unit tests can exercise without a
 * toolkit. The FX half — building the node graph — is added by
 * {@code client.ui.screen.AbstractScreen}.
 */
public interface Screen {

    /**
     * Called every time the screen becomes visible, with the parameters it was
     * navigated to. A cached screen receives this again on each revisit, so it
     * is the right place to (re)load data — not the constructor.
     */
    default void onShow(NavParams params) {
    }

    /** Called when the screen is navigated away from. Cancel timers, drop listeners. */
    default void onHide() {
    }

    /**
     * Opt-in to automatic {@code ClientEventBus} registration around
     * show/hide (E4.3).
     *
     * <p>Must return {@code true} only for screens that actually declare a
     * {@code @Subscribe} method — greenrobot rejects registering an object with
     * none, and a silent try/catch there would hide the real mistake of
     * forgetting the annotation.
     *
     * @return {@code true} to be registered on the bus while visible
     */
    default boolean listensToEvents() {
        return false;
    }
}
