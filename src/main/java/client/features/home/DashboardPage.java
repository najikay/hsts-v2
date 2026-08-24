package client.features.home;

import client.core.ScreenManager;
import client.ui.anim.Animations;
import client.ui.components.Buttons;
import client.ui.components.Icons;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The shared furniture of the four role dashboards (Presentation tier, E5.6).
 *
 * <p>A builder of nodes, no state and no rules — the dashboards' only logic lives
 * in {@link HomeGreeting} and {@link StudentHomeSession}. It exists because the
 * four homes share a layout (greeting header → stat row → content cards) from the
 * approved mockups, and four hand-built copies of it would drift apart by E15.
 *
 * <p><b>Honest empty states.</b> Through E15 the mockups showed numbers no feature
 * could yet produce, so {@link #statCard} rendered a dash and named the epic that
 * would fill it in. Nothing on these screens was ever fabricated data — a fake
 * "3 exams pending" would survive into a demo and be discovered by the audience
 * rather than by us.
 *
 * <p>UI wave 1 (F-10) retired the placeholders, because the epics they named have
 * all landed. {@link #fillCardGrid} renders real counts from the role's dashboard
 * session, each card opening the screen it counted. The honesty rule did not
 * change, it moved into {@link DashboardCard.State}: a card that could not reach
 * the server says "not available" instead of showing a zero, because zero is a
 * fact about the school and a failed read is a fact about the network.
 */
public final class DashboardPage {

    private DashboardPage() {
    }

    /** @return the signed-in user's full name, or {@code ""} when shown without one. */
    public static String currentDisplayName() {
        LoginResult user = ScreenManager.getInstance().signedInUser();
        return user == null ? "" : user.displayName();
    }

    /** @return the signed-in user's courses (taught or enrolled), never {@code null}. */
    public static List<CourseRef> currentCourses() {
        LoginResult user = ScreenManager.getInstance().signedInUser();
        return user == null ? List.of() : user.courses();
    }

    /** @return a scrollable page body holding the given sections. */
    public static ScrollPane page(Node... sections) {
        VBox body = new VBox(20, sections);
        body.setPadding(new Insets(24, 28, 28, 28));

        ScrollPane scroller = new ScrollPane(body);
        scroller.setFitToWidth(true);
        scroller.getStyleClass().add("hsts-page");
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroller;
    }

    /**
     * The greeting header (F1.2: the dashboard says who you are and when).
     *
     * @param displayName full name from the login result
     * @param now         injected so the greeting is deterministic in tests
     */
    public static VBox header(String displayName, LocalDateTime now) {
        Label greeting = new Label(HomeGreeting.greeting(displayName, now));
        greeting.getStyleClass().add("h1");

        Label date = new Label(HomeGreeting.dateLine(now.toLocalDate()));
        date.getStyleClass().addAll("small", "muted");

        // No .hsts-page-header class: that one carries its own padding, and this
        // header already sits inside the padded page body.
        return new VBox(4, greeting, date);
    }

    /**
     * Fills a grid with the cards a dashboard session produced, with the house
     * staggered entrance (UI wave 1 — F-10).
     *
     * <p>Rebuilt rather than patched on every render, because a card's whole
     * shape changes with its state and there are at most three of them. The
     * stagger is what makes a settling dashboard read as arriving rather than
     * flickering: three reads land at three different moments, and without it
     * each one appears as an abrupt substitution.
     *
     * @param grid     the grid to fill, cleared first
     * @param cards    what to render
     * @param navigate what a card click does with its route id
     */
    public static void fillCardGrid(GridPane grid, List<DashboardCard> cards,
                                    Consumer<String> navigate) {
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        grid.setHgap(16);
        grid.setVgap(16);

        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / cards.size());
            column.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(column);

            Node node = navCard(cards.get(i), navigate);
            grid.add(node, i, 0);
            nodes.add(node);
        }
        Animations.staggerIn(nodes);
    }

    /** @return a titled content card wrapping {@code body}. */
    public static VBox card(String title, String subtitle, Node body) {
        Label heading = new Label(title);
        heading.getStyleClass().add("h3");

        VBox headingBox = new VBox(2, heading);
        if (subtitle != null && !subtitle.isBlank()) {
            Label sub = new Label(subtitle);
            sub.getStyleClass().addAll("small", "muted");
            sub.setWrapText(true);
            headingBox.getChildren().add(sub);
        }

        VBox card = new VBox(14, headingBox, body);
        card.getStyleClass().add("hsts-card");
        VBox.setVgrow(body, Priority.ALWAYS);
        return card;
    }

    /**
     * The one card on these dashboards carrying real data: the courses the login
     * result actually returned (taught, or enrolled in).
     */
    public static VBox coursesCard(String title, String subtitle, List<CourseRef> courses,
                                   String emptyText) {
        Node body;
        if (courses == null || courses.isEmpty()) {
            Label empty = new Label(emptyText);
            empty.getStyleClass().addAll("small", "muted");
            empty.setWrapText(true);
            body = empty;
        } else {
            VBox rows = new VBox(8);
            for (CourseRef course : courses) {
                rows.getChildren().add(courseRow(course));
            }
            body = rows;
        }
        return card(title, subtitle, body);
    }

    private static HBox courseRow(CourseRef course) {
        Label code = new Label(course.code());
        code.getStyleClass().addAll("hsts-badge", "accent");

        Label name = new Label(course.name());
        name.getStyleClass().add("strong");

        HBox row = new HBox(10, Icons.of(Icons.BANK, Icons.SIZE_DEFAULT, "nav-icon"), name,
                Buttons.spacer(), code);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Renders one {@link DashboardCard} as a clickable stat card (UI wave 1 — F-10).
     *
     * <p>The whole card is the hit target, not a link inside it. A card that shows
     * a count of things and does not open the list of those things is a poster,
     * and the empty placeholders these replaced were exactly that.
     *
     * <p>The state drives a style class rather than a different node shape, so a
     * card that arrives loading and settles to a number does not change size
     * underneath the pointer.
     *
     * @param navigate what a click does with the card's route id
     */
    public static VBox navCard(DashboardCard card, Consumer<String> navigate) {
        Label caption = new Label(card.title());
        caption.getStyleClass().add("stat-label");

        Label value = new Label(card.value());
        value.getStyleClass().add("stat-value");

        Label hint = new Label(card.hint());
        hint.getStyleClass().add("stat-hint");
        hint.setWrapText(true);

        VBox box = new VBox(2, caption, value, hint);
        box.getStyleClass().addAll("hsts-card", "hsts-stat-card", "hsts-dashboard-card");
        box.getStyleClass().add("state-" + card.state().name().toLowerCase(Locale.ROOT));
        box.setOnMouseClicked(event -> navigate.accept(card.routeId()));
        box.setAccessibleText(card.title() + ", " + card.value() + ". " + card.hint());
        return box;
    }
}
