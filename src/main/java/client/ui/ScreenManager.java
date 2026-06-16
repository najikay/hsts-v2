package client.ui;

import client.network.IClientConnection;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Centralized navigation controller (Singleton Pattern) — the single source of
 * truth for routing between screens (Presentation tier).
 *
 * <p>Owns the primary {@link Stage} and the shared {@link IClientConnection} so
 * any screen can reach the network adapter without holding its own copy. New
 * screens inherit from {@link AbstractScreenUI} and are shown via
 * {@link #setScreen(AbstractScreenUI)}.
 */
public final class ScreenManager {

    private static ScreenManager instance;

    private Stage primaryStage;
    private IClientConnection client;

    private ScreenManager() {
    }

    /** @return the lazily-created singleton instance. */
    public static synchronized ScreenManager getInstance() {
        if (instance == null) {
            instance = new ScreenManager();
        }
        return instance;
    }

    /** Wires the JavaFX primary stage (called once from ClientApp). */
    public void init(Stage primaryStage) {
        this.primaryStage = primaryStage;
        this.primaryStage.setTitle("HSTS Prototype");
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

    /**
     * Renders the given screen and shows it on the primary stage. Uses the
     * screen's template {@code load()} so each screen's post-render hook fires.
     */
    public void setScreen(AbstractScreenUI screen) {
        Parent root = screen.load();
        Scene current = primaryStage.getScene();
        if (current == null) {
            primaryStage.setScene(new Scene(root));
        } else {
            current.setRoot(root);
        }
        if (!primaryStage.isShowing()) {
            primaryStage.show();
        }
    }
}
