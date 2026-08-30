package client.features.data;

import client.core.NavParams;
import client.features.results.ResultsCopy;
import client.ui.components.DataTable;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.components.StatChart;
import client.ui.screen.AbstractScreen;
import common.dto.report.ReportRow;
import common.dto.results.ResultStatistics;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;

/**
 * One closed sitting's results, read-only (Presentation tier, E15.2 — F9.3, F8.5, S-7, T-11.2,
 * U-44, the lead's ruling of 2026-08-30).
 *
 * <p>What the Data browser's Results rows open: the six stat cards E14 already prints, the score
 * distribution as a table, and the histogram the same stored deciles feed. Every figure was
 * frozen when the sitting's last grade was approved and travelled unchanged through
 * {@code ReportRow} — nothing on this screen computes a mean, a σ, a pass rate or a bucket
 * (H14.4 ⚑).
 *
 * <h2>The cards and the chart are the teacher's, unchanged</h2>
 *
 * <p>{@link ResultsCopy#statCards} builds the six cards and {@link StatChart} draws the
 * histogram, both exactly as {@code TeacherResultsView} uses them. One sitting therefore reads
 * the same on her screen and on the teacher's, which is the same rule {@code DataCopy} follows
 * for the list's pass-rate column.
 *
 * <h2>Why the table is a distribution and not a list of students ⚑</h2>
 *
 * <p>Because that is what the wire carries and what the role is for. {@code DATA_RESULTS_GET}
 * answers with one row per sitting and its frozen figures; the named per-student list is
 * {@code RESULTS_EXECUTION_GET}, which is scoped to the exams the caller <b>wrote</b> (S-35) and
 * is a scope the principal does not have and must not be given by widening the teacher's. F9.3
 * gives her the school's data as entered, and a sitting as entered is its distribution.
 * {@link DataDetailCopy#DISTRIBUTION_HINT} says so on screen rather than leaving it to be
 * noticed.
 *
 * <h2>What is missing, and is missing by construction ⚑</h2>
 *
 * <p>No control here sends anything. The only interactive thing on the screen is the histogram's
 * own Count/Percent toggle, which changes an axis label and nothing else (T-11.3).
 *
 * <p>Thin by the usual rule and on the coverage exclusion list by name: every sentence and every
 * row is built in {@link DataDetailCopy} or {@link ResultsCopy}, and every load decision is in
 * {@link DataSittingSession}.
 */
public final class DataSittingView extends AbstractScreen {

    /** The nav parameter a Results row carries: the sitting's execution id. */
    public static final String PARAM_EXECUTION = "executionId";

    /** Local zone for every wire instant on this screen; the wire is UTC (ADR-010). */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final VBox root = new VBox(14);
    private final Label heading = new Label(DataDetailCopy.SITTING_TITLE);
    private final Label meta = new Label();
    private final Label participants = new Label();
    private final Label readOnly = new Label(DataDetailCopy.READ_ONLY_NOTE);
    private final Label distributionTitle = new Label(DataDetailCopy.DISTRIBUTION_TITLE);
    private final Label distributionHint = new Label(DataDetailCopy.DISTRIBUTION_HINT);

    private final FlowPane statCards = new FlowPane(12, 12);
    private final DataTable<DataDetailCopy.DecileRow> distribution = new DataTable<>();
    private final StatChart chart = new StatChart();
    private final EmptyState unavailable = new EmptyState(Icons.RESULTS,
            DataDetailCopy.SITTING_FAILED_TITLE, DataDetailCopy.SITTING_FAILED_HINT);

    private DataSittingSession session;

    @Override
    protected Parent build() {
        session = new DataSittingSession(dispatcher(), onFxThread()).onChange(this::render);

        heading.getStyleClass().add("h1");
        meta.getStyleClass().addAll("small", "muted");
        meta.setWrapText(true);
        participants.getStyleClass().addAll("small", "muted", "data-sitting-participants");
        participants.setWrapText(true);
        readOnly.getStyleClass().addAll("small", "muted", "data-read-only-note");
        readOnly.setWrapText(true);
        distributionTitle.getStyleClass().add("h3");
        distributionHint.getStyleClass().addAll("small", "muted", "data-distribution-hint");
        distributionHint.setWrapText(true);

        statCards.getStyleClass().addAll("results-stats", "data-sitting-stats");
        statCards.setAlignment(Pos.CENTER_LEFT);

        buildTable();
        chart.setPrefHeight(280);

        root.getStyleClass().addAll("hsts-page", "principal-data-sitting");
        root.setPadding(new Insets(24, 28, 24, 24));
        root.getChildren().addAll(new VBox(2, heading, meta, readOnly), unavailable, statCards,
                participants, new VBox(4, distributionTitle, distributionHint), distribution,
                chart);
        VBox.setVgrow(distribution, Priority.ALWAYS);
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        session.open(params.getLong(PARAM_EXECUTION, 0));
    }

    @Override
    public boolean listensToEvents() {
        // A closed sitting's statistics are frozen history, not a live feed.
        return false;
    }

    // ===================== Layout ========================================

    private void buildTable() {
        distribution.column("Score", DataDetailCopy.DecileRow::range);
        distribution.column(countColumn());
        // F-9: the band is two short numbers and the share is a count with a percentage after
        // it; an even split would leave both columns swimming.
        distribution.columnWidths(220, 220);
        distribution.numericColumns(1);
        distribution.getStyleClass().add("data-distribution");
        distribution.emptyState(new EmptyState(Icons.RESULTS,
                DataDetailCopy.SITTING_FAILED_TITLE, DataDetailCopy.SITTING_FAILED_HINT));
    }

    /**
     * The count column, sorted as a number.
     *
     * <p>The plain {@code column(String, Function)} helper renders strings and would sort them
     * lexically, which on a column of counts puts 10 above 2. The cell prints
     * {@link DataDetailCopy.DecileRow#share()} — the count with its percentage — while the
     * column sorts on the count behind it.
     */
    private static TableColumn<DataDetailCopy.DecileRow, Integer> countColumn() {
        TableColumn<DataDetailCopy.DecileRow, Integer> column = new TableColumn<>("Students");
        column.setCellValueFactory(cell ->
                new ReadOnlyObjectWrapper<>(cell.getValue().count()));
        column.setCellFactory(ignored -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(Integer value, boolean empty) {
                super.updateItem(value, empty);
                DataDetailCopy.DecileRow row = empty || getTableRow() == null
                        ? null : getTableRow().getItem();
                setText(row == null ? null : row.share());
            }
        });
        return column;
    }

    // ===================== Rendering =====================================

    private void render() {
        ReportRow row = session.sitting().orElse(null);
        boolean failed = session.state().showsError();

        unavailable.set(DataDetailCopy.SITTING_FAILED_TITLE,
                session.error().orElse(DataDetailCopy.SITTING_FAILED_HINT));
        show(unavailable, failed);

        boolean loaded = row != null;
        show(statCards, loaded);
        show(participants, loaded);
        show(distributionTitle, loaded);
        show(distributionHint, loaded);
        show(distribution, loaded);
        show(chart, loaded);

        if (!loaded) {
            heading.setText(DataDetailCopy.SITTING_TITLE);
            meta.setText("");
            statCards.getChildren().clear();
            if (session.isLoading()) {
                distribution.showLoading();
            }
            return;
        }
        heading.setText(DataCopy.sittingLabel(row));
        meta.setText(DataDetailCopy.sittingMeta(row, ZONE));
        participants.setText(DataDetailCopy.participantsLine(row));

        ResultStatistics stats = row.statistics();
        statCards.getChildren().clear();
        for (ResultsCopy.StatCard card : ResultsCopy.statCards(stats)) {
            statCards.getChildren().add(cardNode(card));
        }

        distribution.setItems(session.distribution());
        chart.setHeading(DataCopy.sittingLabel(row));
        chart.setData(session.chartData());
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
        // U-28: what the card says is its own minimum; the pane above wraps.
        box.setMinWidth(Region.USE_PREF_SIZE);
        return box;
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
