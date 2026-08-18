package client.core;

import client.events.ClientEventBus;
import client.events.FxThreadPoster;
import client.net.IClientConnection;
import client.net.RequestDispatcher;
import javafx.scene.Parent;

/**
 * Base class for all screens (Template Method Pattern, Presentation tier).
 *
 * <p>Defines the skeleton of a screen's load lifecycle: build the scene graph
 * ({@link #render()}), then run a post-render hook ({@link #onShown()}). The
 * fixed sequence lives in the {@code final} {@link #load()}; subclasses supply
 * only the variable steps.
 */
public abstract class AbstractScreenUI {

    /**
     * Template method — fixed algorithm: render the UI, then fire the hook.
     * Called by {@link ScreenManager#setScreen(AbstractScreenUI)}.
     */
    public final Parent load() {
        Parent root = render();
        onShown();
        return root;
    }

    /** Build and return this screen's root node. Must be implemented. */
    public abstract Parent render();

    /**
     * Hook invoked immediately after {@link #render()} — e.g. to kick off the
     * initial data request. Default is a no-op.
     */
    protected void onShown() {
    }

    /** Convenience accessor for the shared network adapter. */
    protected IClientConnection client() {
        return ScreenManager.getInstance().getClient();
    }

    /** Convenience accessor for the shared request/response correlator. */
    protected RequestDispatcher dispatcher() {
        return ScreenManager.getInstance().getDispatcher();
    }

    /**
     * The single hop back onto the JavaFX Application Thread (ARCHITECTURE §6).
     * Screens completing a {@code dispatcher()} future wrap their UI work in this.
     */
    protected FxThreadPoster onFxThread() {
        return ClientEventBus.getInstance().poster();
    }
}
