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
import client.ui.components.StatusChip;
import common.protocol.Verb;
import org.greenrobot.eventbus.Subscribe;
import common.dto.notify.NotificationType;
import common.dto.notify.NotificationDto;
import client.events.ServerPushEvent;
import client.ui.components.WarnConfirm;
import client.ui.components.logic.ChipSpec;
import client.ui.components.logic.ChipTone;
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
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

/**
 * The teacher's Bot Manager (Presentation tier, E16.12 — F12.1/F12.2/F12.3/F12.4).
 *
 * <p>A renderer over {@link BotManagerListSession} and, for the course it has selected, over
 * that course's {@link BotManagerSession}. Every mutating action re-renders from the page the
 * server sends back, so nothing on screen can drift from the database (NFR-18: no refresh
 * button, and nothing to refresh).
 *
 * <h2>Master and detail, because "one bot" was being read off a screen showing one card ⚑</h2>
 *
 * <p>2026-08-29, manual round 3, U-26. This screen managed one course's bot and put the choice of
 * course behind a nav parameter, so {@code dana.cohen} — who teaches Algebra 11 and Calculus 12 —
 * opened it, saw a single bot, and reported that a teacher gets only one. The rule she was
 * reading is real and unchanged: <b>one study bot per course</b> (S-30, F12.1), held structurally
 * by {@code UNIQUE(course)} in V6 and by an idempotent {@code BOT_CREATE}. What was wrong is that
 * the screen never showed her the other one. So the manager is now a list: a card per taught
 * course on the left, that course's bot page on the right, in the shape {@code ExamListView}
 * already uses for exams and their versions. {@link BotCopy#LIST_SUBTITLE} states the rule in
 * words on the same screen, because a teacher who has just been shown two bots is owed the
 * sentence that says why she cannot have three.
 *
 * <p>The {@link #PARAM_COURSE} deep link is unchanged and now means "select this card": the
 * co-teacher source notification and the analytics screen's Back both land on the course they
 * are about, with the rest of her bots beside it rather than hidden behind it.
 *
 * <h2>Edit locks, and the ordering rule</h2>
 *
 * <p>Sources are edit-locked (E18.5, F10.4) through the shared {@link LockAwareEditor}, composed
 * rather than inherited. The rendering rule is the one the E0 prototype screen established and
 * this screen repeats deliberately: <b>the lock state is applied last</b>. Acquiring answers on
 * this very thread when the server is quick, so a render that re-enabled the buttons after the
 * lock had disabled them would hand a teacher an editor she does not hold. Selecting a different
 * course closes the lock first, for the same reason leaving the screen does: a lock held on a
 * row nobody can see is a colleague blocked by a ghost.
 *
 * <h2>Deleting a bot lives on its card ⚑ (U-39)</h2>
 *
 * <p>2026-08-30, the lead's ruling. A teacher can delete a course's bot, and the affordance is
 * a {@code danger} button on that course's card, under the Manage it is the opposite of. On a
 * screen that lists every taught course, "which bot" has to be answered by where the button is
 * rather than by what the teacher remembers selecting a moment ago, and the same goes for the
 * refusal: a bot that students have used answers {@code CONFLICT} with the count of their
 * conversations, and that sentence is drawn on the card it is about. Everything else is the
 * shape the screen already had, because the server answers a delete with the same
 * {@code BotManagerPage} it answers a toggle with: the card flips to Create because the page
 * says there is no bot, not because this class patched it.
 *
 * <h2>Empty states are states, not gaps</h2>
 *
 * <p>A course with no bot gets a card offering Create and, when it is selected, an empty state
 * with the same button; a bot with no sources gets one naming the three kinds it accepts plus the
 * question bank it uses for free; a teacher attached to no course gets a sentence naming the one
 * thing she can do about that. None of them is a blank panel: PRD §4.1's bar is that no screen
 * makes anybody ask "what now?".
 */
public final class BotManagerView extends AbstractScreen {

    /** Nav parameter naming the taught course to select. */
    public static final String PARAM_COURSE = "courseCode";

    private final BorderPane root = new BorderPane();
    private final StackPane stack = new StackPane(root);
    private final Label heading = new Label(BotCopy.LIST_TITLE);
    private final Label subheading = new Label(BotCopy.LIST_SUBTITLE);
    private final Label explainer = new Label(BotCopy.MANAGER_EXPLAINER);

    private final VBox courseCards = new VBox(10);
    private final Label listTitle = new Label(BotCopy.LIST_TITLE);
    private final EmptyState noCourses =
            new EmptyState(Icons.BOT, BotCopy.NO_COURSES_TITLE, BotCopy.NO_COURSES_HINT);

    private final Label detailHeading = new Label();
    private final Label detailSubheading = new Label();
    private final Label noSelection = new Label(BotCopy.CHOOSE_COURSE);
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
    private final VBox detail = new VBox(12);

    private BotManagerListSession list;
    private LockAwareEditor locks;
    private List<String> builtFor = List.of();
    private long lockedSourceId;

    @Override
    protected Parent build() {
        root.getStyleClass().add("bot-manager");
        root.setTop(buildHeader());
        root.setCenter(buildBody());
        noBot.action(BotCopy.CREATE_BOT, () -> createBot(selectedCourse()));
        initLocks();
        stack.getChildren().add(progress);
        return stack;
    }

    /**
     * Builds the list for whoever is signed in and selects the course the navigation named.
     *
     * <p>The session survives a second visit: the sessions it holds are per course and the
     * courses do not change inside one sign-in, so rebuilding would only throw away pages that
     * are about to be re-read anyway. It is rebuilt when the taught set itself differs, which is
     * a different user in the same process — the shape every screen in this client has to
     * survive because {@code ScreenManager} caches screens across a sign-out.
     */
    @Override
    public void onShow(NavParams params) {
        List<CourseRef> courses = taughtCourses();
        List<String> codes = courses.stream().map(CourseRef::code).toList();
        if (list == null || !codes.equals(builtFor)) {
            list = new BotManagerListSession(dispatcher(), eventBus().poster(), courses);
            list.onChange(() -> onFxThread().run(this::render));
            builtFor = codes;
        }
        // A blank parameter is a rail click rather than a deep link, and keeps the selection the
        // list already has. A course she does not teach is refused by the session, which is why
        // this does not read the answer: there is nothing better to fall back to.
        String requested = params.getString(PARAM_COURSE, "");
        if (!requested.isBlank() && !list.isSelected(requested)) {
            releaseLock();
            list.select(requested);
        }
        render();
        list.refreshAll();
    }

    /** Leaving the screen gives any held source lock straight back (E18.3). */
    @Override
    public void onHide() {
        releaseLock();
    }

    // ===================== Layout ========================================

    private Parent buildHeader() {
        heading.getStyleClass().add("page-title");
        subheading.getStyleClass().add("page-subtitle");
        // F-14: the subheading was the course name alone, which named the subject and never the
        // screen. U-26 gave it the one-per-course rule instead, and the explainer still says what
        // the sources below actually do.
        explainer.getStyleClass().addAll("page-subtitle", "muted");
        explainer.setWrapText(true);
        VBox titles = new VBox(2, heading, subheading, explainer);
        HBox row = new HBox(12, titles, Buttons.spacer());
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(16, 20, 8, 20));
        return row;
    }

    private Parent buildBody() {
        HBox body = new HBox(20, buildCourseList(), buildDetail());
        body.setPadding(new Insets(0, 20, 20, 20));
        return body;
    }

    /** The master half: one card per taught course (U-26). */
    private Node buildCourseList() {
        listTitle.getStyleClass().add("section-title");

        VBox column = new VBox(10, listTitle, noCourses, courseCards);
        ScrollPane scroller = new ScrollPane(column);
        scroller.setFitToWidth(true);
        scroller.getStyleClass().add("edge-to-edge");
        scroller.setPrefWidth(320);
        scroller.setMinWidth(280);
        scroller.setMaxWidth(360);
        return scroller;
    }

    /** The detail half: the single-bot page E16.12 already had, for the selected course. */
    private Node buildDetail() {
        status.getStyleClass().add("bot-status");
        status.setWrapText(true);
        setShown(status, false);

        detailHeading.getStyleClass().add("h3");
        detailHeading.setWrapText(true);
        detailSubheading.getStyleClass().addAll("small", "muted");
        noSelection.getStyleClass().addAll("small", "muted");
        noSelection.setWrapText(true);

        active.setOnAction(e -> selectedSession()
                .ifPresent(session -> session.setActive(active.isSelected())));
        addFile.setOnAction(e -> chooseFile());
        addText.setOnAction(e -> pasteText());
        analytics.setOnAction(e -> navigator().navigate(Routes.BOT_ANALYTICS.id(),
                NavParams.of(PARAM_COURSE, selectedCourse())));

        Label sourcesTitle = new Label(BotCopy.SOURCES_TITLE);
        sourcesTitle.getStyleClass().add("section-title");
        HBox sourceActions = new HBox(8, addFile, addText);
        sourceActions.setAlignment(Pos.CENTER_RIGHT);
        HBox sourcesHeader = new HBox(12, sourcesTitle, Buttons.spacer(), sourceActions);
        sourcesHeader.setAlignment(Pos.CENTER_LEFT);

        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));
        card.getChildren().addAll(lockBanner, active, status, sourcesHeader, noSources, sourceRows);

        HBox detailHeader = new HBox(12, new VBox(2, detailHeading, detailSubheading),
                Buttons.spacer(), analytics);
        detailHeader.setAlignment(Pos.CENTER_LEFT);

        detail.getChildren().addAll(detailHeader, noSelection, noBot, card);
        ScrollPane scroller = new ScrollPane(detail);
        scroller.setFitToWidth(true);
        scroller.getStyleClass().add("edge-to-edge");
        HBox.setHgrow(scroller, Priority.ALWAYS);
        VBox.setVgrow(scroller, Priority.ALWAYS);
        return scroller;
    }

    /** Wires the shared lock helper in, following the recipe in its javadoc. */
    /**
     * 2026-08-31, U-62 (Naji, round 5): "bot info isn't being updated in both screens, I had to
     * press the notification for it to work". The co-teacher's screen re-reads its courses on
     * the two bot notifications the server already sends to her (a source changed, the bot
     * deleted). The editor's own screen updates from its own answer, so the push it does not
     * receive is not needed there. Registered by ScreenLifecycle while the screen is shown.
     */
    @Override
    public boolean listensToEvents() {
        return true;
    }

    /**
     * A server push landed; re-read every course card if a colleague changed a bot.
     *
     * <p>Two types, and the second is why U-63 touched this method. {@code BOT_SOURCE_CHANGED}
     * covers material being added, changed or removed, and a bot being deleted (U-62, and
     * {@code NotificationCatalog.botDeleted} for that reuse). {@code BOT_CHANGED} is the pair of
     * events the server did not announce at all before U-63: a <b>toggle</b> and a
     * <b>create</b>. Both move a card on this screen, and a create moves it hardest, because a
     * co-teacher's empty state stops being an empty state: it goes on offering Create for a bot
     * that now exists, and the server answers that click by handing her somebody else's bot
     * (S-30).
     *
     * <p>{@link BotManagerListSession#refreshAll()} rather than patching one card, for the
     * reason {@code renderCourseList} gives about rebuilding rather than patching: the push says
     * one thing changed and a card has five facts on it, any of which may have moved.
     *
     * @param event the push, straight off the bus
     */
    @Subscribe
    public void onServerPush(ServerPushEvent event) {
        if (event == null || event.verb() != Verb.PUSH_NOTIFICATION || list == null) {
            return;
        }
        if (event.payload() instanceof NotificationDto item
                && (item.type() == NotificationType.BOT_SOURCE_CHANGED
                    || item.type() == NotificationType.BOT_CHANGED)) {
            list.refreshAll();
        }
    }

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
        if (list == null) {
            return;
        }
        renderCourseList();
        renderDetail();
    }

    /**
     * Rebuilds the course cards.
     *
     * <p>Rebuilt rather than patched, for {@code ExamListView.renderVersions}' stated reason: the
     * list is a handful of rows, and a patch would have to know which of a card's five facts can
     * change under it. The bot's name, its state and its source count all can, on any answer from
     * any of the courses.
     */
    private void renderCourseList() {
        setShown(noCourses, list.isEmpty());
        setShown(courseCards, !list.isEmpty());
        courseCards.getChildren().clear();
        for (BotCourseSummary summary : list.summaries()) {
            courseCards.getChildren().add(buildCourseCard(summary));
        }
    }

    /**
     * One course, its bot's state, and the one button that course needs.
     *
     * <p>The whole card selects, and the button on it selects too. They are not two behaviours:
     * Manage is the affordance a teacher looks for and the card is the target she actually hits,
     * and the only difference is that Create goes on to open the naming dialog. Both carry
     * {@code summary.courseCode()} rather than reading the selection back, so the course the
     * dialog names is the course whose card was pressed.
     */
    private Node buildCourseCard(BotCourseSummary summary) {
        Label course = new Label(summary.courseLabel());
        course.getStyleClass().addAll("body", "strong");
        course.setWrapText(true);

        Label bot = new Label(summary.botLabel());
        bot.getStyleClass().addAll("small", "muted");
        bot.setWrapText(true);

        HBox top = new HBox(10, new VBox(2, course, bot), Buttons.spacer());
        top.setAlignment(Pos.CENTER_LEFT);
        if (summary.loaded() && summary.hasBot()) {
            top.getChildren().add(new StatusChip(ChipSpec.of(summary.stateLabel(),
                    summary.active() ? ChipTone.OK : ChipTone.NEUTRAL)));
        }

        VBox box = new VBox(8, top);
        box.getStyleClass().addAll("hsts-card", "bot-course-card");
        if (list.isSelected(summary.courseCode())) {
            box.getStyleClass().add("selected");
        }
        box.setOnMouseClicked(e -> selectCourse(summary.courseCode()));

        if (summary.loaded()) {
            Label sources = new Label(summary.sourcesLabel());
            sources.getStyleClass().addAll("small", "muted");
            Button action = summary.hasBot()
                    ? Buttons.styled(summary.actionLabel(), Buttons.OUTLINE, Buttons.SMALL)
                    : Buttons.styled(summary.actionLabel(), Buttons.PRIMARY, Buttons.SMALL);
            action.setOnAction(e -> {
                selectCourse(summary.courseCode());
                if (!summary.hasBot()) {
                    createBot(summary.courseCode());
                }
            });
            HBox bottom = new HBox(10, sources, Buttons.spacer(), action);
            bottom.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(bottom);

            // ⚑ 2026-08-30, live session, U-39. Delete goes on the card that carries Manage,
            // not in the detail pane: the manager is a list, so "which bot" has to be answered
            // by where the button is rather than by what the teacher remembers selecting a
            // moment ago.
            //
            // Its own row under the actions rather than in them. The label is the longest thing
            // on the card and the column is 280px wide at the narrow window, so the two on one
            // line is the shape TruncatedTextGuardTest exists to catch; and a destructive
            // action sharing a line with the ordinary one is a mis-click waiting for a teacher
            // in a hurry. Right-aligned so it still reads as this card's action.
            if (summary.hasBot()) {
                Button delete = Buttons.styled(BotCopy.DELETE_BOT, Buttons.DANGER, Buttons.SMALL);
                delete.setOnAction(e -> {
                    selectCourse(summary.courseCode());
                    confirmDeleteBot(summary);
                });
                HBox dangerRow = new HBox(Buttons.spacer(), delete);
                dangerRow.setAlignment(Pos.CENTER_RIGHT);
                box.getChildren().add(dangerRow);
            }
        }
        // The course's own refusal, on the course's own card (U-39). It is the session's
        // existing error path and nothing new: what is new is that the delete's CONFLICT
        // counts the student conversations it is protecting, and a sentence about this bot
        // shown only in the detail pane is a sentence about whichever bot is selected.
        if (summary.hasStatus()) {
            Label cardStatus = new Label(summary.status());
            cardStatus.getStyleClass().add("bot-status");
            cardStatus.setWrapText(true);
            cardStatus.setMaxWidth(Double.MAX_VALUE);
            box.getChildren().add(cardStatus);
        }
        return box;
    }

    /**
     * Asks before deleting one course's bot ⚑ (U-39, 2026-08-30).
     *
     * <p>A {@code danger} confirm rather than a {@code warn} one: this is the only action on
     * the screen that destroys something a teacher cannot rebuild by pressing the button again,
     * and the dialog names the course for the reason the button lives on the card at all.
     *
     * <p>It deliberately does <b>not</b> pre-check whether students have used the bot. The
     * server owns that rule, counts the conversations and writes the sentence; a client that
     * guessed at it would either hide a button that would have worked or promise one that will
     * not, and both would be guessing from a page that is as old as the last read. The refusal
     * comes back on this course's own session and lands on this course's own card.
     */
    private void confirmDeleteBot(BotCourseSummary summary) {
        Optional<BotManagerSession> maybe = list == null
                ? Optional.empty()
                : list.sessionFor(summary.courseCode());
        if (maybe.isEmpty()) {
            return;
        }
        boolean confirmed = WarnConfirm.show(window(), WarnConfirm.spec(BotCopy.DELETE_TITLE)
                .explanation(BotCopy.deleteExplanation(summary.courseName()))
                .confirmText(BotCopy.DELETE_CONFIRM)
                .cancelText(BotCopy.DELETE_CANCEL)
                .danger());
        if (confirmed) {
            // Any advisory lock this teacher is holding belongs to a source that is about to
            // stop existing, so it goes back first. E18.3's rule, applied one level up.
            releaseLock();
            maybe.get().deleteBot();
        }
    }

    /** Draws the selected course's bot, which is the screen E16.12 shipped. */
    private void renderDetail() {
        Optional<BotManagerSession> maybe = selectedSession();
        if (maybe.isEmpty()) {
            progress.hide();
            setShown(noSelection, true);
            setShown(noBot, false);
            setShown(card, false);
            setShown(analytics, false);
            detailHeading.setText("");
            detailSubheading.setText("");
            return;
        }
        BotManagerSession session = maybe.get();
        setShown(noSelection, false);
        setShown(analytics, true);

        if (session.isBusy()) {
            progress.show();
        } else {
            progress.hide();
        }
        boolean hasBot = session.hasBot();
        setShown(noBot, session.isLoaded() && !hasBot);
        setShown(card, hasBot);

        detailHeading.setText(hasBot
                ? session.page().bot().name()
                : courseNameOf(session.courseCode()));
        detailSubheading.setText(hasBot
                ? session.page().bot().courseName()
                : BotCopy.NO_BOT_YET);
        if (hasBot) {
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
        // disabled. Same ordering rule the prototype bank screen established.
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
        boolean heldByAnother = locks != null && lockedSourceId == row.sourceId()
                && !locks.isEditable();
        remove.setDisable(heldByAnother);

        // U-28: the title wraps and the text column shrinks first, so a long file name is
        // never the reason Edit or Remove loses room for its own label.
        title.setWrapText(true);
        VBox text = new VBox(2, title, meta);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox line = new HBox(12, icon, text, Buttons.spacer());
        // ⚑ B-21. F12.3's third verb, and the first thing on this screen the BOT_SOURCE edit
        // lock has ever had an actual editor to protect (F10.2). Free text only: see
        // BotSourceRow.isEditable() for why a PDF row does not get one.
        if (row.isEditable()) {
            Button edit = Buttons.styled(BotCopy.EDIT, Buttons.GHOST, Buttons.SMALL);
            edit.setOnAction(e -> editText(row));
            edit.setDisable(heldByAnother);
            line.getChildren().add(edit);
        }
        line.getChildren().add(remove);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("source-row");
        // ⚑ 2026-08-29, manual round 3, U-33. The row's pressable TREATMENT is gone from
         // 2026-08-30, live session, U-33 reopened: the row itself does nothing on click.
        // The wave before this one kept a click that took the advisory lock (to surface a
        // colleague's hold on a file row), and the banner sliding in and out as the lock was
        // granted read as a twitch. Naji's ruling: Edit and Remove are the only actions; a
        // colleague's hold is met on those, not by pressing the row.
        return line;
    }

    /**
     * Applies a lock state to the banner.
     *
     * <p>Whether a row's Remove button is usable is decided when the row is built, from the same
     * snapshot; the server refuses a locked removal regardless
     * ({@code BotMessages.SOURCE_LOCKED}), so this is the courtesy and that is the rule.
     */
    private void renderLockState(EditLockState.Snapshot state) {
        // No hop of our own: LockAwareEditor.publish delivers every snapshot on the FX
        // thread since 2026-08-24 (the recipe's rule 4). A second hop here would defer the
        // banner one more pulse and, in tests, past the harness teardown that nulls the bus.
        lockBanner.show(state, BotCopy.SOURCE_NOUN);
    }

    // ===================== Actions =======================================

    /**
     * Shows one course's bot on the right (U-26).
     *
     * <p>The held lock goes back first. A source lock belongs to a row of the course being left,
     * and carrying it across would leave a co-teacher of that course looking at a read-only row
     * held by somebody who is now on a different screen in all but name.
     */
    private void selectCourse(String courseCode) {
        if (list == null || list.isSelected(courseCode)) {
            return;
        }
        releaseLock();
        list.select(courseCode);
    }

    private void releaseLock() {
        if (locks != null) {
            locks.close();
        }
        lockedSourceId = 0;
    }

    /**
     * Names and creates one course's bot, in the house dialog (UI wave 1 — F-11).
     *
     * <p>Addressed to a course rather than to "the" bot, which is what makes U-26's last rule
     * hold: the request goes to that course's own {@link BotManagerSession} and the page that
     * comes back replaces that session's page only. A sibling course's card cannot move, because
     * there is no shared page for it to move with. {@code BOT_CREATE} is idempotent besides, so
     * a co-teacher who pressed it first hands this one the bot rather than a refusal (S-30).
     */
    private void createBot(String courseCode) {
        Optional<BotManagerSession> maybe = list == null
                ? Optional.empty()
                : list.sessionFor(courseCode);
        if (maybe.isEmpty()) {
            return;
        }
        TextField name = new TextField(courseNameOf(courseCode) + " study bot");
        name.setPromptText("Name");
        boolean confirmed = WarnConfirm.show(window(), WarnConfirm.spec(BotCopy.CREATE_BOT)
                .explanation(BotCopy.NO_BOT_HINT)
                .confirmText(BotCopy.CREATE_BOT)
                .cancelText("Cancel")
                .detail(name)
                .info());
        if (confirmed && !name.getText().isBlank()) {
            maybe.get().create(name.getText().trim());
        }
    }

    private void chooseFile() {
        Optional<BotManagerSession> maybe = selectedSession();
        if (maybe.isEmpty()) {
            return;
        }
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
            maybe.get().addSource(BotSourceKind.ofFileName(file.getName()), file.getName(), bytes);
        } catch (IOException e) {
            // Reading a file the user just picked can still fail: a network share
            // that went away, a permission that changed. Say so rather than nothing.
            status.setText("That file could not be read from disk. Choose it again.");
            setShown(status, true);
        }
    }

    private void pasteText() {
        Optional<BotManagerSession> maybe = selectedSession();
        if (maybe.isEmpty()) {
            return;
        }
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
            maybe.get().addSource(BotSourceKind.TEXT, firstLineOf(area.getText()),
                    area.getText().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Edits a free-text source in place ⚑ (F12.3, B-21).
     *
     * <p>The same {@code WarnConfirm} the Add text action uses, opened on what is actually
     * stored rather than on an empty box — {@code BotSourceRow.text} carries the body of a
     * typed source for exactly this. Editing a typo means seeing the typo.
     *
     * <p>The advisory lock is taken on the row before the dialog opens, which is what makes
     * F10.2's "read-only view while another teacher edits" mean something on this screen: a
     * colleague's remove or edit is refused behind it, with her name in the sentence.
     *
     * <p>A dialog closed with the text unchanged still sends, and deliberately: the teacher
     * pressed Save, the server bumps the version and tells her co-teachers, and a client that
     * silently decided her edit was not worth sending would be guessing at intent.
     */
    private void editText(BotSourceRow row) {
        Optional<BotManagerSession> maybe = selectedSession();
        if (maybe.isEmpty()) {
            return;
        }
        openLockFor(row);
        TextArea area = new TextArea(row.text() == null ? "" : row.text());
        area.setPrefRowCount(12);
        area.setWrapText(true);
        TextField title = new TextField(row.title());
        title.setPromptText("Title");

        boolean confirmed = WarnConfirm.show(window(), WarnConfirm.spec(BotCopy.EDIT_TEXT_TITLE)
                .explanation(BotCopy.EDIT_TEXT_EXPLANATION)
                .confirmText(BotCopy.EDIT_CONFIRM)
                .cancelText("Cancel")
                .detail(new VBox(8, title, area))
                .info());
        if (confirmed && !area.getText().isBlank() && !title.getText().isBlank()) {
            maybe.get().updateSource(row.sourceId(), BotSourceKind.TEXT, title.getText().trim(),
                    area.getText().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void confirmRemove(BotSourceRow row) {
        Optional<BotManagerSession> maybe = selectedSession();
        if (maybe.isEmpty()) {
            return;
        }
        boolean confirmed = WarnConfirm.show(window(), WarnConfirm.spec(BotCopy.REMOVE_TITLE)
                .explanation(row.title() + "\n\n" + BotCopy.REMOVE_EXPLANATION)
                .confirmText(BotCopy.REMOVE_CONFIRM)
                .cancelText(BotCopy.REMOVE_CANCEL)
                .warn());
        if (confirmed) {
            maybe.get().removeSource(row.sourceId());
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

    private String selectedCourse() {
        return list == null ? "" : list.selectedCourse();
    }

    private Optional<BotManagerSession> selectedSession() {
        return list == null ? Optional.empty() : list.selected();
    }

    private static List<CourseRef> taughtCourses() {
        LoginResult user = ScreenManager.getInstance().signedInUser();
        return user == null ? List.of() : user.courses();
    }

    private String courseNameOf(String code) {
        return taughtCourses().stream()
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

    /** @return the course cards, for the TestFX assertions over the list (U-26). */
    public VBox courseCardsBox() {
        return courseCards;
    }

    /** @return the course the detail pane is showing, for the TestFX deep-link assertion. */
    public String selectedCourseCode() {
        return selectedCourse();
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
