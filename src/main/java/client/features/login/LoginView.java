package client.features.login;

import client.core.NavParams;
import client.core.ScreenManager;
import client.net.IClientConnection;
import client.ui.anim.Animations;
import client.ui.anim.Motion;
import client.ui.components.Buttons;
import client.ui.components.FormField;
import client.ui.components.Icons;
import client.ui.components.Logo;
import client.ui.components.StatusChip;
import client.ui.components.logic.ChipCatalog;
import client.ui.screen.AbstractScreen;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Optional;

/**
 * The sign-in screen (Presentation tier, E5.3 — F1.1).
 *
 * <p>The approved Login artboard: an accent brand panel on the left (logo, name,
 * tagline, what the system does) and a 400px card on the right holding the form.
 * Everything below the surface belongs to {@link LoginSession} — the state
 * machine, the error text, the caps-lock rule — so this class only builds nodes
 * and mirrors the session's state onto them.
 *
 * <p>Three details are deliberate rather than decorative: Enter submits from
 * either field (a login form the keyboard cannot finish is a daily irritation),
 * the button becomes its own progress state instead of an overlay (NFR-21 asks
 * for feedback on every async op, and the form is the thing that is busy), and
 * the connection chip stays visible so a failure to sign in is never confused
 * with a failure to reach the server.
 */
public final class LoginView extends AbstractScreen {

    private static final double BRAND_WIDTH = 620;
    private static final double CARD_WIDTH = 400;

    private LoginSession session;

    private FormField usernameField;
    private FormField passwordField;
    private Button signInButton;
    private Label buttonLabel;
    private ProgressIndicator spinner;
    private HBox capsRow;
    private HBox errorRow;
    private Label errorText;
    private VBox card;
    private HBox connectionRow;

    @Override
    protected Parent build() {
        session = new LoginSession(dispatcher(), onFxThread())
                .capsLockProbe(LoginView::probeCapsLock)
                .onChange(this::render);

        HBox page = new HBox(brandPanel(), formPanel());
        page.getStyleClass().add("hsts-page");
        return page;
    }

    @Override
    public void onShow(NavParams params) {
        // A cached screen is revisited after logout: nothing of the previous
        // attempt — typed name, error, spinner — may still be on display.
        session.reset();
        usernameField.textField().clear();
        passwordField.textField().clear();
        renderConnection();
        Animations.slideInY(card, true, 12, Motion.SLOW_MS);
        usernameField.textField().requestFocus();
    }

    @Override
    public void onHide() {
        Animations.stop(card);
    }

    // ------------------------------------------------------------ brand panel

    private VBox brandPanel() {
        Label name = new Label("HSTS");
        name.getStyleClass().add("brand-title");

        Label full = new Label("High School Test System");
        full.getStyleClass().add("brand-subtitle");

        Label tagline = new Label(
                "Build, approve, run and grade exams — with a study bot for every course.");
        tagline.getStyleClass().add("brand-tagline");
        tagline.setWrapText(true);
        tagline.setMaxWidth(420);

        VBox bullets = new VBox(12,
                bullet("One question bank, versioned — nothing is ever lost."),
                bullet("Timed executions the server owns, so a closed exam stays closed."),
                bullet("Grades released only after the teacher approves them."));

        // Hebrew course line: the app's UI is English (X-I18N) while the course
        // itself is Hebrew, and the footer is where that is honest.
        Label course = new Label("הנדסת תוכנה 203.3140 · Spring 2026");
        course.getStyleClass().add("brand-footnote");
        course.setNodeOrientation(javafx.geometry.NodeOrientation.RIGHT_TO_LEFT);

        VBox panel = new VBox(0,
                new VBox(14, Logo.create(52), name, full),
                spacer(28), tagline,
                spacer(28), bullets,
                Buttons.verticalSpacer(), course);
        panel.getStyleClass().add("hsts-brand-panel");
        panel.setPadding(new Insets(56, 56, 40, 56));
        panel.setPrefWidth(BRAND_WIDTH);
        panel.setMinWidth(340);
        panel.setMaxWidth(BRAND_WIDTH);
        panel.setAlignment(Pos.TOP_LEFT);
        return panel;
    }

    private HBox bullet(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("brand-bullet");
        label.setWrapText(true);
        label.setMaxWidth(400);

        HBox row = new HBox(10, Icons.of(Icons.CHECK, Icons.SIZE_DEFAULT, "brand-bullet-icon"), label);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    // ------------------------------------------------------------- form panel

    private StackPane formPanel() {
        Label title = new Label("Sign in");
        title.getStyleClass().add("h1");

        Label subtitle = new Label("Use the account your school gave you.");
        subtitle.getStyleClass().addAll("small", "muted");
        subtitle.setWrapText(true);

        usernameField = FormField.text("Username", "dana.cohen").required();
        passwordField = new FormField("Password", new PasswordField());
        passwordField.required();
        ((PasswordField) passwordField.control()).setPromptText("Your password");

        wireField(usernameField.textField());
        wireField(passwordField.textField());
        passwordField.textField().addEventFilter(KeyEvent.ANY, e -> renderCapsLock());
        passwordField.textField().focusedProperty().addListener((obs, was, is) -> renderCapsLock());

        capsRow = capsLockRow();
        errorRow = errorRow();
        signInButton = signInButton();

        card = new VBox(16, title, subtitle, usernameField, passwordField, capsRow, errorRow,
                signInButton);
        card.getStyleClass().add("hsts-card");
        card.setMaxWidth(CARD_WIDTH);
        card.setMinWidth(CARD_WIDTH);

        connectionRow = connectionRow();

        VBox column = new VBox(16, card, connectionRow);
        column.setAlignment(Pos.CENTER);
        column.setMaxWidth(CARD_WIDTH);

        StackPane panel = new StackPane(column);
        panel.setPadding(new Insets(40));
        HBox.setHgrow(panel, Priority.ALWAYS);
        return panel;
    }

    /** Enter submits from either field; typing clears a stale error. */
    private void wireField(TextField field) {
        field.setOnAction(e -> submit());
        field.textProperty().addListener((obs, old, value) -> {
            session.clearError();
            renderButton();
        });
    }

    private Button signInButton() {
        spinner = new ProgressIndicator();
        spinner.setPrefSize(14, 14);
        spinner.setMaxSize(14, 14);
        setShown(spinner, false);

        buttonLabel = new Label("Sign in");
        HBox content = new HBox(8, spinner, buttonLabel);
        content.setAlignment(Pos.CENTER);

        Button button = Buttons.styled("", Buttons.PRIMARY, Buttons.LARGE, Buttons.BLOCK);
        button.setGraphic(content);
        button.setDefaultButton(true);
        button.setDisable(true);
        button.setOnAction(e -> submit());
        return button;
    }

    private HBox capsLockRow() {
        Label label = new Label(LoginSession.CAPS_LOCK_WARNING);
        label.getStyleClass().addAll("small", "warn-text");

        HBox row = new HBox(6, Icons.of(Icons.WARNING, 13, "field-message-icon"), label);
        row.setAlignment(Pos.CENTER_LEFT);
        setShown(row, false);
        return row;
    }

    private HBox errorRow() {
        errorText = new Label();
        errorText.getStyleClass().add("banner-text");
        errorText.setWrapText(true);

        HBox row = new HBox(8, Icons.of(Icons.ERROR, Icons.SIZE_DEFAULT, "banner-icon"), errorText);
        row.getStyleClass().addAll("hsts-banner", "danger");
        row.setAlignment(Pos.CENTER_LEFT);
        setShown(row, false);
        return row;
    }

    private HBox connectionRow() {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER);
        return row;
    }

    // ---------------------------------------------------------------- actions

    private void submit() {
        session.submit(usernameField.text(), passwordField.text());
    }

    /** Renders the whole session state — one method, called on every change. */
    private void render() {
        boolean submitting = session.isSubmitting();
        buttonLabel.setText(submitting ? "Signing in…" : "Sign in");
        setShown(spinner, submitting);
        usernameField.control().setDisable(submitting);
        passwordField.control().setDisable(submitting);
        renderButton();

        setShown(errorRow, session.hasError());
        if (session.hasError()) {
            errorText.setText(session.errorMessage());
            Animations.slideInY(errorRow, true, 8, Motion.FAST_MS);
        }
        renderCapsLock();

        session.result().ifPresent(login -> ShellBoot.enter(ScreenManager.getInstance(), login));
    }

    private void renderButton() {
        signInButton.setDisable(!session.canSubmit(usernameField.text(), passwordField.text()));
    }

    private void renderCapsLock() {
        setShown(capsRow, session.isCapsLockOn());
    }

    /** Chip + endpoint, so "wrong password" is never confused with "no server". */
    private void renderConnection() {
        IClientConnection client = client();
        boolean connected = client != null && client.isConnectionOpen();
        String endpoint = client == null ? "" : client.getHost() + ":" + client.getPort();

        Label label = new Label(connected ? endpoint : "Not connected");
        label.getStyleClass().addAll("small", "muted");

        connectionRow.getChildren().setAll(
                new StatusChip(ChipCatalog.forConnection(connected ? "CONNECTED" : "DISCONNECTED")),
                label);
    }

    /**
     * The caps-lock probe. {@code Platform.isKeyLocked} answers empty where the
     * platform cannot tell (and throws before the toolkit exists), which is
     * exactly why {@link LoginSession} takes it as a seam.
     */
    private static Optional<Boolean> probeCapsLock() {
        try {
            return Platform.isKeyLocked(KeyCode.CAPS);
        } catch (RuntimeException | Error e) {
            return Optional.empty();
        }
    }

    private static Node spacer(double height) {
        javafx.scene.layout.Region region = new javafx.scene.layout.Region();
        region.setMinHeight(height);
        region.setPrefHeight(height);
        return region;
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
