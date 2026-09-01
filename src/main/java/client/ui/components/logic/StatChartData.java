package client.ui.components.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything the score histogram draws, and nothing else (Presentation tier, E14.3 — F9.2).
 *
 * <p>A toolkit-free value class: no JavaFX, no wire types, no repositories. It exists so the
 * chart can be built from three different directions — the teacher's results screen (E14.3b),
 * the report engine (F9.4) and the design gallery — without any of them agreeing on a DTO,
 * and so {@link StatChartLogic} can be unit tested against hand-computed fixtures.
 *
 * <h2>The buckets are the server's deciles, unchanged</h2>
 *
 * <p>{@code buckets} is the ten-element decile distribution
 * {@code server.features.grading.ScoreStatistics#deciles()} computes and stores: index
 * {@code i} holds the scores in {@code [i*10, i*10+9]}, <b>except index 9</b>, which is
 * {@code [90, 100]} so a perfect score lands in the top bar rather than in an eleventh one.
 * The chart never re-buckets and never recomputes: F8.5's numbers are frozen on the
 * execution, and a histogram that binned the raw scores itself would eventually disagree with
 * the stat cards printed directly above it.
 *
 * <p>The same rule governs {@link #mean}, {@link #median} and {@link #standardDeviation}: they
 * are <b>read</b> from the stored statistics, never derived here. σ is the <b>population</b>
 * form (divisor {@code n}) because that is what the server stored; recomputing it with the
 * sample divisor would move the ±1σ band by about a point and look like a rendering bug.
 *
 * <h2>How E14 builds one (Member B)</h2>
 *
 * <p>The teacher's results screen receives the execution's frozen statistics on the grading
 * wire and maps them straight across:
 *
 * <pre>{@code
 * // `stats` is the execution's stored statistics as they arrive on the wire.
 * StatChartData data = StatChartData.of(
 *         stats.deciles(),            // List<Integer>, exactly ten counts
 *         stats.mean(),
 *         stats.median(),
 *         stats.standardDeviation(),  // population form, divisor n
 *         stats.count());             // participants with a final score
 * statChart.setData(data);
 * }</pre>
 *
 * <p>An execution nobody sat has no statistics at all (the server answers an empty Optional
 * rather than a record full of zeros), and that case is {@link #empty()} — which the chart
 * renders as "No results yet" rather than as ten bars of height zero.
 *
 * @param buckets           ten counts, one per decile, in ascending score order
 * @param mean              the stored arithmetic average, 0..100
 * @param median            the stored middle score, 0..100
 * @param standardDeviation the stored population σ, never negative
 * @param participantCount  how many attempts carry a final score; the denominator every
 *                          percentage on the chart is computed against
 */
public record StatChartData(List<Integer> buckets,
                            double mean,
                            double median,
                            double standardDeviation,
                            int participantCount,
                            Integer subjectScore) {

    /** The decile distribution is ten buckets wide, matching {@code ScoreStatistics}. */
    public static final int BUCKET_COUNT = 10;

    /** The lowest score the axis can show. Zero-based axes are not negotiable (F9.2). */
    public static final int MIN_SCORE = 0;

    /** The highest score the axis can show. */
    public static final int MAX_SCORE = 100;

    /**
     * @throws NullPointerException     when {@code buckets} or any element is null
     * @throws IllegalArgumentException when the distribution is not ten buckets, a count is
     *                                  negative, σ is negative, a statistic falls outside
     *                                  0..100, or the participant count is negative
     */
    public StatChartData {
        Objects.requireNonNull(buckets, "buckets");
        if (buckets.size() != BUCKET_COUNT) {
            throw new IllegalArgumentException(
                    "A decile distribution has exactly " + BUCKET_COUNT + " buckets, got "
                            + buckets.size());
        }
        List<Integer> copy = new ArrayList<>(BUCKET_COUNT);
        for (Integer count : buckets) {
            if (count == null) {
                throw new IllegalArgumentException("buckets contains a null count");
            }
            if (count < 0) {
                throw new IllegalArgumentException("A bucket cannot hold " + count + " students");
            }
            copy.add(count);
        }
        buckets = List.copyOf(copy);

        if (participantCount < 0) {
            throw new IllegalArgumentException("participantCount cannot be " + participantCount);
        }
        if (subjectScore != null && (subjectScore < MIN_SCORE || subjectScore > MAX_SCORE)) {
            throw new IllegalArgumentException("subjectScore outside 0..100: " + subjectScore);
        }
        if (standardDeviation < 0) {
            throw new IllegalArgumentException("standardDeviation cannot be " + standardDeviation);
        }
        requireScore(mean, "mean");
        requireScore(median, "median");
    }

    /**
     * The mapping E14's results screen uses.
     *
     * @param deciles           the execution's stored ten-bucket distribution
     * @param mean              the stored average
     * @param median            the stored median
     * @param standardDeviation the stored population σ
     * @param participantCount  attempts carrying a final score
     * @return the chart's input
     */
    public static StatChartData of(List<Integer> deciles, double mean, double median,
                                   double standardDeviation, int participantCount) {
        return new StatChartData(deciles, mean, median, standardDeviation, participantCount,
                null);
    }

    /**
     * The same data with one person's score marked on it (REPORTS A2: the principal's
     * by-student report). {@code null} marks nothing and is every other caller's value.
     */
    public StatChartData withSubjectScore(Integer score) {
        return new StatChartData(buckets, mean, median, standardDeviation, participantCount,
                score);
    }

    /**
     * @return the "nobody sat this yet" input: ten empty buckets and no participants. The
     *         chart renders it as an empty state, never as a flat row of zero bars
     */
    public static StatChartData empty() {
        return new StatChartData(List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0, 0, 0, 0, null);
    }

    /**
     * @param index a decile, 0..9
     * @return how many students scored inside it
     * @throws IndexOutOfBoundsException when {@code index} is not a decile
     */
    public int countIn(int index) {
        return buckets.get(index);
    }

    /**
     * @return the tallest bucket's count; {@code 0} when nobody sat the exam. This is what
     *         the y axis is scaled against, before headroom
     */
    public int tallestBucket() {
        int tallest = 0;
        for (int count : buckets) {
            tallest = Math.max(tallest, count);
        }
        return tallest;
    }

    /**
     * @return the counts added up. Normally equal to {@link #participantCount}; the two are
     *         kept separate because the participant count is the stored figure and is what
     *         percentages divide by, so a distribution that disagreed with it would show up
     *         in {@link #isConsistent()} rather than silently skewing every percentage
     */
    public int totalInBuckets() {
        int total = 0;
        for (int count : buckets) {
            total += count;
        }
        return total;
    }

    /** @return {@code true} when the buckets account for exactly the participants (a sanity aid). */
    public boolean isConsistent() {
        return totalInBuckets() == participantCount;
    }

    /** @return the ±1σ interval's lower end, before clamping — {@code mean - σ}. */
    public double sigmaLow() {
        return mean - standardDeviation;
    }

    /** @return the ±1σ interval's upper end, before clamping — {@code mean + σ}. */
    public double sigmaHigh() {
        return mean + standardDeviation;
    }

    private static void requireScore(double value, String name) {
        if (value < MIN_SCORE || value > MAX_SCORE) {
            throw new IllegalArgumentException(
                    name + " must be a score in " + MIN_SCORE + ".." + MAX_SCORE + ", got " + value);
        }
    }
}
