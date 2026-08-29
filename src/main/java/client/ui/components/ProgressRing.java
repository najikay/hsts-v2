package client.ui.components;

import client.ui.components.logic.RingGeometry;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;

/**
 * A circular score ring (Presentation tier, UI wave 2).
 *
 * <p>Two arcs and a number: a full-circle track, the filled arc over it, and the
 * rounded average in the middle. It carries the student's term average in the My
 * Grades hero, which is the one place in the app where a single number about the
 * reader is the point of the screen.
 *
 * <p>An {@link Arc} rather than a {@code ProgressIndicator} because the stock
 * control draws a filled pie with a percentage under it, and because the ring
 * has to sit on the accent band, where its stroke is the on-accent token rather
 * than a theme colour. Colours come from the stylesheet through the style
 * classes below, never from code.
 *
 * <p>All arithmetic — the sweep, the direction, the clamping, the round-cap
 * overhang, the label — is {@link RingGeometry}, which is unit tested. This
 * class positions nodes.
 */
public final class ProgressRing extends Pane {

    /** The approved diameter, in px. */
    public static final double DIAMETER = 84;

    /** How thick the ring's stroke is. */
    private static final double STROKE = 7;

    /** The stroke is centred on this, so the ring's outer edge is the diameter. */
    private static final double RADIUS = (DIAMETER - STROKE) / 2;

    private final Arc fill;
    private final Label centre = new Label();

    /** @param score a mark out of 100; anything outside the scale is clamped */
    public ProgressRing(double score) {
        getStyleClass().add("hsts-progress-ring");
        setMinSize(DIAMETER, DIAMETER);
        setPrefSize(DIAMETER, DIAMETER);
        setMaxSize(DIAMETER, DIAMETER);

        Arc track = arc(RADIUS);
        track.getStyleClass().add("ring-track");
        track.setLength(RingGeometry.FULL_SWEEP);

        fill = arc(RADIUS);
        fill.getStyleClass().add("ring-fill");

        centre.getStyleClass().add("ring-value");
        // 2026-08-29, manual round 2: this used to be a StackPane, which centres each
        // child by its OWN bounds. A full circle and a partial arc have different bounds,
        // so the fill was centred on itself and drifted off the track as the score
        // changed: the "off-centre filling" the tester saw. A Pane lays nothing out, the
        // arcs sit on one explicit centre, and only the label is centred by hand.
        centre.layoutXProperty().bind(widthProperty().subtract(centre.widthProperty()).divide(2));
        centre.layoutYProperty().bind(heightProperty().subtract(centre.heightProperty()).divide(2));

        getChildren().addAll(track, fill, centre);
        set(score);
    }

    /**
     * Re-renders for a new score, so a ring that arrives at zero and settles on
     * an average does not have to be rebuilt.
     */
    public void set(double score) {
        // 2026-08-28, manual round 1: the sweep is shortened by one round cap at
        // each end, because a round cap is painted outside the angle it ends. The
        // stroke and the radius go in so the geometry class can work that out;
        // the label is untouched, and it is still the number the reader is given.
        fill.setLength(RingGeometry.sweepFor(score, STROKE, RADIUS));
        centre.setText(RingGeometry.centreLabel(score));
        setAccessibleText("Term average " + RingGeometry.centreLabel(score) + " out of 100.");
    }

    private static Arc arc(double radius) {
        Arc arc = new Arc(DIAMETER / 2, DIAMETER / 2, radius, radius, RingGeometry.START_ANGLE, 0);
        arc.setType(ArcType.OPEN);
        arc.setStrokeWidth(STROKE);
        arc.setStrokeLineCap(StrokeLineCap.ROUND);
        // Fill is set to null in code rather than in CSS: an OPEN arc with no
        // explicit fill still paints a black chord across the ring, which is a
        // defect that only shows once the ring is over a coloured band.
        arc.setFill(null);
        return arc;
    }
}
