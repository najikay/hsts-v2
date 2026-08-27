package client.features.approval;

import client.core.NavParams;
import client.core.Routes;
import client.ui.components.DataTable;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.components.StatusChip;
import client.ui.screen.AbstractScreen;
import common.dto.approval.ApprovalRow;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The coordinator's approval queue (Presentation tier, E8.3 — F4.1).
 *
 * <p>A renderer over {@link ApprovalQueueSession}: one row per exam version waiting on her,
 * with the facts she triages by — exam, course, author, length, when it arrived — and one
 * action, which is to open it. Nothing is decided from this screen, deliberately: F4.1's
 * whole point is that a decision follows a reading of the exam, and a queue with Approve
 * buttons on it would be an invitation to skip that.
 *
 * <p>It holds no rules. Which rows exist, which empty state applies and what the error
 * sentence is are all the session's, and the session is unit-tested without a toolkit.
 *
 * <p>The self-authored badge (F4.3) is information rather than a warning: a coordinator may
 * approve her own exam, and what she is owed is to know the system noticed.
 */
public final class ApprovalQueueView extends AbstractScreen {

    private final BorderPane root = new BorderPane();
    private final DataTable<ApprovalRow> table = new DataTable<>();
    private final Label error = new Label();

    private ApprovalQueueSession session;
    private EmptyState empty;

    @Override
    protected Parent build() {
        session = new ApprovalQueueSession(dispatcher(), onFxThread())
                .onChange(this::render)
                // The live re-read when an exam arrives in her queue (B-30, NFR-18).
                // Subscribed by the SESSION rather than by this screen, which is the shape
                // ExamListView and MyGradesView use and for its stated reason: the wiring then
                // sits where a test can reach it, instead of behind a listensToEvents override
                // only the shell can exercise.
                .subscribeTo(eventBus());

        empty = new EmptyState(Icons.INBOX,
                ApprovalCopy.QUEUE_EMPTY_TITLE, ApprovalCopy.QUEUE_EMPTY_HINT);

        buildColumns();
        table.title(ApprovalCopy.QUEUE_TITLE)
                .searchable("Search exam, course or teacher", ApprovalQueueView::matches)
                .emptyState(empty)
                .onRetry(ApprovalCopy.QUEUE_LOAD_FAILED, () -> session.load());
        table.openOnClick(this::openPreview);
        VBox.setVgrow(table, Priority.ALWAYS);

        root.getStyleClass().add("approval-queue");
        root.setTop(buildHeader());
        root.setCenter(table);
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        session.load();
    }

    @Override
    public boolean listensToEvents() {
        // False, and it is not the same "false" it was before B-30. This flag governs whether
        // ScreenLifecycle registers THE SCREEN on the bus, and the screen has no @Subscribe:
        // the subscription belongs to ApprovalQueueSession, wired in build() above. Turning
        // this on would register an object with nothing to receive.
        return false;
    }

    // ===================== Rendering =====================================

    private void render() {
        error.setText(session.error().orElse(""));
        show(error, session.error().isPresent());
        empty.set(session.emptyTitle(), session.emptyHint());

        switch (session.state()) {
            case IDLE, LOADING -> table.showLoading();
            case ERROR -> table.showError();
            case READY, EMPTY -> table.setItems(session.rows());
        }
    }

    private void buildColumns() {
        TableColumn<ApprovalRow, String> exam = new TableColumn<>("Exam");
        exam.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(cell.getValue().examLabel()));
        exam.setPrefWidth(260);

        table.column(exam)
                .column("Course", ApprovalRow::courseLabel)
                .column("Teacher", ApprovalRow::authorName)
                .column("Questions", row -> ApprovalCopy.questions(row.questionCount()))
                .column("Submitted", row -> ApprovalCopy.submittedAt(row.submittedAt()))
                .column(statusColumn())
                // Exam and Status carry their own widths above; the rest are sized
                // to their content so a teacher's name and a date are never clipped.
                .columnWidths(260, 150, 190, 110, 170, 220)
                // UI wave 2: a question count is a number, so it is right
                // aligned in tabular figures like every other number in the app.
                .numericColumns(3);
    }

    /**
     * The status column: a chip, plus the F4.3 badge when she wrote it herself.
     *
     * <p>A cell rather than a string, because a chip is how every other state in the app is
     * shown (E4.15) and a queue that spelled its states as words would be the one screen that
     * looked different.
     */
    private static TableColumn<ApprovalRow, ApprovalRow> statusColumn() {
        TableColumn<ApprovalRow, ApprovalRow> column = new TableColumn<>("Status");
        column.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleObjectProperty<>(cell.getValue()));
        column.setPrefWidth(220);
        column.setCellFactory(unused -> new TableCell<>() {
            @Override
            protected void updateItem(ApprovalRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setGraphic(null);
                    return;
                }
                HBox cell = new HBox(8, StatusChip.examStatus(row.state().name()));
                cell.setAlignment(Pos.CENTER_LEFT);
                if (row.selfAuthored()) {
                    Label badge = new Label(ApprovalCopy.SELF_AUTHORED_BADGE);
                    badge.getStyleClass().addAll("hsts-chip", "neutral", "self-authored");
                    cell.getChildren().add(badge);
                }
                setGraphic(cell);
            }
        });
        return column;
    }

    private void openPreview(ApprovalRow row) {
        navigator().navigate(Routes.EXAM_PREVIEW.id(),
                NavParams.of("examVersionId", row.examVersionId()));
    }

    /** @return whether a row matches the toolbar search, on the three fields people type. */
    private static boolean matches(ApprovalRow row, String needle) {
        return contains(row.examName(), needle)
                || contains(row.examDisplayId(), needle)
                || contains(row.courseName(), needle)
                || contains(row.courseCode(), needle)
                || contains(row.authorName(), needle);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null
                && haystack.toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    // ===================== Layout ========================================

    private VBox buildHeader() {
        Label title = new Label(ApprovalCopy.QUEUE_TITLE);
        title.getStyleClass().add("h1");

        Label subtitle = new Label(ApprovalCopy.QUEUE_SUBTITLE);
        subtitle.getStyleClass().addAll("small", "muted");

        error.getStyleClass().addAll("small", "danger-text");
        error.setWrapText(true);
        show(error, false);

        VBox header = new VBox(6, title, subtitle, error);
        header.setPadding(new Insets(24, 28, 12, 28));
        return header;
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
