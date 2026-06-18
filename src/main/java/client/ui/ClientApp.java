package client.ui;

import client.network.HSTSClient;
import javafx.application.Application;
import javafx.stage.Stage;

/**
 * JavaFX application entry point (Presentation tier).
 *
 * <p>Bootstraps the client: wires the {@link ScreenManager} singleton to the
 * primary stage, creates the OCSF-backed {@link HSTSClient} adapter for the Fat
 * Server at localhost:5555, then shows the {@link ConnectView}. The connect
 * screen opens the socket asynchronously and, on success, asks the
 * {@link ScreenManager} to swap to {@link QuestionsView}.
 *
 * <p>NOTE: Because this class extends {@link Application}, it must NOT be the
 * Fat JAR's Main-Class. The manifest Main-Class is {@link Launcher}, which calls
 * {@link #main(String[])} here, to bypass JavaFX module restrictions.
 */
public class ClientApp extends Application {

    private static final String SERVER_HOST = "localhost";
    private static final int    SERVER_PORT = 5555;

    private HSTSClient client;

    @Override
    public void start(Stage primaryStage) {
        ScreenManager manager = ScreenManager.getInstance();
        manager.init(primaryStage);

        // Create the network adapter (Adapter Pattern); ConnectView opens it.
        client = new HSTSClient(SERVER_HOST, SERVER_PORT);
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
