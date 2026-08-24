package client.ui.components;

import client.ui.anim.Animations;
import client.ui.anim.Motion;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.Objects;

/**
 * A number that rolls when it changes (Presentation tier, UI wave 2).
 *
 * <p>The motion spec asks for one thing and it is worth being precise about
 * what: <b>numbers that change while you are looking at them</b> roll
 * vertically, over {@link Motion#NUMBER_ROLL_MS}. A count that is simply
 * rendered — the value a card was born with — does not, because there was no
 * change to show. So the roll happens in {@link #set(String)} and never in the
 * constructor.
 *
 * <p>Two labels in a clipped column: the outgoing value slides up and out, the
 * incoming one follows it into the slot. Setting the same value twice does
 * nothing at all, which matters because the two callers — the live sitting's
 * submitted count and the bell's unread badge — both re-render on every push,
 * and most pushes do not change their number.
 */
public final class NumberRoll extends Pane {

    private final Label showing = new Label();
    private final Label incoming = new Label();
    private final VBox column = new VBox(showing, incoming);
    private final Rectangle clip = new Rectangle();

    private String value;
    private Timeline running;

    /**
     * @param initial     the value to render with no animation
     * @param styleClass  the text style class both labels carry, so the roll is
     *                    invisible to the stylesheet
     */
    public NumberRoll(String initial, String styleClass) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(styleClass, "styleClass");
        getStyleClass().add("hsts-number-roll");

        showing.getStyleClass().add(styleClass);
        incoming.getStyleClass().add(styleClass);
        showing.setText(initial);
        incoming.setText(initial);
        this.value = initial;

        column.setFillWidth(true);
        // The clip is what makes it a roll rather than two labels sliding about.
        setClip(clip);
        getChildren().add(column);
        setAccessibleText(initial);
    }

    /** @return the value currently displayed. */
    public String value() {
        return value;
    }

    /**
     * Shows a new value, rolling to it.
     *
     * <p>Under reduced motion the roll collapses with everything else: the
     * animation's duration comes from {@link Animations}, which asks
     * {@link Motion} for it, and the travel is the label's own height either way
     * because a roll with no travel would leave the old value on screen.
     *
     * @param next what to show; {@code null} is treated as no change
     */
    public void set(String next) {
        if (next == null || next.equals(value)) {
            return;
        }
        this.value = next;
        setAccessibleText(next);

        // Interruptible like every other house animation: a second push landing
        // mid-roll must not leave two timelines fighting over one translate.
        if (running != null) {
            running.stop();
            column.setTranslateY(0);
        }
        double step = rowHeight();
        incoming.setText(next);
        column.setTranslateY(0);

        Timeline roll = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(column.translateYProperty(), 0, Animations.EASE)),
                new KeyFrame(Duration.millis(Motion.effectiveMillis(Motion.NUMBER_ROLL_MS)),
                        new KeyValue(column.translateYProperty(), -step, Animations.EASE)));
        this.running = roll;
        roll.setOnFinished(event -> {
            // Settle: the incoming value becomes the showing one and the column
            // returns to its origin, so the next roll starts from the same place
            // rather than drifting a row further up each time.
            showing.setText(next);
            column.setTranslateY(0);
        });
        roll.play();
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        column.resizeRelocate(0, 0, width, height * 2);
        clip.setWidth(width);
        clip.setHeight(height);
    }

    @Override
    protected double computePrefHeight(double width) {
        return showing.prefHeight(width);
    }

    @Override
    protected double computePrefWidth(double height) {
        return Math.max(showing.prefWidth(height), incoming.prefWidth(height));
    }

    /**
     * @return how far the column travels: one row. Falls back to the label's
     *         preferred height before the first layout pass, so a value set
     *         before the node is on screen still lands in the right place
     */
    private double rowHeight() {
        double height = getHeight();
        return height > 0 ? height : Math.max(showing.prefHeight(-1), 1);
    }
}
