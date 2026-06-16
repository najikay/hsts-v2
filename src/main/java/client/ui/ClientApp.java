package client.ui;

import client.network.HSTSClient;
import javafx.application.Application;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

/**
 * JavaFX application entry point (Presentation tier).
 *
 * <p>Bootstraps the client: wires the {@link ScreenManager} singleton to the
 * primary stage, opens the OCSF-backed {@link HSTSClient} adapter to the Fat
 * Server at localhost:5555, then loads the {@link QuestionsView}.
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

        // Open the network adapter (Adapter Pattern) to the Fat Server.
        client = new HSTSClient(SERVER_HOST, SERVER_PORT);
        manager.setClient(client);

        try {
            client.connect();
        } catch (Exception e) {
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Could not connect to the HSTS server at "
                            + SERVER_HOST + ":" + SERVER_PORT
                            + "\n\nIs ServerMain running?\n\n" + e.getMessage());
            alert.setHeaderText("Connection failed");
            alert.showAndWait();
            // Still show the (empty) screen so the window isn't blank-on-crash.
        }

        manager.setScreen(new QuestionsView());
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
