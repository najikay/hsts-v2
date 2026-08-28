package client.features.exambuild;

import client.core.NavParams;
import client.core.ScreenManager;
import client.ui.components.Buttons;
import client.ui.components.DataTable;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.components.StatusChip;
import client.ui.components.WarnConfirm;
import client.ui.screen.AbstractScreen;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.authoring.ExamListRow;
import common.dto.authoring.ExamVersionRow;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

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
        // Master-detail: the click drives the versions panel, it does not navigate,
        // so it must not light the "Open" hint (M-6).
        table.selectOnClick(row -> session.select(row.examId()));
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
        // Every version opens, and which of two things that means is the BUILDER's to decide
        // from the state the server answers with: a draft opens for editing, anything else opens
        // read-only. This screen deliberately does not pass that decision along - it has an
        // ExamVersionRow that a push could have made stale, and the builder has the fresh answer.
        //
        // The read half is contract §8's read path (ruled 2026-08-25). The retirement of
        // MyApprovalsView took away the only way to open a version and read the paper, and this
        // is where it comes back.
        Button open = Buttons.outline(version.isEditable()
                ? ExamListCopy.EDIT
                : ExamListCopy.VIEW);
        open.setOnAction(e -> openInBuilder(version));
        actions.getChildren().add(open);
        if (session.canSubmit(version)) {
            Button submit = Buttons.primary(ExamListCopy.SUBMIT);
            submit.setDisable(session.isActing());
            submit.setOnAction(e -> confirmSubmit(exam, version));
            actions.getChildren().add(submit);
        }
        if (session.canRevise(exam, version)) {
            Button revise = Buttons.secondary(ExamListCopy.REVISE);
            revise.setDisable(session.isActing());
            revise.setOnAction(e -> confirmRevise(exam, version));
            actions.getChildren().add(revise);
        }
        card.getChildren().add(actions);
        return card;
    }

    /**
     * Opens one version in the builder (E7.11, and §8's read path).
     *
     * <p>Carries only the version id. The builder asks {@code EXAM_VERSION_GET} for it and reads
     * the mode off the answer, so a row that went stale between the render and the click cannot
     * open an approved exam in an editable form.
     */
    private void openInBuilder(ExamVersionRow version) {
        navigator().navigate(ExamBuildRoutes.BUILDER,
                NavParams.of("examVersionId", version.examVersionId()));
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

        HBox titleRow = new HBox(16, new VBox(6, title, subtitle),
                Buttons.spacer(), buildNewExamBox());
        titleRow.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(10, titleRow, error);
        header.setPadding(new Insets(24, 28, 12, 28));
        return header;
    }

    /**
     * The one control that opens the builder on nothing (M-3).
     *
     * <p>A {@link MenuButton} rather than a button plus a dialog, because the course has to be
     * chosen before the builder opens and there is no third thing to ask. {@code openNew} takes
     * the code as given: the bank picker is scoped to it and {@code ExamCreateRequest} carries
     * it, so a builder entered without one can pick no questions and save nothing. One press,
     * one course, one navigation.
     *
     * <h2>The courses it offers, and the guarantee it deliberately does not claim ⚑</h2>
     *
     * <p>They come from the sign-in payload, which is where {@code BankView} reads them and needs
     * no verb. <b>That set is teaching UNION enrolment</b>, not the taught set:
     * {@code CourseRepository.findForUser} merges a {@code CourseTeacher} query with an
     * {@code Enrollment} one, and {@code enrollments} carries no role constraint. So a teacher
     * enrolled in a colleague's course would be offered it here and refused by
     * {@code requireTeachesCourse} on save.
     *
     * <p><b>An earlier version of this paragraph claimed the menu "cannot offer a course the
     * server would then refuse". That was false when it was written</b>, and it is the same
     * sentence {@code BankSession.canWriteIn} already had to retract on the same field for the
     * same reason. It is unreachable under today's seed, which enrols only students, and that is
     * precisely what makes it worth writing down: a claim the seed happens to satisfy is P-7's
     * shape. <b>The client has no taught-only set to switch to</b>, nothing on the wire carries
     * one, so this stays the best available approximation and the server stays the decider. What
     * changed is that this comment no longer says otherwise.
     *
     * <p>Deliberately <b>not</b> {@code BankSession.courseOptions}'s further union with the
     * browsed bank: that widening exists for the bank's read scope, which is wider than
     * authorship on purpose, and it would add courses she demonstrably cannot write.
     *
     * <h2>Empty is disabled and explained on screen, never hidden ⚑</h2>
     *
     * <p>A hidden control is indistinguishable from the missing one this method exists to fix.
     * <b>The reason is a visible label beside the control and not only a tooltip</b>: JavaFX does
     * not deliver hover events to a disabled node, so a tooltip installed on one is a sentence
     * nobody can read. The tooltip stays as well, for the pointer that does find it, but nothing
     * depends on it. {@code rina.barak} is the account this is for, and she is a starred demo
     * login and scenario 4's approver, so a dead end here is a dead end on stage.
     */
    private Node buildNewExamBox() {
        MenuButton newExam = new MenuButton(ExamListCopy.NEW_EXAM);
        // "button" as well as "primary": the stylesheet's rules are all `.button.primary`, and a
        // MenuButton's default class is `menu-button`, so `primary` alone matches nothing and the
        // headline action renders as default chrome. Checked against hsts.css rather than
        // assumed. No graphic, because Buttons.primary carries none anywhere in this client.
        newExam.getStyleClass().addAll("button", Buttons.PRIMARY, "new-exam");

        List<CourseRef> courses = coursesOfSignedInUser();
        if (courses.isEmpty()) {
            newExam.setDisable(true);
            newExam.setTooltip(new Tooltip(ExamListCopy.NEW_EXAM_NO_COURSES));

            Label why = new Label(ExamListCopy.NEW_EXAM_NO_COURSES);
            why.getStyleClass().addAll("small", "muted");
            why.setWrapText(true);
            why.setMaxWidth(260);

            HBox box = new HBox(10, why, newExam);
            box.setAlignment(Pos.CENTER_RIGHT);
            return box;
        }

        MenuItem prompt = new MenuItem(ExamListCopy.NEW_EXAM_PROMPT);
        prompt.setDisable(true);
        newExam.getItems().add(prompt);
        for (CourseRef course : courses) {
            MenuItem item = new MenuItem(ExamListCopy.courseOption(course));
            item.setOnAction(e -> startNewExam(course.code()));
            newExam.getItems().add(item);
        }
        return newExam;
    }

    /**
     * Opens the builder with a course and no version, which is {@code Mode.CREATE}.
     *
     * <p>The mirror of {@link #openInBuilder}: that one carries {@code examVersionId} and never
     * a course, this one carries {@code courseCode} and never a version, and
     * {@code ExamBuilderView.onShow} reads exactly that difference to decide which of the two
     * it is doing.
     *
     * <p>The key is {@link ExamBuilderView#PARAM_COURSE} and not a literal, which is the house
     * convention every other navigator in this client already follows
     * ({@code QuestionEditorView}, the four bot screens). Two spellings of one key, checked
     * against each other nowhere, is a rename away from a builder opened on a null course.
     */
    private void startNewExam(String courseCode) {
        navigator().navigate(ExamBuildRoutes.BUILDER,
                NavParams.of(ExamBuilderView.PARAM_COURSE, courseCode));
    }

    /** @return the signed-in user's own courses, the way {@code BankView} reads them */
    private static List<CourseRef> coursesOfSignedInUser() {
        LoginResult user = ScreenManager.getInstance().signedInUser();
        return user == null ? List.of() : user.courses();
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
