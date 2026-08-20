package client.features.bot;

import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.ui.components.Buttons;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.screen.AbstractScreen;
import common.dto.auth.LoginResult;
import common.dto.bot.BotTopQuestion;
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

import java.time.format.DateTimeFormatter;

/**
 * The teacher's anonymised view of how her bot is used (Presentation tier,
 * E16.15 — F12.11, S-34 ⚑).
 *
 * <p>Three things: how many questions, when they were asked, and which ones come
 * up most. Nothing on this screen can be clicked through to a person, because
 * {@code BotAnalytics} has no field that could name one — the anonymity is a
 * property of the data this view receives, not of a decision this view makes.
 *
 * <p>{@link BotCopy#ANONYMOUS_NOTE} is shown always, including on the empty state.
 * S-34 is a promise made to students about a screen they never see, and stating it
 * where it applies is how the teacher knows what she is and is not looking at.
 */
public final class BotAnalyticsView extends AbstractScreen {

    /** Nav parameter naming the taught course to report on. */
    public static final String PARAM_COURSE = "courseCode";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM");

    private final BorderPane root = new BorderPane();
    private final Label heading = new Label(BotCopy.ANALYTICS_TITLE);
    private final Label subheading = new Label();
    private final Label status = new Label();
    private final Label totalValue = new Label("0");
    private final Label busiestValue = new Label("-");
    private final ActivityBars chart = new ActivityBars();
    private final VBox frequent = new VBox(6);
    private final EmptyState empty = new EmptyState(Icons.RESULTS,
            BotCopy.ANALYTICS_EMPTY_TITLE, BotCopy.ANALYTICS_EMPTY_HINT);
    private final VBox content = new VBox(16);

    private BotAnalyticsSession session;
    private String courseCode = "";

    @Override
    protected Parent build() {
        root.getStyleClass().add("bot-analytics");
        root.setTop(buildHeader());
        root.setCenter(buildBody());
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        String requested = params.getString(PARAM_COURSE, "");
        String resolved = requested.isBlank() ? firstCourse() : requested;
        if (session == null || !resolved.equalsIgnoreCase(courseCode)) {
            courseCode = resolved;
            session = new BotAnalyticsSession(dispatcher(), resolved);
            session.onChange(() -> onFxThread().run(this::render));
        }
        session.refresh();
    }

    private Parent buildHeader() {
        heading.getStyleClass().add("page-title");
        subheading.getStyleClass().add("page-subtitle");
        Button manager = Buttons.secondary(BotCopy.MANAGER_TITLE);
        manager.setOnAction(e -> navigator().navigate(Routes.BOT_MANAGER.id(),
                NavParams.of(PARAM_COURSE, courseCode)));
        HBox row = new HBox(12, new VBox(2, heading, subheading), Buttons.spacer(), manager);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(16, 20, 8, 20));
        return row;
    }

    private Parent buildBody() {
        status.getStyleClass().add("bot-status");
        status.setWrapText(true);
        status.setVisible(false);
        status.setManaged(false);

        Label note = new Label(BotCopy.ANONYMOUS_NOTE);
        note.getStyleClass().add("anonymous-note");
        note.setWrapText(true);

        HBox stats = new HBox(12,
                statCard(BotCopy.TOTAL_QUESTIONS, totalValue),
                statCard(BotCopy.BUSIEST_DAY, busiestValue));

        Label chartTitle = new Label(BotCopy.ACTIVITY_TITLE);
        chartTitle.getStyleClass().add("section-title");
        Label frequentTitle = new Label(BotCopy.FREQUENT_TITLE);
        frequentTitle.getStyleClass().add("section-title");

        content.getChildren().addAll(stats, chartTitle, chart, frequentTitle, frequent);
        VBox body = new VBox(12, status, note, empty, content);
        body.setPadding(new Insets(0, 20, 20, 20));
        ScrollPane scroller = new ScrollPane(body);
        scroller.setFitToWidth(true);
        VBox.setVgrow(scroller, Priority.ALWAYS);
        return scroller;
    }

    private static Node statCard(String label, Label value) {
        value.getStyleClass().add("stat-value");
        Label caption = new Label(label);
        caption.getStyleClass().add("stat-caption");
        VBox card = new VBox(2, value, caption);
        card.getStyleClass().addAll("card", "stat-card");
        card.setPadding(new Insets(14, 18, 14, 18));
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private void render() {
        if (session == null) {
            return;
        }
        subheading.setText(session.analytics().courseName());
        String message = session.status();
        status.setText(message);
        setShown(status, !message.isBlank());

        boolean hasData = !session.analytics().isEmpty();
        setShown(empty, session.isLoaded() && !hasData && message.isBlank());
        setShown(content, hasData);

        totalValue.setText(String.valueOf(session.analytics().totalQuestions()));
        busiestValue.setText(session.busiestDay()
                .map(point -> DAY.format(point.day()) + " (" + point.count() + ")")
                .orElse("-"));
        chart.setPoints(session.activity());

        frequent.getChildren().clear();
        for (BotTopQuestion question : session.frequent()) {
            frequent.getChildren().add(frequentRow(question));
        }
    }

    private static Node frequentRow(BotTopQuestion question) {
        Label text = new Label(question.question());
        text.setWrapText(true);
        text.getStyleClass().add("frequent-question");
        Label count = new Label(question.timesLabel());
        count.getStyleClass().add("frequent-count");
        HBox row = new HBox(12, text, Buttons.spacer(), count);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("frequent-row");
        return row;
    }

    private String firstCourse() {
        LoginResult user = ScreenManager.getInstance().signedInUser();
        return user == null || user.courses().isEmpty() ? "" : user.courses().get(0).code();
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /** @return the frequent-questions list, for the TestFX assertions. */
    public VBox frequentBox() {
        return frequent;
    }

    /** @return the total-questions stat value, for the TestFX assertions. */
    public Label totalValue() {
        return totalValue;
    }
}
