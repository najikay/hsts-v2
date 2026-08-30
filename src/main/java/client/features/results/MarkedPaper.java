package client.features.results;

import common.dto.grading.AnswerReviewRow;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;

/**
 * One marked question, drawn the same way for whoever is reading it (Presentation tier,
 * E12.6 / E13.4 — F8.2, F9.1).
 *
 * <p>Lifted out of {@code CheckedFormView} unchanged on 2026-08-30 (live session, U-38), when
 * the teacher's review screen needed the same paper. It renders one
 * {@link AnswerReviewRow} — the row shape the server assembles once in
 * {@code GradeReviewService.answers} and serves to both wires — so a teacher and her student
 * are looking at the same drawing of the same question, which is the point: a review screen
 * that marked a paper its own way would be a second opinion about what the grader decided.
 *
 * <p><b>It lives here rather than in {@code client.ui.components} on purpose.</b> The marking
 * rules it renders are {@link CheckedFormCopy}'s — three outcomes, the style class per outcome,
 * the points line — and those are measured. A component package that may not import a feature
 * would have had to carry a second copy of them, and two implementations of "was this question
 * right" is exactly the drift this class exists to prevent.
 *
 * <h2>One label differs, and only one</h2>
 *
 * <p>The chosen option is tagged "Your answer" for the student and "Student's answer" for the
 * teacher, so the tag is the caller's to supply. Everything else reads identically to both,
 * including the correct-answer tag: what was right is not a matter of who is looking.
 */
public final class MarkedPaper {

    private MarkedPaper() {
        // static helper — no instances
    }

    /**
     * One marked question: its text, its four options, and how it was marked.
     *
     * @param row       a marked question
     * @param chosenTag what to tag the chosen option with, in the reader's own person
     * @return the card, ready to add to a column of them
     */
    public static VBox card(AnswerReviewRow row, String chosenTag) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(chosenTag, "chosenTag");

        Label number = new Label(row.ordinal() + ". " + row.questionText());
        number.setWrapText(true);
        number.getStyleClass().add("checked-form-question");

        Label outcome = new Label(CheckedFormCopy.outcome(row));
        outcome.getStyleClass().addAll("hsts-chip", CheckedFormCopy.outcomeStyle(row));

        Label points = new Label(CheckedFormCopy.points(row));
        points.getStyleClass().addAll("small", "muted");

        Label displayId = new Label(row.displayId());
        displayId.getStyleClass().addAll("small", "muted");

        HBox marks = new HBox(8, outcome, points, spacer(), displayId);
        marks.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(6, number, marks);
        card.getStyleClass().addAll("hsts-card", "checked-form-card",
                CheckedFormCopy.outcomeStyle(row));

        List<String> options = List.of(row.answer1(), row.answer2(), row.answer3(), row.answer4());
        int chosen = CheckedFormCopy.chosenOption(row);
        for (int i = 0; i < options.size(); i++) {
            card.getChildren().add(optionRow(i + 1, options.get(i), chosen, row.correct(), chosenTag));
        }
        return card;
    }

    /**
     * One option, tagged when it was chosen and when it was right.
     *
     * <p>Both tags are shown even when they are the same option: "chosen" and "correct" together
     * is how a reader sees the question was answered right, and dropping one of them on a
     * correct question would make the two cases render inconsistently.
     */
    private static HBox optionRow(int index, String text, int chosen, byte correct,
                                  String chosenTag) {
        Label label = new Label(index + ") " + text);
        label.setWrapText(true);
        HBox.setHgrow(label, Priority.ALWAYS);

        HBox line = new HBox(8, label);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("checked-form-option");

        if (index == chosen) {
            line.getChildren().add(tag(chosenTag, "neutral"));
            line.getStyleClass().add("chosen");
        }
        if (index == correct) {
            line.getChildren().add(tag(CheckedFormCopy.CORRECT_ANSWER, "success"));
            line.getStyleClass().add("correct");
        }
        return line;
    }

    private static Label tag(String text, String variant) {
        Label chip = new Label(text);
        chip.getStyleClass().addAll("hsts-chip", variant);
        return chip;
    }

    private static HBox spacer() {
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
