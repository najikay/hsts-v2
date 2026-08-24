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

    // ================= UI wave 2 — the approved motion spec ================
    //
    // The canvas names a duration per gesture rather than reusing three house
    // constants for everything, because the gestures are not the same size: a
    // whole screen arriving is a bigger event than a popover opening, and a
    // popover closing is smaller than either. They are constants here, not
    // literals at the call sites, so the spec is one file to read and one file
    // to change.

    /** Route change: fade plus an 8px rise, incoming screen only. */
    public static final int ROUTE_MS = 180;

    /** How far an incoming screen or card rises, in px. */
    public static final double RISE_DISTANCE = 8;

    /** Popover opening: fade plus a 6px slide. */
    public static final int POPOVER_OPEN_MS = 140;

    /** Popover closing. Shorter than opening: leaving should not be waited for. */
    public static final int POPOVER_CLOSE_MS = 100;

    /** Dialog entrance: scale {@link #DIALOG_FROM_SCALE} to 1 with a parallel scrim fade. */
    public static final int DIALOG_MS = 160;

    /** A dialog starts this close to its final size. Nearly there, so it settles rather than pops. */
    public static final double DIALOG_FROM_SCALE = 0.98;

    /** Gap between consecutive dashboard cards in their entrance stagger. */
    public static final int CARD_STAGGER_STEP_MS = 30;

    /**
     * How many dashboard cards are staggered before the rest share the last slot.
     *
     * <p>Six, because a stagger is a reading order and past half a dozen items
     * nobody is reading in order any more — they are waiting.
     */
    public static final int CARD_STAGGER_MAX = 6;

    /** Gap between consecutive table rows on a first load. Linear, no easing. */
    public static final int ROW_STAGGER_STEP_MS = 20;

    /** Card hover lift. */
    public static final int CARD_HOVER_MS = 150;

    /** Table row hover tint. Faster than a card: a row is passed over, not aimed at. */
    public static final int ROW_HOVER_MS = 100;

    /**
     * The empty-state icon's breathing loop.
     *
     * <p>Ambient rather than a transition, so it is exempt from {@link #MAX_MS}
     * on the same terms {@code Animations.shimmer} is, and it is deliberately
     * slow: an empty screen should feel awake, not busy.
     */
    public static final int BREATHE_MS = 2400;

    /** How far the breathing icon scales: 4%, which reads as breathing and not as pulsing. */
    public static final double BREATHE_SCALE = 1.04;

    /**
     * The live-sitting halo loop.
     *
     * <p>The second and last ambient loop in the app, and it is allowed only
     * while something is <i>genuinely</i> live. A halo on a card that is not
     * showing a running sitting is decoration, and decoration that moves is the
     * thing PRD §4.1's budget exists to prevent.
     */
    public static final int LIVE_PULSE_MS = 1600;

    /** A number changing on screen rolls vertically over this long. */
    public static final int NUMBER_ROLL_MS = 240;

    /**
     * What every animation collapses to when reduced motion is on.
     *
     * <p>A plain fade and nothing else: no travel, no scale, no stagger, no
     * loop. Zero would be an option, but a screen that swaps with no transition
     * at all reads as a glitch, and 80ms is short enough that nobody who asked
     * for less motion has been given motion.
     */
    public static final int REDUCED_FADE_MS = 80;

    /** System property that turns reduced motion on for a whole run. */
    public static final String REDUCED_MOTION_PROPERTY = "hsts.motion.reduced";

    private static volatile boolean reducedMotion =
            Boolean.getBoolean(REDUCED_MOTION_PROPERTY);

    private Motion() {
    }

    // ================= reduced motion =====================================

    /**
     * @return {@code true} when every animation should collapse to
     *         {@link #REDUCED_FADE_MS} of plain fade
     */
    public static boolean isReducedMotion() {
        return reducedMotion;
    }

    /**
     * Turns reduced motion on or off for the running app.
     *
     * <p>Read once from {@link #REDUCED_MOTION_PROPERTY} at class load, so a
     * demo machine can be started calm without a settings visit, and settable
     * afterwards because a preference that needs a restart is not a preference.
     */
    public static void setReducedMotion(boolean reduced) {
        reducedMotion = reduced;
    }

    /**
     * The duration a caller actually gets.
     *
     * <p>One function rather than an {@code if} at forty call sites: with
     * reduced motion on, every request becomes {@link #REDUCED_FADE_MS};
     * otherwise it is clamped to the budget exactly as before.
     *
     * @param requested what the caller asked for
     * @return the duration to play
     */
    public static int effectiveMillis(int requested) {
        return reducedMotion ? REDUCED_FADE_MS : clampMillis(requested);
    }

    /**
     * The travel a caller actually gets.
     *
     * @return {@code 0} under reduced motion — a fade has no distance — and
     *         {@code distance} otherwise
     */
    public static double effectiveDistance(double distance) {
        return reducedMotion ? 0 : distance;
    }

    /**
     * The stagger delay a caller actually gets.
     *
     * @return {@code 0} under reduced motion, so a staggered list arrives at
     *         once rather than trickling
     */
    public static int effectiveDelay(int delayMs) {
        return reducedMotion ? 0 : Math.max(delayMs, 0);
    }

    /**
     * Whether an ambient loop (the breathing empty state, the live halo) may run.
     *
     * <p>These are the two animations that never end on their own, which makes
     * them the two a reader who asked for less motion would notice most.
     *
     * @return {@code false} under reduced motion
     */
    public static boolean ambientLoopsAllowed() {
        return !reducedMotion;
    }

    // ================= wave-2 stagger arithmetic ==========================

    /**
     * Entrance delay for dashboard card {@code index}.
     *
     * <p>Capped by count rather than by time: the seventh card and the
     * seventieth both start with the sixth, so a role that grows a card row
     * later cannot turn the dashboard's arrival into a queue.
     *
     * @param index zero-based position; negatives are treated as 0
     * @return the delay in milliseconds
     */
    public static int cardStaggerDelay(int index) {
        int safeIndex = Math.min(Math.max(index, 0), CARD_STAGGER_MAX - 1);
        return effectiveDelay(safeIndex * CARD_STAGGER_STEP_MS);
    }

    /**
     * Entrance delay for table row {@code index} on a first load.
     *
     * <p>Bounded by {@link #STAGGER_CAP_MS} like every other stagger, so a
     * 400-row data browser still finishes arriving within a blink.
     *
     * @param index zero-based position; negatives are treated as 0
     * @return the delay in milliseconds
     */
    public static int rowStaggerDelay(int index) {
        return effectiveDelay(staggerDelay(index, ROW_STAGGER_STEP_MS, STAGGER_CAP_MS));
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
