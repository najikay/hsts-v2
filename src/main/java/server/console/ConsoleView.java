package server.console;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import server.core.SessionManager;
import client.ui.components.WarnConfirm;
import server.db.seed.Confirmation;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * The server console window (Presentation, E19.2 to E19.7 / F13.1).
 *
 * <p>A thin view, and thin in the specific sense the coverage policy means: it
 * builds nodes, wires them to {@link ConsoleSession} and repaints on a timer.
 * Every decision it renders is made somewhere measured. What the header says is
 * {@link ConsoleModel}; what a button does and what it answers is
 * {@link ConsoleSession}; what the cards read is {@link HealthSnapshot}; what the
 * table's cells contain is {@link ConsoleClients}; what the log pane shows is
 * {@link LogTailModel}. This class contains no {@code if} that decides anything a
 * person could disagree with.
 *
 * <h2>Layout</h2>
 *
 * <p>Top to bottom, in the order an operator uses them: the address, big enough
 * to read from the back of the room, with copy, the address override and the
 * discovery id beside it; the listener control; four status cards; then the two
 * live panes, clients and log, side by side.
 *
 * <h2>Repainting</h2>
 *
 * <p>One JavaFX {@link Timeline} at {@link #REFRESH_SECONDS}, not a thread per
 * pane. The health probe, the client table and the log tail all read cheap
 * in-memory state (the database probe is one {@code SELECT 1}), so a single
 * one-second tick is both simpler and less work than three subscriptions.
 *
 * <p>The one genuine push is {@link SessionManager}'s session listener, which
 * fires on an OCSF thread when somebody signs in or out. It hops to the FX thread
 * here, which is the same single documented crossing point the client uses.
 */
public final class ConsoleView {

    /** How often the cards, the table and the log pane repaint. */
    static final int REFRESH_SECONDS = 1;

    private final ConsoleSession session;
    private final ConsoleModel model;
    private final ConsoleClients clients;
    private final SessionManager sessions;
    private final LogTailModel logTail;
    private final Clock clock;

    private final ObservableList<ConsoleClients.Row> clientRows = FXCollections.observableArrayList();
    private final ObservableList<String> logLines = FXCollections.observableArrayList();

    private Label addressLabel;
    private Label fingerprintLabel;
    private Label listenStatus;
    private Label actionMessage;
    private Button listenButton;
    private CheckBox discoveryToggle;
    private ComboBox<String> addressPicker;
    private ComboBox<LogLevel> levelPicker;
    private TextField logSearch;
    private Button pauseButton;
    private Label logStatus;
    private Label emptyClients;

    private Label databaseValue;
    private Label databaseDetail;
    private Label clientsValue;
    private Label clientsDetail;
    private Label memoryValue;
    private Label memoryDetail;
    private Label providersValue;
    private Label providersDetail;

    private Timeline refresh;
    private SessionManager.SessionListener sessionListener;
    private BorderPane root;

    public ConsoleView(ConsoleSession session, ConsoleClients clients, SessionManager sessions,
                       LogTailModel logTail, Clock clock) {
        this.session = Objects.requireNonNull(session, "session");
        this.model = session.model();
        this.clients = Objects.requireNonNull(clients, "clients");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.logTail = Objects.requireNonNull(logTail, "logTail");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @return the console's root node, ready to put in a scene. */
    public Region build() {
        root = new BorderPane();
        root.getStyleClass().add("hsts-page");
        root.setPadding(new Insets(20));
        root.setTop(new VBox(16, header(), listenerRow(), cards(), messageRow()));
        root.setCenter(panes());
        BorderPane.setMargin(root.getCenter(), new Insets(16, 0, 0, 0));
        refreshAll();
        return root;
    }

    /** Starts the repaint timer and subscribes to session changes. */
    public void start() {
        sessionListener = () -> Platform.runLater(this::refreshClients);
        sessions.addSessionListener(sessionListener);
        refresh = new Timeline(new KeyFrame(Duration.seconds(REFRESH_SECONDS), e -> refreshAll()));
        refresh.setCycleCount(Animation.INDEFINITE);
        refresh.play();
    }

    /** Stops the timer and unsubscribes. Called when the window closes. */
    public void stop() {
        if (refresh != null) {
            refresh.stop();
        }
        if (sessionListener != null) {
            sessions.removeSessionListener(sessionListener);
        }
    }

    // ===================== Header ========================================

    private Region header() {
        addressLabel = new Label(model.headerText());
        addressLabel.getStyleClass().add("console-address");
        addressLabel.setStyle("-fx-font-size: 40px; -fx-font-weight: bold;");

        Button copy = plainButton("Copy", "secondary");
        copy.setId("console-copy");
        copy.setTooltip(new Tooltip("Copy the address to the clipboard"));
        copy.setOnAction(e -> copyAddress());

        fingerprintLabel = new Label(model.fingerprintText());
        fingerprintLabel.getStyleClass().addAll("small", "muted");

        addressPicker = new ComboBox<>();
        addressPicker.setId("console-address-picker");
        addressPicker.setEditable(true);
        addressPicker.setPrefWidth(280);
        addressPicker.setOnAction(e -> applyAddress());
        addressPicker.getEditor().setOnAction(e -> applyAddress());

        Label pickerLabel = new Label("Show a different address");
        pickerLabel.getStyleClass().addAll("small", "muted");

        VBox override = new VBox(4, pickerLabel, addressPicker);
        override.setAlignment(Pos.CENTER_LEFT);

        HBox line = new HBox(16, addressLabel, copy, spacer(), override);
        line.setAlignment(Pos.CENTER_LEFT);

        Label caption = new Label("Point clients at this address");
        caption.getStyleClass().addAll("small", "muted");

        VBox box = new VBox(6, caption, line, fingerprintLabel);
        box.getStyleClass().add("hsts-card");
        return box;
    }

    private Region listenerRow() {
        listenButton = plainButton(model.listenButtonText(), "primary");
        listenButton.setId("console-listen");
        listenButton.setOnAction(e -> show(session.toggleListening()));

        listenStatus = new Label(model.listenStatusText());
        listenStatus.getStyleClass().addAll("small", "muted");
        listenStatus.setWrapText(true);

        discoveryToggle = new CheckBox("Answer discovery broadcasts");
        discoveryToggle.setId("console-discovery");
        discoveryToggle.setSelected(model.isDiscoveryEnabled());
        discoveryToggle.setOnAction(e -> show(session.toggleDiscovery()));

        Button loadSeed = plainButton("Load demo data if missing", "secondary");
        loadSeed.setId("console-seed-load");
        loadSeed.setOnAction(e -> show(session.loadSeedIfMissing()));

        Button reseed = plainButton("Reload demo data", "warn");
        reseed.setId("console-seed-reseed");
        reseed.setOnAction(e -> show(session.reseed(dialogConfirmation())));

        HBox row = new HBox(12, listenButton, discoveryToggle, spacer(), loadSeed, reseed);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, row, listenStatus);
    }

    private Region messageRow() {
        actionMessage = new Label();
        actionMessage.getStyleClass().addAll("small", "muted");
        actionMessage.setWrapText(true);
        actionMessage.setId("console-message");
        return actionMessage;
    }

    // ===================== Cards =========================================

    private Region cards() {
        databaseValue = new Label();
        databaseDetail = new Label();
        clientsValue = new Label();
        clientsDetail = new Label();
        memoryValue = new Label();
        memoryDetail = new Label();
        providersValue = new Label();
        providersDetail = new Label();

        HBox row = new HBox(12,
                card("Database", databaseValue, databaseDetail),
                card("Clients", clientsValue, clientsDetail),
                card("Memory", memoryValue, memoryDetail),
                card("Study bot", providersValue, providersDetail));
        row.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));
        return row;
    }

    private static Region card(String title, Label value, Label detail) {
        Label heading = new Label(title);
        heading.getStyleClass().addAll("small", "muted");
        value.getStyleClass().add("h2");
        detail.getStyleClass().addAll("small", "faint");
        detail.setWrapText(true);
        VBox box = new VBox(4, heading, value, detail);
        box.getStyleClass().addAll("hsts-card", "compact");
        return box;
    }

    // ===================== Panes =========================================

    private Region panes() {
        HBox row = new HBox(16, clientsPane(), logPane());
        HBox.setHgrow(row.getChildren().get(0), Priority.SOMETIMES);
        HBox.setHgrow(row.getChildren().get(1), Priority.ALWAYS);
        return row;
    }

    private Region clientsPane() {
        TableView<ConsoleClients.Row> table = new TableView<>(clientRows);
        table.setId("console-clients");
        table.getStyleClass().add("hsts-table");
        table.getColumns().addAll(List.of(
                column("User", ConsoleClients.Row::user, 160),
                column("Role", ConsoleClients.Row::role, 110),
                column("IP", ConsoleClients.Row::address, 130),
                column("Connected", ConsoleClients.Row::since, 100)));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);

        emptyClients = new Label();
        emptyClients.getStyleClass().addAll("small", "faint");
        emptyClients.setWrapText(true);

        Label title = new Label("Connected clients");
        title.getStyleClass().add("h3");
        VBox box = new VBox(8, title, table, emptyClients);
        box.getStyleClass().addAll("hsts-card", "compact");
        box.setPrefWidth(560);
        return box;
    }

    private static TableColumn<ConsoleClients.Row, String> column(
            String title, java.util.function.Function<ConsoleClients.Row, String> reader, int width) {
        TableColumn<ConsoleClients.Row, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(reader.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private Region logPane() {
        ListView<String> list = new ListView<>(logLines);
        list.setId("console-log");
        list.getStyleClass().add("mono");
        VBox.setVgrow(list, Priority.ALWAYS);

        levelPicker = new ComboBox<>(FXCollections.observableArrayList(LogLevel.values()));
        levelPicker.setId("console-log-level");
        levelPicker.setValue(logTail.minimumLevel());
        levelPicker.setOnAction(e -> {
            logTail.setMinimumLevel(levelPicker.getValue());
            refreshLog();
        });

        logSearch = new TextField();
        logSearch.setId("console-log-search");
        logSearch.setPromptText("Filter text");
        logSearch.getStyleClass().add("text-input");
        logSearch.textProperty().addListener((obs, old, value) -> {
            logTail.setSearch(value);
            refreshLog();
        });
        HBox.setHgrow(logSearch, Priority.ALWAYS);

        pauseButton = plainButton("Pause", "secondary");
        pauseButton.setId("console-log-pause");
        pauseButton.setOnAction(e -> {
            logTail.togglePaused();
            refreshLog();
        });

        Button clear = plainButton("Clear", "ghost");
        clear.setId("console-log-clear");
        clear.setOnAction(e -> {
            logTail.clear();
            refreshLog();
        });

        logStatus = new Label();
        logStatus.getStyleClass().addAll("small", "faint");
        logStatus.setId("console-log-status");

        Label title = new Label("Server log");
        title.getStyleClass().add("h3");
        HBox controls = new HBox(8, levelPicker, logSearch, pauseButton, clear);
        controls.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, title, controls, list, logStatus);
        box.getStyleClass().addAll("hsts-card", "compact");
        return box;
    }

    // ===================== Actions =======================================

    private void copyAddress() {
        ClipboardContent content = new ClipboardContent();
        content.putString(model.clipboardText());
        Clipboard.getSystemClipboard().setContent(content);
        show(new ConsoleSession.Outcome(true, "Copied " + model.clipboardText() + " to the clipboard."));
    }

    /**
     * Applies whatever the operator picked or typed in the address box.
     *
     * <p>A blank candidate is ignored rather than refused. Repopulating an
     * editable {@code ComboBox}'s items resets its selection, which fires this
     * handler with nothing in it, and an operator who has touched nothing should
     * not watch a validation error appear on the console by itself.
     */
    private void applyAddress() {
        String typed = addressPicker.getEditor().getText();
        String chosen = typed == null || typed.isBlank() ? addressPicker.getValue() : typed;
        if (chosen == null || chosen.isBlank()) {
            return;
        }
        // A picker row reads "192.168.1.42 (Wi-Fi)"; the model wants the address.
        show(session.selectAddress(chosen.trim().split(" ")[0]));
        refreshHeader();
    }

    /**
     * The seed button's {@link Confirmation} (E19.6).
     *
     * <p>The prompt shown is <b>the loader's own text</b>, handed in here as
     * {@code prompt} and rendered verbatim. The console never writes its own
     * description of a reseed, so this dialog and the command line cannot come to
     * describe the same destructive action differently. {@code LOAD_IF_MISSING}
     * never reaches this method at all, because it deletes nothing.
     *
     * <p>Rendered with the design system's {@link WarnConfirm}, which is the
     * component E4.13 built for exactly this shape of moment: legal but unusual,
     * with a consequence that has to be read before it is accepted. Deleting every
     * row in the database qualifies, so it gets the danger treatment and a confirm
     * button that names the action rather than saying OK.
     *
     * <p>This is the <b>one</b> place server code imports a client UI component,
     * and it is a deliberate exception rather than a drift: reusing the dialog is
     * what makes the console's most dangerous button look and behave exactly like
     * every other destructive confirm in the product (E19.7). Everything else the
     * console draws is plain JavaFX with the shared style classes; see
     * {@link ConsoleTheme}.
     */
    private Confirmation dialogConfirmation() {
        return prompt -> WarnConfirm.show(window(),
                WarnConfirm.spec("Reload the demo data?")
                        .explanation(prompt)
                        .confirmText("Delete and reload")
                        .cancelText("Keep my data")
                        .danger());
    }

    /** @return the console's own window, so the dialog is modal to it. */
    private javafx.stage.Window window() {
        return root == null || root.getScene() == null ? null : root.getScene().getWindow();
    }

    private void show(ConsoleSession.Outcome outcome) {
        actionMessage.setText(outcome.message());
        actionMessage.getStyleClass().removeAll("danger-text", "muted");
        actionMessage.getStyleClass().add(outcome.ok() ? "muted" : "danger-text");
        refreshHeader();
    }

    // ===================== Repaint =======================================

    private void refreshAll() {
        refreshHeader();
        refreshCards();
        refreshClients();
        refreshLog();
    }

    private void refreshHeader() {
        addressLabel.setText(model.headerText());
        fingerprintLabel.setText(model.fingerprintText());
        listenButton.setText(model.listenButtonText());
        listenStatus.setText(model.listenStatusText());
        discoveryToggle.setSelected(model.isDiscoveryEnabled());
        List<String> choices = model.addressChoices().stream().map(NetworkAddress::display).toList();
        if (!addressPicker.getItems().equals(choices)) {
            addressPicker.getItems().setAll(choices);
        }
    }

    private void refreshCards() {
        HealthSnapshot health = session.refreshHealth();
        databaseValue.setText(health.databaseText());
        databaseDetail.setText(health.databaseDetail());
        databaseValue.getStyleClass().removeAll("ok-text", "danger-text");
        databaseValue.getStyleClass().add(health.databaseUp() ? "ok-text" : "danger-text");
        clientsValue.setText(health.clientsText());
        clientsDetail.setText(health.clientsDetail());
        memoryValue.setText(health.memoryText());
        memoryDetail.setText(health.memoryDetail());
        providersValue.setText(health.providersText());
        providersDetail.setText(health.providersDetail());
    }

    private void refreshClients() {
        List<ConsoleClients.Row> rows = clients.rows(sessions.connectedClients(), clock.instant());
        clientRows.setAll(rows);
        emptyClients.setText(ConsoleClients.emptyStateText(rows.size()));
    }

    private void refreshLog() {
        logLines.setAll(logTail.visibleLines().stream().map(LogLine::display).toList());
        logStatus.setText(logTail.statusText());
        pauseButton.setText(logTail.isPaused() ? "Resume" : "Pause");
    }

    // ===================== Node helpers ==================================

    private static Button plainButton(String text, String variant) {
        Button button = new Button(text);
        button.getStyleClass().addAll("button", variant);
        return button;
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
