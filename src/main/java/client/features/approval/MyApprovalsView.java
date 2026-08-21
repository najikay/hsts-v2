package client.features.approval;

import client.core.NavParams;
import client.core.Routes;
import client.ui.components.Buttons;
import client.ui.components.DataTable;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.components.StatusChip;
import client.ui.screen.AbstractScreen;
import common.dto.approval.ApprovalRow;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The teacher's own exams and what became of them (Presentation tier, E8.6 — F4.2).
 *
 * <p>F4.2 asks for a rejection reason to be "stored and pushed to the authoring teacher as a
 * notification <b>and</b> visible on the exam". This screen is the second half. A notification
 * is a moment; a teacher who dismissed it, or who reads her bell on Monday and sits down to
 * fix the exam on Wednesday, needs somewhere the reason still is. The rejection notification's
 * reference points here, at this route id, carrying the version so the right row opens.
 *
 * <p>A renderer over {@link MyApprovalsSession}, which owns every decision it makes.
 *
 * <h2>Deliberately small, and honest about it</h2>
 *
 * <p>This is not the exam list: E7 owns that, along with the builder, the version history and
 * the actions. What is here is approval state and the reason panel, which is what E8 is
 * responsible for and all it claims. E7 replaces this screen at the same route id, and the
 * rail item's tooltip says so rather than letting the label over-promise.
 */
public final class MyApprovalsView extends AbstractScreen {

    private final BorderPane root = new BorderPane();
    private final DataTable<ApprovalRow> table = new DataTable<>();
    private final VBox rejectedPanel = new VBox(8);
    private final Label rejectedExam = new Label();
    private final Label rejectedReason = new Label();
    private final Button openRejected = Buttons.secondary("Open the exam");
    private final Label error = new Label();

    private MyApprovalsSession session;

    @Override
    protected Parent build() {
        session = new MyApprovalsSession(dispatcher(), onFxThread()).onChange(this::render);

        buildColumns();
        table.title(ApprovalCopy.MINE_TITLE)
                .emptyState(new EmptyState(Icons.EXAMS,
                        ApprovalCopy.MINE_EMPTY_TITLE, ApprovalCopy.MINE_EMPTY_HINT))
                .onRetry(ApprovalCopy.MINE_LOAD_FAILED, () -> session.load());
        table.table().setRowFactory(view -> openOnDoubleClick());
        VBox.setVgrow(table, Priority.ALWAYS);

        openRejected.setOnAction(e -> session.focused().ifPresent(this::openPreview));

        root.getStyleClass().add("my-approvals");
        root.setTop(buildHeader());
        root.setCenter(table);
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        // The rejection notification carries the version it is about; a plain rail click
        // carries nothing, and the session falls back to the first sent-back exam, which is
        // what somebody opening this screen unprompted is most likely here for.
        session.selectedVersionId(params.getLong("examVersionId", 0));
        session.load();
    }

    @Override
    public boolean listensToEvents() {
        return false;
    }

    // ===================== Rendering =====================================

    private void render() {
        error.setText(session.error().orElse(""));
        show(error, session.error().isPresent());

        switch (session.state()) {
            case IDLE, LOADING -> table.showLoading();
            case ERROR -> table.showError();
            case READY, EMPTY -> table.setItems(session.rows());
        }
        renderRejectedPanel();
    }

    /**
     * The reason panel, which exists only when there is a reason (F4.2).
     *
     * <p>Hidden rather than emptied when nothing was sent back: a heading with nothing under
     * it is the mystery state PRD §4.1 forbids, and a teacher whose exams are all fine should
     * see a clean list rather than an empty complaint box.
     */
    private void renderRejectedPanel() {
        var reason = session.focusedRejectionReason();
        if (reason.isEmpty()) {
            show(rejectedPanel, false);
            return;
        }
        session.focused().ifPresent(row -> rejectedExam.setText(row.examLabel()));
        rejectedReason.setText(reason.get());
        show(rejectedPanel, true);
    }

    private void buildColumns() {
        TableColumn<ApprovalRow, String> exam = new TableColumn<>("Exam");
        exam.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(cell.getValue().examLabel()));
        exam.setPrefWidth(260);

        table.column(exam)
                .column("Course", ApprovalRow::courseLabel)
                .column("Questions", row -> ApprovalCopy.questions(row.questionCount()))
                .column("Submitted", row -> ApprovalCopy.submittedAt(row.submittedAt()))
                .column(statusColumn());
    }

    private static TableColumn<ApprovalRow, ApprovalRow> statusColumn() {
        TableColumn<ApprovalRow, ApprovalRow> column = new TableColumn<>("Status");
        column.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleObjectProperty<>(cell.getValue()));
        column.setPrefWidth(180);
        column.setCellFactory(unused -> new TableCell<>() {
            @Override
            protected void updateItem(ApprovalRow row, boolean empty) {
                super.updateItem(row, empty);
                setGraphic(empty || row == null ? null : StatusChip.examStatus(row.state().name()));
            }
        });
        return column;
    }

    private TableRow<ApprovalRow> openOnDoubleClick() {
        TableRow<ApprovalRow> row = new TableRow<>();
        row.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && !row.isEmpty()) {
                openPreview(row.getItem());
            }
        });
        return row;
    }

    /**
     * Opens the author's own version in the preview screen.
     *
     * <p>The same screen the coordinator reviews on, and the server allows it for the same
     * reason it allows her: the author of a version may read it back, which is what makes a
     * rejection reason actionable.
     */
    private void openPreview(ApprovalRow row) {
        navigator().navigate(Routes.EXAM_PREVIEW.id(),
                NavParams.of("examVersionId", row.examVersionId()));
    }

    // ===================== Layout ========================================

    private VBox buildHeader() {
        Label title = new Label(ApprovalCopy.MINE_TITLE);
        title.getStyleClass().add("h1");

        Label subtitle = new Label(ApprovalCopy.MINE_SUBTITLE);
        subtitle.getStyleClass().addAll("small", "muted");

        error.getStyleClass().addAll("small", "danger-text");
        error.setWrapText(true);
        show(error, false);

        buildRejectedPanel();

        VBox header = new VBox(10, new VBox(6, title, subtitle), error, rejectedPanel);
        header.setPadding(new Insets(24, 28, 12, 28));
        return header;
    }

    private void buildRejectedPanel() {
        Label heading = new Label(ApprovalCopy.REJECTED_PANEL_TITLE);
        heading.getStyleClass().add("h3");

        rejectedExam.getStyleClass().addAll("small", "muted");
        rejectedReason.getStyleClass().addAll("body", "strong", "rejection-reason");
        rejectedReason.setWrapText(true);

        HBox actions = new HBox(8, Buttons.spacer(), openRejected);
        actions.setAlignment(Pos.CENTER_LEFT);

        rejectedPanel.getChildren().addAll(heading, rejectedExam, rejectedReason, actions);
        rejectedPanel.getStyleClass().addAll("hsts-card", "rejected-panel");
        show(rejectedPanel, false);
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
