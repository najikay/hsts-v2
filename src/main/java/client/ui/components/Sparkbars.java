package client.ui.components;

import client.ui.components.logic.SparkbarSpec;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;

/**
 * Ten slim bars showing where a class landed (Presentation tier, UI wave 2).
 *
 * <p>A glance, not a chart. It sits on the teacher's "last closed sitting"
 * dashboard card under the mean, and it answers one question — was the class
 * bunched or spread — in the moment before she decides whether to open the
 * sitting. {@link StatChart} is still the real histogram and is what the Results
 * screen behind the card draws, with axes, labels and a scale toggle.
 *
 * <p>A loop over {@link SparkbarSpec} and nothing else. Which bar is the mode,
 * how tall each is, and what an empty bucket looks like are all decided there,
 * where they are measured.
 */
public final class Sparkbars extends HBox {

    /** The strip's height in px. Tall enough to have a shape, short enough to be a detail. */
    public static final double HEIGHT = 34;

    /**
     * @param deciles the ten stored counts
     *                ({@code ResultStatistics.deciles()})
     */
    public Sparkbars(List<Integer> deciles) {
        Objects.requireNonNull(deciles, "deciles");
        getStyleClass().add("hsts-sparkbars");
        setAlignment(Pos.BOTTOM_LEFT);
        setSpacing(3);
        setMinHeight(HEIGHT);
        setPrefHeight(HEIGHT);

        for (SparkbarSpec bar : SparkbarSpec.of(deciles)) {
            getChildren().add(bar(bar));
        }
        setAccessibleText(accessibleTextOf(deciles));
    }

    private static VBox bar(SparkbarSpec spec) {
        Region fill = new Region();
        fill.getStyleClass().add("sparkbar-fill");
        if (spec.modal()) {
            fill.getStyleClass().add("modal");
        }
        if (spec.isEmpty()) {
            fill.getStyleClass().add("empty");
        }
        double height = Math.max(1, HEIGHT * spec.fraction());
        fill.setMinHeight(height);
        fill.setPrefHeight(height);
        fill.setMaxHeight(height);

        // A column per bucket so every bar is bottom-aligned in its own slot and
        // the strip reads as a distribution rather than as a row of dashes.
        VBox column = new VBox(fill);
        column.getStyleClass().add("sparkbar");
        column.setAlignment(Pos.BOTTOM_CENTER);
        column.setMinHeight(HEIGHT);
        column.setPrefHeight(HEIGHT);
        HBox.setHgrow(column, javafx.scene.layout.Priority.ALWAYS);
        column.setFillWidth(true);
        return column;
    }

    /**
     * @return one sentence naming the fullest band, because ten unlabelled bars
     *         read aloud one at a time are ten numbers and no shape
     */
    private static String accessibleTextOf(List<Integer> deciles) {
        for (SparkbarSpec bar : SparkbarSpec.of(deciles)) {
            if (bar.modal()) {
                return "Most students scored " + bar.rangeLabel() + ".";
            }
        }
        return "Score distribution.";
    }
}
