package client.features.results;

import client.core.NavParams;
import client.ui.components.BackLink;
import client.ui.components.Buttons;
import client.ui.components.DataTable;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.components.StatChart;
import client.ui.screen.AbstractScreen;
import common.dto.grading.StudentGradeRow;
import common.dto.results.ExamResultRow;
import common.dto.results.ExecutionResultRow;
import common.dto.results.ExecutionResults;
import common.dto.results.ResultStatistics;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;

/**
 * The teacher's results and statistics screen (Presentation tier, E14.2/E14.3b/E14.4 — F9.2).
 *
 * <p>A renderer over {@link TeacherResultsSession}. Her exams down the left, an execution
 * picker across the top, the six stat cards, and then either the sortable student table or the
 * histogram, chosen by one segmented toggle (T-10).
 *
 * <p>Thin, on the {@code ExecutionMonitorView} pattern: every decision — which sitting opens
 * first, what the cards say, what the chart is fed, whether grading is finished — is made in
 * {@link TeacherResultsSession} and {@link ResultsCopy}, both of which are measured and tested.
 * This class owns nodes and nothing else, which is why it is on the coverage exclusion list by
 * name.
 *
 * <h2>The chart is wired, not rebuilt</h2>
 *
 * <p>{@link StatChart} arrived complete in E14.3. All that happens here is
 * {@code chart.setData(session.chartData())} — the stored deciles, mean, median, population σ
 * and count, straight from the wire. No geometry, no bucketing and no statistics are computed
 * anywhere on this screen (F8.5, H14.4 ⚑).
 *
 * <h2>Print layout (E14.4)</h2>
 *
 * <p>The print toggle drops the exam rail, flattens the cards through one style class, and
 * leaves the table and the chart stacked in a single column. Modest on purpose: what a teacher
 * needs from "print" here is the numbers without the application around them, and the report
 * engine (F9.4) is where a real export belongs.
 */
public final class TeacherResultsView extends AbstractScreen {

    /** Local zone for every wire instant on this screen; the wire is UTC (ADR-010). */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final BorderPane root = new BorderPane();

    private final ListView<ExamResultRow> examList = new ListView<>();
    private final VBox examRail = new VBox(10);

    private final Label examName = new Label();
    private final Label examMeta = new Label();
    private final ComboBox<ExecutionResultRow> executionPicker = new ComboBox<>();
    private final Label releasedByOther = new Label("Run by another teacher");
    private final Label markedLabel = new Label();

    private final HBox statCards = new HBox(12);
    private final EmptyState unfinished = new EmptyState(Icons.RESULTS,
            ResultsCopy.GRADING_UNFINISHED_TITLE, ResultsCopy.GRADING_UNFINISHED_HINT);

    private final ToggleButton tableSegment = new ToggleButton("Table");
    private final ToggleButton chartSegment = new ToggleButton("Histogram");
    private final ToggleButton printToggle = new ToggleButton("Print layout");

    private final EmptyState tableEmpty = new EmptyState(Icons.RESULTS,
            ResultsCopy.NO_EXAMS_TITLE, ResultsCopy.NO_EXAMS_HINT);
    private final DataTable<StudentGradeRow> table = new DataTable<>();
    private final StatChart chart = new StatChart();
    private final StackPane body = new StackPane();

    private final Label error = new Label();

    private TeacherResultsSession session;
    private boolean selecting;
    private Node chartBack;
    private Node chartPane;

    @Override
    protected Parent build() {
        session = new TeacherResultsSession(dispatcher(), onFxThread()).onChange(this::render);

        root.getStyleClass().add("teacher-results");
        root.setLeft(buildExamRail());
        root.setCenter(buildBody());
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        session.load();
    }

    @Override
    public boolean listensToEvents() {
        // Nothing is pushed to this screen: results are frozen history, not a live feed.
        return false;
    }

    // ===================== Layout ========================================

    private VBox buildExamRail() {
        Label title = new Label("Your exams");
        title.getStyleClass().add("h3");
        Label hint = new Label("Every exam you wrote, including sittings run by others.");
        hint.getStyleClass().addAll("small", "muted");
        hint.setWrapText(true);

        examList.getStyleClass().add("results-exam-list");
        examList.setCellFactory(view -> new ExamCell());
        examList.getSelectionModel().selectedItemProperty().addListener((obs, old, exam) -> {
            if (!selecting && exam != null) {
                session.selectExam(exam);
            }
        });
        VBox.setVgrow(examList, Priority.ALWAYS);

        examRail.getStyleClass().add("results-exam-rail");
        examRail.setPadding(new Insets(24, 12, 24, 24));
        examRail.setPrefWidth(300);
        examRail.setMinWidth(240);
        examRail.getChildren().addAll(title, hint, examList);
        return examRail;
    }

    private VBox buildBody() {
        examName.getStyleClass().add("h1");
        examMeta.getStyleClass().addAll("small", "muted");
        releasedByOther.getStyleClass().addAll("hsts-chip", "neutral", "results-foreign-run");
        markedLabel.getStyleClass().addAll("small", "muted");

        executionPicker.setCellFactory(view -> new ExecutionCell());
        executionPicker.setButtonCell(new ExecutionCell());
        executionPicker.setPrefWidth(420);
        executionPicker.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, execution) -> {
                    if (!selecting && execution != null) {
                        session.openExecution(execution);
                    }
                });

        HBox pickerRow = new HBox(12, executionPicker, releasedByOther, Buttons.spacer(),
                buildToggle(), printToggle);
        pickerRow.setAlignment(Pos.CENTER_LEFT);

        statCards.getStyleClass().add("results-stats");
        statCards.setAlignment(Pos.CENTER_LEFT);
        // Named so the "grading is not finished" state is assertable by visibility rather
        // than by text: a hidden node's labels are still found by a scene-graph lookup.
        unfinished.getStyleClass().add("results-unfinished");

        buildTable();
        chart.setPrefHeight(300);

        // F-7: the histogram fills the pane that held the table, so it is a drill-in
        // in everything but the route. The segmented control that brought the reader
        // here is a toggle, not an exit, and a full-pane view owes the same way back
        // every other drill-in gives.
        chartBack = BackLink.action("Table",
                () -> select(TeacherResultsSession.ResultsView.TABLE));
        VBox pane = new VBox(8, chartBack, chart);
        chartPane = pane;
        VBox.setVgrow(chart, Priority.ALWAYS);

        body.getStyleClass().add("results-body");
        body.getChildren().addAll(table, pane);
        VBox.setVgrow(body, Priority.ALWAYS);

        error.getStyleClass().addAll("small", "danger-text");
        error.setWrapText(true);

        VBox content = new VBox(14, new VBox(2, examName, examMeta), pickerRow, error,
                statCards, unfinished, markedLabel, body);
        content.setPadding(new Insets(24, 28, 24, 20));
        content.getStyleClass().add("hsts-page");
        VBox.setVgrow(content, Priority.ALWAYS);
        return content;
    }

    private HBox buildToggle() {
        ToggleGroup group = new ToggleGroup();
        tableSegment.setToggleGroup(group);
        chartSegment.setToggleGroup(group);
        tableSegment.setSelected(true);
        // A segmented control must never end up with nothing selected: clicking the active
        // half would otherwise leave the screen showing neither view.
        tableSegment.setOnAction(e -> select(TeacherResultsSession.ResultsView.TABLE));
        chartSegment.setOnAction(e -> select(TeacherResultsSession.ResultsView.HISTOGRAM));
        printToggle.setOnAction(e -> session.setPrintLayout(printToggle.isSelected()));

        HBox segmented = new HBox(tableSegment, chartSegment);
        segmented.getStyleClass().addAll("hsts-segmented", "results-view-toggle");
        return segmented;
    }

    private void select(TeacherResultsSession.ResultsView next) {
        tableSegment.setSelected(next == TeacherResultsSession.ResultsView.TABLE);
        chartSegment.setSelected(next == TeacherResultsSession.ResultsView.HISTOGRAM);
        session.setView(next);
    }

    private void buildTable() {
        table.title("Students");
        table.column("Student", StudentGradeRow::studentName);
        table.column(numberColumn("Score", StudentGradeRow::effectiveScore));
        table.column(numberColumn("Auto", StudentGradeRow::autoScore));
        table.column(numberColumn("Final", StudentGradeRow::finalScore));
        table.column("State", row -> ResultsCopy.gradeStateLabel(row.state()));
        // ⚑ B-16. T-10.2 asks for "score, submitted vs timed out, solving time" and only the
        // score used to arrive. Both are words rather than a tint: "Timed out" survives a
        // printout, a screenshot and a colour-blind reader, and it is the fact that separates
        // "did not finish" from "did badly" for the one seeded student it applies to.
        table.column("Attempt", row -> ResultsCopy.attemptStatusLabel(row.attemptStatus()));
        table.column("Time", row -> ResultsCopy.solvingTimeLabel(row.actualMinutes()));
        table.column("", ResultsCopy::adjustedMarker);
        // F-9: the student name is the only wide column here; the numeric ones, the two
        // B-16 columns and the unheaded marker are each sized to what they hold.
        table.columnWidths(240, 100, 100, 100, 150, 120, 110, 60);
        // UI wave 2: the three score columns are numbers and line up as such.
        table.numericColumns(1, 2, 3);
        table.searchable("Find a student",
                (row, needle) -> row.studentName().toLowerCase(java.util.Locale.ROOT)
                        .contains(needle));
        // One node, re-worded per situation: four different facts share the panel, and
        // swapping nodes on every render would churn the scene graph for no reason.
        table.emptyState(tableEmpty);
    }

    /**
     * A numeric column, so the table sorts 100 above 45.
     *
     * <p>The plain {@code column(String, Function)} helper renders strings and would sort them
     * lexically, which on a column of scores is a bug a teacher would read as data.
     */
    private static TableColumn<StudentGradeRow, Integer> numberColumn(
            String title, Function<StudentGradeRow, Integer> reader) {
        TableColumn<StudentGradeRow, Integer> column = new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new ReadOnlyObjectWrapper<>(reader.apply(cell.getValue())));
        return column;
    }

    // ===================== Rendering =====================================

    private void render() {
        renderExams();
        renderHeader();
        renderStats();
        renderRows();
        renderView();
        renderPrintLayout();
    }

    private void renderExams() {
        selecting = true;
        try {
            examList.setItems(FXCollections.observableArrayList(session.exams()));
            session.selectedExam().ifPresent(exam -> examList.getSelectionModel().select(exam));
        } finally {
            selecting = false;
        }
        boolean failed = session.error().isPresent();
        error.setText(session.error().orElse(session.detailError().orElse("")));
        show(error, failed || session.detailError().isPresent());
    }

    private void renderHeader() {
        ExamResultRow exam = session.selectedExam().orElse(null);
        if (exam == null) {
            // The empty panel below carries the explanation; a header repeating it would
            // say the same sentence twice on a screen that has nothing else on it.
            show(examName, false);
            show(examMeta, false);
            show(executionPicker, false);
            show(releasedByOther, false);
            executionPicker.setItems(FXCollections.observableArrayList());
            markedLabel.setText("");
            return;
        }
        show(examName, true);
        show(examMeta, true);
        examName.setText(exam.examName());
        examMeta.setText(exam.courseName() + " · " + exam.courseCode()
                + " · exam " + exam.displayId());

        selecting = true;
        try {
            executionPicker.setItems(FXCollections.observableArrayList(exam.executions()));
            session.selectedExecution()
                    .ifPresent(execution -> executionPicker.getSelectionModel().select(execution));
        } finally {
            selecting = false;
        }
        // An exam nobody released has no sitting to pick; the empty panel says why.
        show(executionPicker, !exam.neverReleased());
        show(releasedByOther, session.selectedExecution()
                .map(ExecutionResultRow::releasedByAnotherTeacher).orElse(false));
        markedLabel.setText(session.results().map(ResultsCopy::markedLabel).orElse(""));
    }

    private void renderStats() {
        ResultStatistics stats = session.statistics().orElse(null);
        statCards.getChildren().clear();
        if (stats != null) {
            for (ResultsCopy.StatCard card : ResultsCopy.statCards(stats)) {
                statCards.getChildren().add(cardNode(card));
            }
        }
        show(statCards, stats != null);
        show(unfinished, session.isGradingUnfinished());
    }

    private void renderRows() {
        ResultsCopy.EmptyPanel panel = session.emptyPanel();
        tableEmpty.set(panel.title(), panel.hint());

        ExecutionResults results = session.results().orElse(null);
        if (results == null) {
            if (session.detailState().showsError()) {
                table.showError();
            } else if (session.isOpeningExecution()) {
                table.showLoading();
            } else {
                // Nothing to load, rather than something still loading: a skeleton here would
                // spin forever for a teacher who has written nothing.
                table.setItems(List.of());
            }
            chart.setData(session.chartData());
            return;
        }
        table.setItems(results.rows());
        chart.setHeading(results.examName() + " · code " + results.execution().code4());
        chart.setData(session.chartData());
    }

    private void renderView() {
        boolean histogram = session.view() == TeacherResultsSession.ResultsView.HISTOGRAM;
        show(table, !histogram);
        // The pane and the chart both, not the pane alone. JavaFX does not push
        // visibility down into children, so a hidden wrapper still reports its own
        // chart as visible, and "is the chart on screen" is exactly what the T-10
        // regression test asks.
        show(chartPane, histogram);
        show(chart, histogram);
    }

    private void renderPrintLayout() {
        boolean printing = session.isPrintLayout();
        root.getStyleClass().remove(ResultsCopy.PRINT_STYLE_CLASS);
        if (printing) {
            root.getStyleClass().add(ResultsCopy.PRINT_STYLE_CLASS);
        }
        // The rail is navigation, and navigation is exactly what a printed page does not need.
        show(examRail, !printing);
        root.setLeft(printing ? null : examRail);
        // Same rule for the histogram's way back: it is navigation, not content.
        show(chartBack, !printing);
    }

    private static VBox cardNode(ResultsCopy.StatCard card) {
        Label value = new Label(card.value());
        value.getStyleClass().add("stat-value");
        Label label = new Label(card.label());
        label.getStyleClass().add("stat-label");
        Label hint = new Label(card.hint());
        hint.getStyleClass().add("stat-hint");
        VBox box = new VBox(2, value, label, hint);
        box.getStyleClass().addAll("hsts-card", "hsts-stat-card", "compact", "results-stat-card");
        box.setMinWidth(130);
        return box;
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    // ===================== Cells =========================================

    /** One exam in the rail: its name, its course, and how often it has been sat. */
    private static final class ExamCell extends ListCell<ExamResultRow> {

        @Override
        protected void updateItem(ExamResultRow exam, boolean empty) {
            super.updateItem(exam, empty);
            if (empty || exam == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label name = new Label(exam.examName());
            name.getStyleClass().add("strong");
            Label meta = new Label(exam.courseCode() + " · " + (exam.neverReleased()
                    ? ResultsCopy.NEVER_RELEASED_TITLE.toLowerCase(java.util.Locale.ROOT)
                    : exam.executionCount() + (exam.executionCount() == 1
                            ? " sitting" : " sittings")));
            meta.getStyleClass().addAll("small", "muted");
            VBox box = new VBox(1, name, meta);
            box.getStyleClass().add("results-exam-item");
            setText(null);
            setGraphic(box);
        }
    }

    /** One sitting in the picker: code, window, state and how many sat it. */
    private static final class ExecutionCell extends ListCell<ExecutionResultRow> {

        @Override
        protected void updateItem(ExecutionResultRow execution, boolean empty) {
            super.updateItem(execution, empty);
            if (empty || execution == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setGraphic(null);
            setText(ResultsCopy.executionLabel(execution, ZONE));
        }
    }
}
