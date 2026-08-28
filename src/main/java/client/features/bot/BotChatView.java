package client.features.bot;

import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.ui.components.Buttons;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.components.WarnConfirm;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.Clock;
import java.util.List;

import client.ui.screen.AbstractScreen;

/**
 * The student's chat with her course's study bot (Presentation tier, E16.13 —
 * F12.5, C-4).
 *
 * <p>A renderer over {@link BotChatSession} and {@link BotChatModel}. Every
 * decision it makes about what to show comes from one {@link ChatState}, which is
 * the whole reason the state is an enum: there are five, each has a rendering
 * here, and there is no combination of flags that could produce a screen nobody
 * designed (PRD §4.1: zero mystery states).
 *
 * <ul>
 *   <li>{@code IDLE} — composer enabled, no banner;</li>
 *   <li>{@code THINKING} — composer disabled, typing indicator running;</li>
 *   <li>{@code RETRYABLE_ERROR} — composer enabled with her question back in it,
 *       banner explaining what to do;</li>
 *   <li>{@code UNAVAILABLE} — composer disabled, banner carrying the server's own
 *       sentence (not enrolled, no bot, switched off, or the C-4 lockout);</li>
 *   <li>{@code NEEDS_ACKNOWLEDGEMENT} — the C-4 confirmation, once, calmly.</li>
 * </ul>
 *
 * <p>The integrity confirmation is a {@code WarnConfirm} rather than a red alert:
 * ADR-018 permits the action, so the dialog states what happened, what will be
 * reported and asks her to confirm. It is shown once per notice, never nagged.
 *
 * <h2>Which course's bot ⚑ (U-2)</h2>
 *
 * <p>{@link Routes#BOT_CHAT} has always been "one route for one course at a time; which
 * course arrives as a nav parameter". The rail item carries no parameter, so the screen fell
 * back to the first course in the sign-in result and a student in three courses could reach
 * exactly one bot: the history screen's course came from here, so did the manager's, and
 * <b>C-4's cross-course path had no way in at all</b> — the notice fires when she opens
 * another course's bot while sitting an exam, and there was no other course's bot to open.
 *
 * <p>So a student in more than one course gets a picker. One course and there is no picker
 * and nothing else changes, which is what every student in the seed except Maya sees.
 *
 * <p>It is a {@code ComboBox} rather than a segmented control because every picker over a
 * data-driven list in this client is one — the principal's course filter on the Data screen,
 * her subject picker on Reports — while {@code .hsts-segmented} is reserved for fixed
 * enumerations whose members are known at compile time (the Data tabs, the report dimensions,
 * a chart's scale). A course list is neither fixed nor bounded: a student with seven courses
 * would turn segments into a second rail.
 *
 * <p>Switching rebuilds the session and the model for the chosen course, which is
 * {@code BotManagerView}'s pattern and the honest one: sessions are per course server-side,
 * the histories are separate, and a C-4 acknowledgement belongs to the sitting and the bot it
 * was given for, so carrying any of it across would be a lie.
 */
public final class BotChatView extends AbstractScreen {

    /** Nav parameter naming the course whose bot to open. */
    public static final String PARAM_COURSE = "courseCode";

    /** Nav parameter naming a past conversation to reopen (F12.10). */
    public static final String PARAM_SESSION = "sessionId";

    private final BorderPane root = new BorderPane();
    private final Label heading = new Label();
    private final Label subheading = new Label();
    private final VBox messages = new VBox(10);
    private final ScrollPane scroller = new ScrollPane(messages);
    private final TypingIndicator typing = new TypingIndicator();
    private final Label banner = new Label();
    private final TextField input = new TextField();
    private final Button send = Buttons.primary(BotCopy.SEND);
    private final EmptyState empty =
            new EmptyState(Icons.BOT, BotCopy.CHAT_EMPTY_TITLE, BotCopy.CHAT_EMPTY_HINT);
    private final ComboBox<CourseRef> coursePicker = new ComboBox<>();
    private final HBox courseBox = new HBox(8);

    private BotChatSession session;
    private String courseCode = "";
    private int renderedEntries;
    /** Where the optimistic bubble is, so exactly one node is redrawn when it lands. */
    private int pendingIndex = -1;
    private boolean noticeShowing;
    /** True while the picker is being set from code, so its listener stays quiet (U-2). */
    private boolean selecting;

    @Override
    protected Parent build() {
        root.getStyleClass().add("bot-chat");
        root.setTop(buildHeader());
        root.setCenter(buildConversation());
        root.setBottom(buildComposer());
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        String requested = params.getString(PARAM_COURSE, "");
        String resolved = requested.isBlank() ? firstCourse() : requested;
        // Before the session, so the picker never shows one course while the chat holds
        // another. Refreshed on every entry rather than built once: the screen is cached
        // per session and the enrolment it reads belongs to whoever is signed in now.
        refreshPicker(resolved);
        if (session == null || !resolved.equalsIgnoreCase(courseCode)) {
            startSessionFor(resolved);
        }
        long reopen = params.getLong(PARAM_SESSION, 0);
        if (reopen > 0 && session != null) {
            session.reopen(reopen);
        }
        input.requestFocus();
    }

    /**
     * Fills the picker and shows it only to a student who has a choice to make (U-2).
     *
     * <p>One course is one bot, and a dropdown with one entry is a control that cannot be
     * operated. Hidden <i>and</i> unmanaged, so the header keeps the layout it has had since
     * E16 for everybody it was already right for.
     *
     * @param selected the course whose bot is about to be, or already is, on screen
     */
    private void refreshPicker(String selected) {
        List<CourseRef> courses = courses();
        selecting = true;
        coursePicker.getItems().setAll(courses);
        // Cleared rather than left alone when the course is not one of hers, which a deep
        // link could ask for: a picker still naming the course she was on while the chat is
        // on another is the one thing this control must never do.
        courses.stream()
                .filter(course -> course.code().equalsIgnoreCase(selected))
                .findFirst()
                .ifPresentOrElse(coursePicker.getSelectionModel()::select,
                        coursePicker.getSelectionModel()::clearSelection);
        selecting = false;
        setShown(courseBox, courses.size() > 1);
    }

    /**
     * Builds a session for one course and wires it to this screen.
     *
     * <p>Also what switching courses does (U-2): a fresh model, a fresh session, an empty
     * conversation view. Nothing carries over, because nothing should. Her history for the
     * course she just left is still hers and is one button away under Past conversations,
     * and the C-4 consent she may have given belonged to a sitting and a bot that this is
     * not.
     */
    private void startSessionFor(String course) {
        courseCode = course;
        String name = courseNameOf(course);
        BotChatModel model = new BotChatModel(course, name);
        session = new BotChatSession(dispatcher(), eventBus().poster(), model, Clock.systemUTC());
        renderedEntries = 0;
        pendingIndex = -1;
        messages.getChildren().clear();
        model.onChange(() -> onFxThread().run(this::render));
        heading.setText(name.isBlank() ? BotCopy.CHAT_TITLE : name + " study bot");
        // F-14: the subheading used to repeat the empty state's hint, so it vanished
        // in meaning the moment the conversation had a message in it. It now says
        // what the screen is, which stays true all the way down the thread.
        subheading.setText(BotCopy.CHAT_EXPLAINER);
        render();
    }

    // ===================== Layout ========================================

    private Parent buildHeader() {
        heading.getStyleClass().add("page-title");
        subheading.getStyleClass().add("page-subtitle");
        subheading.setWrapText(true);

        Button history = Buttons.secondary(BotCopy.OPEN_HISTORY);
        history.setOnAction(e -> navigator().navigate(Routes.BOT_HISTORY.id(),
                NavParams.of(PARAM_COURSE, courseCode)));
        Button fresh = Buttons.outline(BotCopy.NEW_CONVERSATION);
        fresh.setOnAction(e -> {
            if (session != null) {
                session.model().startFresh();
            }
        });

        HBox actions = new HBox(8, buildCoursePicker(), history, fresh);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(12, new VBox(2, heading, subheading), Buttons.spacer(), actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(16, 20, 8, 20));
        return row;
    }

    /**
     * The course picker (U-2), built once and shown or hidden per student by
     * {@link #refreshPicker(String)}.
     *
     * <p>The listener is the whole switch: it calls the same method the first entry calls, so
     * there is one path into a course's bot and not two that can drift.
     */
    private HBox buildCoursePicker() {
        Label label = new Label(BotCopy.COURSE_PICKER_LABEL);
        label.getStyleClass().addAll("small", "muted");

        coursePicker.setCellFactory(view -> new CourseCell());
        coursePicker.setButtonCell(new CourseCell());
        coursePicker.setPrefWidth(200);
        coursePicker.getStyleClass().add("bot-course-picker");
        coursePicker.setTooltip(new Tooltip(BotCopy.COURSE_PICKER_TOOLTIP));
        coursePicker.getSelectionModel().selectedItemProperty()
                .addListener((observable, was, course) -> {
                    if (selecting || course == null
                            || course.code().equalsIgnoreCase(courseCode)) {
                        return;
                    }
                    startSessionFor(course.code());
                });

        courseBox.setAlignment(Pos.CENTER_LEFT);
        courseBox.getChildren().setAll(label, coursePicker);
        setShown(courseBox, false);
        return courseBox;
    }

    private Parent buildConversation() {
        messages.setPadding(new Insets(8, 20, 8, 20));
        messages.setFillWidth(true);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        banner.getStyleClass().add("bot-banner");
        banner.setWrapText(true);
        banner.setVisible(false);
        banner.setManaged(false);
        banner.setMaxWidth(Double.MAX_VALUE);

        VBox column = new VBox(8, banner, empty, scroller, typing);
        column.setPadding(new Insets(0, 20, 0, 20));
        VBox.setVgrow(column, Priority.ALWAYS);
        return column;
    }

    private Parent buildComposer() {
        input.setPromptText(BotCopy.ASK_PLACEHOLDER);
        input.getStyleClass().add("bot-input");
        HBox.setHgrow(input, Priority.ALWAYS);
        input.setOnAction(e -> submit());
        send.setOnAction(e -> submit());

        HBox row = new HBox(8, input, send);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(12, 20, 16, 20));
        return row;
    }

    // ===================== Behaviour =====================================

    private void submit() {
        if (session == null || !session.model().state().acceptsInput()) {
            return;
        }
        String question = input.getText();
        if (question == null || question.isBlank()) {
            return;
        }
        input.clear();
        session.ask(question).whenComplete((ignored, failure) -> onFxThread().run(this::render));
    }

    /**
     * Renders the whole screen from the model.
     *
     * <p>Messages are appended rather than rebuilt: a full rebuild would replay
     * every bubble's entrance animation on every state change, which turns a chat
     * into a slot machine. The index of what has already been drawn is the only
     * view state this class keeps.
     */
    private void render() {
        if (session == null) {
            return;
        }
        BotChatModel model = session.model();
        List<ChatEntry> entries = model.entries();

        if (entries.size() < renderedEntries) {
            // A pending bubble was withdrawn, or a conversation was reopened.
            messages.getChildren().clear();
            renderedEntries = 0;
            pendingIndex = -1;
        }
        for (int i = renderedEntries; i < entries.size(); i++) {
            ChatEntry entry = entries.get(i);
            messages.getChildren().add(new ChatBubble(entry, true));
            if (entry.pending()) {
                pendingIndex = i;
            }
        }
        renderedEntries = entries.size();
        confirmPendingBubble(entries);
        scroller.setVvalue(1.0);

        ChatState state = model.state();
        setShown(empty, model.isEmpty() && !state.isBlocked());
        setShown(scroller, !model.isEmpty());
        if (state.isThinking()) {
            typing.start();
        } else {
            typing.stop();
        }
        String message = model.banner();
        setShown(banner, !message.isBlank() && state != ChatState.NEEDS_ACKNOWLEDGEMENT);
        banner.setText(message);
        banner.pseudoClassStateChanged(
                javafx.css.PseudoClass.getPseudoClass("blocked"), state.isBlocked());

        boolean editable = state.acceptsInput();
        input.setDisable(!editable);
        send.setDisable(!editable);

        if (state == ChatState.NEEDS_ACKNOWLEDGEMENT) {
            askForAcknowledgement(model);
        }
    }

    /**
     * Redraws the one bubble that has stopped being provisional.
     *
     * <p>Only that one, and without its entrance animation. Redrawing the trailing
     * bubble instead would replace the answer that was added a line earlier and
     * throw away the animation it was just given, which is a subtle way of making
     * a chat feel broken.
     */
    private void confirmPendingBubble(List<ChatEntry> entries) {
        if (pendingIndex < 0 || pendingIndex >= entries.size()
                || pendingIndex >= messages.getChildren().size()
                || entries.get(pendingIndex).pending()) {
            return;
        }
        messages.getChildren().set(pendingIndex, new ChatBubble(entries.get(pendingIndex), false));
        pendingIndex = -1;
    }

    /**
     * The C-4 confirmation (ADR-018).
     *
     * <p>Guarded by a flag because {@code render()} runs on every model change and
     * a modal that reopened itself would be exactly the nagging ADR-018 rules out.
     */
    private void askForAcknowledgement(BotChatModel model) {
        if (noticeShowing) {
            return;
        }
        noticeShowing = true;
        boolean confirmed = WarnConfirm.show(window(), WarnConfirm.spec(BotCopy.INTEGRITY_TITLE)
                .explanation(model.banner() + "\n\n" + BotCopy.INTEGRITY_DETAIL)
                .confirmText(BotCopy.INTEGRITY_CONFIRM)
                .cancelText(BotCopy.INTEGRITY_CANCEL)
                .warn());
        noticeShowing = false;
        if (confirmed) {
            session.acknowledgeAndAsk()
                    .whenComplete((ignored, failure) -> onFxThread().run(this::render));
        } else {
            // Her question goes back in the box, unsent. Losing what she typed
            // because she declined a notice would be a small, avoidable cruelty.
            input.setText(session.decline());
            render();
        }
    }

    // ===================== Helpers =======================================

    private Window window() {
        return view().getScene() == null ? null : view().getScene().getWindow();
    }

    /** @return the signed-in student's courses, or empty when there is no session yet. */
    private List<CourseRef> courses() {
        LoginResult user = ScreenManager.getInstance().signedInUser();
        return user == null ? List.of() : user.courses();
    }

    private String firstCourse() {
        List<CourseRef> courses = courses();
        return courses.isEmpty() ? "" : courses.get(0).code();
    }

    private String courseNameOf(String code) {
        return courses().stream()
                .filter(course -> course.code().equalsIgnoreCase(code))
                .map(CourseRef::name)
                .findFirst()
                .orElse(code);
    }

    /** One row of the picker: the course's name, which is what she calls it. */
    private static final class CourseCell extends javafx.scene.control.ListCell<CourseRef> {

        @Override
        protected void updateItem(CourseRef course, boolean empty) {
            super.updateItem(course, empty);
            setGraphic(null);
            setText(empty || course == null ? null
                    : course.name() == null || course.name().isBlank()
                            ? course.code() : course.name());
        }
    }

    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /** @return the composer's text field; the TestFX flow types into it. */
    public TextField input() {
        return input;
    }

    /** @return the send button; the TestFX flow clicks it. */
    public Button sendButton() {
        return send;
    }

    /** @return the typing indicator, for the test that asserts it appears. */
    public TypingIndicator typingIndicator() {
        return typing;
    }

    /** @return the course picker (U-2); the TestFX flow selects through it. */
    public ComboBox<CourseRef> coursePicker() {
        return coursePicker;
    }

    /**
     * @return the picker with its label, which is the node that is shown or hidden (U-2).
     *         The control's own {@code visible} says nothing: a student in one course has a
     *         populated {@code ComboBox} inside a row that was never added to the layout.
     */
    public HBox coursePickerRow() {
        return courseBox;
    }
}
