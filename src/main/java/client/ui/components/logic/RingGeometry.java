package client.ui.components.logic;

/**
 * The arithmetic behind the circular progress ring (Presentation tier, UI wave 2).
 *
 * <p>The student's My Grades hero shows her term average as an 84px ring. Drawing
 * it is three JavaFX nodes; deciding what to draw is this, and it has the edge
 * cases: a score above 100, a negative one, an average with nothing to average.
 *
 * <p>JavaFX angles are mathematical — 0 degrees is three o'clock and positive is
 * counter-clockwise — and a progress ring is neither. Both conversions live here
 * so no caller has to remember which way round they go.
 */
public final class RingGeometry {

    /** Twelve o'clock, in JavaFX's angle system. Every ring starts here. */
    public static final double START_ANGLE = 90;

    /** A full turn. */
    public static final double FULL_SWEEP = 360;

    /** Every exam totals 100 points by construction (§8.1). */
    public static final double SCALE_MAX = 100;

    private RingGeometry() {
    }

    /**
     * The filled arc's length, in JavaFX degrees.
     *
     * @param score a mark out of {@link #SCALE_MAX}; values outside the scale are
     *              clamped rather than rejected, because a ring that throws is a
     *              blank hero and a ring that clamps is a full circle
     * @return a <b>negative</b> length, because a progress ring fills clockwise
     *         and JavaFX measures counter-clockwise
     */
    public static double sweepFor(double score) {
        return -FULL_SWEEP * fractionOf(score);
    }

    /**
     * The filled arc's length with the round stroke caps taken off both ends.
     *
     * <p>2026-08-28, manual round 1: the fill is drawn with round caps, and a
     * round cap is a half-disc that sits <b>outside</b> the angle it terminates.
     * Each end therefore overhangs by half the stroke width, so a ring set to 60
     * read as roughly 63 and a ring set to 1 showed as a dot at twelve o'clock
     * that was two caps wide and no arc at all. Testers reported the ring as "a
     * little off", which is exactly what it was.
     *
     * <p>Keeping the caps and shortening the angle is the fix rather than
     * squaring the ends off: the round end is the look the design asked for, and
     * the caps put back the length this takes away, so what is painted matches
     * the score. The two boundaries stay exact — 0 draws nothing and 100 draws
     * the whole circle, neither of which has a visible end to overhang.
     *
     * @param score       a mark out of {@link #SCALE_MAX}
     * @param strokeWidth how thick the ring's stroke is, in px
     * @param radius      the arc's radius, in px
     * @return a negative length, as {@link #sweepFor(double)}; never longer than
     *         the untrimmed sweep, and never the wrong way round for a value so
     *         small the two caps alone would overrun it
     */
    public static double sweepFor(double score, double strokeWidth, double radius) {
        double sweep = sweepFor(score);
        double length = Math.abs(sweep);
        if (length == 0 || length >= FULL_SWEEP) {
            return sweep;
        }
        return -Math.max(0, length - 2 * capAngle(strokeWidth, radius));
    }

    /**
     * How far one round cap reaches past the end of its arc, in degrees.
     *
     * <p>The cap is a half-disc of radius half the stroke, centred on the end
     * point, so the angle it covers is that half-stroke over the ring's radius.
     *
     * @return {@code 0} for a stroke or radius that is not a positive, finite
     *         number, so a ring built before it has been sized subtracts nothing
     *         rather than an angle nobody can reason about
     */
    public static double capAngle(double strokeWidth, double radius) {
        if (!(strokeWidth > 0) || !(radius > 0)
                || !Double.isFinite(strokeWidth) || !Double.isFinite(radius)) {
            return 0;
        }
        return Math.toDegrees((strokeWidth / 2) / radius);
    }

    /**
     * @param score a mark out of {@link #SCALE_MAX}
     * @return how full the ring is, in {@code [0, 1]}; {@code 0} for NaN, so a
     *         ring can never be handed an angle JavaFX rejects
     */
    public static double fractionOf(double score) {
        if (Double.isNaN(score)) {
            return 0;
        }
        return Math.max(0, Math.min(1, score / SCALE_MAX));
    }

    /**
     * The number printed inside the ring.
     *
     * <p>Rounded to the nearest whole mark, because a term average shown as
     * {@code 78.3333} is a number that invites a student to ask which of the
     * three digits she can act on.
     *
     * @param score the average
     * @return for example {@code "78"}
     */
    public static String centreLabel(double score) {
        if (Double.isNaN(score)) {
            return "0";
        }
        return Long.toString(Math.round(Math.max(0, Math.min(SCALE_MAX, score))));
    }
}
