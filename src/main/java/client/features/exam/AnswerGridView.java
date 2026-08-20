package client.features.exam;

import common.dto.exam.AttemptSummaryEntry;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * The answer-summary grid (Presentation tier, E10.13/E10.14 — F6.9, F6.10, F6.4).
 *
 * <p>One component, three appearances, because they are one idea:
 *
 * <ul>
 *   <li><b>in the submit dialog</b> the chips are clickable and jump to their question,
 *       which is what makes "you have 3 unanswered" actionable instead of merely
 *       alarming (F6.9);</li>
 *   <li><b>on the Submitted screen</b> they are a record of what was handed in;</li>
 *   <li><b>on the Time Up takeover</b> they are the same record, locked, with no way back
 *       into the paper (F6.4).</li>
 * </ul>
 *
 * <p>Built from {@link QuestionChip} in the live case and from {@link AttemptSummaryEntry}
 * in the two ending cases, so the dialog reads the client's model while the endings read
 * what the <b>server</b> says it stored. That is deliberate: after the attempt is over, the
 * only account of it worth showing is the one the grader will mark.
 */
public final class AnswerGridView extends VBox {

    private final FlowPane cells = new FlowPane();
    private final Label caption = new Label();

    /** Builds an empty grid; fill it with one of the two {@code show} methods. */
    public AnswerGridView() {
        getStyleClass().add("answer-grid");
        setSpacing(8);
        cells.setHgap(6);
        cells.setVgap(6);
        cells.getStyleClass().add("answer-grid-cells");
        caption.getStyleClass().addAll("small", "muted");
        getChildren().addAll(caption, cells);
    }

    /**
     * The live case: clickable chips over the client's current answers (F6.9).
     *
     * @param chips  the model's chips
     * @param onJump what to do when one is clicked, given its 0-based index
     */
    public void show(List<QuestionChip> chips, IntConsumer onJump) {
        cells.getChildren().clear();
        long answered = chips.stream().filter(QuestionChip::answered).count();
        caption.setText(ExamCopy.progress((int) answered, chips.size()));
        for (QuestionChip chip : chips) {
            Button cell = new Button(chip.label());
            cell.getStyleClass().addAll("answer-cell");
            cell.getStyleClass().addAll(chip.styleClass().split(" "));
            cell.setTooltip(new Tooltip(chip.tooltip()));
            cell.setOnAction(e -> onJump.accept(chip.index()));
            cells.getChildren().add(cell);
        }
    }

    /**
     * The ending case: what the server recorded, not clickable.
     *
     * @param summary the outcome's per-question grid
     */
    public void showSummary(List<AttemptSummaryEntry> summary) {
        cells.getChildren().clear();
        long answered = summary.stream().filter(AttemptSummaryEntry::answered).count();
        caption.setText(ExamCopy.progress((int) answered, summary.size()));
        for (AttemptSummaryEntry entry : summary) {
            Label cell = new Label(Integer.toString(entry.ordinal()));
            cell.getStyleClass().addAll("answer-cell",
                    entry.answered() ? "answered" : "blank");
            cell.setTooltip(new Tooltip("Question " + entry.ordinal() + " · " + entry.displayId()
                    + (entry.answered() ? " · answered" : " · not answered")));
            cells.getChildren().add(cell);
        }
    }
}
