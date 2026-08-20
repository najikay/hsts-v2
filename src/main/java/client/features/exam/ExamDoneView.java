package client.features.exam;

import client.ui.anim.Animations;
import client.ui.anim.Motion;
import client.ui.components.Buttons;
import client.ui.components.Icons;
import common.dto.exam.AttemptOutcome;
import common.dto.exam.AttemptState;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * The two endings (Presentation tier, E10.13/E10.14 ⚑ — F6.4, F6.10).
 *
 * <p>One full-screen takeover with two moods, because F6.10 says so explicitly: the
 * Submitted screen and the Time Up screen are "the same layout family, the difference is
 * celebratory versus locked, confirm-before versus no-confirm". Building them as two
 * screens would guarantee they drifted, and the drift would be visible in the demo.
 *
 * <h2>What makes it a takeover rather than a panel</h2>
 *
 * <p>It covers the paper completely and is not dismissible. There is exactly one control
 * on it, and that control navigates away. That is the v1 bug's opposite: v1 left the exam
 * open behind an expired timer, and a student could keep clicking. Here the form is
 * unreachable the moment this appears, the model refuses further selections, and the server
 * would refuse them anyway.
 *
 * <p><b>No confirmation on the timed-out path.</b> Asking "are you sure?" about something
 * that has already happened is the kind of dialog that makes people distrust software, and
 * F6.4 forbids it in as many words.
 */
public final class ExamDoneView extends StackPane {

    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final Label summary = new Label();
    private final AnswerGridView grid = new AnswerGridView();
    private final Button back = Buttons.primary(ExamCopy.BACK_TO_DASHBOARD);
    private final StackPane iconDisc = new StackPane();

    private Runnable onLeave = () -> { };

    /** Builds the takeover, hidden until {@link #show(AttemptOutcome)}. */
    public ExamDoneView() {
        getStyleClass().add("exam-done");
        setAlignment(Pos.CENTER);

        title.getStyleClass().add("done-title");
        subtitle.getStyleClass().addAll("body", "muted");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(420);
        subtitle.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        summary.getStyleClass().addAll("small", "muted");
        iconDisc.getStyleClass().add("done-icon-disc");

        back.setOnAction(e -> onLeave.run());

        VBox card = new VBox(16, iconDisc, title, subtitle, summary, grid, back);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(36));
        card.setMaxWidth(560);
        card.setMaxHeight(Double.MAX_VALUE);
        card.getStyleClass().addAll("hsts-card", "done-card");

        getChildren().add(card);
        setVisible(false);
        setManaged(false);
    }

    /** Registers the single action: back to the dashboard, and out of the exam for good. */
    public void onLeave(Runnable handler) {
        this.onLeave = Objects.requireNonNull(handler, "handler");
    }

    /**
     * Reveals the takeover for one outcome.
     *
     * @param outcome what the server handed in
     */
    public void show(AttemptOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        boolean forced = outcome.state() == AttemptState.TIMED_OUT;

        getStyleClass().removeAll("submitted", "timed-out");
        getStyleClass().add(forced ? "timed-out" : "submitted");

        title.setText(ExamCopy.endingTitle(outcome.state()));
        subtitle.setText(ExamCopy.endingSubtitle(outcome.state()));
        summary.setText(ExamCopy.outcomeSummary(outcome));
        grid.showSummary(outcome.summary());

        iconDisc.getChildren().setAll(icon(forced));
        setVisible(true);
        setManaged(true);
        // Under 250ms and interruptible, like every other animation here (PRD §4.1). The
        // timed-out clock and the submitted check use the same entrance so the two screens
        // read as one family.
        Animations.scaleIn(this, Motion.SLOW_MS);
        Animations.scalePop(iconDisc);
    }

    /** Hides it again. Used when the screen is left, so a re-entry starts clean. */
    public void reset() {
        setVisible(false);
        setManaged(false);
        Animations.reset(this);
    }

    private static Node icon(boolean forced) {
        return Icons.of(forced ? Icons.CLOCK : Icons.CHECK, 44,
                forced ? "done-icon-timeout" : "done-icon-check");
    }
}
