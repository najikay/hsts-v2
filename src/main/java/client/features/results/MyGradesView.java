package client.features.results;

import client.core.NavParams;
import client.core.Routes;
import client.ui.anim.Animations;
import client.ui.components.Buttons;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.components.Kicker;
import client.ui.components.ProgressRing;
import client.ui.components.Skeletons;
import client.ui.components.StatusChip;
import client.ui.components.logic.ChipSpec;
import client.ui.components.logic.ChipTone;
import client.ui.screen.AbstractScreen;
import common.dto.grading.StudentGradeRow;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The student's <b>My Grades</b> screen (Presentation tier, E13.3 — F9.1, T-9.1;
 * remodelled in UI wave 2).
 *
 * <p>A renderer over {@link MyGradesSession} and nothing else. Every decision this screen
 * appears to make is made elsewhere and measured there: what state the list is in comes from
 * the session, what the term average is comes from the session, and every string and formatted
 * value comes from {@link MyGradesCopy}. That is why this class is on the coverage exclusion
 * list by name, on the same terms as every other view in the product.
 *
 * <h2>What wave 2 changed, and what it deliberately did not</h2>
 *
 * <p>The table became a <b>hero band and a grid of cards</b>. This is the one screen in the
 * app whose entire content is a set of numbers about the person reading it, and a five-column
 * table is the wrong shape for that: it presents a student's own transcript as a spreadsheet
 * row among rows. The hero states the one number she came for, and each grade gets a card.
 *
 * <p><b>It is a view swap and nothing more.</b> The session is untouched: the same read, the
 * same push subscription, the same states. The drill-in is the same navigation to the same
 * checked form with the same parameter, so the marked paper and its print layout are reached
 * exactly as before. Nothing about the wire, the verbs or the rules moved.
 *
 * <h2>No refresh control, anywhere</h2>
 *
 * <p>The list loads when the screen opens and re-reads itself when a grade is published
 * (NFR-18, E13.6). A refresh button here would be an admission that the push cannot be trusted,
 * and a student pressing it would be doing the application's job.
 *
 * <h2>What a student is shown, and what she is not</h2>
 *
 * <p>Exam, course, teacher, grade, approval date and the teacher's note. An adjusted grade
 * carries a marker saying a teacher reviewed it — and never the justification, which the wire strips
 * structurally before it reaches this tier (S-23). The distinction is the whole reason
 * {@link MyGradesCopy} is a separate file from {@link ResultsCopy}: the same row, two
 * audiences, two vocabularies.
 */
public final class MyGradesView extends AbstractScreen {

    /** Local zone for the approval dates; the wire is UTC (ADR-010). */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** The approved grid width. Three cards read as a set; four read as a table. */
    private static final int COLUMNS = 3;

    private final VBox root = new VBox(16);
    private final VBox heroHost = new VBox();
    private final GridPane grid = new GridPane();
    private final StackPane body = new StackPane();
    private final Node skeleton = Skeletons.list(3);
    private final Label error = new Label();

    private MyGradesSession session;
    private Node scroller;
    private Node errorState;
    private Node emptyState;

    @Override
    protected Parent build() {
        session = new MyGradesSession(dispatcher(), onFxThread())
                .onChange(this::render)
                .subscribeTo(eventBus());

        root.getStyleClass().add(MyGradesCopy.STYLE_CLASS);
        root.setPadding(new Insets(24));
        root.getChildren().addAll(buildHeader(), heroHost, buildError(), buildBody());
        VBox.setVgrow(body, Priority.ALWAYS);
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        session.load();
    }

    @Override
    public boolean listensToEvents() {
        // The session subscribes itself in build(), so the live refresh is wired where it can
        // be tested. This screen has no @Subscribe method of its own.
        return false;
    }

    // ===================== Layout ========================================

    private VBox buildHeader() {
        Label title = new Label(MyGradesCopy.TITLE);
        title.getStyleClass().add("h1");

        Label subtitle = new Label(MyGradesCopy.SUBTITLE);
        subtitle.getStyleClass().addAll("small", "muted");
        subtitle.setWrapText(true);

        VBox header = new VBox(4, title, subtitle);
        header.getStyleClass().add("my-grades-header");
        return header;
    }

    private Label buildError() {
        error.getStyleClass().addAll("small", "danger-text");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);
        return error;
    }

    /**
     * The scrolling content area.
     *
     * <p>The grid scrolls inside its own container rather than growing the page:
     * a student with two terms of marks would otherwise push the hero — the one
     * thing she opened the screen for — off the top.
     */
    private StackPane buildBody() {
        grid.getStyleClass().add("grades-grid");
        grid.setHgap(16);
        grid.setVgap(16);
        for (int i = 0; i < COLUMNS; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / COLUMNS);
            column.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(column);
        }

        ScrollPane pane = new ScrollPane(grid);
        pane.setFitToWidth(true);
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pane.getStyleClass().add("grades-scroller");
        scroller = pane;

        errorState = EmptyState.error(MyGradesSession.LOAD_FAILED, () -> session.load());
        emptyState = new EmptyState(Icons.RESULTS, MyGradesCopy.EMPTY_SLOT_TITLE,
                MyGradesSession.NOTHING_YET);

        body.getChildren().addAll(pane, skeleton, emptyState, errorState);
        return body;
    }

    // ===================== The hero band =================================

    /**
     * The accent band at the top of the screen (UI wave 2).
     *
     * <p>A ring carrying the term average, one warm sentence, and a right-hand
     * "next exam" slot that is shown only when {@link MyGradesSession#nextExam()}
     * has one. It never does in this build, and that is a wire fact recorded on
     * that method rather than a hole here: no verb answers "which sitting is next
     * for me", so the slot is built, driven and hidden.
     */
    private Node hero(List<StudentGradeRow> grades) {
        ProgressRing ring = new ProgressRing(MyGradesSession.termAverage(grades));

        Label kicker = Kicker.label(MyGradesCopy.HERO_KICKER);
        kicker.getStyleClass().add("on-accent");

        Label title = new Label(MyGradesCopy.HERO_TITLE);
        title.getStyleClass().addAll("h2", "on-accent");

        Label count = new Label(MyGradesCopy.heroCount(grades.size(), session.courseCount()));
        count.getStyleClass().addAll("small", "on-accent-muted");

        Label warm = new Label(grades.isEmpty()
                ? MyGradesCopy.HERO_WARM_EMPTY : MyGradesCopy.HERO_WARM);
        warm.getStyleClass().addAll("small", "on-accent-muted");
        warm.setWrapText(true);

        VBox words = new VBox(4, kicker, title, count, warm);
        words.setAlignment(Pos.CENTER_LEFT);

        HBox band = new HBox(20, ring, words, Buttons.spacer());
        band.setAlignment(Pos.CENTER_LEFT);
        band.getStyleClass().add("grades-hero");

        session.nextExam().ifPresent(next -> band.getChildren().add(nextExamSlot(next)));
        // The highlight is a node rather than a gradient stop: JavaFX cannot
        // derive a lighter shade of a looked-up colour inside a linear-gradient,
        // so the band is flat -hsts-accent with a soft radial wash over it. That
        // is the approved fallback, and it is the one that survives all five
        // accent palettes without a per-palette gradient to keep in step.
        StackPane wash = new StackPane();
        wash.getStyleClass().add("grades-hero-wash");
        wash.setMouseTransparent(true);

        StackPane hero = new StackPane(band, wash);
        hero.getStyleClass().add("grades-hero-host");
        return hero;
    }

    private VBox nextExamSlot(String next) {
        Label label = Kicker.label(MyGradesCopy.NEXT_EXAM_LABEL);
        label.getStyleClass().add("on-accent");

        Label value = new Label(next);
        value.getStyleClass().addAll("strong", "on-accent");
        value.setWrapText(true);

        VBox slot = new VBox(2, label, value);
        slot.setAlignment(Pos.CENTER_RIGHT);
        slot.getStyleClass().add("grades-next-exam");
        return slot;
    }

    // ===================== The card grid =================================

    /**
     * One grade, as a card.
     *
     * <p>The whole card opens the marked paper, on one click, which is the same
     * gesture wave 1 gave the table row (F-8) pointed at the same route with the
     * same parameter. The server re-checks all three of its conditions, so a card
     * that opens nothing is a refusal and not a broken link: the checked form
     * says so itself rather than this grid trying to predict it.
     */
    private Node gradeCard(StudentGradeRow row) {
        Label course = Kicker.label(MyGradesCopy.courseCode(row));

        ChipSpec spec = MyGradesCopy.passed(row)
                ? ChipSpec.of(MyGradesCopy.CHIP_PASSED, ChipTone.OK)
                : ChipSpec.of(MyGradesCopy.CHIP_BELOW, ChipTone.WARN);
        HBox top = new HBox(8, course, Buttons.spacer(), new StatusChip(spec));
        top.setAlignment(Pos.CENTER_LEFT);

        Label name = new Label(MyGradesCopy.examName(row));
        name.getStyleClass().addAll("h3", "grade-exam");
        name.setWrapText(true);

        Label score = new Label(MyGradesCopy.score(row));
        score.getStyleClass().add("grade-score");

        Label date = new Label(MyGradesCopy.approvedOn(row, ZONE));
        date.getStyleClass().addAll("small", "faint");

        Label open = new Label(MyGradesCopy.CARD_OPEN + " →");
        open.getStyleClass().add("card-link");

        HBox footer = new HBox(8, date, Buttons.spacer(), open);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, top, name);

        // A7: whose exam this was, directly under its name and above the number, in the same
        // muted line the marked paper uses. Left out entirely when the server could not resolve
        // a name — a label with nothing after it reads as data that failed to load.
        addLine(card, MyGradesCopy.teacherLine(row), "grade-teacher");

        card.getChildren().add(score);
        if (MyGradesCopy.wasAdjusted(row)) {
            Label adjusted = new Label(MyGradesCopy.ADJUSTED_MARKER);
            adjusted.getStyleClass().addAll("small", "muted");
            adjusted.setWrapText(true);
            card.getChildren().add(adjusted);
        }

        // A3's comment finally on the list it was always on the wire for. Under the score,
        // because it is about the score, and wrapped rather than clipped: a teacher's sentence
        // to one student is not a cell that can be allowed to end in the middle of a word.
        addLine(card, MyGradesCopy.noteLine(row), "grade-note");

        card.getChildren().addAll(Buttons.spacer(), footer);
        card.getStyleClass().addAll("hsts-card", "grade-card");
        card.setOnMouseClicked(event -> navigator().navigate(Routes.CHECKED_FORM.id(),
                NavParams.of("gradeId", row.gradeId())));
        card.setAccessibleText(MyGradesCopy.rowDescription(row, ZONE));
        Animations.liftOnHover(card);
        return card;
    }

    /**
     * Appends one muted line to a card, or nothing at all (A7).
     *
     * <p>A helper rather than two copies of four lines, because the two lines it draws follow
     * exactly the same rule — {@link MyGradesCopy} answers {@code null} for "there is nothing to
     * say", and the card's answer to that is a card without the line rather than a line without
     * a value.
     */
    private static void addLine(VBox card, String text, String styleClass) {
        if (text == null) {
            return;
        }
        Label line = new Label(text);
        line.getStyleClass().addAll("small", "muted", styleClass);
        line.setWrapText(true);
        card.getChildren().add(line);
    }

    /**
     * The dashed placeholder that closes the grid.
     *
     * <p>It is not an empty state — the grid it sits in is full of real grades.
     * It is the shape of the next card, saying what puts one there, which is the
     * same rule the dashboards' empty cards follow: name what fills it rather
     * than restate the absence.
     */
    private Node emptySlot() {
        StackPane disc = new StackPane(Icons.of(Icons.RESULTS, 24, "slot-icon"));
        disc.getStyleClass().add("slot-disc");

        Label hint = new Label(MyGradesCopy.EMPTY_SLOT_HINT);
        hint.getStyleClass().addAll("small", "muted");
        hint.setWrapText(true);

        VBox slot = new VBox(10, disc, hint);
        slot.setAlignment(Pos.CENTER);
        slot.getStyleClass().add("grade-slot");
        return slot;
    }

    // ===================== Rendering =====================================

    private void render() {
        String message = session.error().orElse("");
        error.setText(message);
        error.setVisible(!message.isEmpty());
        error.setManaged(!message.isEmpty());

        List<StudentGradeRow> grades = session.grades();
        heroHost.getChildren().setAll(hero(grades));

        switch (session.state()) {
            case LOADING -> show(true, false, false, false);
            case ERROR -> show(false, false, false, true);
            case EMPTY -> show(false, false, true, false);
            default -> {
                fillGrid(grades);
                show(false, true, false, false);
            }
        }
    }

    private void fillGrid(List<StudentGradeRow> grades) {
        grid.getChildren().clear();
        List<Node> cards = new ArrayList<>();
        for (StudentGradeRow row : grades) {
            cards.add(gradeCard(row));
        }
        cards.add(emptySlot());
        for (int i = 0; i < cards.size(); i++) {
            grid.add(cards.get(i), i % COLUMNS, i / COLUMNS);
        }
        Animations.staggerCards(cards);
    }

    private void show(boolean loading, boolean content, boolean empty, boolean failed) {
        setShown(skeleton, loading);
        setShown(scroller, content);
        setShown(emptyState, empty);
        setShown(errorState, failed);
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
