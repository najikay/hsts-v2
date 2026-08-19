package client.ui.screen;

import client.core.NavParams;
import client.core.Navigator;
import client.core.Screen;
import client.core.ScreenManager;
import client.events.ClientEventBus;
import client.events.FxThreadPoster;
import client.net.IClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.ToastStack;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;

/**
 * Base class for every screen (Presentation tier, E4.3 — Template Method).
 *
 * <p>The fixed algorithm lives in the {@code final} {@link #view()}: build the
 * node graph <b>once</b>, cache it, hand it back on every revisit. Subclasses
 * supply only {@link #build()}, plus optional {@link #onShow}/{@link #onHide}
 * hooks. Because the graph is built once and shown many times, per-visit work
 * belongs in {@code onShow} — the point the framework guarantees is called with
 * the navigation parameters.
 *
 * <p>EventBus membership is not this class's business either: {@code ScreenManager}
 * drives it through {@code ScreenLifecycle}, which registers a screen while it is
 * visible and unregisters it when it is not. A screen with a {@code @Subscribe}
 * method only has to say so by overriding {@link #listensToEvents()}.
 *
 * <p>Convenience accessors reach the shared collaborators through
 * {@link ScreenManager}, so a screen never wires its own connection or bus.
 */
public abstract class AbstractScreen implements Screen {

    private Parent cachedView;

    /**
     * Template method: builds the view on first call, returns the cache after.
     *
     * @return this screen's root node
     */
    public final Parent view() {
        if (cachedView == null) {
            cachedView = build();
            if (cachedView == null) {
                throw new IllegalStateException(getClass().getSimpleName() + ".build() returned null");
            }
        }
        return cachedView;
    }

    /** @return {@code true} once the node graph has been built. */
    public final boolean isBuilt() {
        return cachedView != null;
    }

    /**
     * Builds this screen's node graph. Called exactly once.
     *
     * <p>Do not put data loading here — a cached screen's {@code build()} does not
     * run again, so a refresh belongs in {@link #onShow(NavParams)}.
     */
    protected abstract Parent build();

    /**
     * Loads an FXML file whose controller is this screen.
     *
     * @throws UncheckedIOException when the FXML is missing or malformed — a
     *         packaging or authoring error that must surface immediately
     */
    protected final Parent loadFxml(String resourcePath) {
        URL url = getClass().getResource(resourcePath);
        if (url == null) {
            throw new IllegalStateException("Missing FXML on the classpath: " + resourcePath);
        }
        FXMLLoader loader = new FXMLLoader(url);
        loader.setController(this);
        try {
            return loader.load();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + resourcePath, e);
        }
    }

    // ------------------------------------------------------- shared services

    /** @return the app's navigator, for moving to another route. */
    protected final Navigator navigator() {
        return ScreenManager.getInstance().navigator();
    }

    /** @return the shared network adapter (Adapter Pattern boundary). */
    protected final IClientConnection client() {
        return ScreenManager.getInstance().getClient();
    }

    /** @return the shared request/response correlator. */
    protected final RequestDispatcher dispatcher() {
        return ScreenManager.getInstance().getDispatcher();
    }

    /** @return the app-wide event bus. */
    protected final ClientEventBus eventBus() {
        return ScreenManager.getInstance().eventBus();
    }

    /**
     * The single hop back onto the JavaFX Application Thread (ARCHITECTURE §6) —
     * screens completing a {@code dispatcher()} future wrap their UI work in this.
     */
    protected final FxThreadPoster onFxThread() {
        return eventBus().poster();
    }

    /** @return the shell's toast overlay, or {@code null} before the shell exists. */
    protected final ToastStack toasts() {
        return ScreenManager.getInstance().toasts();
    }
}
