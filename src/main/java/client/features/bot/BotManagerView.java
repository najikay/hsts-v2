package client.features.bot;

import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.features.locks.EditLockState;
import client.features.locks.FxHeartbeat;
import client.features.locks.LockAwareEditor;
import client.features.locks.LockBanner;
import client.features.locks.LockCopy;
import client.ui.components.Buttons;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.components.ProgressOverlay;
import client.ui.components.WarnConfirm;
import client.ui.screen.AbstractScreen;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.bot.BotSourceKind;
import common.dto.bot.BotSourceRow;
import common.dto.lock.EntityRef;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.util.Optional;

/**
 * The teacher's Bot Manager (Presentation tier, E16.12 — F12.1/F12.2/F12.3/F12.4).
 *
 * <p>A renderer over {@link BotManagerSession}. Three areas: the bot card with its
 * name and the active toggle, the sources table, and the two ways to add material.
 * Every mutating action re-renders from the page the server sends back, so the
 * table cannot drift from the database (NFR-18: no refresh button, and nothing to
 * refresh).
 *
 * <h2>Edit locks, and the ordering rule</h2>
 *
 * <p>Sources are edit-locked (E18.5, F10.4) through the shared
 * {@link LockAwareEditor}, composed rather than inherited. The rendering rule is
 * the one {@code QuestionsView} established and this screen repeats deliberately:
 * <b>the lock state is applied last</b>. Acquiring answers on this very thread
 * when the server is quick, so a render that re-enabled the buttons after the lock
 * had disabled them would hand a teacher an editor she does not hold.
 *
 * <h2>Empty states are states, not gaps</h2>
 *
 * <p>A course with no bot gets an empty state with the create button, and a bot
 * with no sources gets one naming the three kinds it accepts plus the question
 * bank it uses for free. Neither is a blank panel: PRD §4.1's bar is that no
 * screen makes anybody ask "what now?".
 */
public final class BotManagerView extends AbstractScreen {

    /** Nav parameter naming the taught course to manage. */
    public static final String PARAM_COURSE = "courseCode";

    private final BorderPane root = new BorderPane();
    private final StackPane stack = new StackPane(root);
    private final Label heading = new Label();
    private final Label subheading = new Label();
    private final Label status = new Label();
    private final CheckBox active = new CheckBox(BotCopy.ACTIVE_LABEL);
    private final VBox sourceRows = new VBox(8);
    private final EmptyState noBot =
            new EmptyState(Icons.BOT, BotCopy.NO_BOT_TITLE, BotCopy.NO_BOT_HINT);
    private final EmptyState noSources =
            new EmptyState(Icons.INBOX, BotCopy.SOURCES_EMPTY_TITLE, BotCopy.SOURCES_EMPTY_HINT);
    private final LockBanner lockBanner = new LockBanner();
    private final ProgressOverlay progress = new ProgressOverlay(BotCopy.UPLOADING);
    private final Button addFile = Buttons.primary(BotCopy.ADD_FILE);
    private final Button addText = Buttons.secondary(BotCopy.ADD_TEXT);
    private final Button analytics = Buttons.outline(BotCopy.ANALYTICS_TITLE);
    private final VBox card = new VBox(10);

    private BotManagerSession session;
    private LockAwareEditor locks;
    private String courseCode = "";
    private long lockedSourceId;

    @Override
    protected Parent build() {
        root.getStyleClass().add("bot-manager");
        root.setTop(buildHeader());
        root.setCenter(buildBody());
        noBot.action(BotCopy.CREATE_BOT, this::createBot);
        initLocks();
        stack.getChildren().add(progress);
        return stack;
    }

    @Override
    public void onShow(NavParams params) {
        String requested = params.getString(PARAM_COURSE, "");
        String resolved = requested.isBlank() ? firstTaughtCourse() : requested;
        if (session == null || !resolved.equalsIgnoreCase(courseCode)) {
            courseCode = resolved;
            session = new BotManagerSession(dispatcher(), resolved);
            session.onChange(() -> onFxThread().run(this::render));
            heading.setText(BotCopy.MANAGER_TITLE);
            subheading.setText(courseNameOf(resolved));
        }
        session.refresh();
    }

    /** Leaving the screen gives any held source lock straight back (E18.3). */
    @Override
    public void onHide() {
        if (locks != null) {
            locks.close();
        }
        lockedSourceId = 0;
    }

    // ===================== Layout ========================================

    private Parent buildHeader() {
        heading.getStyleClass().add("page-title");
        subheading.getStyleClass().add("page-subtitle");
        analytics.setOnAction(e -> navigator().navigate(Routes.BOT_ANALYTICS.id(),
                NavParams.of(PARAM_COURSE, courseCode)));
        HBox row = new HBox(12, new VBox(2, heading, subheading), Buttons.spacer(), analytics);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(16, 20, 8, 20));
        return row;
    }

    private Parent buildBody() {
        status.getStyleClass().add("bot-status");
        status.setWrapText(true);
        status.setVisible(false);
        status.setManaged(false);

        active.setOnAction(e -> {
            if (session != null) {
                session.setActive(active.isSelected());
            }
        });

        addFile.setOnAction(e -> chooseFile());
        addText.setOnAction(e -> pasteText());

        Label sourcesTitle = new Label(BotCopy.SOURCES_TITLE);
        sourcesTitle.getStyleClass().add("section-title");
        HBox sourceActions = new HBox(8, addFile, addText);
        sourceActions.setAlignment(Pos.CENTER_RIGHT);
        HBox sourcesHeader = new HBox(12, sourcesTitle, Buttons.spacer(), sourceActions);
        sourcesHeader.setAlignment(Pos.CENTER_LEFT);

        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));
        card.getChildren().addAll(lockBanner, active, status, sourcesHeader, noSources, sourceRows);

        VBox body = new VBox(12, noBot, card);
        body.setPadding(new Insets(0, 20, 20, 20));
        ScrollPane scroller = new ScrollPane(body);
        scroller.setFitToWidth(true);
        VBox.setVgrow(scroller, Priority.ALWAYS);
        return scroller;
    }

    /** Wires the shared lock helper in, following the recipe in its javadoc. */
    private void initLocks() {
        lockBanner.hide();
        LoginResult user = ScreenManager.getInstance().signedInUser();
        if (dispatcher() == null || user == null) {
            // Reachable from the gallery and from tests without a session; the
            // screen stays fully editable there rather than refusing to build.
            return;
        }
        locks = new LockAwareEditor(dispatcher(), eventBus(), user.userId(),
                new FxHeartbeat(), BotCopy.SOURCE_NOUN);
        locks.onStateChanged(this::renderLockState);
        lockBanner.setOnTakeOver(this::confirmTakeOver);
    }

    // ===================== Rendering =====================================

    private void render() {
        if (session == null) {
            return;
        }
        if (session.isBusy()) {
            progress.show();
        } else {
            progress.hide();
        }
        boolean hasBot = session.hasBot();
        setShown(noBot, session.isLoaded() && !hasBot);
        setShown(card, hasBot);

        if (hasBot) {
            heading.setText(session.page().bot().name());
            subheading.setText(session.page().bot().courseName());
            active.setSelected(session.page().bot().active());
        }
        String message = session.status();
        status.setText(message);
        setShown(status, !message.isBlank());

        sourceRows.getChildren().clear();
        for (BotSourceRow row : session.sources()) {
            sourceRows.getChildren().add(buildSourceRow(row));
        }
        setShown(noSources, hasBot && session.sources().isEmpty());
        setShown(sourceRows, !session.sources().isEmpty());

        // The lock has the last word, so it goes last: acquiring can answer on this
        // very thread, and the lines above would otherwise re-enable what it just
        // disabled. Same ordering rule as QuestionsView.
        if (locks != null) {
            renderLockState(locks.state());
        }
    }

    private Node buildSourceRow(BotSourceRow row) {
        Label icon = new Label();
        icon.setGraphic(Icons.of(BotCopy.iconFor(row.kind())));

        Label title = new Label(row.title());
        title.getStyleClass().add("source-title");
        Label meta = new Label(row.kind().label() + " · " + row.sizeLabel() + " · " + row.addedBy());
        meta.getStyleClass().add("source-meta");

        Button remove = Buttons.styled(BotCopy.REMOVE, Buttons.GHOST, Buttons.SMALL);
        remove.setOnAction(e -> confirmRemove(row));
        remove.setDisable(locks != null && lockedSourceId == row.sourceId() && !locks.isEditable());

        HBox line = new HBox(12, icon, new VBox(2, title, meta), Buttons.spacer(), remove);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("source-row");
        line.setOnMouseClicked(e -> openLockFor(row));
        return line;
    }

    /**
     * Applies a lock state to the banner.
     *
     * <p>Hopped onto the FX thread rather than applied where it arrives: the lock
     * helper publishes from whichever thread completed the request, which in
     * production is the socket read thread. The hop is a no-op for correctness when
     * the caller is already the FX thread and it keeps the "lock has the last word"
     * ordering either way, because a posted action runs after the render that
     * queued it.
     *
     * <p>Whether a row's Remove button is usable is decided when the row is built,
     * from the same snapshot; the server refuses a locked removal regardless
     * ({@code BotMessages.SOURCE_LOCKED}), so this is the courtesy and that is the
     * rule.
     */
    private void renderLockState(EditLockState.Snapshot state) {
        onFxThread().run(() -> lockBanner.show(state, BotCopy.SOURCE_NOUN));
    }

    // ===================== Actions =======================================

    private void createBot() {
        if (session == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog(courseNameOf(courseCode) + " study bot");
        dialog.setTitle(BotCopy.CREATE_BOT);
        dialog.setHeaderText(BotCopy.NO_BOT_HINT);
        dialog.setContentText("Name");
        Optional<String> name = dialog.showAndWait();
        name.ifPresent(chosen -> session.create(chosen));
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(BotCopy.ADD_FILE);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Course material", "*.pdf", "*.docx", "*.txt"));
        File file = chooser.showOpenDialog(window());
        if (file == null) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            session.addSource(BotSourceKind.ofFileName(file.getName()), file.getName(), bytes);
        } catch (IOException e) {
            // Reading a file the user just picked can still fail: a network share
            // that went away, a permission that changed. Say so rather than nothing.
            status.setText("That file could not be read from disk. Choose it again.");
            setShown(status, true);
        }
    }

    private void pasteText() {
        TextArea area = new TextArea();
        area.setPromptText("Paste the course material here");
        area.setPrefRowCount(12);
        area.setWrapText(true);
        boolean confirmed = WarnConfirm.show(window(), WarnConfirm.spec(BotCopy.ADD_TEXT)
                .explanation("The bot will answer from this text as well as from the files.")
                .confirmText(BotCopy.ADD_TEXT)
                .cancelText("Cancel")
                .detail(area)
                .info());
        if (confirmed && !area.getText().isBlank()) {
            session.addSource(BotSourceKind.TEXT, firstLineOf(area.getText()),
                    area.getText().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void confirmRemove(BotSourceRow row) {
        boolean confirmed = WarnConfirm.show(window(), WarnConfirm.spec(BotCopy.REMOVE_TITLE)
                .explanation(row.title() + "\n\n" + BotCopy.REMOVE_EXPLANATION)
                .confirmText(BotCopy.REMOVE_CONFIRM)
                .cancelText(BotCopy.REMOVE_CANCEL)
                .warn());
        if (confirmed) {
            session.removeSource(row.sourceId());
        }
    }

    /** Takes the advisory lock on the source the teacher just focused (E18.5). */
    private void openLockFor(BotSourceRow row) {
        if (locks == null) {
            return;
        }
        lockedSourceId = row.sourceId();
        locks.open(new EntityRef(EntityRef.BOT_SOURCE, row.sourceId()));
    }

    /** Asks before taking a colleague's lock; never a silent grab (E18.3). */
    private void confirmTakeOver() {
        EditLockState.Snapshot state = locks.state();
        boolean confirmed = WarnConfirm.show(window(), WarnConfirm.spec(LockCopy.TAKEOVER_TITLE)
                .explanation(state.reason() == null ? ""
                        : LockCopy.takeoverExplanation(state.reason(), BotCopy.SOURCE_NOUN))
                .confirmText(LockCopy.TAKEOVER_CONFIRM)
                .cancelText(LockCopy.TAKEOVER_CANCEL)
                .info());
        if (confirmed) {
            locks.takeOver();
        } else {
            locks.declineTakeover();
        }
    }

    // ===================== Helpers =======================================

    private static String firstLineOf(String text) {
        String first = text.strip().lines().findFirst().orElse("Pasted text");
        return first.length() > 60 ? first.substring(0, 60) : first;
    }

    private Window window() {
        return view().getScene() == null ? null : view().getScene().getWindow();
    }

    private String firstTaughtCourse() {
        LoginResult user = ScreenManager.getInstance().signedInUser();
        return user == null || user.courses().isEmpty() ? "" : user.courses().get(0).code();
    }

    private String courseNameOf(String code) {
        LoginResult user = ScreenManager.getInstance().signedInUser();
        if (user == null) {
            return code;
        }
        return user.courses().stream()
                .filter(course -> course.code().equalsIgnoreCase(code))
                .map(CourseRef::name)
                .findFirst()
                .orElse(code);
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /** @return the sources list, for the TestFX assertions. */
    public VBox sourcesBox() {
        return sourceRows;
    }

    /** @return the lock banner, for the TestFX assertion that it appears. */
    public LockBanner lockBanner() {
        return lockBanner;
    }

    /** @return the active toggle, for the TestFX flow that clicks it. */
    public CheckBox activeToggle() {
        return active;
    }
}
