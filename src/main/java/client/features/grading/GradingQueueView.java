package client.features.grading;

import client.core.NavParams;
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
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
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

    @Override
    public void onShow(NavParams params) {
        session.load();
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
        selectAll.setOnAction(event -> session.selectAllApprovable());
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
                .column(GradingCopy.COLUMN_ADJUSTED, GradingCopy::adjustedMarker);

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

    // ===================== Rendering =====================================

    private void render() {
        selecting = true;
        try {
            queueList.getItems().setAll(session.queue());
            boolean empty = session.queue().isEmpty();
            show(queueEmpty, empty);
            show(queueList, !empty);

            session.openExecution().ifPresentOrElse(open -> {
                examName.setText(GradingCopy.examLabel(open.summary()));
                progress.setText(GradingCopy.progress(open.summary()) + "  ·  closed "
                        + GradingCopy.closedAt(open.summary(), ZONE));
                table.setItems(session.rows());
            }, () -> {
                examName.setText("");
                progress.setText("");
                table.setItems(java.util.List.of());
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

    private Optional<StudentGradeRow> selectedRow() {
        return Optional.ofNullable(table.table().getSelectionModel().getSelectedItem());
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
     * The override dialog: a score, the reason that must accompany it (S-23), and the optional
     * comment for the student (S-22).
     *
     * <p><b>Two boxes, not one, and they are separated on purpose.</b> They are written at the
     * same moment about the same paper, but they have different readers: the reason is the
     * audit trail and never leaves the staff room, the comment is the only free text the
     * student ever sees. Merging them would mean either a teacher writing for the record in
     * front of a student, or writing for the student in the audit log — and each label says
     * which one this box is, because a box's placement cannot.
     *
     * <p>The comment box opens <b>empty even when the grade already has a comment</b>, and the
     * label says that leaving it empty keeps what is saved. Pre-filling would be friendlier
     * until the first teacher cleared the box expecting the comment to go away, which on this
     * wire it does not (the contract's A3 null-preserves rule).
     */
    private void openOverrideDialog() {
        Optional<StudentGradeRow> target = selectedRow();
        if (target.isEmpty()) {
            return;
        }
        StudentGradeRow row = target.get();

        Spinner<Integer> score = new Spinner<>(0, 100, row.effectiveScore());
        score.setEditable(true);

        TextArea reason = new TextArea();
        reason.setPromptText(GradingCopy.JUSTIFICATION_PROMPT);
        reason.setWrapText(true);
        reason.setPrefRowCount(3);

        Label reasonLabel = new Label(GradingCopy.JUSTIFICATION_LABEL);
        reasonLabel.setWrapText(true);
        reasonLabel.getStyleClass().addAll("small", "muted");

        TextArea comment = new TextArea();
        comment.setPromptText(GradingCopy.COMMENT_PROMPT);
        comment.setWrapText(true);
        comment.setPrefRowCount(3);

        Label commentLabel = new Label(GradingCopy.COMMENT_LABEL);
        commentLabel.setWrapText(true);
        commentLabel.getStyleClass().addAll("small", "muted");

        Separator between = new Separator();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(GradingCopy.OVERRIDE_TITLE);
        dialog.setHeaderText(row.studentName());
        dialog.getDialogPane().setContent(
                new VBox(8, score, reasonLabel, reason, between, commentLabel, comment));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        dialog.showAndWait()
                .filter(button -> button == ButtonType.OK)
                .ifPresent(button -> session.override(row.gradeId(), score.getValue(),
                        reason.getText(), comment.getText()));
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
