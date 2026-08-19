package client.features.connect;

import client.core.ConnectPrefs;
import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.core.ServerEndpoint;
import client.ui.anim.Animations;
import client.ui.anim.Motion;
import client.ui.components.Buttons;
import client.ui.components.FormField;
import client.ui.components.Icons;
import client.ui.components.Logo;
import client.ui.components.logic.FormValidator;
import client.ui.components.logic.ValidationState;
import client.ui.screen.AbstractScreen;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * The connect screen (Presentation tier, E4.5, F1.5).
 *
 * <p>Rebuilt on the E4 design system: a centred card on the page background, the
 * brand mark above it, host and port as {@link FormField}s with inline
 * validation, and one primary button that becomes its own progress state. This
 * is the Login artboard's right-hand side; the brand panel that sits beside it
 * arrives with the Login screen in E5, which is why the card is a self-contained
 * node rather than a full-width layout.
 *
 * <p>Everything with a rule lives outside this class and is unit-tested:
 * {@link ConnectPrefs} resolves the pre-filled endpoint (last successful connect →
 * {@code client.properties} → {@code localhost:5555}) and validates the two
 * fields; {@link FormValidator} decides when the button is enabled and when a
 * field may show an error. What remains here is node building plus the one
 * genuinely FX-shaped concern: connecting on a background thread and posting the
 * outcome back through the documented FX hop.
 */
public final class ConnectView extends AbstractScreen {

    private static final String FIELD_HOST = "host";
    private static final String FIELD_PORT = "port";

    private final ConnectPrefs prefs;
    private final FormValidator form = new FormValidator()
            .field(FIELD_HOST, ConnectPrefs::validateHost)
            .field(FIELD_PORT, ConnectPrefs::validatePort);

    private FormField hostField;
    private FormField portField;
    private Button connectButton;
    private ProgressIndicator spinner;
    private Label buttonLabel;
    private HBox errorBox;
    private Label errorText;
    private VBox card;

    private volatile boolean connecting;

    public ConnectView() {
        this(ConnectPrefs.userHome());
    }

    /** @param prefs endpoint resolution + persistence; injected in tests */
    public ConnectView(ConnectPrefs prefs) {
        this.prefs = Objects.requireNonNull(prefs, "prefs");
    }

    @Override
    protected Parent build() {
        hostField = FormField.text("Server address", "localhost or 192.168.1.42").required();
        portField = FormField.text("Port", "5555").required();

        hostField.textField().textProperty().addListener((obs, old, value) -> onEdited(FIELD_HOST, value));
        portField.textField().textProperty().addListener((obs, old, value) -> onEdited(FIELD_PORT, value));
        // The hint hangs off the wide field: on the 120px port field it wraps to
        // four ragged lines and pushes the button down.
        hostField.hint("The server console shows the address and port to use.");

        HBox fields = new HBox(12, hostField, portField);
        HBox.setHgrow(hostField, javafx.scene.layout.Priority.ALWAYS);
        portField.setMaxWidth(120);
        portField.setPrefWidth(120);

        connectButton = buildConnectButton();
        errorBox = buildErrorBox();

        Label title = new Label("Connect to the HSTS server");
        title.getStyleClass().add("h2");
        Label subtitle = new Label("Enter the address your teacher or the server console shows.");
        subtitle.getStyleClass().addAll("small", "muted");
        subtitle.setWrapText(true);

        card = new VBox(16, title, subtitle, fields, errorBox, connectButton);
        card.getStyleClass().add("hsts-card");
        card.setMaxWidth(460);
        card.setMinWidth(420);

        VBox brand = new VBox(10, Logo.create(56), brandName());
        brand.setAlignment(Pos.CENTER);

        VBox column = new VBox(24, brand, card);
        column.setAlignment(Pos.CENTER);

        StackPane page = new StackPane(column);
        page.getStyleClass().add("hsts-page");
        page.setPadding(new javafx.geometry.Insets(40));
        return page;
    }

    @Override
    public void onShow(NavParams params) {
        prefillFromPrefs();
        Animations.slideInY(card, true, 12, Motion.SLOW_MS);
        hostField.textField().requestFocus();
    }

    @Override
    public void onHide() {
        Animations.stop(card);
    }

    // ------------------------------------------------------------------ state

    /** Pre-fills the fields from the resolved endpoint (F1.5's precedence chain). */
    private void prefillFromPrefs() {
        ServerEndpoint endpoint = prefs.resolve(client.core.ClientConfig.load());
        hostField.textField().setText(endpoint.host());
        portField.textField().setText(Integer.toString(endpoint.port()));
        form.set(FIELD_HOST, endpoint.host());
        form.set(FIELD_PORT, Integer.toString(endpoint.port()));
        hostField.clearValidation();
        portField.clearValidation();
        refreshButton();
    }

    private void onEdited(String field, String value) {
        form.set(field, value);
        (field.equals(FIELD_HOST) ? hostField : portField).apply(form.state(field));
        hideError();
        refreshButton();
    }

    private void refreshButton() {
        connectButton.setDisable(connecting || !form.isValid());
    }

    // ------------------------------------------------------------- connecting

    private void onConnectPressed() {
        if (connecting || !form.submit()) {
            hostField.apply(form.state(FIELD_HOST));
            portField.apply(form.state(FIELD_PORT));
            return;
        }
        ServerEndpoint endpoint = ConnectPrefs.parse(form.value(FIELD_HOST), form.value(FIELD_PORT));
        setConnecting(true);
        hideError();

        ScreenManager manager = ScreenManager.getInstance();
        ConnectWiring.Wiring wiring = ConnectWiring.forEndpoint(endpoint, manager.eventBus());
        manager.setClient(wiring.client());
        manager.setDispatcher(wiring.dispatcher());

        Thread worker = new Thread(() -> {
            try {
                manager.getClient().connect();
                onFxThread().run(() -> onConnected(endpoint));
            } catch (Exception e) {
                onFxThread().run(() -> onFailed(endpoint, e));
            }
        }, "hsts-connect");
        worker.setDaemon(true);
        worker.start();
    }

    private void onConnected(ServerEndpoint endpoint) {
        // Only a connection that actually opened is worth pre-filling next time.
        prefs.remember(endpoint);
        setConnecting(false);
        // Connect → Login, never back: replace() so the connect screen does not
        // sit behind a session on the back-stack (F1.5 → F1.1).
        navigator().replace(Routes.LOGIN.id());
    }

    private void onFailed(ServerEndpoint endpoint, Throwable failure) {
        setConnecting(false);
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        showError("Could not reach " + endpoint.display() + " — " + detail
                + ". Check the server is running and the address is right.");
        hostField.apply(ValidationState.invalid("Could not reach this address"));
    }

    private void setConnecting(boolean active) {
        this.connecting = active;
        buttonLabel.setText(active ? "Connecting…" : "Connect");
        spinner.setVisible(active);
        spinner.setManaged(active);
        hostField.control().setDisable(active);
        portField.control().setDisable(active);
        refreshButton();
    }

    // ------------------------------------------------------------------ nodes

    private Button buildConnectButton() {
        spinner = new ProgressIndicator();
        spinner.setPrefSize(14, 14);
        spinner.setMaxSize(14, 14);
        spinner.setVisible(false);
        spinner.setManaged(false);

        buttonLabel = new Label("Connect");
        HBox content = new HBox(8, spinner, buttonLabel);
        content.setAlignment(Pos.CENTER);

        Button button = Buttons.styled("", Buttons.PRIMARY, Buttons.LARGE, Buttons.BLOCK);
        button.setGraphic(content);
        button.setOnAction(e -> onConnectPressed());
        button.setDefaultButton(true);
        return button;
    }

    private HBox buildErrorBox() {
        errorText = new Label();
        errorText.getStyleClass().add("banner-text");
        errorText.setWrapText(true);

        HBox box = new HBox(8, Icons.of(Icons.ERROR, Icons.SIZE_DEFAULT, "banner-icon"), errorText);
        box.getStyleClass().addAll("hsts-banner", "danger");
        box.setAlignment(Pos.CENTER_LEFT);
        setShown(box, false);
        return box;
    }

    private void showError(String message) {
        errorText.setText(message);
        setShown(errorBox, true);
        Animations.slideInY(errorBox, true, 8, Motion.FAST_MS);
    }

    private void hideError() {
        setShown(errorBox, false);
    }

    private static Label brandName() {
        Label label = new Label("High School Test System");
        label.getStyleClass().addAll("small", "muted");
        return label;
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
