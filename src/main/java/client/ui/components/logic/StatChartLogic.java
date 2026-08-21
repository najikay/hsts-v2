package client.ui.components.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The score histogram's whole brain (Presentation tier, E14.3 — F9.2).
 *
 * <p>v1's statistics view was a graded weak point, and the reason is worth stating because it
 * is what this class is shaped against: it drew bars whose heights were relative to each other
 * on an axis that started wherever it liked, so two adjacent bars of 7 and 8 students looked
 * like a landslide. Every rule here exists to make that impossible.
 *
 * <h2>The honesty rules</h2>
 *
 * <ol>
 *   <li><b>The y axis starts at zero.</b> {@link #axisMax(Scale)} returns the top; the bottom
 *       is {@code 0} and there is no method that could move it. A truncated bar chart is a
 *       lie with a legend on it.</li>
 *   <li><b>The y axis has headroom.</b> The top is {@value #HEADROOM_FRACTION} above the
 *       tallest bar, rounded up to a whole tick, so the tallest bar never touches the frame
 *       and the reader can see that it <em>is</em> the tallest rather than the clipped one.</li>
 *   <li><b>The x axis is the score, not the bucket index.</b> Bucket {@code i} occupies
 *       {@code [i*10, i*10+10)} of the plot width, so {@link #xForScore} places the mean and
 *       median markers on the same ruler as the bars. A chart whose overlay used a different
 *       mapping from its bars would put a mean of 72.5 in the wrong bucket.</li>
 *   <li><b>Percentages divide by the participant count</b>, which is the stored figure the
 *       stat cards above the chart print, so the two can never disagree.</li>
 * </ol>
 *
 * <h2>Empty is not the same as insufficient</h2>
 *
 * <p>Nobody sat it ({@link State#EMPTY}) and one person sat it ({@link State#INSUFFICIENT})
 * are different sentences to a teacher, and neither of them is a chart. A distribution, a mean
 * and a σ over a single score are all technically computable and all meaningless; drawing them
 * would invite a reading nothing supports. The threshold is {@value #MIN_PARTICIPANTS}.
 *
 * <p>FX-free on purpose, like {@link CountdownLogic}: the geometry, the scaling, the clamping
 * and the copy are all unit tested with plain numbers, and {@code StatChart} is left with
 * nothing to get wrong but where it puts the nodes.
 */
public final class StatChartLogic {

    /** How much clear space sits above the tallest bar, as a fraction of its value. */
    public static final double HEADROOM_FRACTION = 0.15;

    /**
     * Fewer participants than this and there is nothing honest to chart (F9.2).
     *
     * <p>Two, not three: two scores already have a spread, a mean between them and a σ that
     * means something. One has none of those.
     */
    public static final int MIN_PARTICIPANTS = 2;

    /** Share of each bucket's slot left empty, so neighbouring bars read as separate. */
    public static final double BAR_GAP_FRACTION = 0.22;

    /** How many y-axis gridlines to aim for, before the step is rounded to a readable one. */
    public static final int TARGET_TICKS = 4;

    /** What the bars and the y axis are measuring right now. */
    public enum Scale {

        /** Bars are student counts; the axis is whole people. */
        COUNT("Students"),

        /** Bars are shares of the class; the axis is percent. */
        PERCENT("Share of class");

        private final String axisTitle;

        Scale(String axisTitle) {
            this.axisTitle = axisTitle;
        }

        /** @return the caption for the y axis in this scale. */
        public String axisTitle() {
            return axisTitle;
        }
    }

    /** What the chart should be showing at all. */
    public enum State {

        /** Nobody has a final score yet. */
        EMPTY,

        /** Somebody does, but too few to chart (fewer than {@value #MIN_PARTICIPANTS}). */
        INSUFFICIENT,

        /** There is a distribution worth drawing. */
        READY
    }

    /**
     * One bar, positioned and measured inside the plot area.
     *
     * @param index      the decile, 0..9
     * @param x          left edge, in plot-local pixels
     * @param width      bar width in pixels, gaps already removed
     * @param y          top edge, in plot-local pixels (y grows downwards)
     * @param height     bar height in pixels; {@code 0} for an empty bucket
     * @param count      students in the bucket
     * @param percent    that count as a share of the class, 0..100
     * @param label      the bucket's axis label, "60-69"
     * @param tooltip    the hover sentence, "60-69 · 2 students · 25%"
     */
    public record Bar(int index, double x, double width, double y, double height,
                      int count, double percent, String label, String tooltip) {

        /** @return {@code true} when nobody scored in this range. */
        public boolean isEmpty() {
            return count == 0;
        }
    }

    /**
     * One horizontal gridline and its label.
     *
     * @param value the axis value it marks
     * @param y     its position in plot-local pixels
     * @param label the printed form, "3" or "30%"
     */
    public record Tick(double value, double y, String label) {
    }

    /**
     * A score interval on the 0..100 axis, already clamped into range.
     *
     * @param low  the lower score
     * @param high the upper score
     */
    public record Interval(double low, double high) {

        /** @return the interval's width in score points. */
        public double span() {
            return high - low;
        }
    }

    private final StatChartData data;

    /**
     * @param data the distribution and statistics to render
     * @throws NullPointerException when {@code data} is null
     */
    public StatChartLogic(StatChartData data) {
        this.data = Objects.requireNonNull(data, "data");
    }

    /** @return the input this logic is reading. */
    public StatChartData data() {
        return data;
    }

    // ===================== What to show at all ============================

    /**
     * The F9.2 empty / insufficient / ready decision, in one place so the view cannot
     * accidentally draw a chart of one score.
     *
     * @return which of the three the chart should render
     */
    public State state() {
        if (data.participantCount() <= 0) {
            return State.EMPTY;
        }
        return data.participantCount() < MIN_PARTICIPANTS ? State.INSUFFICIENT : State.READY;
    }

    /** @return {@code true} when there is a distribution worth drawing. */
    public boolean isChartable() {
        return state() == State.READY;
    }

    // ===================== The y axis =====================================

    /**
     * The top of the y axis: the tallest bar plus {@value #HEADROOM_FRACTION} of headroom,
     * rounded up to a whole tick so the topmost gridline is also the frame.
     *
     * <p>Never returns zero. A chart whose axis ran 0..0 would divide by it, and an execution
     * where every bucket is empty is an empty state rather than a flat chart anyway.
     *
     * @param scale whether the axis is counting students or percent
     * @return the axis maximum; the minimum is always {@code 0}
     */
    public double axisMax(Scale scale) {
        Objects.requireNonNull(scale, "scale");
        double tallest = tallestValue(scale);
        if (tallest <= 0) {
            return scale == Scale.COUNT ? 1 : 10;
        }
        double step = tickStep(scale);
        double rounded = Math.ceil(tallest * (1 + HEADROOM_FRACTION) / step) * step;
        // A percent axis genuinely ends at 100: a class where every score lands in one bucket
        // is 100%, and an axis running to 150% to preserve headroom would invent room that
        // does not exist. This is the one case where the tallest bar may touch the frame.
        if (scale == Scale.PERCENT) {
            rounded = Math.min(rounded, StatChartData.MAX_SCORE);
        }
        return Math.max(rounded, tallest);
    }

    /**
     * The horizontal gridlines, bottom value first.
     *
     * <p>The step comes from the same computation {@link #axisMax(Scale)} used, so the top
     * gridline is always the frame: deriving it a second time from the rounded maximum can
     * pick a coarser step and leave the axis labelled up to 10 on a chart that runs to 12.
     *
     * @param scale      the axis being labelled
     * @param plotHeight the plot area's height in pixels
     * @return {@code 0} through {@link #axisMax(Scale)} inclusive, one entry per tick
     */
    public List<Tick> yTicks(Scale scale, double plotHeight) {
        double max = axisMax(scale);
        double step = tickStep(scale);
        List<Tick> ticks = new ArrayList<>();
        // Accumulating with a counter rather than `value += step` so a fractional percent step
        // cannot drift past the maximum and produce a stray gridline outside the frame.
        int steps = (int) Math.round(max / step);
        for (int i = 0; i <= steps; i++) {
            double value = i * step;
            ticks.add(new Tick(value, yForValue(value, scale, plotHeight), tickLabel(value, scale)));
        }
        return List.copyOf(ticks);
    }

    /**
     * @param value      an axis value
     * @param scale      the axis it belongs to
     * @param plotHeight the plot area's height in pixels
     * @return its distance from the top of the plot, in pixels; {@code 0} is the baseline
     */
    public double yForValue(double value, Scale scale, double plotHeight) {
        double max = axisMax(scale);
        double fraction = max <= 0 ? 0 : clamp01(value / max);
        return plotHeight * (1 - fraction);
    }

    /** @return the printed form of an axis value, "3" or "30%". */
    public String tickLabel(double value, Scale scale) {
        Objects.requireNonNull(scale, "scale");
        return scale == Scale.COUNT ? number(value) : number(value) + "%";
    }

    // ===================== Counts and percentages =========================

    /**
     * @param count a bucket's student count
     * @return that count as a share of the class, 0..100; {@code 0} when nobody sat the exam
     */
    public double toPercent(int count) {
        if (data.participantCount() <= 0) {
            return 0;
        }
        return count * 100.0 / data.participantCount();
    }

    /**
     * The inverse of {@link #toPercent}, for reading a value back off the percent axis.
     *
     * @param percent a share of the class, 0..100
     * @return how many students that is, rounded to the nearest whole person
     */
    public int toCount(double percent) {
        if (data.participantCount() <= 0) {
            return 0;
        }
        return (int) Math.round(percent * data.participantCount() / 100.0);
    }

    /** @return the value of bucket {@code index} on the given scale. */
    public double valueOf(int index, Scale scale) {
        Objects.requireNonNull(scale, "scale");
        int count = data.countIn(index);
        return scale == Scale.COUNT ? count : toPercent(count);
    }

    // ===================== Bar geometry ===================================

    /**
     * Lays the ten bars out across the plot area.
     *
     * <p>Every bucket gets a bar, including the empty ones: a histogram with the empty ranges
     * left out is a different chart, and "nobody at all scored in the 30s" is exactly the
     * shape a teacher is looking for.
     *
     * @param plotWidth  the plot area's width in pixels
     * @param plotHeight the plot area's height in pixels
     * @param scale      what the bars are measuring
     * @return one bar per decile, left to right; empty when the width or height is not positive
     */
    public List<Bar> bars(double plotWidth, double plotHeight, Scale scale) {
        Objects.requireNonNull(scale, "scale");
        if (plotWidth <= 0 || plotHeight <= 0) {
            return List.of();
        }
        double slot = slotWidth(plotWidth);
        double barWidth = slot * (1 - BAR_GAP_FRACTION);
        double inset = (slot - barWidth) / 2;

        List<Bar> bars = new ArrayList<>(StatChartData.BUCKET_COUNT);
        for (int i = 0; i < StatChartData.BUCKET_COUNT; i++) {
            int count = data.countIn(i);
            double percent = toPercent(count);
            double value = scale == Scale.COUNT ? count : percent;
            double top = yForValue(value, scale, plotHeight);
            bars.add(new Bar(i,
                    i * slot + inset,
                    barWidth,
                    top,
                    plotHeight - top,
                    count,
                    percent,
                    bucketLabel(i),
                    tooltip(i)));
        }
        return List.copyOf(bars);
    }

    /** @return the width of one bucket's slot, gap included. */
    public double slotWidth(double plotWidth) {
        return plotWidth / StatChartData.BUCKET_COUNT;
    }

    // ===================== The score axis and its overlays ================

    /**
     * Maps a score onto the plot's horizontal ruler.
     *
     * <p>The same ruler the bars are laid out on: score 60 lands exactly on the left edge of
     * the 60-69 slot and score 100 on the right frame, which is what lets the mean marker be
     * read against the bars rather than merely drawn near them.
     *
     * @param score      a score, clamped into 0..100
     * @param plotWidth  the plot area's width in pixels
     * @return its x position in plot-local pixels
     */
    public double xForScore(double score, double plotWidth) {
        double clamped = clampScore(score);
        return plotWidth * clamped / StatChartData.MAX_SCORE;
    }

    /** @return the mean's x position on the score axis. */
    public double meanX(double plotWidth) {
        return xForScore(data.mean(), plotWidth);
    }

    /** @return the median's x position on the score axis. */
    public double medianX(double plotWidth) {
        return xForScore(data.median(), plotWidth);
    }

    /**
     * The ±1σ band, clamped into the axis.
     *
     * <p>Clamped rather than allowed to overflow: with a mean of 72.5 and σ of 17.5 the upper
     * end is 90, but a high mean and a wide spread routinely put it past 100, and a shaded
     * band running off the frame reads as a rendering fault. The clamp is honest because the
     * axis genuinely ends there — no score above 100 exists to be hidden.
     *
     * @return the interval {@code [mean - σ, mean + σ]} clamped to {@code [0, 100]}
     */
    public Interval sigmaBand() {
        return new Interval(clampScore(data.sigmaLow()), clampScore(data.sigmaHigh()));
    }

    /**
     * @param plotWidth the plot area's width in pixels
     * @return the ±1σ band as a left edge and a width in plot-local pixels
     */
    public Interval sigmaBandPixels(double plotWidth) {
        Interval band = sigmaBand();
        double left = xForScore(band.low(), plotWidth);
        double right = xForScore(band.high(), plotWidth);
        return new Interval(left, right - left);
    }

    /** @return the mean marker's label, "Mean 72.5". */
    public String meanLabel() {
        return "Mean " + number(data.mean());
    }

    /** @return the median marker's label, "Median 72.5". */
    public String medianLabel() {
        return "Median " + number(data.median());
    }

    /**
     * @return the σ band's label, "±1σ · 55 to 90" — the interval spelled out, because a
     *         shaded rectangle with no numbers on it is decoration rather than information
     */
    public String sigmaLabel() {
        Interval band = sigmaBand();
        return "±1σ · " + number(band.low()) + " to " + number(band.high());
    }

    // ===================== Copy ===========================================

    /**
     * @param index a decile, 0..9
     * @return its axis label; the top bucket reads "90-100" because it holds the perfect
     *         score, and labelling it "90-99" would be false
     */
    public static String bucketLabel(int index) {
        if (index < 0 || index >= StatChartData.BUCKET_COUNT) {
            throw new IndexOutOfBoundsException("Not a decile: " + index);
        }
        int low = index * 10;
        int high = index == StatChartData.BUCKET_COUNT - 1 ? StatChartData.MAX_SCORE : low + 9;
        return low + "-" + high;
    }

    /**
     * The hover sentence: range, how many, and what share of the class.
     *
     * <p>All three, because each answers a different question and the reader should not have
     * to do arithmetic against the axis to get the other two.
     *
     * @param index a decile, 0..9
     * @return "60-69 · 2 students · 25%", or "30-39 · nobody" for an empty bucket
     */
    public String tooltip(int index) {
        int count = data.countIn(index);
        if (count == 0) {
            return bucketLabel(index) + " · nobody";
        }
        return bucketLabel(index)
                + " · " + count + (count == 1 ? " student" : " students")
                + " · " + number(toPercent(count)) + "%";
    }

    /** @return the empty state's title, for an execution nobody has a score in yet. */
    public static String emptyTitle() {
        return "No results yet";
    }

    /** @return the empty state's explanation. */
    public static String emptyHint() {
        return "Scores appear here once this execution has been graded.";
    }

    /** @return the insufficient-data state's title. */
    public static String insufficientTitle() {
        return "Not enough results to chart";
    }

    /**
     * @param participants how many results there are
     * @return the insufficient-data explanation, which says what is missing rather than
     *         merely that something is
     */
    public static String insufficientHint(int participants) {
        return "One result cannot show a distribution. "
                + (participants == 1
                        ? "There is 1 graded attempt so far."
                        : "There are " + participants + " graded attempts so far.");
    }

    /**
     * @return the caption under the chart, "8 students · mean 72.5 · median 72.5 · σ 17.5"
     */
    public String summaryCaption() {
        StringBuilder text = new StringBuilder();
        text.append(data.participantCount())
                .append(data.participantCount() == 1 ? " student" : " students");
        if (isChartable()) {
            text.append(" · mean ").append(number(data.mean()))
                    .append(" · median ").append(number(data.median()))
                    .append(" · σ ").append(number(data.standardDeviation()));
        }
        return text.toString();
    }

    // ===================== Formatting =====================================

    /**
     * Formats a statistic for display: at most one decimal, and none at all when the value is
     * whole.
     *
     * <p>72.5 stays 72.5 and 90.0 prints as 90. Rounding 72.5 to 73 would contradict the stat
     * card above the chart; printing 90.0 would make an exact figure look computed.
     *
     * @param value the number
     * @return its display form
     */
    public static String number(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        if (rounded == Math.rint(rounded)) {
            return String.valueOf((long) Math.rint(rounded));
        }
        return String.format(Locale.ROOT, "%.1f", rounded);
    }

    // ===================== Internals ======================================

    /** @return the tallest bar's value on this scale, before headroom. */
    private double tallestValue(Scale scale) {
        return scale == Scale.COUNT ? data.tallestBucket() : toPercent(data.tallestBucket());
    }

    /**
     * A readable gridline step: 1, 2, 2.5 or 5 times a power of ten, whichever is the smallest
     * that keeps the tick count near {@link #TARGET_TICKS}.
     *
     * <p>Counts are restricted to whole-number steps, because "1.5 students" is not a quantity
     * an axis may claim exists. Percentages are not, because 2.5% is a real share.
     *
     * @param scale the axis being stepped
     * @return the distance between two gridlines, always positive
     */
    double tickStep(Scale scale) {
        Objects.requireNonNull(scale, "scale");
        double tallest = tallestValue(scale);
        if (tallest <= 0) {
            return scale == Scale.COUNT ? 1 : 10;
        }
        double raw = tallest * (1 + HEADROOM_FRACTION) / TARGET_TICKS;
        if (scale == Scale.COUNT) {
            for (long candidate : new long[]{1, 2, 5, 10, 20, 25, 50, 100}) {
                if (candidate >= raw) {
                    return candidate;
                }
            }
            return Math.ceil(raw / 100) * 100;
        }
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        for (double candidate : new double[]{1, 2, 2.5, 5, 10}) {
            double step = candidate * magnitude;
            if (step >= raw) {
                return step;
            }
        }
        return 10 * magnitude;
    }

    private static double clampScore(double score) {
        return Math.max(StatChartData.MIN_SCORE, Math.min(StatChartData.MAX_SCORE, score));
    }

    private static double clamp01(double fraction) {
        return Math.max(0, Math.min(1, fraction));
    }
}
