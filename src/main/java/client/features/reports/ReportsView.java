package client.features.reports;

import client.core.NavParams;
import client.ui.components.Buttons;
import client.ui.components.DataTable;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.components.StatChart;
import client.ui.screen.AbstractScreen;
import common.dto.report.ReportDimension;
import common.dto.report.ReportRow;
import common.dto.report.ReportSubject;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The principal's reports screen (Presentation tier, E15.4 — F9.4, F9.3).
 *
 * <p>A renderer over {@link ReportsSession}. A segmented dimension picker across the top, the
 * subject picker beside it, the cross-row summary cards, the table of closed sittings, and the
 * histogram of whichever row is selected.
 *
 * <p>Thin, on the {@code TeacherResultsView} pattern: every decision — which subject opens
 * first, which row the chart draws, what the cards say, which explanation belongs where the
 * table would be — is made in {@link ReportsSession} and {@link ReportsCopy}, both of which are
 * measured and tested. This class owns nodes and nothing else, which is why it is on the
 * coverage exclusion list by name.
 *
 * <h2>The segments are the enum, not a list written here</h2>
 *
 * <p>{@code ReportDimension.values()} builds the segmented control and
 * {@code dimension.subjectNoun()} labels the picker, so this file names none of the three
 * comparisons. A fourth dimension appears on this screen by existing (S-37). That is the one
 * thing in the class worth reading twice, because a segmented control is exactly where three
 * hard-coded buttons would normally go.
 *
 * <h2>Read-only by construction (F9.3, S-7)</h2>
 *
 * <p>There is no button on this screen that sends anything but the two report reads. The
 * principal's rail offers no authoring screen and her session registers no route to one; this
 * class is the last link in that chain and it holds no editable control at all.
 */
public final class ReportsView extends AbstractScreen {

    /** Local zone for every wire instant on this screen; the wire is UTC (ADR-010). */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final VBox root = new VBox(14);

    private final Map<ReportDimension, ToggleButton> segments =
            new EnumMap<>(ReportDimension.class);
    private final HBox segmented = new HBox();
    private final ComboBox<ReportSubject> subjectPicker = new ComboBox<>();
    private final ToggleButton printToggle = new ToggleButton("Print layout");

    private final Label heading = new Label();
    private final Label error = new Label();
    private final Label participants = new Label();
    private final HBox summaryCards = new HBox(12);

    private final EmptyState tableEmpty = new EmptyState(Icons.REPORTS,
            ReportsCopy.NOTHING_PICKED.title(), ReportsCopy.NOTHING_PICKED.hint());
    private final DataTable<ReportRow> table = new DataTable<>();
    private final StatChart chart = new StatChart();

    private ReportsSession session;
    private boolean selecting;

    @Override
    protected Parent build() {
        session = new ReportsSession(dispatcher(), onFxThread()).onChange(this::render);

        root.getStyleClass().addAll("hsts-page", "principal-reports");
        root.setPadding(new Insets(24, 28, 24, 24));

        Label title = new Label(ReportsCopy.TITLE);
        title.getStyleClass().add("h1");
        Label subtitle = new Label(ReportsCopy.SUBTITLE);
        subtitle.getStyleClass().addAll("small", "muted");
        subtitle.setWrapText(true);

        heading.getStyleClass().add("h2");
        error.getStyleClass().addAll("small", "danger-text");
        error.setWrapText(true);
        participants.getStyleClass().addAll("small", "muted");

        summaryCards.getStyleClass().add("reports-summary");
        summaryCards.setAlignment(Pos.CENTER_LEFT);

        buildTable();
        chart.setPrefHeight(300);

        root.getChildren().addAll(new VBox(2, title, subtitle), buildPickerRow(), error,
                heading, summaryCards, participants, table, chart);
        VBox.setVgrow(table, Priority.ALWAYS);
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        session.load();
    }

    @Override
    public boolean listensToEvents() {
        // Nothing is pushed here: a report compares sittings that have already closed, and
        // nothing about it can move while it is on screen.
        return false;
    }

    // ===================== Layout ========================================

    private HBox buildPickerRow() {
        ToggleGroup group = new ToggleGroup();
        // The segments are the enum. Not one dimension is named in this file, which is what
        // makes a fourth one appear here by existing rather than by being added here (S-37).
        for (ReportDimension dimension : ReportDimension.values()) {
            ToggleButton segment = new ToggleButton(dimension.segment());
            segment.setToggleGroup(group);
            segment.setSelected(dimension == ReportDimension.defaultDimension());
            segment.setOnAction(event -> select(dimension));
            segments.put(dimension, segment);
            segmented.getChildren().add(segment);
        }
        segmented.getStyleClass().addAll("hsts-segmented", "reports-dimension-toggle");

        subjectPicker.setCellFactory(view -> new SubjectCell());
        subjectPicker.setButtonCell(new SubjectCell());
        subjectPicker.setPrefWidth(360);
        subjectPicker.getStyleClass().add("reports-subject-picker");
        subjectPicker.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, subject) -> {
                    if (!selecting && subject != null) {
                        session.selectSubject(subject);
                    }
                });

        printToggle.setOnAction(event -> session.setPrintLayout(printToggle.isSelected()));

        HBox row = new HBox(12, segmented, subjectPicker, Buttons.spacer(), printToggle);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("reports-picker-row");
        return row;
    }

    private void select(ReportDimension next) {
        // A segmented control must never end up with nothing selected: clicking the active
        // segment would otherwise leave the screen showing neither dimension.
        segments.forEach((dimension, segment) -> segment.setSelected(dimension == next));
        session.selectDimension(next);
    }

    private void buildTable() {
        table.title(ReportsCopy.ROWS_TABLE_TITLE);
        table.column("Sitting", ReportsCopy::rowLabel);
        table.column("Date", row -> ReportsCopy.rowDate(row.openAt(), ZONE));
        table.column(numberColumn("Mean", row -> row.statistics().mean()));
        table.column(numberColumn("Median", row -> row.statistics().median()));
        table.column(numberColumn("Sigma", row -> row.statistics().standardDeviation()));
        table.column("Pass rate", row -> ReportsCopy.passRate(row.statistics()));
        table.column("Participants", row -> Integer.toString(row.participants()));
        // One node, re-worded per situation: three different facts share the panel, and
        // swapping nodes on every render would churn the scene graph for no reason.
        table.emptyState(tableEmpty);
        table.getStyleClass().add("reports-rows");

        table.table().getSelectionModel().selectedItemProperty()
                .addListener((obs, old, row) -> {
                    if (!selecting && row != null) {
                        session.selectRow(row);
                    }
                });
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
        column.setCellFactory(ignored -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(Double value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : ReportsCopy.number(value));
            }
        });
        return column;
    }

    // ===================== Rendering =====================================

    private void render() {
        renderPickers();
        renderSummary();
        renderRows();
        renderPrintLayout();
    }

    private void renderPickers() {
        segments.forEach((dimension, segment) ->
                segment.setSelected(dimension == session.dimension()));
        subjectPicker.setPromptText(ReportsCopy.subjectPrompt(session.dimension()));

        selecting = true;
        try {
            subjectPicker.setItems(FXCollections.observableArrayList(session.subjects()));
            session.selectedSubject()
                    .ifPresent(subject -> subjectPicker.getSelectionModel().select(subject));
        } finally {
            selecting = false;
        }
        show(subjectPicker, !session.subjects().isEmpty());

        String message = session.subjectsError().orElse(session.reportError().orElse(""));
        error.setText(message);
        show(error, !message.isEmpty());

        heading.setText(session.heading());
        show(heading, !session.heading().isEmpty());
    }

    private void renderSummary() {
        summaryCards.getChildren().clear();
        boolean any = !session.summary().isEmpty();
        if (any) {
            for (ReportsCopy.SummaryCard card : ReportsCopy.summaryCards(session.summary())) {
                summaryCards.getChildren().add(cardNode(card));
            }
        }
        show(summaryCards, any);
        participants.setText(any ? ReportsCopy.participantsLine(session.summary()) : "");
        show(participants, any);
    }

    private void renderRows() {
        ReportsCopy.EmptyPanel panel = session.emptyPanel();
        tableEmpty.set(panel.title(), panel.hint());

        List<ReportRow> rows = session.rows();
        if (rows.isEmpty()) {
            if (session.reportState().showsError()) {
                table.showError();
            } else if (session.isLoading()) {
                table.showLoading();
            } else {
                // Nothing to load, rather than something still loading: a skeleton here would
                // spin forever on a subject that has never had a sitting close.
                table.setItems(List.of());
            }
        } else {
            table.setItems(rows);
        }

        selecting = true;
        try {
            session.selectedRow()
                    .ifPresent(row -> table.table().getSelectionModel().select(row));
        } finally {
            selecting = false;
        }

        chart.setHeading(session.chartHeading());
        chart.setData(session.chartData());
    }

    private void renderPrintLayout() {
        boolean printing = session.isPrintLayout();
        root.getStyleClass().remove(ReportsCopy.PRINT_STYLE_CLASS);
        if (printing) {
            root.getStyleClass().add(ReportsCopy.PRINT_STYLE_CLASS);
        }
        // The pickers are navigation, and navigation is exactly what a printed page does not
        // need. The heading stays, because a printed comparison with no subject on it is a page
        // of numbers about nobody.
        show(segmented, !printing);
        show(printToggle, true);
    }

    private static VBox cardNode(ReportsCopy.SummaryCard card) {
        Label value = new Label(card.value());
        value.getStyleClass().add("stat-value");
        Label label = new Label(card.label());
        label.getStyleClass().add("stat-label");
        Label hint = new Label(card.hint());
        hint.getStyleClass().add("stat-hint");
        VBox box = new VBox(2, value, label, hint);
        box.getStyleClass().addAll("hsts-card", "hsts-stat-card", "compact", "reports-stat-card");
        box.setMinWidth(140);
        return box;
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    // ===================== Cells =========================================

    /** One subject in the picker: its name, and how many sittings it has to compare. */
    private static final class SubjectCell extends ListCell<ReportSubject> {

        @Override
        protected void updateItem(ReportSubject subject, boolean empty) {
            super.updateItem(subject, empty);
            if (empty || subject == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setGraphic(null);
            setText(ReportsCopy.subjectLabel(subject));
        }
    }
}
