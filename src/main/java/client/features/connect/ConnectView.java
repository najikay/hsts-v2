package client.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Splash / connection screen (Presentation tier), defined in FXML.
 *
 * <p>The first screen shown by {@link ClientApp}. It opens the OCSF connection on
 * a background thread (so the FX thread never blocks), shows a spinner while it
 * waits, and on success asks the {@link ScreenManager} singleton to swap to
 * {@link QuestionsView}. On failure it surfaces an inline error and a Retry
 * button. This demonstrates the navigation controller swapping screens while
 * keeping the {@link AbstractScreenUI} Template Method lifecycle intact.
 */
public class ConnectView extends AbstractScreenUI {

    private static final String FXML_PATH = "/fxml/ConnectView.fxml";

    @FXML private StackPane logoBox;
    @FXML private ProgressIndicator spinner;
    @FXML private Label statusLabel;
    @FXML private Label errorLabel;
    @FXML private Button retryButton;

    @Override
    public Parent render() {
        FXMLLoader loader = new FXMLLoader(getClass().getResource(FXML_PATH));
        loader.setController(this);
        try {
            Parent root = loader.load();
            logoBox.getChildren().add(Logo.create(88));
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + FXML_PATH, e);
        }
    }

    @Override
    protected void onShown() {
        playEntrance();
        attemptConnect();
    }

    /** A subtle fade + scale-in so the splash feels deliberate, not abrupt. */
    private void playEntrance() {
        FadeTransition fade = new FadeTransition(Duration.millis(380), logoBox.getParent());
        fade.setFromValue(0.0);
        fade.setToValue(1.0);

        ScaleTransition scale = new ScaleTransition(Duration.millis(380), logoBox.getParent());
        scale.setFromX(0.96);
        scale.setFromY(0.96);
        scale.setToX(1.0);
        scale.setToY(1.0);
        scale.setInterpolator(Interpolator.EASE_OUT);

        new ParallelTransition(fade, scale).play();
    }

    @FXML
    private void onRetry() {
        attemptConnect();
    }

    /** Connects off the FX thread; routes the outcome back via Platform.runLater. */
    private void attemptConnect() {
        showConnecting();
        Thread t = new Thread(() -> {
            try {
                client().connect();
                Platform.runLater(this::onConnected);
            } catch (Exception e) {
                Platform.runLater(() -> showError(e));
            }
        }, "hsts-connect");
        t.setDaemon(true);
        t.start();
    }

    private void showConnecting() {
        spinner.setVisible(true);
        spinner.setManaged(true);
        statusLabel.setText("Connecting to server…");
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
        setNodeShown(errorLabel, false);
        setNodeShown(retryButton, false);
    }

    private void onConnected() {
        statusLabel.setText("Connected. Loading…");
        ScreenManager.getInstance().setScreen(new QuestionsView());
    }

    private void showError(Throwable e) {
        spinner.setVisible(false);
        spinner.setManaged(false);
        statusLabel.setText("Could not reach the HSTS server.");
        errorLabel.setText(client().getHost() + ":" + client().getPort()
                + " — is the server running?\n" + e.getMessage());
        setNodeShown(errorLabel, true);
        setNodeShown(retryButton, true);
    }

    private static void setNodeShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
