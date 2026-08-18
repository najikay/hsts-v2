package client.core;

import client.core.ClientConfig.Settings;
import client.features.connect.ConnectView;
import client.net.HSTSClient;
import javafx.application.Application;
import javafx.stage.Stage;

/**
 * JavaFX application entry point (Presentation tier).
 *
 * <p>Bootstraps the client: wires the {@link ScreenManager} singleton to the
 * primary stage, creates the OCSF-backed {@link HSTSClient} adapter using
 * {@link ClientConfig}, then shows the {@link ConnectView}. The connect screen
 * opens the socket asynchronously and, on success, asks the
 * {@link ScreenManager} to swap to {@link client.features.bank.QuestionsView}.
 *
 * <p>NOTE: Because this class extends {@link Application}, it must NOT be the
 * client JAR's Main-Class. The manifest Main-Class is {@link ClientLauncher},
 * which calls {@link #main(String[])} here, to bypass JavaFX module restrictions.
 */
public class ClientApp extends Application {

    private HSTSClient client;

    @Override
    public void start(Stage primaryStage) {
        ScreenManager manager = ScreenManager.getInstance();
        manager.init(primaryStage);

        Settings settings = ClientConfig.load();
        System.out.println("[ClientApp] Connecting to " + settings.host() + ":" + settings.port());

        // Create the network adapter (Adapter Pattern); ConnectView opens it.
        client = new HSTSClient(settings.host(), settings.port());
        manager.setClient(client);

        manager.setScreen(new ConnectView());
    }

    @Override
    public void stop() {
        // Cleanly close the socket when the window is closed.
        if (client != null && client.isConnectionOpen()) {
            try {
                client.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
