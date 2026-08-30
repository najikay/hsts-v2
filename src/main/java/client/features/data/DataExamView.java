package client.features.data;

import client.core.NavParams;
import client.features.approval.ApprovalCopy;
import client.features.approval.ExamPaperPane;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.components.StatusChip;
import client.ui.screen.AbstractScreen;
import client.ui.components.Buttons;
import common.dto.approval.ApprovalRow;
import common.dto.approval.ExamPreview;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * One exam of the school's catalogue, exactly as its students saw it (Presentation tier, E15.2 —
 * F9.3, S-7, T-11.2, U-44, the lead's ruling of 2026-08-30).
 *
 * <p>What the Data browser's Exams rows open. It is the coordinator's preview <b>minus its two
 * decisions</b>: the same {@code EXAM_PREVIEW_GET}, the same paper built by
 * {@link ExamPaperPane} from {@code ExamQuestion} — the student's own wire type through the
 * student's own card component — and the same fenced teacher-only block beside it. E8's argument
 * is that there is no second renderer, and this screen is what that argument buys: the principal
 * reads the paper the students read, because it is drawn by the code that draws theirs.
 *
 * <h2>What is missing, and is missing by construction ⚑</h2>
 *
 * <p>There is no Approve, no Send back and no footer, and none of them is hidden or disabled:
 * this file never builds one, and the session behind it ({@link DataExamSession}) has no method
 * that could send one. {@code ExamPreviewView}'s footer is built in that file and stays there.
 * That is what makes S-7 structurally true here rather than a matter of a flag — T-11.3 asks a
 * reviewer to look for a mutating control anywhere in the principal's shell, and on this screen
 * there is nothing to find.
 *
 * <p>The way back is the shell's navbar Back, which {@code ShellBoot} aliases to the Data screen.
 * The coordinator's named "Back to approvals" button has no counterpart here for the same reason
 * the rest of the footer has none.
 *
 * <p>Thin by the usual rule and on the coverage exclusion list by name: every sentence is in
 * {@link DataDetailCopy} or {@link ApprovalCopy}, and every load decision is in the session.
 */
public final class DataExamView extends AbstractScreen {

    /** The nav parameter an Exams row carries: the exam's latest version id. */
    public static final String PARAM_EXAM_VERSION = "examVersionId";

    private final BorderPane root = new BorderPane();
    private final Label examName = new Label(DataDetailCopy.EXAM_TITLE);
    private final Label meta = new Label();
    private final Label banner = new Label(DataDetailCopy.EXAM_BANNER);
    private final Label readOnly = new Label(DataDetailCopy.READ_ONLY_NOTE);
    private final HBox statusRow = new HBox(8);

    private final VBox paper = new VBox(16);
    private final VBox teacherPanel = new VBox(12);
    private final EmptyState unavailable = new EmptyState(Icons.EXAMS,
            DataDetailCopy.EXAM_FAILED_TITLE, DataDetailCopy.EXAM_FAILED_HINT);

    private DataExamSession session;

    @Override
    protected Parent build() {
        session = new DataExamSession(dispatcher(), onFxThread()).onChange(this::render);

        root.getStyleClass().addAll("exam-preview", "principal-data-exam");
        root.setTop(buildHeader());
        root.setCenter(buildBody());
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        session.open(params.getLong(PARAM_EXAM_VERSION, 0));
    }

    @Override
    public boolean listensToEvents() {
        // An exam version is immutable and its approval state is not on this screen, so there
        // is nothing here a push could change.
        return false;
    }

    // ===================== Layout ========================================

    private VBox buildHeader() {
        examName.getStyleClass().add("h1");
        meta.getStyleClass().addAll("small", "muted");
        meta.setWrapText(true);
        banner.getStyleClass().addAll("small", "faint");
        banner.setWrapText(true);
        readOnly.getStyleClass().addAll("small", "muted", "data-read-only-note");
        readOnly.setWrapText(true);

        HBox top = new HBox(16, new VBox(2, examName, meta), Buttons.spacer(), statusRow);
        top.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(10, top, banner, readOnly, unavailable);
        header.setPadding(new Insets(24, 28, 12, 28));
        return header;
    }

    /** The paper on the left, the staff-only block on the right, as E8 lays it out. */
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
        body.setPadding(new Insets(0, 28, 20, 0));
        return body;
    }

    // ===================== Rendering =====================================

    private void render() {
        ExamPreview preview = session.preview().orElse(null);
        boolean failed = session.state().showsError();

        unavailable.set(DataDetailCopy.EXAM_FAILED_TITLE,
                session.error().orElse(DataDetailCopy.EXAM_FAILED_HINT));
        show(unavailable, failed);

        if (preview == null) {
            examName.setText(DataDetailCopy.EXAM_TITLE);
            meta.setText("");
            statusRow.getChildren().clear();
            paper.getChildren().clear();
            teacherPanel.getChildren().clear();
            return;
        }
        ApprovalRow summary = preview.summary();
        examName.setText(summary.examName());
        // The same line the coordinator reads, and deliberately so: an exam is quoted by its
        // display id and version number, and two screens naming it differently is how a
        // principal and a coordinator end up discussing two different documents.
        meta.setText(summary.examDisplayId() + " · v" + summary.versionNo()
                + " · " + summary.courseLabel()
                + " · " + summary.authorName()
                + " · " + ApprovalCopy.minutes(summary.durationMinutes())
                + " · " + ApprovalCopy.questions(preview.questionCount())
                + " · " + preview.totalPoints() + " points");

        // The state chip and nothing beside it: F4.3's self-authored badge is a fact about the
        // coordinator reading the screen, and this reader is not one.
        statusRow.getChildren().setAll(StatusChip.examStatus(summary.state().name()).large());

        ExamPaperPane.renderPaper(paper, preview, session::correctOptionFor);
        ExamPaperPane.renderTeacherOnly(teacherPanel, preview.teacherOnly());
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
