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
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;

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
 *
 * <h2>Ticks and selection are two different things (2026-08-30, live session, U-46)</h2>
 *
 * <p>Which papers she is about to approve lives in a <b>checkbox column</b> and in
 * {@link GradingQueueSession}'s selection behind it. Which row Review and Change score act on is
 * the table's own single selection. They were the same thing until U-46 and that was the defect:
 * the table was in {@code MULTIPLE} mode with a listener mirroring its selected rows into the
 * session, so a plain click on the next student <em>replaced</em> the selection instead of adding
 * to it, Select all was undone by the next click, and Naji found himself "approving only one at a
 * time". A tick is a deliberate act on one row and survives a click anywhere else.
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
    private final Button selectAll = new Button(GradingCopy.SELECT_ALL);
    private final Button override = new Button(GradingCopy.OVERRIDE);

    /**
     * The property key under which {@link #overrideHolder} records the installed U-64
     * tooltip, so installing is idempotent and a test can see the wiring.
     */
    public static final String OVERRIDE_TIP_KEY = "hsts.override-blocked-tip";

    /** U-64's explanation. One instance, installed and uninstalled rather than rebuilt. */
    private final Tooltip overrideBlockedTip = new Tooltip(GradingCopy.OVERRIDE_ONE_AT_A_TIME);

    /**
     * The wrapper the U-64 tooltip lives on (S3 sweep).
     *
     * <p>A disabled control receives no mouse events, so a tooltip set on the disabled
     * button itself could never show - the sentence explaining the way in was unreachable
     * in exactly the state it was written for. The wrapper stays enabled and hoverable.
     */
    private final HBox overrideHolder = new HBox(override);

    private final EmptyState queueEmpty = new EmptyState(Icons.GRADING,
            GradingCopy.QUEUE_EMPTY_TITLE, GradingCopy.QUEUE_EMPTY_HINT);

    private GradingQueueSession session;
    private boolean selecting;

    /**
     * What the checkboxes were last drawn from, so a render knows when they are stale.
     *
     * <p>The cells read the session, and a cell is only asked to redraw itself when its row
     * changes. A re-read that clears the selection usually brings new rows with it and the ticks
     * clear themselves, but not always: a refused approval answers with rows equal to the ones on
     * screen, {@link #setRows} rightly skips the write, and nothing would tell the checkboxes that
     * the session no longer holds them ticked.
     */
    private List<Long> renderedTicks = List.of();

    /**
     * The bulk confirmation, as a seam (2026-08-30, live session, U-46).
     *
     * <p>Production asks in a modal, which is the point of the dialog and is also why no test can
     * press Approve selected: {@code showAndWait} blocks the FX thread the test is driving, so a
     * headless run hangs rather than fails. The confirmation is therefore a field, and
     * {@code GradingInteractionTest} replaces it with "yes" the same reflective way
     * {@code ReleaseManagerInteractionTest} reaches a screen's session. What is under test is what
     * the request carries; that a teacher is asked first is asserted by reading this wiring, not
     * by clicking through it.
     */
    private IntPredicate bulkConfirm = this::askBeforePublishing;

    @Override
    protected Parent build() {
        session = new GradingQueueSession(dispatcher(), onFxThread())
                .onChange(this::render)
                // The live re-read when a sitting closes with papers to mark (U-63, NFR-18).
                // GRADING_DUE already reached her bell; until now the queue under it did not
                // re-read, which on the app's other inbox was B-30.
                .subscribeTo(eventBus());

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

        overrideHolder.getStyleClass().add("override-holder");
        HBox actions = new HBox(8, selectAll, Buttons.spacer(), overrideHolder, approveSelected);
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
        table.column(tickColumn())
                .column(GradingCopy.COLUMN_STUDENT, StudentGradeRow::studentName)
                .column(GradingCopy.COLUMN_AUTO, row -> String.valueOf(row.autoScore()))
                .column(GradingCopy.COLUMN_SCORE, row -> row.effectiveScore() + " / 100")
                .column(GradingCopy.COLUMN_STATE, GradingCopy::state)
                .column(GradingCopy.COLUMN_ADJUSTED, GradingCopy::adjustedMarker)
                .column(reviewColumn())
                // F-9: "Auto" and "Score" hold two or three digits; the student name
                // holds a full name and was clipping at the default window size. The Review
                // column is sized to its button rather than to a heading it does not have, and
                // the tick column to a checkbox, which is the narrowest thing on the row.
                .columnWidths(52, 260, 110, 130, 150, 60, 110)
                .numericColumns(2, 3);

        // Single, and only for Review and Change score. The bulk approve reads the ticks, so a
        // click on a row must be free to mean "this one" without disturbing what is chosen
        // (2026-08-30, live session, U-46).
        table.table().getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        // A row click changes nothing in the session, and Change score is enabled from the row
        // that is selected: without this the button kept whatever it was when the session last
        // changed, so the teacher clicked a paper she could still change and found the control
        // dead (2026-08-30, live session, U-46 addendum). Re-rendering is all it does, and the
        // guard is the one render already sets around its own writes to the two lists.
        table.table().getSelectionModel().selectedItemProperty()
                .addListener((observable, was, now) -> {
                    if (!selecting) {
                        render();
                    }
                });
    }

    /**
     * The column the bulk approve is actually made of (2026-08-30, live session, U-46).
     *
     * <p>One checkbox per row, and only on rows {@link GradingCopy#canOverride} allows: an
     * approved paper cannot be approved again, and a box she can tick and not act on is a control
     * that lies. The ticks <b>are</b> {@link GradingQueueSession}'s selection — drawn from it in
     * {@code updateItem} rather than remembered in the cell — which is what makes the session's
     * clear-on-re-read rule visible: when a refresh drops the selection the boxes empty with it.
     *
     * <p>The mouse press is consumed and the box fired by hand. A press that reached the cell
     * would also move the table's single selection to this row, which is the row Change score
     * acts on: ticking Omer must not silently re-aim the override at him. Consuming in a
     * <em>filter</em> is what makes that deterministic — it runs before the checkbox's own
     * behaviour and before the cell's, so nothing depends on which handler was installed first.
     * {@code setFocusTraversable(false)} keeps the boxes out of the tab order for the same
     * reason: this column is a pointer gesture, and Select all is the keyboard's way in.
     *
     * @return the column, ready to hand to the table
     */
    private TableColumn<StudentGradeRow, StudentGradeRow> tickColumn() {
        TableColumn<StudentGradeRow, StudentGradeRow> column =
                new TableColumn<>(GradingCopy.COLUMN_TICK);
        column.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleObjectProperty<>(cell.getValue()));
        column.setPrefWidth(52);
        column.setMinWidth(44);
        column.setSortable(false);
        column.setCellFactory(unused -> new TableCell<>() {
            private final CheckBox tick = new CheckBox();

            {
                setAlignment(Pos.CENTER);
                tick.setFocusTraversable(false);
                tick.setTooltip(new Tooltip(GradingCopy.TICK_HINT));
                tick.setAccessibleText(GradingCopy.TICK_HINT);
                tick.setOnAction(event -> {
                    StudentGradeRow row = getItem();
                    if (row != null) {
                        session.select(row.gradeId(), tick.isSelected());
                    }
                });
                tick.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                    event.consume();
                    tick.fire();
                });
            }

            @Override
            protected void updateItem(StudentGradeRow row, boolean empty) {
                super.updateItem(row, empty);
                boolean approvable = !empty && row != null && GradingCopy.canOverride(row);
                if (approvable) {
                    tick.setSelected(session.isSelected(row.gradeId()));
                }
                setGraphic(approvable ? tick : null);
            }
        });
        return column;
    }

    /**
     * The column that opens one student's paper (E12.6 — U-38).
     *
     * <p>A button per row rather than the table's own {@code openOnClick} gesture, and the
     * reason is this table's other job: a click on a row here <b>selects</b> it, which is what
     * aims Change score, so a click that also navigated away would fight it. A button is the one
     * affordance that can say "open this one" on a surface where the row itself already means
     * something else. (Since U-46 the bulk approve is the tick column's, not the selection's;
     * the argument for a button is unchanged, and now there is a second control on the row that
     * a navigating click would have fought.)
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

            // The ticks are drawn from the session, and only a cell update redraws them. When
            // the rows did not change but the selection did, ask the table for that update.
            List<Long> ticks = session.selection();
            if (!ticks.equals(renderedTicks)) {
                renderedTicks = ticks;
                table.table().refresh();
            }

            String message = session.error().orElse("");
            error.setText(message);
            show(error, !message.isEmpty());

            boolean canAct = session.openExecution().isPresent() && !session.isBusy();
            approveSelected.setDisable(!canAct || session.selectionSize() == 0);
            selectAll.setDisable(!canAct);
            // 2026-08-31, U-64 (Omar, round 5): with everything ticked, Change score
            // opened for whichever row happened to hold the table's single selection,
            // which read as "the first one ticked". An override is a one-student act:
            // with more than one ticked it is off, and the tooltip says the way in.
            boolean manyTicked = session.selectionSize() > 1;
            override.setDisable(!canAct || manyTicked || selectedRow().isEmpty()
                    || !GradingCopy.canOverride(selectedRow().get()));
            // U-64's second half (S3 sweep): the tooltip goes on the enabled wrapper,
            // because JavaFX shows no tooltip over a disabled control - set on the button
            // it could never appear. Installed once while several rows are ticked and
            // uninstalled when the state passes, rather than rebuilt on every render.
            if (manyTicked) {
                if (overrideHolder.getProperties().get(OVERRIDE_TIP_KEY) == null) {
                    Tooltip.install(overrideHolder, overrideBlockedTip);
                    overrideHolder.getProperties().put(OVERRIDE_TIP_KEY, overrideBlockedTip);
                }
            } else if (overrideHolder.getProperties().get(OVERRIDE_TIP_KEY) != null) {
                Tooltip.uninstall(overrideHolder, overrideBlockedTip);
                overrideHolder.getProperties().remove(OVERRIDE_TIP_KEY);
            }
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
     * Ticks every row that can still be approved.
     *
     * <p>One line since 2026-08-30 (live session, U-46), and that is the fix. It used to select
     * the rows in the <em>table</em> and let a listener mirror them into the session, so the next
     * plain click on any row replaced the whole thing and Select all had never happened. The
     * session owns the ticks; see {@link GradingQueueSession#selectAllApprovable()} for which
     * rows it counts and why the approved ones are left out.
     */
    private void selectAllApprovableRows() {
        session.selectAllApprovable();
    }

    // ===================== Actions =======================================

    /**
     * Asks before publishing a class's marks, then sends every ticked grade.
     *
     * <p>The one destructive-ish action on this screen: approving cannot be undone, because
     * overriding an approved grade answers {@code CONFLICT} by design. The count in the question
     * is the number of ticks, which since U-46 is also exactly what
     * {@link GradingQueueSession#approveSelected()} will send.
     *
     * <p>The asking itself goes through {@link #bulkConfirm} so that a test can drive the rest of
     * this method without a modal on the FX thread; see that field.
     */
    private void confirmAndApprove() {
        int count = session.selectionSize();
        if (count == 0) {
            return;
        }
        if (bulkConfirm.test(count)) {
            session.approveSelected();
        }
    }

    /**
     * The modal half of {@link #confirmAndApprove}: a plain confirmation, named by its count.
     *
     * @param count how many grades are ticked
     * @return {@code true} when she pressed OK
     */
    private boolean askBeforePublishing(int count) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                GradingCopy.bulkConfirm(count), ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText(null);
        return confirm.showAndWait().filter(button -> button == ButtonType.OK).isPresent();
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
