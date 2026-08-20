package client.features.bot;

import common.dto.bot.BotActivityPoint;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A modest bar chart of questions per day (Presentation tier, E16.15 — F12.11).
 *
 * <p>Deliberately small. The project's real charting component is the animated
 * {@code StatChart} of E14, built for grade distributions with mean and sigma
 * overlays; borrowing it here would mean either bending it to a shape it was not
 * designed for or waiting on an epic this one does not depend on. What this screen
 * needs is thirty bars and a tooltip, so that is what this is: one rectangle per
 * day, scaled against the busiest one, with the count on hover.
 *
 * <p>It renders a list of {@link BotActivityPoint}, which has exactly two fields
 * and no way to hold an identity (S-34). A chart cannot leak what its input cannot
 * carry.
 */
public final class ActivityBars extends VBox {

    /** Style class on the container. */
    public static final String STYLE = "activity-bars";

    /** How tall the busiest day's bar is drawn. */
    private static final double MAX_HEIGHT = 120;

    /** The smallest visible bar, so a day with one question is not invisible. */
    private static final double MIN_HEIGHT = 3;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM");

    private final HBox bars = new HBox(3);
    private final Label caption = new Label();

    public ActivityBars() {
        getStyleClass().add(STYLE);
        setSpacing(6);
        bars.setAlignment(Pos.BOTTOM_LEFT);
        bars.setMinHeight(MAX_HEIGHT);
        bars.setPrefHeight(MAX_HEIGHT);
        caption.getStyleClass().add("chart-caption");
        getChildren().addAll(bars, caption);
    }

    /**
     * Draws one series.
     *
     * @param points questions per day, oldest first; an empty list draws nothing
     *               and says so in the caption
     */
    public void setPoints(List<BotActivityPoint> points) {
        bars.getChildren().clear();
        if (points == null || points.isEmpty()) {
            caption.setText(BotCopy.ANALYTICS_EMPTY_HINT);
            return;
        }
        int peak = points.stream().mapToInt(BotActivityPoint::count).max().orElse(1);
        for (BotActivityPoint point : points) {
            bars.getChildren().add(bar(point, Math.max(1, peak)));
        }
        caption.setText(DAY.format(points.get(0).day())
                + " to " + DAY.format(points.get(points.size() - 1).day()));
    }

    private static Region bar(BotActivityPoint point, int peak) {
        Region rect = new Region();
        rect.getStyleClass().add("activity-bar");
        double height = MIN_HEIGHT + (MAX_HEIGHT - MIN_HEIGHT) * (point.count() / (double) peak);
        rect.setMinHeight(height);
        rect.setPrefHeight(height);
        rect.setMinWidth(8);
        HBox.setHgrow(rect, Priority.ALWAYS);
        Tooltip.install(rect, new Tooltip(
                DAY.format(point.day()) + ": " + point.count()
                        + (point.count() == 1 ? " question" : " questions")));
        return rect;
    }
}
