package client.features.approval;

import client.features.exam.QuestionCardView;
import common.dto.approval.ExamPreview;
import common.dto.approval.TeacherOnlyBlock;
import common.dto.exam.ExamQuestion;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * One exam version drawn as a student would receive it, plus the half no student sees
 * (Presentation tier, E8.4 ⚑ / E15.2 — F4.1, F9.3).
 *
 * <p>Lifted out of {@code ExamPreviewView} on 2026-08-30 (live session, U-44), when the
 * principal's Data browser gained an exam detail. Two screens now render one preview: the
 * coordinator's, which puts Approve and Send back under it, and {@code DataExamView}, which puts
 * nothing under it. The renderer is shared because E8's whole argument is that there is no
 * second renderer — the paper is a column of {@link QuestionCardView} over {@code ExamQuestion},
 * the student's own component over the student's own wire type — and a copy of it made for the
 * principal would be exactly the drift that argument rules out.
 *
 * <h2>It draws no decision, and cannot ⚑</h2>
 *
 * <p>Nothing here is a {@code Button} and nothing here sends anything. The cards are
 * {@linkplain QuestionCardView#readOnly() read-only}: the options are shown and legible and none
 * of them responds, because "read-only" has to look like the same exam rather than like a
 * different one. What each screen puts <em>after</em> this is the whole difference between them,
 * which is what keeps the principal's exam screen free of a mutating control by construction
 * (S-7) rather than by a flag that could be passed the wrong way round.
 *
 * <h2>The key is marked on the card and listed in its own pane</h2>
 *
 * <p>{@link #renderPaper} marks the correct option with a style class on a card that has no idea
 * what it means: the key arrives as an argument, off the preview's teacher-only block, never off
 * the question, because {@code ExamQuestion} has nowhere to hold one. {@link #renderTeacherOnly}
 * draws the same key as a list in a separate pane, and the separation is the point of the
 * layout: the moment the key is drawn only <em>onto</em> the paper, the paper stops being an
 * honest picture of the student's screen.
 */
public final class ExamPaperPane {

    private ExamPaperPane() {
        // static renderer - no instances
    }

    /**
     * Fills a column with the instructions and one card per question.
     *
     * @param paper     the column to fill; cleared first
     * @param preview   the loaded version
     * @param correctOf which option is right for a given question, 1..4, or {@code 0} when the
     *                  preview carries no key for it
     */
    public static void renderPaper(VBox paper, ExamPreview preview,
                                   ToIntFunction<ExamQuestion> correctOf) {
        Objects.requireNonNull(paper, "paper");
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(correctOf, "correctOf");

        paper.getChildren().clear();
        if (preview.hasStudentText()) {
            paper.getChildren().add(instructions(preview));
        }
        if (preview.questions().isEmpty()) {
            Label empty = new Label(ApprovalCopy.NO_QUESTIONS);
            empty.getStyleClass().addAll("body", "muted");
            empty.setWrapText(true);
            paper.getChildren().add(empty);
            return;
        }
        for (ExamQuestion question : preview.questions()) {
            QuestionCardView card =
                    new QuestionCardView(question, preview.questionCount()).readOnly();
            card.markCorrect(correctOf.applyAsInt(question));
            paper.getChildren().add(card);
        }
    }

    private static VBox instructions(ExamPreview preview) {
        Label title = new Label("Instructions");
        title.getStyleClass().add("h3");
        Label text = new Label(preview.studentText());
        text.getStyleClass().add("body");
        text.setWrapText(true);
        VBox block = new VBox(6, title, text);
        block.getStyleClass().addAll("hsts-card", "exam-instructions");
        return block;
    }

    /**
     * Fills a pane with everything a student never sees: the notes, the author, the key.
     *
     * @param panel       the pane to fill; its children are replaced
     * @param teacherOnly the fenced staff-only block off the preview
     */
    public static void renderTeacherOnly(VBox panel, TeacherOnlyBlock teacherOnly) {
        Objects.requireNonNull(panel, "panel");
        Objects.requireNonNull(teacherOnly, "teacherOnly");

        Label title = new Label(ApprovalCopy.TEACHER_PANEL_TITLE);
        title.getStyleClass().add("h3");

        Label author = new Label("Written by " + teacherOnly.authorName());
        author.getStyleClass().addAll("small", "muted");

        Label notes = new Label(teacherOnly.hasTeacherText()
                ? teacherOnly.teacherText()
                : ApprovalCopy.NO_TEACHER_NOTES);
        notes.getStyleClass().addAll("body", teacherOnly.hasTeacherText() ? "strong" : "muted");
        notes.setWrapText(true);

        Label keyTitle = new Label(ApprovalCopy.ANSWER_KEY_TITLE);
        keyTitle.getStyleClass().add("h3");

        VBox key = new VBox(4);
        key.getStyleClass().add("answer-key-list");
        teacherOnly.answerKey().forEach(row -> {
            Label line = new Label(row.label());
            line.getStyleClass().addAll("small", "mono", "answer-key-row");
            key.getChildren().add(line);
        });

        panel.getChildren().setAll(title, author, notes, keyTitle, key);
    }
}
