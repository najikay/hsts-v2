package client.features.exambuild;

import client.core.NavParams;
import client.ui.components.Buttons;
import client.ui.components.DataTable;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.components.StatusChip;
import client.ui.components.WarnConfirm;
import client.ui.screen.AbstractScreen;
import common.dto.authoring.ExamListRow;
import common.dto.authoring.ExamVersionRow;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The teacher's exams, every version of each, and what became of them (Presentation tier,
 * E7.10 / E7.15 — F3.5, F3.6, F4.2).
 *
 * <p>A renderer over {@link ExamListSession}, which owns every decision it makes. This screen
 * replaces {@code MyApprovalsView} behind route id {@code exams}, which is contract section 8
 * and the lead's ruling at the E8 freeze.
 *
 * <h2>Master and detail, because "expandable" has no component here</h2>
 *
 * <p>E7.10 asks for versions to be expandable. There is no {@code TreeTableView} anywhere in
 * this client and {@code DataTable} is a flat table, so an expandable row would mean a new
 * shared component under {@code client/ui}, which is not this member's to write. Master and
 * detail says the same thing with the components the design system already has and that the
 * bank screen already uses: the exams on the left, the selected exam's versions on the right.
 *
 * <h2>The rejection reason is on the version, not in one panel ⚑</h2>
 *
 * <p>The screen this replaces had a single panel showing the focused row's reason. Here every
 * sent-back version carries its own on its own card. That is deliberately a superset: F4.2 wants
 * the reason "visible on the exam", and a teacher whose v2 and v4 were both sent back for
 * different reasons could previously read only one of them at a time. The deep link still lands
 * her on the right version, which is {@link ExamListSession#focusedVersion()}.
 */
public final class ExamListView extends AbstractScreen {

    private final BorderPane root = new BorderPane();
    private final DataTable<ExamListRow> table = new DataTable<>();
    private final VBox versionsPanel = new VBox(12);
    private final Label versionsHeading = new Label(ExamListCopy.VERSIONS_TITLE);
    private final Label noSelection = new Label(ExamListCopy.NO_SELECTION);
    private final Label error = new Label();

    private ExamListSession session;

    /** Guards against a second dialog while one is up, the way {@code BankView} does. */
    private boolean showingDialog;

    @Override
    protected Parent build() {
        session = new ExamListSession(dispatcher(), onFxThread())
                .onChange(this::render)
                // The live re-read on a coordinator's decision (NFR-18). Subscribed by the
                // SESSION rather than by this screen, which is the shape MyGradesView uses and
                // for its stated reason: the wiring then sits where a test can reach it, instead
                // of behind a listensToEvents override only the shell can exercise.
                .subscribeTo(eventBus());

        buildColumns();
        table.title(ExamListCopy.TITLE)
                .emptyState(new EmptyState(Icons.EXAMS,
                        ExamListCopy.EMPTY_TITLE, ExamListCopy.EMPTY_HINT))
                .onRetry(ExamListCopy.LOAD_FAILED, () -> session.load());
        table.openOnClick(row -> session.select(row.examId()));
        VBox.setVgrow(table, Priority.ALWAYS);

        root.getStyleClass().add("exam-list");
        root.setTop(buildHeader());
        root.setCenter(buildBody());
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        // The approval notification carries the version it is about; a plain rail click carries
        // nothing, and the session falls back to the first sent-back version, which is what
        // somebody opening this screen unprompted is most likely here for.
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
        renderVersions();
        renderNotices();
    }

    /**
     * Rebuilds the versions panel for the selected exam.
     *
     * <p>Rebuilt rather than patched: the panel is small, the list it renders is immutable, and
     * a patch would have to know which of a version's facts can change under it. The state and
     * the lock token both can, on any approval push.
     */
    private void renderVersions() {
        versionsPanel.getChildren().clear();
        var exam = session.selectedExam();
        if (exam.isEmpty()) {
            versionsPanel.getChildren().addAll(versionsHeading, noSelection);
            return;
        }

        Label name = new Label(exam.get().name());
        name.getStyleClass().add("h3");
        name.setWrapText(true);

        Label summary = new Label(ExamListCopy.examSummary(exam.get()));
        summary.getStyleClass().addAll("small", "muted");

        versionsPanel.getChildren().addAll(versionsHeading, name, summary);
        for (ExamVersionRow version : session.versions()) {
            versionsPanel.getChildren().add(versionCard(exam.get(), version));
        }
    }

    /**
     * One version, with the buttons its state permits and nothing else.
     *
     * <p>Both actions are built from {@code version} and hand that same object back to the
     * session, so the token that travels is the token of the card it was pressed on. Passing an
     * id here and letting the session look the row up again is what would let a push landing
     * between the click and the send substitute a different one.
     */
    private Node versionCard(ExamListRow exam, ExamVersionRow version) {
        Label heading = new Label(ExamListCopy.versionSummary(version));
        heading.getStyleClass().add("body");
        heading.setWrapText(true);

        HBox top = new HBox(10, heading, Buttons.spacer(),
                StatusChip.examStatus(version.state().name()));
        top.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, top);
        card.getStyleClass().addAll("hsts-card", "exam-version-card");

        if (version.hasRejectedReason()) {
            Label reasonHeading = new Label(ExamListCopy.REJECTED_PANEL_TITLE);
            reasonHeading.getStyleClass().addAll("small", "muted");

            Label reason = new Label(version.rejectedReason());
            reason.getStyleClass().addAll("body", "strong", "rejection-reason");
            reason.setWrapText(true);

            card.getChildren().addAll(reasonHeading, reason);
        }

        HBox actions = new HBox(8, Buttons.spacer());
        actions.setAlignment(Pos.CENTER_LEFT);
        if (session.canSubmit(version)) {
            Button submit = Buttons.primary(ExamListCopy.SUBMIT);
            submit.setDisable(session.isActing());
            submit.setOnAction(e -> confirmSubmit(exam, version));
            actions.getChildren().add(submit);
        }
        if (session.canRevise(version)) {
            Button revise = Buttons.secondary(ExamListCopy.REVISE);
            revise.setDisable(session.isActing());
            revise.setOnAction(e -> confirmRevise(exam, version));
            actions.getChildren().add(revise);
        }
        if (actions.getChildren().size() > 1) {
            card.getChildren().add(actions);
        }
        return card;
    }

    /** Success as a toast, refusal as a toast; both dismissed so they do not repeat. */
    private void renderNotices() {
        session.actionNotice().ifPresent(notice -> {
            toasts().success(notice);
            session.dismissNotice();
        });
        session.actionError().ifPresent(sentence -> {
            toasts().error(ExamListCopy.TITLE, sentence);
            session.dismissActionError();
        });
    }

    // ===================== The two confirmations (E7.15) =================

    private void confirmSubmit(ExamListRow exam, ExamVersionRow version) {
        if (showingDialog) {
            return;
        }
        showingDialog = true;
        try {
            boolean confirmed = WarnConfirm.show(window(),
                    WarnConfirm.spec(ExamListCopy.SUBMIT_TITLE)
                            .explanation(ExamListCopy.SUBMIT_EXPLANATION)
                            .detail(new Label(ExamListCopy.submitSummary(exam, version)))
                            .confirmText(ExamListCopy.SUBMIT)
                            .cancelText(ExamListCopy.CANCEL)
                            .warn());
            if (confirmed) {
                session.submit(exam, version);
            }
        } finally {
            showingDialog = false;
        }
    }

    private void confirmRevise(ExamListRow exam, ExamVersionRow version) {
        if (showingDialog) {
            return;
        }
        showingDialog = true;
        try {
            boolean confirmed = WarnConfirm.show(window(),
                    WarnConfirm.spec(ExamListCopy.REVISE_TITLE)
                            .explanation(ExamListCopy.REVISE_EXPLANATION)
                            .detail(new Label(ExamListCopy.reviseSummary(exam, version)))
                            .confirmText(ExamListCopy.REVISE)
                            .cancelText(ExamListCopy.CANCEL)
                            .info());
            if (confirmed) {
                session.revise(exam, version);
            }
        } finally {
            showingDialog = false;
        }
    }

    // ===================== Layout ========================================

    private void buildColumns() {
        TableColumn<ExamListRow, String> exam = new TableColumn<>(ExamListCopy.COLUMN_EXAM);
        exam.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(cell.getValue().name()));
        exam.setPrefWidth(260);

        table.column(exam)
                .column(ExamListCopy.COLUMN_ID, ExamListRow::displayId6)
                .column(ExamListCopy.COLUMN_COURSE, ExamListCopy::courseLabel)
                .column(ExamListCopy.COLUMN_VERSIONS, row -> String.valueOf(row.versionCount()))
                .column(statusColumn())
                // Exam and Status carry their own widths; the rest are sized to their content
                // so a course name is never clipped to "12 · Calcu…" (F-9).
                .columnWidths(260, 90, 170, 100, 180)
                .numericColumns(1, 3);
    }

    /**
     * The chip column, which describes the LATEST version.
     *
     * <p>Which is the same fact {@code ExamListRow.name} comes from, so the row reads as one
     * statement about where the exam is now. Older versions have their own chips on their own
     * cards, which is where a history belongs.
     */
    private static TableColumn<ExamListRow, ExamListRow> statusColumn() {
        TableColumn<ExamListRow, ExamListRow> column = new TableColumn<>(ExamListCopy.COLUMN_LATEST);
        column.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleObjectProperty<>(cell.getValue()));
        column.setPrefWidth(180);
        column.setCellFactory(unused -> new TableCell<>() {
            @Override
            protected void updateItem(ExamListRow row, boolean empty) {
                super.updateItem(row, empty);
                ExamVersionRow latest = row == null ? null : row.latestVersion();
                setGraphic(empty || latest == null
                        ? null
                        : StatusChip.examStatus(latest.state().name()));
            }
        });
        return column;
    }

    private VBox buildHeader() {
        Label title = new Label(ExamListCopy.TITLE);
        title.getStyleClass().add("h1");

        Label subtitle = new Label(ExamListCopy.SUBTITLE);
        subtitle.getStyleClass().addAll("small", "muted");

        error.getStyleClass().addAll("small", "danger-text");
        error.setWrapText(true);
        show(error, false);

        VBox header = new VBox(10, new VBox(6, title, subtitle), error);
        header.setPadding(new Insets(24, 28, 12, 28));
        return header;
    }

    private Node buildBody() {
        versionsHeading.getStyleClass().add("h3");
        noSelection.getStyleClass().addAll("small", "muted");
        noSelection.setWrapText(true);

        versionsPanel.setPadding(new Insets(0, 0, 12, 0));
        versionsPanel.getChildren().addAll(versionsHeading, noSelection);

        ScrollPane versions = new ScrollPane(versionsPanel);
        versions.setFitToWidth(true);
        versions.getStyleClass().add("edge-to-edge");
        versions.setPrefWidth(380);
        versions.setMinWidth(320);

        HBox body = new HBox(20, table, versions);
        body.setPadding(new Insets(0, 28, 24, 28));
        HBox.setHgrow(table, Priority.ALWAYS);
        return body;
    }

    private javafx.stage.Window window() {
        return view().getScene() == null ? null : view().getScene().getWindow();
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
