package client.features.login;

import client.core.ConnectPrefs;
import client.core.NavParams;
import client.core.Routes;
import client.core.ServerEndpoint;
import client.events.ConnectionLostEvent;
import client.features.connect.ConnectFlow;
import client.features.connect.ConnectView;
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
import javafx.scene.control.Hyperlink;
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
import org.greenrobot.eventbus.Subscribe;

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

    /**
     * Navigation parameter: the username to pre-fill (⚑ U-52).
     *
     * <p>Set by the shell when a reconnect drops her back here. She did not choose
     * to sign out, so re-typing the name she was already signed in with is friction
     * this screen has no reason to add.
     */
    public static final String PARAM_USERNAME = "username";

    /**
     * Navigation parameter: a sentence to show above the form (⚑ U-52).
     *
     * <p>The copy is the caller's — {@code ConnectFlow.RECONNECTED_SIGN_IN_AGAIN}
     * today — because the reason she is looking at this screen is not something
     * the screen itself knows.
     */
    public static final String PARAM_NOTICE = "notice";

    /** The status line when there is no server to name. */
    private static final String NOT_CONNECTED = "Not connected";

    /** The one thing to do about that; it goes to the connect screen. */
    private static final String RECONNECT = "Reconnect";

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
    private HBox noticeRow;
    private Label noticeText;
    private VBox card;
    private HBox connectionRow;

    /**
     * Whether the server was reachable the last time this screen looked ⚑.
     *
     * <p>2026-08-28, manual round 1, lead's ruling. The status row was computed once, in
     * {@code build()}, and a screen is built once: kill the server in front of a signed-out
     * client and the chip went on saying Connected while every sign-in attempt timed out.
     * The row is now recomputed on every visit and on {@link ConnectionLostEvent}, and this
     * field is what the button reads, because a form that cannot reach anything must not
     * look ready.
     */
    private boolean connectionUp = true;

    @Override
    protected Parent build() {
        // The dispatcher is captured once, here, and this screen is cached for the life of
        // the process ⚑. That is safe because a reconnect rebinds the dispatcher rather than
        // replacing it (2026-08-29, manual round 2, U-17: it used to replace it, and this
        // form went on writing to a socket the restarted server had already dropped).
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

        // The reconnect route arrives with both of these (⚑ U-52): she was signed in a
        // moment ago and did not ask to leave, so her name comes back with her and the
        // sentence says why the dashboard turned into a login form.
        String username = params == null ? "" : params.getString(PARAM_USERNAME, "");
        usernameField.textField().setText(username);
        showNotice(params == null ? "" : params.getString(PARAM_NOTICE, ""));

        // Asked of the manager rather than assumed ⚑ (2026-08-30, Findings.txt, U-52).
        // This used to be an unconditional `true`, so signing out after the client lost
        // the network produced a status row that said Connected on a socket nobody could
        // reach: the adapter still answered isConnectionOpen(), and the drop that had
        // already been announced was forgotten the moment the screen was shown again.
        connectionUp = ScreenManager.getInstance().isConnectionAlive();
        renderConnection();
        Animations.slideInY(card, true, 12, Motion.SLOW_MS);
        if (username.isBlank()) {
            usernameField.textField().requestFocus();
        } else {
            passwordField.textField().requestFocus();
        }
    }

    @Override
    public void onHide() {
        Animations.stop(card);
    }

    /**
     * Yes, and this is the pre-shell screen that says so.
     *
     * <p>{@code ScreenLifecycle} registers a screen on the bus exactly while it is visible,
     * and it does that for every route rather than only for the ones inside the shell: the
     * navigation that shows Login runs the same {@code show()}. So one override and one
     * {@code @Subscribe} method are the whole subscription, and the unregister that pairs
     * with it is the framework's rather than this class's.
     */
    @Override
    public boolean listensToEvents() {
        return true;
    }

    /**
     * The socket dropped while she was looking at the form (E4.6 — lead's ruling ⚑).
     *
     * <p>Delivered on the FX thread by the bus, so the row is repainted here directly. What
     * she gets is the truth and a way out of it: the chip flips, the line stops naming a
     * server that is not there, the button that would only time out is disabled, and the
     * link goes to the connect screen.
     *
     * @param event the lost connection, named by the endpoint it was talking to
     */
    @Subscribe
    public void onConnectionLost(ConnectionLostEvent event) {
        connectionUp = false;
        renderConnection();
    }

    // ------------------------------------------------------------ brand panel

    private VBox brandPanel() {
        Label name = new Label("HSTS");
        name.getStyleClass().add("brand-title");

        Label full = new Label("High School Test System");
        full.getStyleClass().add("brand-subtitle");

        Label tagline = new Label(
                "Build, approve, run and grade exams, with a study bot for every course.");
        tagline.getStyleClass().add("brand-tagline");
        tagline.setWrapText(true);
        tagline.setMaxWidth(420);

        VBox bullets = new VBox(12,
                bullet("One question bank, versioned. Nothing is ever lost."),
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
        noticeRow = noticeRow();
        signInButton = signInButton();

        card = new VBox(16, title, subtitle, noticeRow, usernameField, passwordField, capsRow,
                errorRow, signInButton);
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

    /**
     * The neutral strip that says why she is here (⚑ U-52).
     *
     * <p>Deliberately not the error row: a reconnect that worked is good news with a
     * chore attached, and painting it red would read as a failed sign-in she never
     * attempted.
     */
    private HBox noticeRow() {
        noticeText = new Label();
        noticeText.getStyleClass().add("banner-text");
        noticeText.setWrapText(true);

        HBox row = new HBox(8, Icons.of(Icons.INFO, Icons.SIZE_DEFAULT, "banner-icon"), noticeText);
        row.getStyleClass().add("hsts-banner");
        row.setAlignment(Pos.CENTER_LEFT);
        setShown(row, false);
        return row;
    }

    private void showNotice(String message) {
        boolean has = message != null && !message.isBlank();
        noticeText.setText(has ? message : "");
        setShown(noticeRow, has);
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

        if (submitting) {
            // The reconnect sentence has done its job the moment she acts on it.
            showNotice("");
        }
        setShown(errorRow, session.hasError());
        if (session.hasError()) {
            errorText.setText(session.errorMessage());
            Animations.slideInY(errorRow, true, 8, Motion.FAST_MS);
        }
        renderCapsLock();

        session.result().ifPresent(login -> ShellBoot.enter(ScreenManager.getInstance(), login));
    }

    private void renderButton() {
        signInButton.setDisable(!connectionUp
                || !session.canSubmit(usernameField.text(), passwordField.text()));
    }

    private void renderCapsLock() {
        setShown(capsRow, session.isCapsLockOn());
    }

    /** Chip + endpoint, so "wrong password" is never confused with "no server". */
    /**
     * The one line of connection user interface a signed-out user sees (E19.11).
     *
     * <p>The lead's ruling: with a pinned reachable server the first screen is
     * Login, "carrying only a subtle status line". So this is a status line and a
     * link, not a panel: which server, and a way to change it. Host and port live
     * on the server console, not in a student's face.
     *
     * <p>The sentence is {@link ConnectFlow#statusLine}, so the name preferred
     * over the address, and the fallback when neither is known, are decided in a
     * tested class rather than here.
     */
    private void renderConnection() {
        IClientConnection client = client();
        // isConnectionAlive() and not isConnectionOpen() ⚑ U-52: a client that lost the
        // network answers the second one with a stale true, because OCSF only notices a
        // dead socket when a read fails. The manager also remembers the drop.
        boolean connected = connectionUp && ScreenManager.getInstance().isConnectionAlive();
        connectionUp = connected;
        ServerEndpoint endpoint = connected
                ? new ServerEndpoint(client.getHost(), client.getPort()) : null;

        Label label = new Label(connected
                ? ConnectFlow.statusLine(ConnectPrefs.userHome().pinnedName().orElse(null), endpoint)
                : NOT_CONNECTED);
        label.getStyleClass().addAll("small", "muted");

        // Two different offers, because they answer two different questions. With a server
        // there, the only useful thing is to point somewhere else; with no server there,
        // it is to get back to one, and the connect screen is where both of those happen.
        Hyperlink action = new Hyperlink(connected
                ? ConnectFlow.changeServerLabel() : RECONNECT);
        action.getStyleClass().add("small");
        action.setOnAction(e -> {
            if (connected) {
                navigator().navigate(Routes.CONNECT.id(),
                        NavParams.of(ConnectView.PARAM_MANUAL, true));
            } else {
                navigator().navigate(Routes.CONNECT.id());
            }
        });

        Label separator = new Label("·");
        separator.getStyleClass().addAll("small", "faint");

        connectionRow.getChildren().setAll(
                new StatusChip(ChipCatalog.forConnection(connected ? "CONNECTED" : "DISCONNECTED")),
                label, separator, action);
        renderButton();
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
