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
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
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

    private BotChatSession session;
    private String courseCode = "";
    private int renderedEntries;
    /** Where the optimistic bubble is, so exactly one node is redrawn when it lands. */
    private int pendingIndex = -1;
    private boolean noticeShowing;

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
        if (session == null || !resolved.equalsIgnoreCase(courseCode)) {
            startSessionFor(resolved);
        }
        long reopen = params.getLong(PARAM_SESSION, 0);
        if (reopen > 0 && session != null) {
            session.reopen(reopen);
        }
        input.requestFocus();
    }

    /** Builds a session for one course and wires it to this screen. */
    private void startSessionFor(String course) {
        courseCode = course;
        String name = courseNameOf(course);
        BotChatModel model = new BotChatModel(course, name);
        session = new BotChatSession(dispatcher(), model, Clock.systemUTC());
        renderedEntries = 0;
        pendingIndex = -1;
        messages.getChildren().clear();
        model.onChange(() -> onFxThread().run(this::render));
        heading.setText(name.isBlank() ? BotCopy.CHAT_TITLE : name + " study bot");
        subheading.setText(BotCopy.CHAT_EMPTY_HINT);
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

        HBox actions = new HBox(8, history, fresh);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(12, new VBox(2, heading, subheading), Buttons.spacer(), actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(16, 20, 8, 20));
        return row;
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

    private String firstCourse() {
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
}
