package client.features.home;

import client.core.ScreenManager;
import client.ui.components.Buttons;
import client.ui.components.Icons;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The shared furniture of the four role dashboards (Presentation tier, E5.6).
 *
 * <p>A builder of nodes, no state and no rules — the dashboards' only logic lives
 * in {@link HomeGreeting} and {@link StudentHomeSession}. It exists because the
 * four homes share a layout (greeting header → stat row → content cards) from the
 * approved mockups, and four hand-built copies of it would drift apart by E15.
 *
 * <p><b>Honest empty states.</b> The mockups show numbers; the features that
 * produce those numbers do not exist yet. So {@link #statCard} renders an em dash
 * and names the epic that will fill it in, and list cards get a real
 * {@code EmptyState} rather than invented rows. Nothing on these screens is
 * fabricated data — a fake "3 exams pending" would survive into a demo and be
 * discovered by the audience rather than by us.
 */
public final class DashboardPage {

    /** The value shown by a stat whose feature has not landed. */
    public static final String NO_VALUE = "–";

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

    /** @return an evenly divided row of stat cards. */
    public static GridPane statGrid(Node... cards) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(16);
        for (int i = 0; i < cards.length; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / cards.length);
            column.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(column);
            grid.add(cards[i], i, 0);
        }
        return grid;
    }

    /**
     * A stat with no number yet.
     *
     * @param label what it will count ("Pending approval")
     * @param hint  when it starts counting ("Arrives with E8")
     */
    public static VBox statCard(String label, String hint) {
        Label caption = new Label(label);
        caption.getStyleClass().add("stat-label");

        Label value = new Label(NO_VALUE);
        value.getStyleClass().add("stat-value");

        Label note = new Label(hint);
        note.getStyleClass().add("stat-hint");
        note.setWrapText(true);

        VBox card = new VBox(2, caption, value, note);
        card.getStyleClass().addAll("hsts-card", "hsts-stat-card");
        return card;
    }

    /** A stat that shows a real number this epic can actually produce. */
    public static VBox statCard(String label, String hint, String realValue) {
        VBox card = statCard(label, hint);
        ((Label) card.getChildren().get(1)).setText(realValue);
        return card;
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
     * The call-to-action of a card whose screen does not exist yet: present, so
     * the dashboard reads as designed, and disabled with a tooltip saying why,
     * so nobody wonders whether they clicked it wrong.
     */
    public static Node pendingAction(String label, String reason) {
        Button button = Buttons.styled(label, Buttons.OUTLINE, Buttons.SMALL);
        button.setDisable(true);
        button.setAccessibleText(label + ", unavailable: " + reason);

        // A disabled Button consumes no hover events, so its own tooltip would
        // never appear; the wrapper is what the pointer actually hits.
        HBox holder = new HBox(button);
        holder.setAlignment(Pos.CENTER_LEFT);
        Tooltip.install(holder, new Tooltip(reason));
        return holder;
    }
}
