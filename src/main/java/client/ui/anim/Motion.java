package client.ui.anim;

/**
 * The arithmetic behind every animation in the app (Presentation tier, E4.20).
 *
 * <p>PRD §4.1 sets one hard motion rule — <b>nothing longer than 250ms, everything
 * interruptible</b> — and a house style of 4px-grid distances and short
 * entrance staggers. Those are numbers with edge cases (a caller asking for
 * 400ms, a list of 200 rows whose stagger would take six seconds, a negative
 * index), so they live here as pure functions rather than scattered through
 * {@link Animations} where nothing could check them.
 *
 * <p>{@link Animations} is then free to be a thin, obvious wrapper over JavaFX
 * transitions: it asks this class for a duration and plays it.
 */
public final class Motion {

    /** The PRD's ceiling. Any requested duration is clamped to this. */
    public static final int MAX_MS = 250;

    /** Micro-feedback: hover/press scale, badge pop. */
    public static final int FAST_MS = 120;

    /** House default: fades, slides, screen transitions. */
    public static final int BASE_MS = 180;

    /** The slowest thing we allow: toast slide-in, dialog entrance. */
    public static final int SLOW_MS = 220;

    /** Gap between consecutive list items in an entrance stagger. */
    public static final int STAGGER_STEP_MS = 28;

    /**
     * Ceiling on the <i>last</i> item's start delay. A 40-row table must finish
     * arriving within a blink; beyond this many rows the remainder simply share
     * the final slot rather than trickling in.
     */
    public static final int STAGGER_CAP_MS = 250;

    /** Default travel for a slide, in px — a multiple of the 4px grid. */
    public static final double SLIDE_DISTANCE = 16;

    /** Default overshoot for {@code scalePop} (6%). */
    public static final double POP_SCALE = 1.06;

    private Motion() {
    }

    /**
     * Clamps a requested duration into the allowed range.
     *
     * @return {@code requested} bounded to {@code [0, }{@link #MAX_MS}{@code ]}
     */
    public static int clampMillis(int requested) {
        if (requested < 0) {
            return 0;
        }
        return Math.min(requested, MAX_MS);
    }

    /** @return {@code true} when the duration would have to be shortened to obey the PRD. */
    public static boolean exceedsBudget(int requested) {
        return requested > MAX_MS;
    }

    /** @return the entrance delay for list item {@code index}, using house defaults. */
    public static int staggerDelay(int index) {
        return staggerDelay(index, STAGGER_STEP_MS, STAGGER_CAP_MS);
    }

    /**
     * Entrance delay for one item of a staggered list.
     *
     * @param index  zero-based position; negatives are treated as 0
     * @param stepMs gap between consecutive items (negatives treated as 0)
     * @param capMs  ceiling for the delay (negatives treated as 0)
     * @return the delay in milliseconds, never negative, never above {@code capMs}
     */
    public static int staggerDelay(int index, int stepMs, int capMs) {
        int safeIndex = Math.max(index, 0);
        int safeStep = Math.max(stepMs, 0);
        int safeCap = Math.max(capMs, 0);
        long delay = (long) safeIndex * safeStep;
        return (int) Math.min(delay, safeCap);
    }

    /**
     * @return total wall time for a staggered entrance of {@code count} items,
     *         i.e. the last item's delay plus its own duration
     */
    public static int staggerTotalMillis(int count, int itemDurationMs) {
        if (count <= 0) {
            return 0;
        }
        return staggerDelay(count - 1) + clampMillis(itemDurationMs);
    }

    /**
     * Signed travel for a slide-in.
     *
     * @param distance   how far the node travels (absolute value is used)
     * @param fromLeadingEdge {@code true} to enter from the left/top (negative
     *                        offset), {@code false} from the right/bottom
     * @return the starting offset relative to the node's laid-out position
     */
    public static double slideOffset(double distance, boolean fromLeadingEdge) {
        double magnitude = Math.abs(distance);
        return fromLeadingEdge ? -magnitude : magnitude;
    }

    /** @return the house slide offset for the given direction. */
    public static double slideOffset(boolean fromLeadingEdge) {
        return slideOffset(SLIDE_DISTANCE, fromLeadingEdge);
    }

    /**
     * Clamps an opacity into the legal range, so a caller's arithmetic
     * ({@code 1 - n * 0.3}) can never hand JavaFX a value it rejects.
     */
    public static double clampOpacity(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }

    /**
     * Clamps a scale factor to a range that still reads as the same element.
     * A "pop" that doubles a node is a bug, not an emphasis.
     *
     * @return {@code value} bounded to {@code [0.5, 1.5]}
     */
    public static double clampScale(double value) {
        if (Double.isNaN(value) || value <= 0) {
            return 1;
        }
        return Math.max(0.5, Math.min(1.5, value));
    }

    /**
     * How many pulse cycles fit in a time budget.
     *
     * @param budgetMs    total time available (e.g. how long a timer stays red)
     * @param oneCycleMs  duration of a single pulse
     * @return at least 1 cycle, or 0 when there is no budget at all
     */
    public static int pulseCycles(int budgetMs, int oneCycleMs) {
        if (budgetMs <= 0 || oneCycleMs <= 0) {
            return 0;
        }
        return Math.max(1, budgetMs / oneCycleMs);
    }
}
