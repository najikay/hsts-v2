package client.features.grading;

import client.core.NavParams;
import client.features.results.MarkedPaper;
import client.ui.components.Buttons;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.screen.AbstractScreen;
import common.dto.grading.AnswerReviewRow;
import common.dto.grading.StudentGradeRow;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * One student's marked paper, opened by the teacher who has to sign it off (Presentation tier,
 * E12.6 — F8.2, T-8).
 *
 * <p>Built 2026-08-30 (live session, U-38). Until it existed, F8.2 was a documented PARTIAL:
 * the grading queue let a teacher approve a paper and change its score, and gave her no way to
 * <em>read</em> it first. The assembler had been on the server since E12.6 and the verb with
 * it; what was missing was the screen. Reached from the queue's per-row Review action, which
 * carries the grade id, and not from the rail, for the same reason the exam preview and the
 * checked form are not on one: it is a view of one paper, and a rail item that needed a grade
 * chosen first would be a dead end.
 *
 * <p>A renderer over {@link GradeReviewSession}, thin by the usual rule and on the coverage
 * exclusion list by name: every load and write decision is in the session and every sentence is
 * in {@link GradingCopy}, both measured.
 *
 * <h2>The same paper the student will see, drawn by the same code</h2>
 *
 * <p>The question cards come from {@link MarkedPaper}, which is what {@code CheckedFormView}
 * renders her copy with. That is the whole reason it was lifted out: a teacher approving a
 * paper should be looking at the thing she is about to publish, not at a second rendering of it
 * that could disagree. One label differs — the chosen option is tagged
 * {@link GradingCopy#STUDENT_ANSWER} rather than "Your answer" — because the reader has
 * changed and nothing else about the paper has.
 *
 * <h2>Both actions, on the paper rather than beside it</h2>
 *
 * <p>Approve and Change score are the two the queue already offers, and they are here as well
 * rather than instead: the queue is where a class is signed off in one gesture, this is where
 * one paper is decided on its merits. They act on the grade on screen, so neither needs a
 * selection, and both disappear behind {@link GradingCopy#REVIEW_APPROVED} once the grade is
 * published — the contract answers {@code CONFLICT} to an override after that, and a button
 * that exists only to be refused is worse than no button.
 *
 * <h2>The exam name is carried in, not read off the wire ⚑</h2>
 *
 * <p>{@code GradeReviewService.teacherRow} leaves {@code examName} and {@code courseCode} null
 * on purpose: a teacher reading one sitting already has them above the queue table, and the
 * v1.1 record populates them on the student paths only. Rather than amend a frozen wire for a
 * heading, the queue passes the label it is already showing as {@link #PARAM_EXAM}, and the
 * line is left out entirely when there is none. A label with nothing after it reads as data
 * that failed to load, which is the one thing this screen must not look like.
 */
public final class GradeReviewView extends AbstractScreen {

    /**
     * Which grade to open.
     *
     * <p>Spelled the way {@code Routes.CHECKED_FORM}'s caller spells it, because it is the same
     * id naming the same row on the same table, and two spellings of one parameter is how a
     * navigation silently opens nothing.
     */
    public static final String PARAM_GRADE = "gradeId";

    /** The exam label to head the paper with; see the class note. Optional. */
    public static final String PARAM_EXAM = "exam";

    private final VBox root = new VBox(14);
    private final Label heading = new Label(GradingCopy.REVIEW_TITLE);
    private final Label studentName = new Label();
    private final Label examLine = new Label();
    private final Label scoreLine = new Label();
    private final Label error = new Label();
    private final Label approvedNotice = new Label(GradingCopy.REVIEW_APPROVED);

    private final Label reasonText = new Label();
    private final VBox reasonBox = new VBox(4);
    private final Label noteText = new Label();
    private final VBox noteBox = new VBox(4);

    private final Button approve = new Button(GradingCopy.APPROVE_ONE);
    private final Button override = new Button(GradingCopy.OVERRIDE);
    private final HBox actions = new HBox(8);

    private final VBox questions = new VBox(12);
    private final EmptyState empty = new EmptyState(Icons.GRADING,
            GradingCopy.REVIEW_EMPTY_TITLE, GradingCopy.REVIEW_EMPTY_HINT);

    private GradeReviewSession session;

    /** The exam label the queue handed in, or null on a deep link; see the class note. */
    private String examLabel;

    @Override
    protected Parent build() {
        session = new GradeReviewSession(dispatcher(), onFxThread()).onChange(this::render);

        heading.getStyleClass().add("h1");
        studentName.getStyleClass().add("h3");
        examLine.getStyleClass().addAll("small", "muted");
        scoreLine.getStyleClass().addAll("small", "muted");
        error.getStyleClass().addAll("small", "danger-text");
        error.setWrapText(true);
        approvedNotice.getStyleClass().addAll("small", "muted");
        approvedNotice.setWrapText(true);

        buildNoteBoxes();

        approve.getStyleClass().add("primary");
        approve.setOnAction(event -> session.approve());
        override.setOnAction(event -> openOverrideDialog());
        actions.getChildren().addAll(override, approve, Buttons.spacer());
        actions.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroller = new ScrollPane(questions);
        scroller.setFitToWidth(true);
        scroller.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroller, Priority.ALWAYS);

        root.getStyleClass().addAll("hsts-page", GradingCopy.REVIEW_STYLE_CLASS);
        root.setPadding(new Insets(24, 28, 24, 28));
        root.getChildren().addAll(heading, new VBox(2, studentName, examLine, scoreLine),
                error, reasonBox, noteBox, approvedNotice, actions, empty, scroller);
        return root;
    }

    private void buildNoteBoxes() {
        Label reasonHeading = new Label(GradingCopy.REVIEW_REASON_HEADING);
        reasonHeading.getStyleClass().addAll("small", "muted");
        reasonText.setWrapText(true);
        reasonBox.getStyleClass().add("checked-form-note");
        reasonBox.getChildren().addAll(reasonHeading, reasonText);

        Label noteHeading = new Label(GradingCopy.REVIEW_NOTE_HEADING);
        noteHeading.getStyleClass().addAll("small", "muted");
        noteText.setWrapText(true);
        noteBox.getStyleClass().add("checked-form-note");
        noteBox.getChildren().addAll(noteHeading, noteText);
    }

    @Override
    public void onShow(NavParams params) {
        examLabel = params.getString(PARAM_EXAM, null);
        long gradeId = params.getLong(PARAM_GRADE, 0);
        if (gradeId > 0) {
            session.open(gradeId);
        }
    }

    @Override
    public boolean listensToEvents() {
        // A closed sitting does not change under her, and nothing is pushed to a teacher about
        // her own approvals. The same answer GradingQueueView gives, for the same reason.
        return false;
    }

    // ===================== Rendering =====================================

    private void render() {
        String message = session.error().orElse("");
        error.setText(message);
        show(error, !message.isEmpty());

        session.grade().ifPresentOrElse(this::renderGrade, this::renderNothing);
    }

    private void renderNothing() {
        show(studentName, false);
        show(examLine, false);
        show(scoreLine, false);
        show(reasonBox, false);
        show(noteBox, false);
        show(actions, false);
        show(approvedNotice, false);
        show(empty, false);
        questions.getChildren().clear();
    }

    private void renderGrade(StudentGradeRow grade) {
        studentName.setText(grade.studentName());
        show(studentName, true);

        examLine.setText(examLabel == null ? "" : examLabel);
        show(examLine, examLabel != null && !examLabel.isBlank());

        scoreLine.setText(GradingCopy.scoreLine(grade));
        show(scoreLine, true);

        String reason = GradingCopy.overrideReason(grade);
        reasonText.setText(reason == null ? "" : reason);
        show(reasonBox, reason != null);

        String note = GradingCopy.teacherNote(grade);
        noteText.setText(note == null ? "" : note);
        show(noteBox, note != null);

        // Either the two actions or the sentence that explains their absence, never both and
        // never neither: an approved paper with a silent gap where the buttons were is the
        // version of this screen a teacher reads as broken.
        boolean stillOpen = GradingCopy.canOverride(grade);
        show(actions, stillOpen);
        show(approvedNotice, !stillOpen);
        approve.setDisable(!session.canAct());
        override.setDisable(!session.canAct());

        renderAnswers();
    }

    private void renderAnswers() {
        questions.getChildren().clear();
        for (AnswerReviewRow row : session.answers()) {
            questions.getChildren().add(MarkedPaper.card(row, GradingCopy.STUDENT_ANSWER));
        }
        show(empty, session.answers().isEmpty());
    }

    // ===================== Actions =======================================

    /**
     * The override, on the paper she is reading.
     *
     * <p>The dialog is {@link OverrideDialog}, the queue's own, rather than a second copy of it:
     * it carries S-23's required justification and S-22's optional comment, and two
     * implementations of that pair is two places for the requirement to drift.
     */
    private void openOverrideDialog() {
        session.grade().ifPresent(grade -> OverrideDialog.show(grade).ifPresent(outcome ->
                session.override(outcome.score(), outcome.justification(),
                        outcome.teacherComment())));
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
