package client.features.home;

import client.core.ScreenManager;
import client.ui.anim.Animations;
import client.ui.components.Buttons;
import client.ui.components.Icons;
import client.ui.components.Kicker;
import client.ui.components.NumberRoll;
import client.ui.components.Sparkbars;
import client.ui.components.StatusChip;
import client.ui.components.logic.ChipSpec;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The shared furniture of the four role dashboards (Presentation tier, E5.6;
 * remodelled in UI wave 2).
 *
 * <p>A builder of nodes, no state and no rules — every decision the dashboards
 * make lives in {@link HomeGreeting}, {@link DashboardSummary},
 * {@link StudentHomeSession} and the four sessions, all of which are FX-free and
 * measured. It exists because the four homes share a layout and four hand-built
 * copies of it would drift apart by E15.
 *
 * <h2>What wave 2 changed here</h2>
 *
 * <p>The header gained a live sentence under the greeting, composed by
 * {@link DashboardSummary} from what the cards themselves loaded. The cards
 * gained a kicker, a chip and a link line, all of which arrive on
 * {@link DashboardCard} rather than being decided here. And two of the teacher's
 * cards render a richer body when the session has the detail for it: the live
 * sitting's progress and its students, and the last closed sitting's
 * distribution.
 *
 * <p><b>The rich bodies are still driven by values.</b>
 * {@link TeacherDashboardSession.LiveDetail} and
 * {@link TeacherDashboardSession.ClosedDetail} are records built and tested in
 * the session; this class turns them into nodes and decides nothing about what
 * they contain. That is what keeps a screen on the coverage exclusion list
 * honest.
 */
public final class DashboardPage {

    /**
     * Property key under which a grid keeps its {@link NumberRoll}s.
     *
     * <p>The grid is rebuilt from scratch on every settle, because a card's whole
     * shape changes with its state. A roll built inside that rebuild would be a
     * brand-new node showing the new value, and the animation the motion spec
     * asks for would never play — the feature would exist and never run. So the
     * rolls outlive the cards that hold them, keyed by the card's kicker rather
     * than by its position, and are moved into each freshly built card.
     */
    private static final Object ROLL_KEY = new Object();

    /** Reserved key for the live card's submitted count, which is not a card value. */
    private static final String SUBMITTED_ROLL = "live.submitted";

    /** "10:30" — the closing time on the live card, in the reader's own zone. */
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

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
     * The greeting header (F1.2: the dashboard says who you are and when; UI
     * wave 2: and what today contains).
     *
     * <p>Three lines rather than two. The middle one is the whole point of the
     * remodel: a sentence, in muted text, saying what is actually happening —
     * built from the cards' own numbers, never from a read of its own.
     *
     * @param displayName full name from the login result
     * @param now         injected so the greeting is deterministic in tests
     * @param summary     the sentence from the role's session
     */
    public static VBox header(String displayName, LocalDateTime now, String summary) {
        Label greeting = new Label(HomeGreeting.greeting(displayName, now));
        greeting.getStyleClass().addAll("h1", "greeting-line");

        Label summaryLine = new Label(summary);
        summaryLine.getStyleClass().addAll("body", "muted", "greeting-summary");
        summaryLine.setWrapText(true);

        Label date = new Label(HomeGreeting.dateLine(now.toLocalDate()));
        date.getStyleClass().addAll("small", "faint");

        // No .hsts-page-header class: that one carries its own padding, and this
        // header already sits inside the padded page body.
        VBox box = new VBox(6, greeting, summaryLine, date);
        box.getStyleClass().add("hsts-greeting");
        return box;
    }

    /**
     * Fills a grid with the cards a dashboard session produced, with the wave-2
     * staggered entrance.
     *
     * <p>Rebuilt rather than patched on every render, because a card's whole
     * shape changes with its state. The stagger is what makes a settling
     * dashboard read as arriving rather than flickering: the reads land at
     * different moments, and without it each one appears as an abrupt
     * substitution.
     *
     * @param grid     the grid to fill, cleared first
     * @param cards    what to render
     * @param navigate what a card click does with its route id
     */
    public static void fillCardGrid(GridPane grid, List<DashboardCard> cards,
                                    Consumer<String> navigate) {
        fillCardGrid(grid, cards, navigate, List.of());
    }

    /**
     * Fills a grid, letting named cards render a richer body.
     *
     * @param bodies one optional extra node per card, by index; a shorter list
     *               or a {@code null} entry simply means "the plain card"
     */
    public static void fillCardGrid(GridPane grid, List<DashboardCard> cards,
                                    Consumer<String> navigate, List<Node> bodies) {
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        grid.getStyleClass().add("hsts-card-grid");
        grid.setHgap(16);
        grid.setVgap(16);

        int columns = Math.max(1, Math.min(cards.size(), 4));
        for (int i = 0; i < columns; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / columns);
            column.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(column);
        }

        Map<String, NumberRoll> rolls = rollsOf(grid);
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            DashboardCard card = cards.get(i);
            Node body = i < bodies.size() ? bodies.get(i) : null;
            NumberRoll value = rolls.computeIfAbsent(card.kicker(),
                    key -> new NumberRoll(card.value(), "stat-value"));
            // No-ops when the value has not changed, which is most renders: the
            // teacher's four reads settle at four different moments and only one
            // card's number moves each time.
            value.set(card.value());

            Node node = navCard(card, navigate, body, value);
            grid.add(node, i % columns, i / columns);
            nodes.add(node);
        }
        Animations.staggerCards(nodes);
    }

    /**
     * @param grid the card grid
     * @return the roll a live card's submitted count should use, created once and
     *         kept for the life of the grid so the count rolls rather than snaps
     */
    public static NumberRoll submittedRoll(GridPane grid) {
        return rollsOf(grid).computeIfAbsent(SUBMITTED_ROLL,
                key -> new NumberRoll("0", "live-submitted-count"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, NumberRoll> rollsOf(GridPane grid) {
        Object existing = grid.getProperties().get(ROLL_KEY);
        if (existing instanceof Map<?, ?> map) {
            return (Map<String, NumberRoll>) map;
        }
        Map<String, NumberRoll> rolls = new HashMap<>();
        grid.getProperties().put(ROLL_KEY, rolls);
        return rolls;
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

    // ===================== The stat card =================================

    /**
     * Renders one {@link DashboardCard} (UI wave 1 — F-10; restyled in wave 2).
     *
     * <p>The whole card is the hit target, not a link inside it. A card that
     * shows a count of things and does not open the list of those things is a
     * poster, and the empty placeholders these replaced were exactly that. The
     * link line at the bottom names the destination anyway, because a hit target
     * with no visible affordance is a card users do not know is clickable.
     *
     * <p>The state drives a style class rather than a different node shape, so a
     * card that arrives loading and settles to a number does not change size
     * underneath the pointer.
     *
     * @param navigate what a click does with the card's route id
     * @param extra    a richer body between the value and the link, or {@code null}
     */
    public static VBox navCard(DashboardCard card, Consumer<String> navigate, Node extra,
                               NumberRoll value) {
        Label kicker = Kicker.label(card.kicker());

        HBox top = new HBox(8, kicker, Buttons.spacer());
        top.setAlignment(Pos.CENTER_LEFT);
        card.statusChip().map(DashboardPage::chip).ifPresent(pill ->
                top.getChildren().add(pill));

        Label hint = new Label(card.hint());
        hint.getStyleClass().add("stat-hint");
        hint.setWrapText(true);

        VBox box = new VBox(6, top, value, hint);
        if (extra != null) {
            box.getChildren().add(extra);
            VBox.setVgrow(extra, Priority.ALWAYS);
        }
        box.getChildren().add(Buttons.spacer());
        box.getChildren().add(link(card.linkText()));

        box.getStyleClass().addAll("hsts-card", "hsts-stat-card", "hsts-dashboard-card");
        box.getStyleClass().add("state-" + card.state().name().toLowerCase(Locale.ROOT));
        box.setOnMouseClicked(event -> navigate.accept(card.routeId()));
        box.setAccessibleText(card.title() + ", " + card.value() + ". " + card.hint());
        Animations.liftOnHover(box);
        return box;
    }

    /** @see #navCard(DashboardCard, Consumer, Node, NumberRoll) */
    public static VBox navCard(DashboardCard card, Consumer<String> navigate) {
        return navCard(card, navigate, null, new NumberRoll(card.value(), "stat-value"));
    }

    /**
     * The accent line at the bottom of a card.
     *
     * <p>The arrow is added here rather than stored in {@link DashboardCopy},
     * because it is a glyph and not a word: a copy test that scanned it would be
     * checking the sentence case of an arrow.
     */
    private static Label link(String text) {
        Label label = new Label(text + " →");
        label.getStyleClass().add("card-link");
        return label;
    }

    private static StatusChip chip(ChipSpec spec) {
        return new StatusChip(spec);
    }

    // ===================== The teacher's rich bodies =====================

    /**
     * The live sitting's body: exam, code, a progress bar and up to three
     * students (UI wave 2).
     *
     * <p>The pulsing dot and its halo are the app's one permitted ambient loop
     * besides the breathing empty state, and it runs here only because something
     * genuinely is: this node is built from a {@link TeacherDashboardSession.LiveDetail},
     * which exists only while a sitting's monitor answered as live.
     */
    public static VBox liveBody(TeacherDashboardSession.LiveDetail detail, ZoneId zone,
                                NumberRoll submitted) {
        Circle dot = new Circle(5);
        dot.getStyleClass().add("live-dot");
        Circle ring = new Circle(5);
        ring.getStyleClass().add("live-halo");
        StackPane pulse = new StackPane(ring, dot);
        pulse.setMinSize(14, 14);
        pulse.setPrefSize(14, 14);
        Animations.livePulse(ring);

        Label name = new Label(detail.examName());
        name.getStyleClass().addAll("strong", "live-exam");
        name.setWrapText(true);

        HBox title = new HBox(8, pulse, name);
        title.setAlignment(Pos.CENTER_LEFT);

        Label code = new Label(DashboardCopy.codeLine(detail.code(), closingTime(detail, zone)));
        code.getStyleClass().addAll("small", "muted");

        Region fill = new Region();
        fill.getStyleClass().add("live-progress-fill");
        StackPane track = new StackPane(fill);
        track.getStyleClass().add("live-progress-track");
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        // Bound rather than set: the card is laid out inside a percentage-width
        // grid column, so its width is not known when it is built.
        fill.maxWidthProperty().bind(track.widthProperty().multiply(detail.progress()));

        submitted.set(Integer.toString(detail.submitted()));
        Label ofTotal = new Label(DashboardCopy.submittedSuffix(detail.sitting()));
        ofTotal.getStyleClass().addAll("small", "muted");

        HBox caption = new HBox(4, submitted, ofTotal);
        caption.setAlignment(Pos.CENTER_LEFT);
        // The roll holds only the digits, so the phrase is reassembled here for a
        // screen reader rather than being read as a number and then a fragment.
        caption.setAccessibleText(
                DashboardCopy.submittedLine(detail.submitted(), detail.sitting()));

        Label left = new Label(DashboardCopy.timeLeftLine(detail.minutesLeft()));
        left.getStyleClass().addAll("small", "faint");

        HBox counts = new HBox(8, caption, Buttons.spacer(), left);
        counts.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(8, title, code, track, counts);
        body.getStyleClass().add("live-body");
        for (TeacherDashboardSession.StudentLine student : detail.students()) {
            body.getChildren().add(studentRow(student));
        }
        if (detail.more()) {
            Label more = new Label(DashboardCopy.LIVE_MORE);
            more.getStyleClass().addAll("small", "faint");
            body.getChildren().add(more);
        }
        return body;
    }

    private static HBox studentRow(TeacherDashboardSession.StudentLine student) {
        Label name = new Label(student.name());
        name.getStyleClass().addAll("small", "live-student");

        HBox row = new HBox(8, name, Buttons.spacer(), new StatusChip(student.chip()));
        row.getStyleClass().add("live-student-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * The last-closed sitting's body: how many passed, and the shape of the
     * class, as ten slim bars (UI wave 2).
     */
    public static VBox closedBody(TeacherDashboardSession.ClosedDetail detail) {
        Label passed = new Label(DashboardCopy.passedLine(detail.passed(), detail.sat()));
        passed.getStyleClass().addAll("small", "muted");

        VBox body = new VBox(8, passed, new Sparkbars(detail.deciles()));
        body.getStyleClass().add("closed-body");
        return body;
    }

    /**
     * @return the closing time in the reader's zone, or the "closing now"
     *         sentence when the sitting has run past it. The wire is UTC
     *         (ADR-010) and this is the tier that converts
     */
    private static String closingTime(TeacherDashboardSession.LiveDetail detail, ZoneId zone) {
        if (detail.closesAt() == null) {
            return DashboardCopy.LIVE_CLOSING.toLowerCase(Locale.ENGLISH);
        }
        return CLOCK.format(detail.closesAt().atZone(zone));
    }
}
