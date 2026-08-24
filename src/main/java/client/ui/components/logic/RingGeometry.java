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
