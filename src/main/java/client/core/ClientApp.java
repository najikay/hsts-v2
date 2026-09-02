package client.core;

import client.events.ClientEventBus;
import client.features.connect.ConnectView;
import client.features.login.LoginView;
import client.net.IClientConnection;
import client.ui.gallery.GalleryScreen;
import client.ui.screen.ScreenFactory;
import client.ui.theme.ThemeManager;
import client.ui.theme.ThemeState;
import javafx.application.Application;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaFX application entry point (Presentation tier, E4.1).
 *
 * <p>Bootstraps the client in one readable sequence: parse switches → build the
 * theme → wire the {@link ScreenManager} → declare the route table → show the
 * first screen. Nothing here decides anything; every rule it applies belongs to
 * a collaborator that is unit-tested on its own ({@link AppArgs},
 * {@link ThemeState}, {@link Navigator}, {@link ScreenCache}).
 *
 * <p>Note the network stack is <b>not</b> created here any more. The user picks
 * the endpoint on the connect screen (F1.5), so
 * {@code client.features.connect.ConnectWiring} builds the client and dispatcher
 * at the moment Connect is pressed — and rebuilds them if the user tries a
 * different address.
 *
 * <p>Because this class extends {@link Application} it must not be the JAR's
 * {@code Main-Class}; {@link ClientLauncher} is.
 */
public class ClientApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(ClientApp.class);

    @Override
    public void start(Stage primaryStage) {
        boot(primaryStage, resolveArgs());
    }

    /**
     * Reads the switches from the launch parameters, falling back to system
     * properties.
     *
     * <p>{@code getParameters()} returns {@code null} for an {@code Application}
     * that was constructed rather than {@code launch}ed — which is exactly how
     * the UI smoke test boots it — so the fallback is what makes the app
     * testable without a real launch.
     */
    AppArgs resolveArgs() {
        Parameters parameters = getParameters();
        return parameters == null
                ? AppArgs.parse(new String[0])
                : AppArgs.parse(parameters.getRaw().toArray(new String[0]));
    }

    /** The bootstrap sequence, with the switches already resolved. */
    void boot(Stage primaryStage, AppArgs args) {
        ClientEventBus eventBus = ClientEventBus.getInstance();

        // Theme first: the gallery runs on a throwaway store so a developer
        // playing with palettes there never rewrites their real preferences.
        ThemeState themeState = args.gallery()
                ? ThemeState.ephemeral(eventBus)
                : ThemeState.userHome(eventBus);
        ThemeManager themeManager = new ThemeManager(themeState);

        ScreenManager manager = ScreenManager.getInstance();
        manager.init(primaryStage, eventBus, themeManager);

        // 2026-09-02, U-94: ThemeMode.SYSTEM follows the OS light/dark setting, and
        // ThemeState.refreshSystem() is built to re-probe "on window focus" - but nothing
        // was calling it, so SYSTEM probed once at launch and never again, which read as
        // "System does nothing". Wired here: regaining focus re-checks the OS, and in SYSTEM
        // mode a flip fires the theme event and re-applies every scene. Harmless in the
        // explicit modes (the probe is recorded, nothing on screen changes).
        primaryStage.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                themeState.refreshSystem();
            }
        });

        if (args.gallery()) {
            log.info("Booting the design-system gallery (--gallery)");
            manager.showStandalone(new GalleryScreen(themeState));
            return;
        }

        registerRoutes(manager);
        manager.navigator().navigate(Routes.CONNECT.id());
    }

    /**
     * Declares the routes that exist <b>before</b> anyone signs in.
     *
     * <p>Everything else is registered once the role is known, by
     * {@link SessionRoutes} — a student's client never learns that a teacher's
     * routes exist. Adding a pre-login screen is one line here; adding a
     * signed-in screen is one line there.
     */
    static void registerRoutes(ScreenManager manager) {
        Routes.registerPreLogin(manager.navigator());

        ScreenFactory screens = manager.screens();
        screens.register(Routes.CONNECT.id(), ConnectView::new);
        screens.register(Routes.LOGIN.id(), LoginView::new);
    }

    @Override
    public void stop() {
        IClientConnection client = ScreenManager.getInstance().getClient();
        if (client != null && client.isConnectionOpen()) {
            try {
                client.disconnect();
            } catch (Exception e) {
                log.debug("Ignoring error while closing the connection: {}", e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
