package client.features.approval;

import client.ui.anim.Animations;
import client.ui.anim.Motion;
import client.ui.components.Buttons;
import client.ui.components.FormField;
import client.ui.components.Icons;
import client.ui.components.ModalHost;
import client.ui.components.logic.ValidationState;
import common.dto.approval.ExamRejectRequest;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.Objects;
import java.util.Optional;

/**
 * The "why are you sending this back?" dialog (Presentation tier, E8.5 — F4.2).
 *
 * <p>{@code WarnConfirm} is the app's dialog for a decision that needs informed confirmation,
 * and this is the one case it cannot serve: rejection needs an <b>input</b>, and a required
 * one. So this is its sibling rather than a variant of it, built the same way — a modal
 * transparent stage over a scrim, inheriting the owner's stylesheets and dark class — with
 * one field in the middle. Both are mounted through
 * {@link client.ui.components.ModalHost}, which is where "the scrim covers the owner
 * window" lives (2026-08-28, manual round 1).
 *
 * <h2>The rule is live, and it is the server's rule</h2>
 *
 * <p>Validation runs on every keystroke through {@link ExamRejectRequest#validate}, the same
 * method the server runs before it writes. The confirm button is disabled until it passes and
 * the message under the field says what is still missing, so a coordinator learns the bar
 * before the round trip rather than after it. Sharing the method is what stops the client's
 * idea of "long enough" from drifting from the server's.
 *
 * <p>The counter is phrased as what is still needed rather than as a character count, because
 * "8 more characters" is an instruction and "12/10" is a puzzle.
 */
public final class RejectDialog {

    private RejectDialog() {
    }

    /**
     * Shows the dialog modally and blocks until the coordinator answers.
     *
     * @param owner    the window to dim and block; may be {@code null}
     * @param examName the exam being sent back, named in the title so there is no doubt
     * @return the trimmed reason, or empty when she cancelled
     */
    public static Optional<String> show(Window owner, String examName) {
        Objects.requireNonNull(examName, "examName");
        String[] answer = {null};

        Stage stage = new Stage(StageStyle.TRANSPARENT);
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }

        TextArea input = new TextArea();
        input.setPromptText(ApprovalCopy.REJECT_REASON_PROMPT);
        input.setWrapText(true);
        input.setPrefRowCount(4);
        input.getStyleClass().add("reject-reason");

        FormField field = new FormField(ApprovalCopy.REJECT_REASON_LABEL, input).required();

        Label counter = new Label();
        counter.getStyleClass().addAll("small", "faint", "reject-counter");

        Button confirm = Buttons.danger(ApprovalCopy.REJECT_CONFIRM);
        confirm.setDisable(true);
        confirm.setOnAction(e -> {
            answer[0] = input.getText().trim();
            stage.close();
        });

        Button cancel = Buttons.secondary(ApprovalCopy.KEEP_LOOKING);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());

        input.textProperty().addListener((obs, old, typed) -> {
            Optional<String> complaint = ExamRejectRequest.validate(typed);
            // Pristine while the box is empty: an untouched required field is not yet a
            // mistake, and a red border on a form nobody has typed in is a scold.
            field.apply(typed == null || typed.isBlank()
                    ? ValidationState.pristine()
                    : ValidationState.from(complaint));
            confirm.setDisable(complaint.isPresent());
            counter.setText(ApprovalCopy.reasonHint(typed));
        });
        counter.setText(ApprovalCopy.reasonHint(""));

        VBox dialog = build(examName, field, counter, cancel, confirm);
        StackPane scrim = ModalHost.mount(stage, owner, dialog);

        Animations.fadeIn(scrim, Motion.DIALOG_MS);
        Animations.scaleIn(dialog, Motion.DIALOG_FROM_SCALE, Motion.DIALOG_MS);
        input.requestFocus();
        stage.showAndWait();
        return Optional.ofNullable(answer[0]);
    }

    private static VBox build(String examName, FormField field, Label counter,
                              Button cancel, Button confirm) {
        StackPane iconDisc = new StackPane(Icons.of(Icons.ERROR, Icons.SIZE_LARGE, "dialog-icon"));
        iconDisc.getStyleClass().add("dialog-icon-disc");

        Label title = new Label(ApprovalCopy.REJECT_TITLE);
        title.getStyleClass().add("dialog-title");
        title.setWrapText(true);

        Label explanation = new Label(examName + ". " + ApprovalCopy.REJECT_EXPLANATION);
        explanation.getStyleClass().add("dialog-explanation");
        explanation.setWrapText(true);

        VBox headingText = new VBox(6, title, explanation);
        HBox heading = new HBox(14, iconDisc, headingText);
        heading.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(headingText, Priority.ALWAYS);

        VBox dialog = new VBox(14, heading, field, counter, Buttons.row(cancel, confirm));
        dialog.getStyleClass().addAll("hsts-dialog", "danger", "reject-dialog");
        dialog.setMaxWidth(520);
        return dialog;
    }
}
