package client.core;

import client.events.ClientEventBus;
import client.net.IClientConnection;
import client.net.RequestDispatcher;
import client.ui.anim.Animations;
import client.ui.anim.Motion;
import client.ui.components.Logo;
import client.ui.components.ToastStack;
import client.ui.screen.AbstractScreen;
import client.ui.screen.ScreenFactory;
import client.ui.shell.AppShell;
import client.ui.theme.ThemeManager;
import common.dto.auth.LoginResult;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Owns the primary {@link Stage} and turns navigation events into scene changes
 * (Presentation tier, E4.2, ARCHITECTURE §6).
 *
 * <p>Deliberately thin. Everything with a rule in it has been moved out:
 * <ul>
 *   <li><b>where we are and where we can go back to</b> → {@link Navigator};</li>
 *   <li><b>which screen object serves a route</b> → {@link ScreenFactory}
 *       (over {@link ScreenCache});</li>
 *   <li><b>when a screen is on the bus</b> → {@link ScreenLifecycle};</li>
 *   <li><b>what anything looks like</b> → {@link ThemeManager}.</li>
 * </ul>
 * What is left here is the part that genuinely needs a {@code Stage}: swap the
 * root node, put shell-hosted screens inside the shell and full-bleed ones
 * (Connect, Login, exam takeovers) directly on the scene, and play the entrance
 * transition.
 *
 * <p>Still a Singleton so any screen can reach shared collaborators without
 * threading them through constructors — the accessors are what
 * {@code AbstractScreen} exposes to subclasses.
 */
public final class ScreenManager {

    private static final Logger log = LoggerFactory.getLogger(ScreenManager.class);

    private static ScreenManager instance;

    private final Navigator navigator = new Navigator();
    private final ScreenFactory screens = new ScreenFactory();

    private Stage primaryStage;
    private Scene scene;
    private ClientEventBus eventBus;
    private ScreenLifecycle lifecycle;
    private ThemeManager themeManager;
    private AppShell shell;
    private IClientConnection client;
    private RequestDispatcher dispatcher;
    private LoginResult signedInUser;

    private ScreenManager() {
    }

    /** @return the lazily-created singleton. */
    public static synchronized ScreenManager getInstance() {
        if (instance == null) {
            instance = new ScreenManager();
        }
        return instance;
    }

    /**
     * Discards the singleton. Test-only seam: the manager holds a Stage and a
     * navigator, and one test's state must not leak into the next.
     *
     * <p>The screen that is on screen is <b>hidden first</b>, and that is not
     * tidiness. Screens that started background work keep a "am I still live?"
     * flag which {@code onHide} clears; {@code ConnectView}'s two-second
     * discovery sweep is the one that bites. Dropping the singleton without
     * hiding leaves that flag true, so a sweep landing after the teardown
     * believes it is still on screen, asks a brand-new (uninitialised) manager
     * for its event bus and gets {@code null}. Hiding here is what makes the
     * late work a no-op instead of an exception thrown into the next test.
     *
     * <p>Callers must run this on the FX thread — {@code onHide} stops
     * animations — and should drain pending {@code Platform.runLater} work
     * first; {@code client.core.FxTestHarness} does both.
     */
    static synchronized void resetForTests() {
        if (instance != null && instance.lifecycle != null) {
            try {
                instance.lifecycle.hideCurrent();
            } catch (RuntimeException e) {
                // Teardown must complete even if a screen's onHide misbehaves;
                // the alternative is a leaked singleton poisoning every test after.
                log.debug("A screen failed to hide during test teardown: {}", e.toString());
            }
        }
        instance = null;
    }

    /**
     * Wires the primary stage and the collaborators, and subscribes to the
     * navigator. Called once from {@code ClientApp}.
     */
    public void init(Stage primaryStage, ClientEventBus eventBus, ThemeManager themeManager) {
        this.primaryStage = Objects.requireNonNull(primaryStage, "primaryStage");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.themeManager = Objects.requireNonNull(themeManager, "themeManager");
        this.lifecycle = new ScreenLifecycle(eventBus);

        primaryStage.setTitle("HSTS · High School Test System");
        primaryStage.setMinWidth(1024);
        primaryStage.setMinHeight(680);
        try {
            primaryStage.getIcons().add(Logo.snapshotImage(128));
        } catch (RuntimeException e) {
            // A missing window icon is cosmetic; never block startup over branding.
            log.debug("Could not render the window icon: {}", e.getMessage());
        }
        navigator.addListener(this::onNavigated);
    }

    // ---------------------------------------------------------- collaborators

    /** @return the route registry / back-stack. */
    public Navigator navigator() {
        return navigator;
    }

    /** @return the screen builder + cache. */
    public ScreenFactory screens() {
        return screens;
    }

    /** @return the app-wide event bus. */
    public ClientEventBus eventBus() {
        return eventBus;
    }

    /** @return the theme applier. */
    public ThemeManager themeManager() {
        return themeManager;
    }

    public Stage getPrimaryStage() {
        return primaryStage;
    }

    public void setClient(IClientConnection client) {
        this.client = client;
    }

    public IClientConnection getClient() {
        return client;
    }

    public void setDispatcher(RequestDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public RequestDispatcher getDispatcher() {
        return dispatcher;
    }

    /**
     * Records who is signed in (E5.4). The dashboards read the display name and
     * the course list from here rather than being handed them as navigation
     * parameters, because every screen from E6 on needs them and threading the
     * same object through every route would be noise.
     *
     * <p>It is deliberately a plain field on the manager and not a second
     * singleton: {@link #clearShell()} and this are cleared together on logout,
     * which is exactly the property that matters.
     */
    public void setSignedInUser(LoginResult user) {
        this.signedInUser = user;
    }

    /** @return the signed-in user, or {@code null} before login / after logout. */
    public LoginResult signedInUser() {
        return signedInUser;
    }

    /** Installs the app shell; shell-hosted routes render inside it from then on. */
    public void setShell(AppShell shell) {
        this.shell = shell;
    }

    /**
     * Removes the app shell (logout, E5.7).
     *
     * <p>Dropping the reference is what makes the next navigation render
     * full-bleed again, and — with {@code ScreenFactory.evictAll()} — what
     * guarantees the previous user's rail, avatar and toasts cannot reappear
     * behind the login screen. The scene root is not touched here: the navigation
     * to Login that follows replaces it anyway, and clearing it first would flash
     * an empty window.
     */
    public void clearShell() {
        this.shell = null;
    }

    /** @return the app shell, or {@code null} before login. */
    public AppShell shell() {
        return shell;
    }

    /** @return the shell's toast overlay, or {@code null} before the shell exists. */
    public ToastStack toasts() {
        return shell == null ? null : shell.toasts();
    }

    // -------------------------------------------------------------- rendering

    /**
     * Reacts to a navigation: fetch (or build) the screen, run its lifecycle,
     * and put its node where the route says it belongs.
     */
    private void onNavigated(NavigationEvent event) {
        NavEntry target = event.to();
        AbstractScreen screen = screens.get(target.routeId());

        // Build BEFORE the lifecycle runs: onShow populates the very nodes
        // build() creates, so a first visit would otherwise hand a screen its
        // parameters while all its fields are still null.
        Parent view = screen.view();
        lifecycle.show(screen, target.params());

        if (target.route().requiresShell() && shell != null) {
            mountShell();
            shell.setContent(view);
        } else {
            setRoot(view);
            Animations.fadeIn(view, Motion.BASE_MS);
        }
        primaryStage.setTitle("HSTS · " + target.route().title());
        log.debug("navigated {} → {}", event.previous().map(NavEntry::routeId).orElse("(none)"),
                target.routeId());
    }

    private void mountShell() {
        if (scene == null || scene.getRoot() != shell) {
            setRoot(shell);
        }
    }

    /**
     * Installs a root node, creating the {@link Scene} on first use and asking
     * the {@link ThemeManager} to style it. Reusing one Scene across navigations
     * (rather than building a new one per screen) is what keeps the stylesheets,
     * the window size and the user's manual resize intact between screens.
     */
    private void setRoot(Parent root) {
        if (scene == null) {
            scene = new Scene(root, 1280, 800);
            themeManager.attach(scene);
            primaryStage.setScene(scene);
        } else {
            scene.setRoot(root);
            themeManager.applyDarkClass(root);
        }
        if (!primaryStage.isShowing()) {
            primaryStage.show();
        }
    }

    /**
     * Shows a screen that is not part of the route table — the {@code --gallery}
     * dev screen, and anything a future tool needs. Bypasses the navigator on
     * purpose: it has no route, so it must not land on the back-stack.
     */
    public void showStandalone(AbstractScreen screen) {
        Objects.requireNonNull(screen, "screen");
        Parent view = screen.view();
        lifecycle.show(screen, NavParams.empty());
        setRoot(view);
    }

    /** @return the live scene, or {@code null} before the first screen is shown. */
    public Scene scene() {
        return scene;
    }
}
