package client.features.grading;

import client.core.NavParams;
import client.core.Routes;
import client.ui.components.Buttons;
import client.ui.components.DataTable;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.screen.AbstractScreen;
import common.dto.grading.ExecutionGradingSummary;
import common.dto.grading.StudentGradeRow;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * The teacher's grading screen (Presentation tier, E12.5/E12.6/E12.7 — T-8).
 *
 * <p>Sittings waiting on her down the left, the chosen sitting's students on the right, with
 * bulk approve and the override dialog. A renderer over {@link GradingQueueSession}: every
 * decision — what belongs in the queue, what a write does afterwards, whether a row may still be
 * changed — is made there or in {@link GradingCopy}, both measured. This class owns nodes, which
 * is why it is on the coverage exclusion list by name.
 *
 * <h2>Two confirmations, and only one of them is a dialog</h2>
 *
 * <p>Bulk approve asks first, because it publishes a class's marks and cannot be undone. The
 * override does not ask: its dialog <em>is</em> the deliberation, and a second "are you sure"
 * after someone has typed a justification is a click that teaches nothing.
 */
public final class GradingQueueView extends AbstractScreen {

    /** Local zone for the closing times; the wire is UTC (ADR-010). */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final BorderPane root = new BorderPane();
    private final ListView<ExecutionGradingSummary> queueList = new ListView<>();
    private final Label examName = new Label();
    private final Label progress = new Label();
    private final Label error = new Label();
    private final DataTable<StudentGradeRow> table = new DataTable<>();

    private final Button approveSelected = new Button(GradingCopy.APPROVE_SELECTED);
    private final Button selectAll = new Button("Select all");
    private final Button override = new Button(GradingCopy.OVERRIDE);

    private final EmptyState queueEmpty = new EmptyState(Icons.GRADING,
            GradingCopy.QUEUE_EMPTY_TITLE, GradingCopy.QUEUE_EMPTY_HINT);

    private GradingQueueSession session;
    private boolean selecting;

    @Override
    protected Parent build() {
        session = new GradingQueueSession(dispatcher(), onFxThread()).onChange(this::render);

        root.getStyleClass().add(GradingCopy.STYLE_CLASS);
        root.setLeft(buildQueueRail());
        root.setCenter(buildBody());
        return root;
    }

    /**
     * Re-reads the queue and, when one is open, the sitting under it.
     *
     * <p>{@code load()} alone was right while this screen was the only one that could change a
     * grade. Since 2026-08-30 (live session, U-38) it is not: {@link GradeReviewView} approves
     * and overrides too, and coming back from it with the rail refreshed and the table still
     * showing the score she has just changed is the drift the session's re-read rule exists to
     * prevent. See {@link GradingQueueSession#refresh()}.
     */
    @Override
    public void onShow(NavParams params) {
        session.refresh();
    }

    @Override
    public boolean listensToEvents() {
        // Nothing is pushed here. A closed sitting does not change under her.
        return false;
    }

    // ===================== Layout ========================================

    private VBox buildQueueRail() {
        Label heading = new Label(GradingCopy.QUEUE_HEADING);
        heading.getStyleClass().add("h3");
        Label hint = new Label(GradingCopy.QUEUE_HINT);
        hint.getStyleClass().addAll("small", "muted");
        hint.setWrapText(true);

        queueList.setCellFactory(view -> new QueueCell());
        queueList.getSelectionModel().selectedItemProperty().addListener((obs, old, row) -> {
            if (!selecting && row != null) {
                session.openExecution(row);
            }
        });
        VBox.setVgrow(queueList, Priority.ALWAYS);

        VBox rail = new VBox(10, heading, hint, queueList, queueEmpty);
        rail.getStyleClass().add("grading-queue-rail");
        rail.setPadding(new Insets(24, 12, 24, 24));
        rail.setPrefWidth(320);
        rail.setMinWidth(260);
        return rail;
    }

    private VBox buildBody() {
        examName.getStyleClass().add("h1");
        progress.getStyleClass().addAll("small", "muted");
        error.getStyleClass().addAll("small", "danger-text");
        error.setWrapText(true);

        approveSelected.getStyleClass().add("primary");
        approveSelected.setOnAction(event -> confirmAndApprove());
        selectAll.setOnAction(event -> selectAllApprovableRows());
        override.setOnAction(event -> openOverrideDialog());

        HBox actions = new HBox(8, selectAll, Buttons.spacer(), override, approveSelected);
        actions.setAlignment(Pos.CENTER_LEFT);

        buildTable();

        VBox content = new VBox(14, new VBox(2, examName, progress), error, actions, table);
        content.setPadding(new Insets(24, 28, 24, 20));
        content.getStyleClass().add("hsts-page");
        VBox.setVgrow(content, Priority.ALWAYS);
        VBox.setVgrow(table, Priority.ALWAYS);
        return content;
    }

    private void buildTable() {
        table.title("Students");
        table.column(GradingCopy.COLUMN_STUDENT, StudentGradeRow::studentName)
                .column(GradingCopy.COLUMN_AUTO, row -> String.valueOf(row.autoScore()))
                .column(GradingCopy.COLUMN_SCORE, row -> row.effectiveScore() + " / 100")
                .column(GradingCopy.COLUMN_STATE, GradingCopy::state)
                .column(GradingCopy.COLUMN_ADJUSTED, GradingCopy::adjustedMarker)
                .column(reviewColumn())
                // F-9: "Auto" and "Score" hold two or three digits; the student name
                // holds a full name and was clipping at the default window size. The Review
                // column is sized to its button rather than to a heading it does not have.
                .columnWidths(260, 110, 130, 150, 60, 110)
                .numericColumns(1, 2);

        // Selection drives the bulk approve. Multiple selection rather than a checkbox column:
        // it is the platform's own idiom and needs no extra column to explain itself.
        table.table().getSelectionModel().setSelectionMode(
                javafx.scene.control.SelectionMode.MULTIPLE);
        table.table().getSelectionModel().getSelectedItems()
                .addListener((javafx.collections.ListChangeListener<StudentGradeRow>) change -> {
                    if (selecting) {
                        return;
                    }
                    session.clearSelection();
                    for (StudentGradeRow row : table.table().getSelectionModel().getSelectedItems()) {
                        if (row != null && GradingCopy.canOverride(row)) {
                            session.select(row.gradeId(), true);
                        }
                    }
                });
    }

    /**
     * The column that opens one student's paper (E12.6 — U-38).
     *
     * <p>A button per row rather than the table's own {@code openOnClick} gesture, and the
     * reason is this table's other job: rows are <b>multi-selected</b> here to drive the bulk
     * approve, so a click that navigated away would fight the click that ticks a row. A button
     * is the one affordance that can say "open this one" on a surface where the row itself
     * already means something else.
     *
     * @return the column, ready to hand to the table
     */
    private TableColumn<StudentGradeRow, StudentGradeRow> reviewColumn() {
        TableColumn<StudentGradeRow, StudentGradeRow> column =
                new TableColumn<>(GradingCopy.COLUMN_REVIEW);
        column.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleObjectProperty<>(cell.getValue()));
        column.setPrefWidth(110);
        column.setSortable(false);
        column.setCellFactory(unused -> new TableCell<>() {
            private final Button open = new Button(GradingCopy.REVIEW);

            {
                open.getStyleClass().add("ghost");
                open.setOnAction(event -> {
                    StudentGradeRow row = getItem();
                    if (row != null) {
                        openReview(row);
                    }
                });
            }

            @Override
            protected void updateItem(StudentGradeRow row, boolean empty) {
                super.updateItem(row, empty);
                setGraphic(empty || row == null ? null : open);
            }
        });
        return column;
    }

    /**
     * Opens one paper on the review screen.
     *
     * <p>Carries the exam label as well as the grade id, because the teacher shape of
     * {@code StudentGradeRow} leaves {@code examName} null by design and this screen is already
     * showing the name above the table. {@link GradeReviewView#PARAM_EXAM} explains why that is
     * a nav parameter rather than a wire amendment.
     *
     * @param row the student whose paper she pressed Review on
     */
    private void openReview(StudentGradeRow row) {
        String exam = session.openExecution()
                .map(open -> GradingCopy.examLabel(open.summary()))
                .orElse(null);
        navigator().navigate(Routes.GRADE_REVIEW.id(),
                NavParams.of(GradeReviewView.PARAM_GRADE, row.gradeId(),
                        GradeReviewView.PARAM_EXAM, exam));
    }

    // ===================== Rendering =====================================

    private void render() {
        selecting = true;
        try {
            // Only replace the lists when their contents actually changed. setAll() is a
            // clear-then-add, and JavaFX drops the selection when a backing list is cleared —
            // so refilling on every render silently wiped what the user had just clicked. A
            // render happens on every session change, including the one her click caused.
            if (!queueList.getItems().equals(session.queue())) {
                queueList.getItems().setAll(session.queue());
            }
            boolean empty = session.queue().isEmpty();
            show(queueEmpty, empty);
            show(queueList, !empty);

            session.openExecution().ifPresentOrElse(open -> {
                examName.setText(GradingCopy.examLabel(open.summary()));
                progress.setText(GradingCopy.progress(open.summary()) + "  ·  closed "
                        + GradingCopy.closedAt(open.summary(), ZONE));
                setRows(session.rows());
            }, () -> {
                examName.setText("");
                progress.setText("");
                setRows(java.util.List.of());
            });

            String message = session.error().orElse("");
            error.setText(message);
            show(error, !message.isEmpty());

            boolean canAct = session.openExecution().isPresent() && !session.isBusy();
            approveSelected.setDisable(!canAct || session.selectionSize() == 0);
            selectAll.setDisable(!canAct);
            override.setDisable(!canAct || selectedRow().isEmpty()
                    || !GradingCopy.canOverride(selectedRow().get()));
        } finally {
            selecting = false;
        }
    }

    /**
     * Hands the table its rows, but only when that changes something ⚑.
     *
     * <p>Skipping the write when the rows are already there is what keeps a re-render from
     * wiping the teacher's selection, and that half is unchanged. What it must not skip is
     * the <em>first</em> write: 2026-08-28, manual round 1, lead's ruling, after a sitting
     * whose every paper was already approved answered with zero rows. Empty equals empty, so
     * the guard held, {@code setItems} was never called, and a {@link DataTable} that has
     * never been given content is still showing its loading skeleton. She was left watching
     * a table pretend to load a list the server had already said was empty.
     *
     * <p>"Never been given content" is exactly {@code IDLE} or {@code LOADING}: every other
     * state is one {@code setItems} or an explicit failure put it in.
     *
     * @param rows what the session holds for the open sitting, possibly none
     */
    private void setRows(List<StudentGradeRow> rows) {
        if (!table.table().getItems().equals(rows) || table.state().showsSkeleton()) {
            table.setItems(rows);
        }
    }

    private Optional<StudentGradeRow> selectedRow() {
        return Optional.ofNullable(table.table().getSelectionModel().getSelectedItem());
    }

    /**
     * Ticks every row that can still be approved, <b>in the table</b>.
     *
     * <p>The table is this screen's source of truth for selection, and the listener mirrors it
     * into the session. Driving it the other way — session first, table second — is what broke
     * row selection outright: the listener clears the session before rebuilding it, that clear
     * triggers a render, and a render that touched the table's selection wiped it out from
     * under the listener's own iteration. One direction, no loop, nothing to guard.
     *
     * <p>Approved rows are skipped rather than ticked and refused. Re-approving is harmless by
     * contract, but counting rows already done would make the confirmation overstate what is
     * about to happen.
     */
    private void selectAllApprovableRows() {
        var model = table.table().getSelectionModel();
        model.clearSelection();
        for (StudentGradeRow row : session.rows()) {
            if (GradingCopy.canOverride(row)) {
                model.select(row);
            }
        }
    }

    // ===================== Actions =======================================

    /**
     * Asks before publishing a class's marks.
     *
     * <p>The one destructive-ish action on this screen: approving cannot be undone, because
     * overriding an approved grade answers {@code CONFLICT} by design.
     */
    private void confirmAndApprove() {
        int count = session.selectionSize();
        if (count == 0) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                GradingCopy.bulkConfirm(count), ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText(null);
        confirm.showAndWait()
                .filter(button -> button == ButtonType.OK)
                .ifPresent(button -> session.approveSelected());
    }

    /**
     * The override, on the row she has selected.
     *
     * <p>The dialog itself moved to {@link OverrideDialog} on 2026-08-30 (live session, U-38),
     * when {@link GradeReviewView} gained the same action. It carries S-23's required
     * justification and S-22's optional comment, and the reasoning for every part of its shape
     * moved with it.
     */
    private void openOverrideDialog() {
        selectedRow().ifPresent(row -> OverrideDialog.show(row).ifPresent(outcome ->
                session.override(row.gradeId(), outcome.score(), outcome.justification(),
                        outcome.teacherComment())));
    }

    // ===================== Cells =========================================

    /** One waiting sitting: what it is, how far through it she is, and when it closed. */
    private static final class QueueCell extends ListCell<ExecutionGradingSummary> {

        @Override
        protected void updateItem(ExecutionGradingSummary summary, boolean empty) {
            super.updateItem(summary, empty);
            if (empty || summary == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label name = new Label(GradingCopy.examLabel(summary));
            name.getStyleClass().add("strong");
            Label detail = new Label(GradingCopy.progress(summary));
            detail.getStyleClass().addAll("small", "muted");
            setGraphic(new VBox(2, name, detail));
        }
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
