package client.features.grading;

import common.dto.grading.StudentGradeRow;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.Optional;

/**
 * The "change this score" dialog, once, for both screens that offer it (Presentation tier,
 * E12.3 — F8.2/F8.3, S-22/S-23).
 *
 * <p>Lifted out of {@code GradingQueueView} on 2026-08-30 (live session, U-38), when
 * {@code GradeReviewView} gained the same action. Not copied: this dialog is the whole of
 * S-23's "a manual change requires a justification" as a teacher meets it, and two copies of
 * it is two places for that requirement to be softened by accident. It owns nodes and decides
 * nothing, which is why it is on the coverage exclusion list beside the views.
 *
 * <p><b>Two boxes, not one, and they are separated on purpose.</b> They are written at the
 * same moment about the same paper, but they have different readers: the reason is the audit
 * trail and never leaves the staff room, the comment is the only free text the student ever
 * sees. Merging them would mean either a teacher writing for the record in front of a student,
 * or writing for the student in the audit log — and each label says which one this box is,
 * because a box's placement cannot.
 *
 * <p>The comment box opens <b>empty even when the grade already has a comment</b>, and the
 * label says that leaving it empty keeps what is saved. Pre-filling would be friendlier until
 * the first teacher cleared the box expecting the comment to go away, which on this wire it
 * does not (the contract's A3 null-preserves rule).
 *
 * <p><b>No second confirmation.</b> The dialog <em>is</em> the deliberation, and an "are you
 * sure" after someone has typed a justification is a click that teaches nothing. Bulk approve
 * asks; this does not.
 */
public final class OverrideDialog {

    /**
     * What the teacher filled in, exactly as she left it.
     *
     * <p>Unvalidated on purpose. {@link GradingQueueSession#override} and
     * {@link GradeReviewSession#override} run the blank-reason and range checks, and they run
     * them because the session is the measured half; a dialog that refused first would put the
     * rule in the one class no test opens.
     *
     * @param score          the score she typed, 0..100 by the spinner's own bounds
     * @param justification  the reason for the record, possibly blank
     * @param teacherComment the note for the student, possibly blank
     */
    public record Outcome(int score, String justification, String teacherComment) {
    }

    private OverrideDialog() {
        // static helper — no instances
    }

    /**
     * Opens the dialog and blocks until she answers it.
     *
     * @param row the grade she is changing; its student names the dialog and its effective
     *            score is what the spinner starts on
     * @return what she typed, or empty when she cancelled
     */
    public static Optional<Outcome> show(StudentGradeRow row) {
        Objects.requireNonNull(row, "row");

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

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(GradingCopy.OVERRIDE_TITLE);
        dialog.setHeaderText(row.studentName());
        dialog.getDialogPane().setContent(new VBox(8, score, reasonLabel, reason,
                new Separator(), commentLabel, comment));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        return dialog.showAndWait()
                .filter(button -> button == ButtonType.OK)
                .map(button -> new Outcome(score.getValue(), reason.getText(), comment.getText()));
    }
}
