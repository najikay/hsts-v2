package client.features.approval;

import client.core.NavParams;
import client.core.Navigator;
import client.core.Routes;
import client.core.ScreenManager;
import client.features.exam.QuestionCardView;
import client.features.exambuild.ExamBuildRoutes;
import client.ui.components.Buttons;
import client.ui.components.StatusChip;
import client.ui.components.WarnConfirm;
import client.ui.screen.AbstractScreen;
import common.dto.approval.ApprovalRow;
import common.dto.approval.ExamPreview;
import common.dto.approval.TeacherOnlyBlock;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Optional;

/**
 * One exam version, read-only, as a student would see it (Presentation tier, E8.4 ⚑ — F4.1).
 *
 * <h2>This screen is the v1 fix, and the way it is built is the argument</h2>
 *
 * <p>v1's coordinator approved exams she could not see. The repair is not that this screen
 * exists; it is <b>what it draws with</b>. The left pane is a column of
 * {@link QuestionCardView} — the same component {@code ExamFormView} draws a live paper with —
 * over {@code ExamQuestion}, the same wire type a student receives, from the same
 * no-correctness projection a live attempt is served from. There is no second renderer to
 * drift from the first, because there is no second renderer.
 *
 * <p>The cards are {@linkplain QuestionCardView#readOnly() read-only}: the options are shown
 * and legible and nothing responds, because "read-only" has to look like the same exam rather
 * than like a different one.
 *
 * <h2>And the half no student ever sees</h2>
 *
 * <p>The right pane holds the staff-only block: the teacher's notes, the author, and the
 * answer key. It is a separate pane rather than annotations on the cards, and that is the
 * point of the layout: the moment the key is drawn <em>onto</em> the paper, the left-hand
 * pane stops being an honest picture of the student's screen. The one exception is the
 * {@code answer-key} class the preview puts on the correct option, which is a marking of the
 * student's own card and is what a coordinator checking an exam actually needs.
 *
 * <p>Two decisions live at the bottom. Approve is a {@code WarnConfirm}, because approving is
 * what lets an exam be released and there is no undo; Reject opens {@link RejectDialog},
 * because F4.2 requires a reason and a reason needs a field.
 */
public final class ExamPreviewView extends AbstractScreen {

    /**
     * The nav key naming the screen this preview was opened from (2026-08-30, U-53).
     *
     * <p>House convention, the same shape as {@code ExamBuilderView.PARAM_COURSE}: the receiving
     * view spells the key and every navigator imports it, so a rename cannot leave a producer and
     * a consumer disagreeing silently. Absent means the approvals queue, which is where this
     * screen was reached from for its whole life before the exam builder gained a Preview.
     *
     * <p>A route id rather than a boolean, because "which door" already has more than two
     * answers and a flag would have to be renamed the first time a third one is added.
     */
    public static final String PARAM_FROM = "from";

    private final BorderPane root = new BorderPane();
    private final Label examName = new Label();
    private final Label meta = new Label();
    private final Label banner = new Label(ApprovalCopy.PREVIEW_BANNER);
    private final Label error = new Label();
    private final VBox paper = new VBox(16);
    private final VBox teacherPanel = new VBox(12);
    private final HBox statusRow = new HBox(8);
    private final Button approve = Buttons.primary(ApprovalCopy.APPROVE_CONFIRM);
    private final Button reject = Buttons.danger(ApprovalCopy.REJECT_CONFIRM);
    private final Button back = Buttons.secondary(ApprovalCopy.BACK_TO_APPROVALS);

    private ExamPreviewSession session;

    /**
     * The route this preview was opened from, or {@code ""} for the approvals queue.
     *
     * <p>Read in {@code onShow} and not in {@code build}: the screen is built once and shown on
     * every navigation, so a value captured at build time would be the first visit's answer
     * forever.
     */
    private String openedFrom = "";

    @Override
    protected Parent build() {
        session = new ExamPreviewSession(dispatcher(), onFxThread())
                .onChange(this::render)
                .onDecided(decision -> {
                    if (toasts() != null) {
                        toasts().success("Decision saved", decision.confirmation());
                    }
                    navigator().navigate(Routes.APPROVALS.id());
                });

        approve.setOnAction(e -> confirmApprove());
        reject.setOnAction(e -> confirmReject());
        back.setOnAction(e -> goBack());

        root.getStyleClass().add("exam-preview");
        root.setTop(buildHeader());
        root.setCenter(buildBody());
        root.setBottom(buildFooter());
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        openedFrom = params.getString(PARAM_FROM, "");
        // The label follows the door, and is set here rather than in render() because it is a
        // fact about the navigation rather than about the answer: it must read correctly while
        // the preview is still loading and on the screen that says it could not be opened.
        back.setText(ExamBuildRoutes.BUILDER.equals(openedFrom)
                ? ApprovalCopy.BACK_TO_BUILDER : ApprovalCopy.BACK_TO_APPROVALS);
        long examVersionId = params.getLong("examVersionId", 0);
        session.open(examVersionId);
    }

    /**
     * Back to whichever screen opened this one (2026-08-30, Findings.txt, U-53) ⚑.
     *
     * <p>The footer's control names a destination rather than a direction, which is what its
     * header javadoc says it is for. That was one destination while one screen opened this one.
     * Since U-53 the exam builder's Preview opens it too, and "Back to approvals" on a teacher's
     * screen is worse than wrong: {@code Routes.APPROVALS} is registered for the coordinator
     * alone, so pressing it would throw out of {@code Navigator.navigate} rather than take her
     * anywhere.
     *
     * <p>{@code navigate} rather than {@code back()}: the builder needs its {@code
     * examVersionId} back, exactly as {@code ExamBuilderView.backToTheList} argues, and the
     * entry she arrived from cannot be relied on to carry it. The shell's own navbar Back is
     * the history-first control and stays that.
     *
     * <p>Falls through to the approvals queue for anything unregistered, which is the harness
     * and the component gallery. The button is never a dead end that throws.
     */
    private void goBack() {
        Navigator navigator = navigator();
        if (navigator == null) {
            return;
        }
        if (ExamBuildRoutes.BUILDER.equals(openedFrom)
                && navigator.isRegistered(ExamBuildRoutes.BUILDER)) {
            navigator.navigate(ExamBuildRoutes.BUILDER,
                    NavParams.of("examVersionId", session.preview()
                            .map(loaded -> loaded.summary().examVersionId()).orElse(0L)));
            return;
        }
        if (navigator.isRegistered(Routes.APPROVALS.id())) {
            navigator.navigate(Routes.APPROVALS.id());
            return;
        }
        navigator.back();
    }

    /**
     * Whether the signed-in user may decide on this version at all (U-53) ⚑.
     *
     * <p><b>The role, and only the role.</b> {@code EXAM_APPROVE} and {@code EXAM_REJECT} both
     * open with {@code Authorization.requireRole(caller, Role.COORDINATOR)}, so this is that
     * server line read back rather than a second rule: a teacher who reached either verb is
     * refused, and a screen that offered her the buttons would be asking a question it knows the
     * answer to. The same argument {@code ExamBuilderSession.save} makes about {@code READ_ONLY}.
     *
     * <p>It became a question this screen has to ask on 2026-08-30, when U-53 gave the author a
     * Preview of her own. Until then only a coordinator could reach the route, so the buttons
     * were unconditional and correct; the third reader U-44 admitted, the principal, was given
     * {@code DataExamView} instead precisely so that this screen did not have to grow a mode.
     * The author is the first reader to arrive on this screen who may not decide, and hiding is
     * right rather than disabling: a disabled Approve reads as "not yet", and there is no yet.
     *
     * @return {@code true} for a coordinator, and for a shell with no signed-in user, which is
     *         the harness and the gallery
     */
    private boolean mayDecide() {
        LoginResult user = ScreenManager.getInstance().signedInUser();
        return user == null || user.role() == Role.COORDINATOR;
    }

    @Override
    public boolean listensToEvents() {
        return false;
    }

    // ===================== Rendering =====================================

    private void render() {
        String message = session.decisionError().or(session::error).orElse("");
        error.setText(message);
        show(error, !message.isBlank());

        Optional<ExamPreview> loaded = session.preview();
        if (loaded.isEmpty()) {
            paper.getChildren().clear();
            teacherPanel.getChildren().clear();
            statusRow.getChildren().clear();
            boolean mayDecide = mayDecide();
            show(approve, mayDecide);
            show(reject, mayDecide);
            approve.setDisable(true);
            reject.setDisable(true);
            return;
        }
        ExamPreview preview = loaded.get();
        ApprovalRow summary = preview.summary();

        examName.setText(summary.examName());
        meta.setText(summary.examDisplayId() + " · v" + summary.versionNo()
                + " · " + summary.courseLabel()
                + " · " + summary.authorName()
                + " · " + ApprovalCopy.minutes(summary.durationMinutes())
                + " · " + ApprovalCopy.questions(preview.questionCount())
                + " · " + preview.totalPoints() + " points");

        renderStatus(summary);
        renderPaper(preview);
        renderTeacherPanel(preview.teacherOnly());

        boolean mayDecide = mayDecide();
        show(approve, mayDecide);
        show(reject, mayDecide);
        approve.setDisable(!mayDecide || !session.canDecide());
        reject.setDisable(!mayDecide || !session.canDecide());
    }

    private void renderStatus(ApprovalRow summary) {
        statusRow.getChildren().setAll(StatusChip.examStatus(summary.state().name()).large());
        if (summary.selfAuthored()) {
            // F4.3 is a permission, not a warning: a neutral chip, in the same row as the
            // state, saying what the log will say.
            Label badge = new Label(ApprovalCopy.SELF_AUTHORED_BADGE);
            badge.getStyleClass().addAll("hsts-chip", "neutral", "self-authored");
            statusRow.getChildren().add(badge);
        }
    }

    /**
     * Draws the paper with the student's own card component (F4.1 ⚑).
     *
     * <p>Both halves moved to {@link ExamPaperPane} on 2026-08-30 (live session, U-44), when the
     * principal's Data browser gained an exam detail of its own. The reasoning is E8's own: this
     * screen's argument is that there is no second renderer, so a copy of it made for the second
     * reader would be the drift the argument rules out. What this screen still owns is the
     * footer under it, which is the entire difference between the two.
     */
    private void renderPaper(ExamPreview preview) {
        ExamPaperPane.renderPaper(paper, preview, session::correctOptionFor);
    }

    /** Everything a student never sees, in its own pane so it can never look like the paper. */
    private void renderTeacherPanel(TeacherOnlyBlock teacherOnly) {
        ExamPaperPane.renderTeacherOnly(teacherPanel, teacherOnly);
    }

    // ===================== The two decisions =============================

    /**
     * Approving is confirmed, because it is what lets the exam be released and there is no
     * undo. The dialog says exactly that, and adds the F4.3 line when it applies.
     */
    private void confirmApprove() {
        ExamPreview preview = session.preview().orElse(null);
        if (preview == null) {
            return;
        }
        String explanation = "The teacher is told, and this version can be released to students. "
                + "This cannot be undone.";
        if (session.isSelfAuthored()) {
            explanation = explanation + " " + ApprovalCopy.SELF_APPROVAL_NOTE;
        }
        boolean confirmed = WarnConfirm.show(window(),
                WarnConfirm.spec(ApprovalCopy.APPROVE_TITLE)
                        .explanation(preview.summary().examLabel() + ". " + explanation)
                        .confirmText(ApprovalCopy.APPROVE_CONFIRM)
                        .cancelText(ApprovalCopy.KEEP_LOOKING)
                        .warn());
        if (confirmed) {
            session.approve();
        }
    }

    private void confirmReject() {
        ExamPreview preview = session.preview().orElse(null);
        if (preview == null) {
            return;
        }
        RejectDialog.show(window(), preview.summary().examLabel())
                .ifPresent(reason -> session.reject(reason));
    }

    private javafx.stage.Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    // ===================== Layout ========================================

    private VBox buildHeader() {
        examName.getStyleClass().add("h1");
        meta.getStyleClass().addAll("small", "muted");
        meta.setWrapText(true);

        banner.getStyleClass().addAll("small", "faint");
        banner.setWrapText(true);

        error.getStyleClass().addAll("small", "danger-text");
        error.setWrapText(true);
        show(error, false);

        HBox top = new HBox(16, new VBox(2, examName, meta), Buttons.spacer(), statusRow);
        top.setAlignment(Pos.CENTER_LEFT);

        // The header's own back link is gone: the shell's navbar carries one on every
        // route the rail cannot reach, and this is one of them. The footer's named
        // "Back to approvals" stays, because it names a destination rather than a
        // direction and it is where a coordinator finishes reading.
        VBox header = new VBox(10, top, banner, error);
        header.setPadding(new Insets(24, 28, 12, 28));
        return header;
    }

    /**
     * The paper on the left, the staff-only block on the right.
     *
     * <p>Two scrollers rather than one, because they are two documents of different lengths:
     * a twenty-question paper and a twenty-line key, and scrolling the key off the screen to
     * read question 18 is exactly what a coordinator is here to avoid.
     */
    private HBox buildBody() {
        paper.setPadding(new Insets(4, 24, 24, 28));
        paper.getStyleClass().add("preview-paper");
        ScrollPane paperScroller = new ScrollPane(paper);
        paperScroller.setFitToWidth(true);
        paperScroller.getStyleClass().add("hsts-page");
        paperScroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        HBox.setHgrow(paperScroller, Priority.ALWAYS);

        teacherPanel.setPadding(new Insets(16));
        teacherPanel.getStyleClass().addAll("hsts-card", "teacher-only-panel");
        ScrollPane panelScroller = new ScrollPane(teacherPanel);
        panelScroller.setFitToWidth(true);
        panelScroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        panelScroller.setPrefWidth(320);
        panelScroller.setMinWidth(260);

        HBox body = new HBox(16, paperScroller, panelScroller);
        body.setPadding(new Insets(0, 28, 0, 0));
        return body;
    }

    private HBox buildFooter() {
        HBox footer = new HBox(10, back, Buttons.spacer(), reject, approve);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(12, 28, 20, 28));
        footer.getStyleClass().add("preview-footer");
        return footer;
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
