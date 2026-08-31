package client.features.connect;

import client.core.ConnectPrefs;
import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.core.ServerEndpoint;
import client.core.ServerPin;
import client.ui.anim.Animations;
import client.ui.anim.Motion;
import client.ui.components.Buttons;
import client.ui.components.FormField;
import client.ui.components.Icons;
import client.ui.components.Logo;
import client.ui.components.WarnConfirm;
import client.ui.components.logic.FormValidator;
import client.ui.components.logic.ValidationState;
import client.ui.screen.AbstractScreen;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * Finding and connecting to a server (Presentation tier, E4.5 / E19.10 / E19.11,
 * F1.5 / F13.4).
 *
 * <h2>What E19.11 changed</h2>
 *
 * <p>This screen used to be the first thing anybody saw: two fields asking a
 * fifteen-year-old for an IP address. It is now a fallback. On a machine with a
 * pinned server it appears for the fraction of a second discovery takes and then
 * replaces itself with Login; the host and port editor is reached only when
 * discovery finds nothing and nothing is pinned, when the pinned server cannot be
 * reached, or when somebody clicks "change server" on Login.
 *
 * <p>All of that decision-making is {@link ConnectFlow}, which is pure and
 * exhaustively tested. This class runs discovery off the FX thread, renders one
 * of four states, and posts the outcome back through the one documented hop.
 *
 * <h2>The four states</h2>
 *
 * <ul>
 *   <li><b>Searching.</b> The brand mark and one line. No controls, because there
 *       is nothing useful to do for two seconds and offering a form here would
 *       invite typing that is about to be thrown away.</li>
 *   <li><b>Choosing.</b> One button per server found, each reading name, address
 *       and id, plus a link to the manual form.</li>
 *   <li><b>Manual.</b> The original host and port card, with a sentence saying why
 *       it is on screen, and a link back to the picker when there was one. That
 *       door used to open one way only: a user who chose "enter an address
 *       instead" and thought better of it had to re-run the two-second sweep to
 *       see the list she had just been reading.</li>
 *   <li><b>Connecting.</b> The button becomes its own progress state.</li>
 * </ul>
 *
 * <p>A fingerprint mismatch interrupts with a {@link WarnConfirm} before any
 * socket is opened, and the wording it shows is {@link ConnectFlow}'s, which says
 * what changed without claiming the client can detect an impostor.
 */
public final class ConnectView extends AbstractScreen {

    /** Navigation parameter: skip discovery and show the host and port editor. */
    public static final String PARAM_MANUAL = "manual";

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ConnectView.class);

    private static final String FIELD_HOST = "host";
    private static final String FIELD_PORT = "port";

    private final ConnectPrefs prefs;
    private final DiscoveryClient discovery;
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
    private VBox manualCard;
    private VBox searchingCard;
    private VBox pickerCard;
    private VBox pickerList;
    private Label pickerMessage;
    private Label manualMessage;
    private Hyperlink backToPicker;

    private volatile boolean connecting;
    private volatile boolean showing;

    /**
     * Flips the button copy to "Still trying" when a dial runs long (B-49
     * regression follow-up). The dial bound is generous on purpose - a first
     * connect through a firewall prompt can take ten seconds and still succeed -
     * and a label that changes is what tells the user the wait is progress
     * rather than a hang.
     */
    private javafx.animation.PauseTransition stillTrying;

    /**
     * The last set of servers the picker was given.
     *
     * <p>Kept so that declining a fingerprint warning goes back to the choice the
     * user was making rather than dumping them in the manual form: they said "not
     * that one", which is an answer about one server, not about all of them.
     */
    private List<DiscoveredServer> lastChoices = List.of();

    public ConnectView() {
        this(ConnectPrefs.userHome(), new DiscoveryClient());
    }

    /**
     * @param prefs     endpoint resolution, persistence and pinning; injected in tests
     * @param discovery the LAN sweep; injected in tests so no broadcast is sent
     */
    public ConnectView(ConnectPrefs prefs, DiscoveryClient discovery) {
        this.prefs = Objects.requireNonNull(prefs, "prefs");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
    }

    @Override
    protected Parent build() {
        searchingCard = buildSearchingCard();
        pickerCard = buildPickerCard();
        manualCard = buildManualCard();

        card = new VBox(searchingCard, pickerCard, manualCard);
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
        showing = true;
        Animations.slideInY(card, true, 12, Motion.SLOW_MS);
        if (params != null && params.getBoolean(PARAM_MANUAL, false)) {
            // "change server" on Login: the user asked for the editor, so do not
            // sweep the network and do not silently reconnect to what is pinned.
            prefs.unpin();
            showManual(ConnectFlow.changeServerRequested());
            return;
        }
        startDiscovery();
    }

    @Override
    public void onHide() {
        // A sweep started here can still be running; the flag is what stops its
        // result repainting a screen the user has already left.
        showing = false;
        Animations.stop(card);
    }

    // ------------------------------------------------------------- discovery

    /**
     * Sweeps the network off the FX thread, then applies {@link ConnectFlow}.
     *
     * <p>The poster is captured <b>here</b>, on the FX thread, rather than looked
     * up inside the worker. A discovery sweep takes about two seconds and the
     * screen it belongs to can be gone by the end of it (a user who clicked
     * straight through to manual entry, or a test that tore the manager down), at
     * which point {@code onFxThread()} has nothing to return. Capturing the hop
     * while it certainly exists is what keeps a daemon thread from throwing into
     * nobody's hands after its screen has left.
     */
    private void startDiscovery() {
        showSearching();
        Optional<ServerPin> pin = prefs.pinned();
        Thread worker = new Thread(background(poster ->
                poster.run(applyLater(ConnectFlow.decide(pin, discovery.discover())))),
                "hsts-discovery");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Wraps background work with the FX hop captured up front and every failure
     * contained.
     *
     * <p>Nothing a worker does may escape onto a daemon thread's default handler:
     * an exception there is invisible to the user, unrecoverable for the screen,
     * and (as this project found out) delivered to whichever unrelated test runs
     * next.
     */
    private Runnable background(java.util.function.Consumer<client.events.FxThreadPoster> work) {
        client.events.FxThreadPoster poster = onFxThread();
        return () -> {
            try {
                work.accept(poster);
            } catch (RuntimeException e) {
                // The screen may already be gone; there is nothing left to tell.
                LOG.debug("Background connect work ended early: {}", e.toString());
            }
        };
    }

    /** @return a runnable that applies {@code decision} if this screen is still live. */
    private Runnable applyLater(ConnectFlow.Decision decision) {
        return () -> {
            if (isBuilt() && showing) {
                apply(decision);
            }
        };
    }

    /** Renders one decision. The only branch in this class, and it has no rules. */
    private void apply(ConnectFlow.Decision decision) {
        switch (decision.step()) {
            case CONNECT -> decision.target().ifPresentOrElse(
                    endpoint -> connect(endpoint, decision.serverName(),
                            decision.fingerprintToPin().orElse(null)),
                    () -> showManual(ConnectFlow.changeServerRequested()));
            case CHOOSE_SERVER -> showPicker(decision);
            case CONFIRM_CHANGED_SERVER -> confirmChangedServer(decision);
            case MANUAL_ENTRY -> showManual(decision);
        }
    }

    private void confirmChangedServer(ConnectFlow.Decision decision) {
        boolean accepted = WarnConfirm.show(window(),
                WarnConfirm.spec(ConnectFlow.CHANGED_SERVER_TITLE)
                        .explanation(decision.message())
                        .confirmText("Connect anyway")
                        .cancelText("Choose another server")
                        .danger());
        if (accepted) {
            // Confirming re-pins: the user has said this machine is the right one
            // now, and asking again on every launch would train them to click past it.
            decision.target().ifPresent(endpoint ->
                    connect(endpoint, decision.serverName(), decision.fingerprint()));
            return;
        }
        List<DiscoveredServer> choices =
                decision.choices().isEmpty() ? lastChoices : decision.choices();
        showPicker(new ConnectFlow.Decision(ConnectFlow.Step.CHOOSE_SERVER, null, "", null,
                choices, ConnectFlow.PINNED_MISSING));
    }

    // ------------------------------------------------------------- rendering

    private void showSearching() {
        setShown(searchingCard, true);
        setShown(pickerCard, false);
        setShown(manualCard, false);
    }

    private void showPicker(ConnectFlow.Decision decision) {
        if (decision.choices().isEmpty()) {
            showManual(ConnectFlow.changeServerRequested());
            return;
        }
        pickerMessage.setText(decision.message());
        lastChoices = decision.choices();
        pickerList.getChildren().setAll(decision.choices().stream()
                .map(this::serverButton).toList());
        setShown(searchingCard, false);
        setShown(pickerCard, true);
        setShown(manualCard, false);
    }

    private void showManual(ConnectFlow.Decision decision) {
        manualMessage.setText(decision.message());
        setShown(manualMessage, !decision.message().isBlank());
        prefillFromPrefs();
        // Only offer the way back to a list the user has actually seen. On the paths
        // that reach this card without a picker — nothing answered, "change server" on
        // Login, a failed connect — there is no list to return to and a link promising
        // one would be a dead end.
        setShown(backToPicker, !lastChoices.isEmpty());
        setShown(searchingCard, false);
        setShown(pickerCard, false);
        setShown(manualCard, true);
        hostField.textField().requestFocus();
    }

    /**
     * Returns to the picker with the servers it last offered.
     *
     * <p>The rows and the sentence are the ones {@link #showPicker} last built, so this
     * re-offers the choice the user was actually making rather than whatever a fresh
     * sweep would find a moment later. A sweep is the answer only when there was never
     * a list, which the link's own visibility already rules out.
     */
    private void showLastChoices() {
        if (lastChoices.isEmpty()) {
            startDiscovery();
            return;
        }
        showPicker(new ConnectFlow.Decision(ConnectFlow.Step.CHOOSE_SERVER, null, "", null,
                lastChoices, pickerMessage.getText()));
    }

    private Node serverButton(DiscoveredServer server) {
        Button button = Buttons.styled(server.display(), Buttons.SECONDARY, Buttons.BLOCK);
        button.setOnAction(e -> apply(ConnectFlow.select(prefs.pinned(), server)));
        return button;
    }

    // ------------------------------------------------------------ connecting

    /** Pre-fills the manual fields from the resolved endpoint (F1.5's chain). */
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

    private void onConnectPressed() {
        if (connecting || !form.submit()) {
            hostField.apply(form.state(FIELD_HOST));
            portField.apply(form.state(FIELD_PORT));
            return;
        }
        // Typed by hand, so there is no announced fingerprint to pin. The client
        // pins what a server announced, never what a person typed.
        connect(ConnectPrefs.parse(form.value(FIELD_HOST), form.value(FIELD_PORT)), "", null);
    }

    /**
     * Opens the socket off the FX thread and reports back.
     *
     * @param fingerprint the id to pin on success, or {@code null} when the server
     *                    was reached without being discovered
     */
    private void connect(ServerEndpoint endpoint, String serverName, String fingerprint) {
        setConnecting(true);
        hideError();

        ScreenManager manager = ScreenManager.getInstance();
        // The dispatcher we already have goes in and the same one comes back out,
        // rebound to the new socket ⚑ U-17: screens built on an earlier connection
        // (Login above all) hold that instance for the life of the process.
        ConnectWiring.Wiring wiring = ConnectWiring.forEndpoint(
                endpoint, manager.eventBus(), manager.getDispatcher());
        manager.setClient(wiring.client());
        manager.setDispatcher(wiring.dispatcher());

        Thread worker = new Thread(background(poster -> {
            try {
                manager.getClient().connect();
                // A connect that returned is not a server ⚑ (B-49). The kernel
                // completes the handshake into a stopped server's backlog, so the
                // socket has to answer a question before this screen will believe it.
                ConnectHandshake.prove(manager.getDispatcher());
                poster.run(() -> onConnected(endpoint, serverName, fingerprint));
            } catch (Exception e) {
                // Open and unusable is worse than closed: discard it rather than
                // leave the app holding a socket the user was just told failed.
                ConnectWiring.abandon(manager.getClient());
                poster.run(() -> onFailed(endpoint, e));
            }
        }), "hsts-connect");
        worker.setDaemon(true);
        worker.start();
    }

    private void onConnected(ServerEndpoint endpoint, String serverName, String fingerprint) {
        // Only a connection that actually opened is worth remembering or trusting.
        prefs.remember(endpoint);
        if (fingerprint != null) {
            prefs.pin(endpoint, fingerprint, serverName);
        }
        setConnecting(false);
        // Connect → Login, never back: replace() so this screen does not sit behind
        // a session on the back-stack (F1.5 → F1.1).
        navigator().replace(Routes.LOGIN.id());
    }

    private void onFailed(ServerEndpoint endpoint, Throwable failure) {
        setConnecting(false);
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        // B-37: the throwable is logged and never rendered. Working out what to SAY about it
        // is ConnectFlow's, because that class is pure and its copy has tests; this screen
        // used to compute the sentence itself and put a Java class name on the first screen
        // anyone sees.
        LOG.warn("Connect to {} failed",
                endpoint == null ? "the remembered server" : endpoint.display(), cause);
        ConnectFlow.Decision fallback = ConnectFlow.afterFailedConnect(endpoint, cause);
        showManual(fallback);
        showError(fallback.message());
        hostField.apply(ValidationState.invalid("Could not reach this address"));
    }

    private void setConnecting(boolean active) {
        this.connecting = active;
        buttonLabel.setText(active ? "Connecting…" : "Connect");
        setShown(spinner, active);
        hostField.control().setDisable(active);
        portField.control().setDisable(active);
        refreshButton();
        if (stillTrying != null) {
            stillTrying.stop();
            stillTrying = null;
        }
        if (active) {
            stillTrying = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(4));
            stillTrying.setOnFinished(e -> {
                if (connecting) {
                    buttonLabel.setText("Still trying…");
                }
            });
            stillTrying.play();
        }
    }

    // ------------------------------------------------------------------ nodes

    private VBox buildSearchingCard() {
        ProgressIndicator searching = new ProgressIndicator();
        searching.setPrefSize(22, 22);
        searching.setMaxSize(22, 22);

        Label title = new Label("Looking for your server");
        title.getStyleClass().add("h2");
        Label subtitle = new Label("This takes a couple of seconds on the school network.");
        subtitle.getStyleClass().addAll("small", "muted");
        subtitle.setWrapText(true);

        Hyperlink manual = new Hyperlink("Enter an address instead");
        manual.setOnAction(e -> showManual(ConnectFlow.changeServerRequested()));

        HBox heading = new HBox(12, searching, title);
        heading.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(12, heading, subtitle, manual);
        box.getStyleClass().add("hsts-card");
        box.setId("connect-searching");
        return box;
    }

    private VBox buildPickerCard() {
        Label title = new Label("Choose your server");
        title.getStyleClass().add("h2");

        pickerMessage = new Label();
        pickerMessage.getStyleClass().addAll("small", "muted");
        pickerMessage.setWrapText(true);

        pickerList = new VBox(8);

        Hyperlink manual = new Hyperlink("Enter an address instead");
        manual.setOnAction(e -> showManual(ConnectFlow.changeServerRequested()));

        VBox box = new VBox(14, title, pickerMessage, pickerList, manual);
        box.getStyleClass().add("hsts-card");
        box.setId("connect-picker");
        setShown(box, false);
        return box;
    }

    private VBox buildManualCard() {
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

        manualMessage = new Label();
        manualMessage.getStyleClass().addAll("small", "muted");
        manualMessage.setWrapText(true);

        Hyperlink retry = new Hyperlink("Look for servers again");
        retry.setOnAction(e -> startDiscovery());

        backToPicker = new Hyperlink("Back to the server list");
        backToPicker.setOnAction(e -> showLastChoices());
        setShown(backToPicker, false);

        // The two ways out of this card belong on one line: the left one returns to what
        // the sweep already found, the right one sweeps again.
        HBox links = new HBox(12, backToPicker, retry);
        links.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(16, title, manualMessage, fields, errorBox, connectButton, links);
        box.getStyleClass().add("hsts-card");
        box.setId("connect-manual");
        setShown(box, false);
        return box;
    }

    private Button buildConnectButton() {
        spinner = new ProgressIndicator();
        spinner.setPrefSize(14, 14);
        spinner.setMaxSize(14, 14);
        setShown(spinner, false);

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

    private javafx.stage.Window window() {
        Parent root = view();
        return root == null || root.getScene() == null ? null : root.getScene().getWindow();
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
