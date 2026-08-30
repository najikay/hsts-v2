package client.features.data;

import client.core.NavParams;
import client.core.Routes;
import client.ui.components.Buttons;
import client.ui.components.DataTable;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.screen.AbstractScreen;
import common.dto.bank.BankQuestionRow;
import common.dto.report.DataExamRow;
import common.dto.report.ReportRow;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The principal's data browser (Presentation tier, E15.2 — F9.3, S-7, T-11).
 *
 * <p>A renderer over {@link DataSession}. A segmented tab picker across the top, a text filter
 * and a course picker beside it, and below them whichever of the three tables the selected tab
 * owns.
 *
 * <p>Thin, on the {@code ReportsView} pattern: every decision — which tab loads when, what the
 * filters hide, what the count line says, which explanation belongs where the table would be —
 * is made in {@link DataSession} and {@link DataCopy}, both of which are measured and tested.
 * This class owns nodes and nothing else, which is why it is on the coverage exclusion list by
 * name.
 *
 * <h2>Read-only by construction (F9.3, S-7) ⚑</h2>
 *
 * <p>There is not one control on this screen that sends anything but the three reads behind it,
 * and not one editable field except the filter box, which never leaves the client. No button, no
 * context menu, no dialog. T-11.3 asks a reviewer to look for a create, edit, delete or approve
 * control anywhere in her shell; this file is where she would look for it on the busiest screen
 * the role has, and {@link DataCopy#READ_ONLY_NOTE} says so on screen so that "there are no
 * buttons" cannot be mistaken for "the buttons are not built yet".
 *
 * <h2>The rows open, and opening is still a read (2026-08-30, live session, U-44)</h2>
 *
 * <p>Every row navigates: a question to {@code data.question}, an exam to {@code data.exam}, a
 * sitting to {@code data.results}. That is {@link DataTable#openOnClick}, whose hover hint reads
 * "Open" and is a promise this screen can keep — a click really does go somewhere, which is the
 * distinction {@code selectOnClick} exists for on a master-detail screen where it does not.
 *
 * <p>It changes nothing about the paragraph above. Each of the three destinations is a screen
 * with no mutating control of its own, reached by verbs that were reads before this screen
 * existed, and the way back is the shell's navbar Back, which {@code ShellBoot} aliases to this
 * route. What the ruling of 2026-08-30 fixed is a browse whose rows listed data and would not
 * show it, which is the dead end PRD section 4.1 forbids rather than a safety property.
 *
 * <h2>The segments are the enum</h2>
 *
 * <p>{@code DataTab.values()} builds the segmented control and {@code tab.segment()} labels it,
 * so the picker names none of the three. The three <b>tables</b> are named, once, in
 * {@link #buildTables()}, and they have to be: a question, an exam and a sitting are three wire
 * types with three different column sets, which is exactly the difference between this screen
 * and the reports screen next door, where one result record serves every dimension.
 */
public final class DataView extends AbstractScreen {

    /** Local zone for every wire instant on this screen; the wire is UTC (ADR-010). */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final VBox root = new VBox(14);

    private final Map<DataTab, ToggleButton> segments = new EnumMap<>(DataTab.class);
    private final HBox segmented = new HBox();
    private final TextField filter = new TextField();
    private final ComboBox<DataSession.CourseOption> coursePicker = new ComboBox<>();

    private final Label error = new Label();
    private final Label count = new Label();
    private final Label scopeHint = new Label(DataCopy.SCOPE_HINT);
    private final Label truncated = new Label(DataCopy.TOO_MANY_QUESTIONS);

    private final Map<DataTab, DataTable<?>> tables = new EnumMap<>(DataTab.class);
    private final Map<DataTab, EmptyState> empties = new EnumMap<>(DataTab.class);

    private final DataTable<BankQuestionRow> questions = new DataTable<>();
    private final DataTable<DataExamRow> exams = new DataTable<>();
    private final DataTable<ReportRow> sittings = new DataTable<>();

    private DataSession session;
    private boolean selecting;

    @Override
    protected Parent build() {
        session = new DataSession(dispatcher(), onFxThread()).onChange(this::render);

        root.getStyleClass().addAll("hsts-page", "principal-data");
        root.setPadding(new Insets(24, 28, 24, 24));

        Label title = new Label(DataCopy.TITLE);
        title.getStyleClass().add("h1");
        Label subtitle = new Label(DataCopy.SUBTITLE);
        subtitle.getStyleClass().addAll("small", "muted");
        subtitle.setWrapText(true);
        Label readOnly = new Label(DataCopy.READ_ONLY_NOTE);
        readOnly.getStyleClass().addAll("small", "muted", "data-read-only-note");
        readOnly.setWrapText(true);

        error.getStyleClass().addAll("small", "danger-text");
        error.setWrapText(true);
        count.getStyleClass().addAll("small", "muted", "data-count");
        scopeHint.getStyleClass().addAll("small", "muted", "data-scope-hint");
        scopeHint.setWrapText(true);
        truncated.getStyleClass().addAll("small", "danger-text", "data-truncated");
        truncated.setWrapText(true);

        buildTables();

        VBox body = new VBox(questions, exams, sittings);
        body.getStyleClass().add("data-body");
        VBox.setVgrow(questions, Priority.ALWAYS);
        VBox.setVgrow(exams, Priority.ALWAYS);
        VBox.setVgrow(sittings, Priority.ALWAYS);

        root.getChildren().addAll(new VBox(2, title, subtitle, readOnly), buildPickerRow(),
                error, truncated, count, scopeHint, body);
        VBox.setVgrow(body, Priority.ALWAYS);
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        session.load();
    }

    @Override
    public boolean listensToEvents() {
        // Nothing is pushed here. The bank, the catalogue and the closed sittings are all things
        // a principal reads; none of the three verbs behind this screen has a push beside it.
        return false;
    }

    // ===================== Layout ========================================

    private HBox buildPickerRow() {
        ToggleGroup group = new ToggleGroup();
        // The segments are the enum. Not one tab is named here, which is what keeps a fourth
        // one from being three edits in this method.
        for (DataTab tab : DataTab.values()) {
            ToggleButton segment = new ToggleButton(tab.segment());
            segment.setToggleGroup(group);
            segment.setSelected(tab == DataTab.defaultTab());
            segment.setOnAction(event -> select(tab));
            segments.put(tab, segment);
            segmented.getChildren().add(segment);
        }
        segmented.getStyleClass().addAll("hsts-segmented", "data-tab-toggle");

        filter.setPromptText(DataCopy.FILTER_PROMPT);
        filter.setPrefWidth(260);
        filter.getStyleClass().add("data-filter");
        filter.textProperty().addListener((obs, old, text) -> {
            if (!selecting) {
                session.setFilter(text);
            }
        });

        coursePicker.setCellFactory(view -> new CourseCell());
        coursePicker.setButtonCell(new CourseCell());
        coursePicker.setPrefWidth(220);
        coursePicker.getStyleClass().add("data-course-picker");
        coursePicker.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, option) -> {
                    if (!selecting) {
                        session.selectCourse(option == null ? null : option.code());
                    }
                });

        HBox row = new HBox(12, segmented, Buttons.spacer(), filter, coursePicker);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("data-picker-row");
        return row;
    }

    /**
     * The three tables, named once.
     *
     * <p>Three wire types with three column sets, so unlike the reports screen's single table
     * these cannot be one node. They are built together rather than lazily so that switching a
     * tab is showing a node rather than building one, which is what keeps the switch instant on
     * a list of several hundred rows.
     */
    private void buildTables() {
        // No DataTable.title(): its toolbar would print a heading duplicating the selected
        // segment and a count reading "3 items", beside a count line that already says
        // "3 questions" and says whether a filter is narrowing it.
        questions.column("Id", DataCopy::questionId);
        questions.column("Question", BankQuestionRow::text);
        questions.column("Topic", row -> row.topic() == null ? "" : row.topic());
        questions.column("Difficulty", row -> DataCopy.difficulty(row.difficulty()));
        questions.column("Course", row -> DataCopy.course(row.courseCode(), row.courseName()));
        questions.column("Version", DataCopy::questionVersion);
        questions.column("Written", row -> DataCopy.rowDate(row.lastVersionAt(), ZONE));
        // F-9: the question stem needs the room, the id and the version need almost
        // none, and "Written" is a date that was truncating at the default width.
        questions.columnWidths(90, 380, 180, 120, 190, 100, 150);
        questions.numericColumns(5);
        questions.getStyleClass().add("data-questions");
        questions.openOnClick(row -> navigator().navigate(Routes.DATA_QUESTION.id(),
                NavParams.of(DataQuestionView.PARAM_QUESTION, row.displayId5())));

        exams.column("Id", DataExamRow::displayId6);
        exams.column("Exam", DataExamRow::examName);
        exams.column("Course", row -> DataCopy.course(row.courseCode(), row.courseName()));
        exams.column("Written by", DataExamRow::authorName);
        exams.column("Versions", DataCopy::examVersions);
        exams.column("Last written", row -> DataCopy.rowDate(row.lastVersionAt(), ZONE));
        // F-9: exam name and author are the wide ones; "Last written" is a date.
        exams.columnWidths(90, 300, 190, 200, 110, 160);
        exams.numericColumns(4);
        exams.getStyleClass().add("data-exams");
        exams.openOnClick(row -> navigator().navigate(Routes.DATA_EXAM.id(),
                NavParams.of(DataExamView.PARAM_EXAM_VERSION, row.latestVersionId())));

        sittings.column("Sitting", DataCopy::sittingLabel);
        sittings.column("Course", row -> DataCopy.course(row.courseCode(), row.courseName()));
        sittings.column("Closed", row -> DataCopy.rowDate(row.closeAt(), ZONE));
        sittings.column(numberColumn("Mean", row -> row.statistics().mean()));
        sittings.column(numberColumn("Median", row -> row.statistics().median()));
        sittings.column(numberColumn("Sigma", row -> row.statistics().standardDeviation()));
        sittings.column("Pass rate", row -> DataCopy.passRate(row.statistics()));
        sittings.column("Participants", row -> Integer.toString(row.participants()));
        // F-9: the sitting label carries an exam name and a code, and "Closed" is a
        // date; the four statistics columns need far less than an even split.
        sittings.columnWidths(300, 190, 150, 100, 100, 100, 130, 130);
        sittings.numericColumns(3, 4, 5, 6, 7);
        sittings.getStyleClass().add("data-sittings");
        sittings.openOnClick(row -> navigator().navigate(Routes.DATA_RESULTS.id(),
                NavParams.of(DataSittingView.PARAM_EXECUTION, row.executionId())));

        tables.put(DataTab.QUESTIONS, questions);
        tables.put(DataTab.EXAMS, exams);
        tables.put(DataTab.RESULTS, sittings);

        // One empty-state node per table, re-worded per situation: the two facts that share the
        // panel are different, and swapping nodes on every render would churn the scene graph.
        for (Map.Entry<DataTab, DataTable<?>> entry : tables.entrySet()) {
            DataCopy.EmptyPanel panel = DataCopy.nothingHere(entry.getKey());
            EmptyState empty = new EmptyState(iconFor(entry.getKey()), panel.title(),
                    panel.hint());
            entry.getValue().emptyState(empty);
            empties.put(entry.getKey(), empty);
        }
    }

    private static String iconFor(DataTab tab) {
        return switch (tab) {
            case QUESTIONS -> Icons.BANK;
            case EXAMS -> Icons.EXAMS;
            case RESULTS -> Icons.RESULTS;
        };
    }

    /**
     * A numeric column, so the table sorts 100 above 45 and 9.5 above 17.5 never happens.
     *
     * <p>The plain {@code column(String, Function)} helper renders strings and would sort them
     * lexically, which on a column of scores is a bug a principal would read as data.
     */
    private static TableColumn<ReportRow, Double> numberColumn(
            String title, Function<ReportRow, Double> reader) {
        TableColumn<ReportRow, Double> column = new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new ReadOnlyObjectWrapper<>(reader.apply(cell.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(Double value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : DataCopy.number(value));
            }
        });
        return column;
    }

    private void select(DataTab next) {
        // A segmented control must never end up with nothing selected: clicking the active
        // segment would otherwise leave the screen showing no tab at all.
        segments.forEach((tab, segment) -> segment.setSelected(tab == next));
        session.selectTab(next);
    }

    // ===================== Rendering =====================================

    private void render() {
        DataTab tab = session.tab();
        segments.forEach((each, segment) -> segment.setSelected(each == tab));

        selecting = true;
        try {
            if (!filter.getText().equals(session.filter())) {
                filter.setText(session.filter());
            }
            renderCoursePicker();
        } finally {
            selecting = false;
        }

        String message = session.error().orElse("");
        error.setText(message);
        show(error, !message.isEmpty());
        show(truncated, tab == DataTab.QUESTIONS && session.isBankTruncated());
        count.setText(session.countLine());
        show(count, !session.countLine().isEmpty());
        show(scopeHint, tab == DataTab.RESULTS);

        renderRows(tab);
        tables.forEach((each, table) -> show(table, each == tab));
    }

    private void renderCoursePicker() {
        List<DataSession.CourseOption> options = new ArrayList<>(session.courseOptions());
        // The null entry is "All courses"; the cell renders it as that sentence rather than as
        // a blank row, so clearing the filter is a thing she can pick rather than a thing she
        // has to know about.
        options.add(0, null);
        coursePicker.setItems(FXCollections.observableArrayList(options));
        coursePicker.getSelectionModel().select(session.selectedCourse()
                .flatMap(code -> options.stream()
                        .filter(option -> option != null && option.code().equals(code))
                        .findFirst())
                .orElse(null));
        show(coursePicker, session.courseOptions().size() > 1);
    }

    private void renderRows(DataTab tab) {
        DataCopy.EmptyPanel panel = session.emptyPanel();
        empties.get(tab).set(panel.title(), panel.hint());

        if (session.state().showsError()) {
            tables.get(tab).showError();
            return;
        }
        if (session.isLoading()) {
            tables.get(tab).showLoading();
            return;
        }
        switch (tab) {
            case QUESTIONS -> questions.setItems(session.questions());
            case EXAMS -> exams.setItems(session.exams());
            case RESULTS -> sittings.setItems(session.sittings());
        }
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    // ===================== Cells =========================================

    /** One course in the filter dropdown, or the "All courses" entry when there is no course. */
    private static final class CourseCell extends ListCell<DataSession.CourseOption> {

        @Override
        protected void updateItem(DataSession.CourseOption option, boolean empty) {
            super.updateItem(option, empty);
            setGraphic(null);
            setText(empty ? null : option == null ? DataCopy.ALL_COURSES : option.label());
        }
    }
}
