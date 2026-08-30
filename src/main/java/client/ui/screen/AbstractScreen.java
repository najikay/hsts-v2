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
import common.protocol.Message;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.function.Consumer;

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
 *
 * <h2>A build that lands after the world has gone (2026-08-30, wave 6, U-45)</h2>
 *
 * <p>Under the full build one interaction test in three failed with
 * {@code NullPointerException ... AbstractScreen.eventBus() is null}, attributed to whichever
 * test ran next. The cause is a teardown race rather than a screen bug: a runnable queued by
 * one test builds its screen after {@code FxTestHarness} has discarded the
 * {@link ScreenManager}, so the accessors below ask a brand-new, never-initialised manager for
 * collaborators it does not have. The harness now drains two generations of the FX queue before
 * it resets, which closes the window; these accessors close the door behind it, because a queue
 * drain can never prove that no background thread is about to post one more runnable.
 *
 * <p>So when the manager has no event bus at all - the one state that says {@code init} has not
 * run - a screen is handed <b>detached</b> collaborators instead of {@code null}: an empty bus
 * whose poster drops what it is given, and a dispatcher over a connection that refuses every
 * send. The screen builds, paints into a scene nobody will show, and is discarded with the rest
 * of the test's world. Nothing is silently swallowed in the running app, where {@code init} has
 * always run by the time any screen builds.
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

    /**
     * @return the app's navigator, for moving to another route. Never {@code null}: the
     *         manager builds one in its field initialiser, so even a discarded manager
     *         answers this (with an empty route table, U-45).
     */
    protected final Navigator navigator() {
        return ScreenManager.getInstance().navigator();
    }

    /**
     * @return the shared network adapter (Adapter Pattern boundary), or the detached
     *         connection when the manager was never initialised (U-45). {@code null} only
     *         in the screen gallery and the console harness, which wire a manager but no
     *         socket - the case every caller has always had to check.
     */
    protected final IClientConnection client() {
        IClientConnection wired = ScreenManager.getInstance().getClient();
        return wired != null ? wired : detachedOrNull(Detached.CONNECTION);
    }

    /**
     * @return the shared request/response correlator, or the detached dispatcher when the
     *         manager was never initialised (U-45). {@code null} keeps its old meaning -
     *         gallery and harness, no socket - which is what
     *         {@code QuestionEditorView.openLock} and its two siblings test for.
     */
    protected final RequestDispatcher dispatcher() {
        RequestDispatcher wired = ScreenManager.getInstance().getDispatcher();
        return wired != null ? wired : detachedOrNull(Detached.DISPATCHER);
    }

    /**
     * @return the app-wide event bus, or the detached bus when the manager was never
     *         initialised (U-45), so that a screen building into a torn-down world posts
     *         into nothing rather than throwing at whichever test runs next
     */
    protected final ClientEventBus eventBus() {
        ClientEventBus wired = ScreenManager.getInstance().eventBus();
        return wired != null ? wired : Detached.BUS;
    }

    /**
     * The single hop back onto the JavaFX Application Thread (ARCHITECTURE §6) —
     * screens completing a {@code dispatcher()} future wrap their UI work in this.
     */
    protected final FxThreadPoster onFxThread() {
        return eventBus().poster();
    }

    /**
     * The one question that separates "torn down" from "deliberately unwired" (U-45).
     *
     * <p>{@code ScreenManager.init} sets the event bus and cannot leave it null, so a manager
     * with no bus is one {@code init} never ran on: the fresh instance the singleton hands back
     * after {@code resetForTests}, in a test whose world is already gone. A manager that has a
     * bus but no socket is the screen gallery and the console harness, and those callers have
     * always read {@code null} and rendered anyway - so they keep reading {@code null}.
     *
     * @param detached what to hand back in the torn-down state
     * @return {@code detached}, or {@code null} when the manager is live but unwired
     */
    private static <T> T detachedOrNull(T detached) {
        return ScreenManager.getInstance().eventBus() == null ? detached : null;
    }

    /**
     * Collaborators for a screen that is being built into a world that no longer exists
     * (2026-08-30, wave 6, U-45). Held in a nested class so the app pays for none of it
     * unless a screen actually asks in that state.
     *
     * <p>Each one is chosen to be inert rather than merely non-null: the bus has no
     * subscribers and a poster that drops the work handed to it, so a post cannot reach a
     * screen the test has finished with; the connection refuses every send, so a dispatcher
     * call completes its future exceptionally at once instead of arming a ten-second timeout
     * that outlives the JVM's interest in it. A screen wired to these renders and is thrown
     * away, which is exactly what a teardown wants.
     */
    private static final class Detached {

        static final ClientEventBus BUS =
                new ClientEventBus(ClientEventBus.newBus(), action -> { });

        static final IClientConnection CONNECTION = new DetachedConnection();

        static final RequestDispatcher DISPATCHER = new RequestDispatcher(CONNECTION);

        private Detached() {
        }
    }

    /**
     * A connection that is not one: every send is refused, and it is never open (U-45).
     *
     * <p>Refusing rather than pretending to succeed is the point. {@code RequestDispatcher.send}
     * turns the {@link IOException} into a future completed exceptionally on the calling thread,
     * so a screen's own failure branch runs immediately and its request never sits in the
     * pending map waiting for an answer that cannot come.
     */
    private static final class DetachedConnection implements IClientConnection {

        private static final String REASON =
                "This screen was built after its application was torn down.";

        @Override
        public void connect() throws IOException {
            throw new IOException(REASON);
        }

        @Override
        public void send(Message msg) throws IOException {
            throw new IOException(REASON);
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean isConnectionOpen() {
            return false;
        }

        @Override
        public String getHost() {
            return "";
        }

        @Override
        public int getPort() {
            return 0;
        }

        @Override
        public void setServerMessageHandler(Consumer<Message> handler) {
        }
    }

    /** @return the shell's toast overlay, or {@code null} before the shell exists. */
    protected final ToastStack toasts() {
        return ScreenManager.getInstance().toasts();
    }
}
