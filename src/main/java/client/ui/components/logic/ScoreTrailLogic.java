package client.ui.components.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The geometry of one student's score trail (Presentation tier, U-90 full form).
 *
 * <p>Toolkit-free, like {@link StatChartLogic} and for the same reason: the principal's
 * student report draws her approved scores as a chronological trail, and every x, every y and
 * every gap in that drawing is a decision this class makes so a unit test can pin it with
 * hand-computed fixtures.
 *
 * <h2>Gaps are honest, not interpolated</h2>
 *
 * <p>A sitting whose grade is not approved has no score to draw. The trail SKIPS it - the line
 * breaks rather than bridging the hole - because a line drawn across an unpublished grade
 * would be the chart inventing a number the server refused to publish. The stop still occupies
 * its x slot, so the spacing tells the truth about when the ungraded sitting happened.
 */
public final class ScoreTrailLogic {

    /** One sitting on the trail: its short label and her score, null while unapproved. */
    public record Stop(String label, Integer score) {
        public Stop {
            Objects.requireNonNull(label, "label");
        }
    }

    /** A dot the component draws: position, score, and whether it clears the pass mark. */
    public record Dot(double x, double y, int score, boolean passed, String label) { }

    /** One unbroken run of the polyline; a null score splits the trail into several. */
    public record Segment(List<Double> xs, List<Double> ys) { }

    /** The pass mark the trail draws as a reference line, the product's own 55. */
    public static final int PASS_MARK = 55;

    private final List<Stop> stops;

    public ScoreTrailLogic(List<Stop> stops) {
        this.stops = List.copyOf(Objects.requireNonNull(stops, "stops"));
    }

    /** @return whether there is anything to draw: at least one graded stop. */
    public boolean isDrawable() {
        return stops.stream().anyMatch(stop -> stop.score() != null);
    }

    /** @return how many stops are graded, for the caption. */
    public long gradedCount() {
        return stops.stream().filter(stop -> stop.score() != null).count();
    }

    public int stopCount() {
        return stops.size();
    }

    /**
     * @param index    which stop
     * @param plotWidth the width the dots share
     * @return the stop's x: centred slots, so one stop sits in the middle and n stops divide
     *         the width evenly - the same slotting a bucket chart uses
     */
    public double xFor(int index, double plotWidth) {
        return plotWidth * (index + 0.5) / stopCount();
    }

    /**
     * @param score     0..100
     * @param plotHeight the height the scale maps onto
     * @return the score's y, 100 at the top and 0 on the baseline
     */
    public double yFor(int score, double plotHeight) {
        return plotHeight * (1.0 - score / 100.0);
    }

    /** @return the pass-mark reference line's y. */
    public double passLineY(double plotHeight) {
        return yFor(PASS_MARK, plotHeight);
    }

    /** @return every graded stop as a positioned dot, in chronological order. */
    public List<Dot> dots(double plotWidth, double plotHeight) {
        List<Dot> dots = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = stops.get(i);
            if (stop.score() == null) {
                continue;
            }
            dots.add(new Dot(xFor(i, plotWidth), yFor(stop.score(), plotHeight),
                    stop.score(), stop.score() >= PASS_MARK, stop.label()));
        }
        return dots;
    }

    /**
     * @return the polyline runs. Consecutive graded stops join; an ungraded stop ends the run,
     *         so the line never crosses a score the server has not published.
     */
    public List<Segment> segments(double plotWidth, double plotHeight) {
        List<Segment> segments = new ArrayList<>();
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = stops.get(i);
            if (stop.score() == null) {
                flush(segments, xs, ys);
                continue;
            }
            xs.add(xFor(i, plotWidth));
            ys.add(yFor(stop.score(), plotHeight));
        }
        flush(segments, xs, ys);
        return segments;
    }

    private static void flush(List<Segment> segments, List<Double> xs, List<Double> ys) {
        if (xs.size() >= 2) {
            segments.add(new Segment(List.copyOf(xs), List.copyOf(ys)));
        }
        xs.clear();
        ys.clear();
    }

    /** @return the stop labels in slot order, graded or not, for the axis row. */
    public List<String> labels() {
        return stops.stream().map(Stop::label).toList();
    }

    /** @return the stops, for a component that needs the raw model. */
    public List<Stop> stops() {
        return stops;
    }
}
