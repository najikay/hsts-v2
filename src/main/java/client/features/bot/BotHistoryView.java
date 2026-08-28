package client.features.bot;

import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.ui.components.BackLink;
import client.ui.components.Buttons;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.components.logic.RelativeTime;
import client.ui.screen.AbstractScreen;
import common.dto.auth.LoginResult;
import common.dto.bot.BotSessionRow;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Clock;

/**
 * The student's past conversations with one course's bot (Presentation tier,
 * E16.14 — F12.10).
 *
 * <p>A list and a button. Each row shows when the conversation was, how many
 * questions were in it, and the first one — which is what makes a row
 * recognisable a fortnight later, and the reason the server sends a preview rather
 * than making this screen guess from a date.
 *
 * <p>Reopening navigates to the chat with the session id, and the chat fetches the
 * transcript. That split is deliberate: this screen never holds a whole
 * conversation, so a term's worth of studying is a list of summaries rather than
 * every answer the bot ever gave.
 */
public final class BotHistoryView extends AbstractScreen {

    /** Nav parameter naming the course whose history to show. */
    public static final String PARAM_COURSE = "courseCode";

    private final BorderPane root = new BorderPane();
    private final Label heading = new Label(BotCopy.HISTORY_TITLE);
    private final Label subheading = new Label();
    private final Label explainer = new Label(BotCopy.HISTORY_EXPLAINER);
    private final Label status = new Label();
    private final VBox rows = new VBox(8);
    private final EmptyState empty =
            new EmptyState(Icons.INBOX, BotCopy.HISTORY_EMPTY_TITLE, BotCopy.HISTORY_EMPTY_HINT);

    private BotHistorySession session;
    private String courseCode = "";

    @Override
    protected Parent build() {
        root.getStyleClass().add("bot-history");
        heading.getStyleClass().add("page-title");
        subheading.getStyleClass().add("page-subtitle");
        // F-14: one line saying what this list is, since the subheading is the course.
        explainer.getStyleClass().addAll("page-subtitle", "muted");
        explainer.setWrapText(true);
        status.getStyleClass().add("bot-status");
        status.setWrapText(true);
        status.setVisible(false);
        status.setManaged(false);

        Button back = Buttons.secondary(BotCopy.CHAT_TITLE);
        back.setOnAction(e -> navigator().navigate(Routes.BOT_CHAT.id(),
                NavParams.of(PARAM_COURSE, courseCode)));

        // F-7: the named "Study bot" button stays on the right; the convention's
        // control goes top-left so this screen exits the same way every other
        // drill-in does.
        HBox header = new HBox(12,
                new VBox(2, BackLink.to(navigator(), Routes.BOT_CHAT.id(), "Study bot"),
                        heading, subheading, explainer),
                Buttons.spacer(), back);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 20, 8, 20));

        rows.setPadding(new Insets(0, 20, 20, 20));
        VBox body = new VBox(12, status, empty, rows);
        body.setPadding(new Insets(0, 20, 0, 20));
        ScrollPane scroller = new ScrollPane(body);
        scroller.setFitToWidth(true);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        root.setTop(header);
        root.setCenter(scroller);
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        String requested = params.getString(PARAM_COURSE, "");
        String resolved = requested.isBlank() ? firstCourse() : requested;
        if (session == null || !resolved.equalsIgnoreCase(courseCode)) {
            courseCode = resolved;
            session = new BotHistorySession(dispatcher(), eventBus().poster(), resolved);
            session.onChange(() -> onFxThread().run(this::render));
        }
        session.refresh();
    }

    private void render() {
        if (session == null) {
            return;
        }
        subheading.setText(session.page().courseName());
        String message = session.status();
        status.setText(message);
        setShown(status, !message.isBlank());

        rows.getChildren().clear();
        for (BotSessionRow row : session.rows()) {
            rows.getChildren().add(buildRow(row));
        }
        setShown(empty, session.isLoaded() && session.rows().isEmpty() && message.isBlank());
        setShown(rows, !session.rows().isEmpty());
    }

    private Node buildRow(BotSessionRow row) {
        Label preview = new Label(row.preview().isBlank() ? BotCopy.CHAT_EMPTY_TITLE : row.preview());
        preview.getStyleClass().add("history-preview");
        preview.setWrapText(true);

        Label meta = new Label(RelativeTime.of(row.updatedAt(), Clock.systemUTC().instant())
                + " · " + row.questionLabel());
        meta.getStyleClass().add("history-meta");

        Button reopen = Buttons.styled(BotCopy.REOPEN, Buttons.OUTLINE, Buttons.SMALL);
        reopen.setOnAction(e -> navigator().navigate(Routes.BOT_CHAT.id(),
                NavParams.of(PARAM_COURSE, courseCode, BotChatView.PARAM_SESSION, row.sessionId())));

        HBox line = new HBox(12, new VBox(2, preview, meta), Buttons.spacer(), reopen);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("history-row");
        return line;
    }

    private String firstCourse() {
        LoginResult user = ScreenManager.getInstance().signedInUser();
        return user == null || user.courses().isEmpty() ? "" : user.courses().get(0).code();
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /** @return the rows box, for the TestFX assertions. */
    public VBox rowsBox() {
        return rows;
    }
}
