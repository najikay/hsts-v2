package common.dto.report;

import common.dto.results.ResultStatistics;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a whole report says once, across all its rows (Common tier, E15.3 ⚑ — F9.4).
 *
 * <h2>The mean of the means is a lie, and this record exists to not tell it</h2>
 *
 * <p>The obvious summary of a comparison table is to average its columns: add the row means,
 * divide by the number of rows. It is wrong whenever the sittings differ in size, which is
 * always. Eight students averaging 72.5 and four averaging 65 do not average 68.75; they
 * average 70, because the first sitting contributed twice as many scores. The difference is
 * small, plausible, and permanent once printed, which is the worst combination a number on a
 * principal's report can have.
 *
 * <p>So every figure here is aggregated from what the rows actually carry:
 *
 * <ul>
 *   <li><b>{@code scored}</b> is the sum of the row populations, and equally the sum of the
 *       pooled {@link #deciles}. Those two agreeing is not a coincidence to be checked; the
 *       distribution is one bucket per scored attempt by construction, so pooling the buckets
 *       and summing the counts are the same arithmetic.</li>
 *   <li><b>{@code mean}</b> is participant-weighted: {@code Σ(mean_i × n_i) / Σn_i}. Each row's
 *       stored mean is exactly its own score total divided by its own population, so the
 *       weighted sum <em>is</em> the total of every score in the report, and this is the exact
 *       pooled mean rather than an approximation of it.</li>
 *   <li><b>{@code standardDeviation}</b> is the exact pooled <b>population</b> σ, recovered
 *       from the stored per-row σ and means:
 *       {@code σ² = Σ n_i(σ_i² + μ_i²) / N − μ²}. That identity holds because
 *       {@code n_i(σ_i² + μ_i²)} is the sum of squares of row {@code i}, and sums of squares
 *       add. The divisor stays {@code n} at every step, so the report's σ and the row σ it sits
 *       under are the same convention (H14.4 ⚑); mixing in a sample divisor here would shift
 *       the summary by about a point against rows that had not moved.</li>
 *   <li><b>{@code passCount}</b> is the sum of the stored per-row pass counts and
 *       {@code passRate} is that over {@code scored}. The pass mark of 55 is applied nowhere in
 *       this class. Re-applying it to anything would be the forbidden move (F8.5), and it is
 *       also unnecessary: the numerator is stored.</li>
 *   <li><b>{@code min} / {@code max}</b> are the extremes of the stored extremes, which is
 *       exact for the same reason a minimum of minima is.</li>
 * </ul>
 *
 * <h2>Why there is no pooled median, and a bucket instead</h2>
 *
 * <p>A median cannot be recovered from medians. Two sittings with medians 60 and 80 can have a
 * combined median anywhere between them depending on the shape of each, and averaging the two
 * would produce a number with no referent — the same class of dishonesty as the mean of means,
 * but without even an approximate defence. What the rows <em>do</em> carry is the pooled
 * distribution, and that pins the median to a band: {@link #medianBucket} is the decile the
 * middle score falls in, read off the pooled buckets.
 *
 * <p>Concretely it is the bucket holding the {@code ⌈scored / 2⌉}-th lowest score. For an odd
 * population that is the median itself; for an even one it is the lower of the two middle
 * scores, and the screen prints the band rather than a point. Saying "the middle score is in
 * the 70s" is a claim the stored data supports. Saying "the median is 71.3" is not.
 *
 * @param executions        how many sittings this report compares
 * @param participants      how many students sat them in total, attempts rather than grades
 * @param scored            how many marked papers the figures below cover
 * @param mean              the participant-weighted mean, exact
 * @param standardDeviation the pooled population σ, exact, divisor {@code scored}
 * @param medianBucket      the decile the middle score falls in, or {@link #NO_MEDIAN_BUCKET}
 *                          when there is nothing to take a middle of
 * @param min               the lowest score in any of the sittings
 * @param max               the highest score in any of the sittings
 * @param passCount         how many of the {@code scored} papers reached the stored pass mark
 * @param passRate          {@code passCount / scored} as a fraction in {@code [0, 1]}
 * @param deciles           the pooled distribution, exactly ten buckets, summing to
 *                          {@code scored}
 */
public record ReportSummary(int executions,
                            int participants,
                            int scored,
                            double mean,
                            double standardDeviation,
                            int medianBucket,
                            int min,
                            int max,
                            int passCount,
                            double passRate,
                            List<Integer> deciles) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The pooled distribution is ten buckets wide, exactly as every row's is. */
    public static final int BUCKET_COUNT = ResultStatistics.BUCKET_COUNT;

    /** What {@link #medianBucket} is when no score exists to be in the middle of. */
    public static final int NO_MEDIAN_BUCKET = -1;

    /**
     * The summary of nothing: a subject with no reportable sitting.
     *
     * <p>Ten zero buckets rather than an empty list, so a reader that walks the distribution
     * needs no special case, and {@link #isEmpty()} rather than a zero mean is what the screen
     * branches on. A mean of 0.0 for a teacher whose exams have never closed would be a
     * statement about her classes, and it would be false.
     */
    public static final ReportSummary EMPTY = new ReportSummary(0, 0, 0, 0, 0,
            NO_MEDIAN_BUCKET, 0, 0, 0, 0, List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));

    /**
     * @throws NullPointerException     when {@code deciles} is null
     * @throws IllegalArgumentException when the distribution is not {@value #BUCKET_COUNT}
     *                                  buckets wide
     */
    public ReportSummary {
        Objects.requireNonNull(deciles, "deciles");
        if (deciles.size() != BUCKET_COUNT) {
            throw new IllegalArgumentException(
                    "A pooled distribution has exactly " + BUCKET_COUNT + " buckets, got "
                            + deciles.size());
        }
        deciles = List.copyOf(deciles);
    }

    /**
     * Aggregates a report's rows into its one summary.
     *
     * <p>The whole of the arithmetic documented above, in one place, so the client and the
     * server cannot hold two versions of it. Every input is a number that was frozen when a
     * sitting's last grade was approved; this method adds, multiplies and divides those, and
     * touches no grade row and no score.
     *
     * @param rows the report's rows, in any order; an empty list gives {@link #EMPTY}
     * @return the cross-row summary
     * @throws NullPointerException when {@code rows} or one of them is null
     */
    public static ReportSummary across(List<ReportRow> rows) {
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty()) {
            return EMPTY;
        }

        int participants = 0;
        int scored = 0;
        int passCount = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        double weightedScoreTotal = 0;
        double sumOfSquares = 0;
        List<Integer> pooled = new ArrayList<>(BUCKET_COUNT);
        for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
            pooled.add(0);
        }

        for (ReportRow row : rows) {
            ResultStatistics stats = Objects.requireNonNull(row, "rows contains a null row")
                    .statistics();
            int population = stats.count();
            participants += row.participants();
            scored += population;
            passCount += stats.passCount();
            min = Math.min(min, stats.min());
            max = Math.max(max, stats.max());
            weightedScoreTotal += stats.mean() * population;
            // n(σ² + μ²) is this sitting's sum of squares. Sums of squares add; standard
            // deviations do not, which is the reason this line is not an average of sigmas.
            double sigma = stats.standardDeviation();
            sumOfSquares += population * (sigma * sigma + stats.mean() * stats.mean());
            for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
                pooled.set(bucket, pooled.get(bucket) + stats.deciles().get(bucket));
            }
        }

        if (scored == 0) {
            // Rows exist but nobody was marked in any of them. There is a count of sittings to
            // report and nothing else; inventing a mean of zero would be worse than saying so.
            return new ReportSummary(rows.size(), participants, 0, 0, 0, NO_MEDIAN_BUCKET,
                    0, 0, 0, 0, pooled);
        }

        double mean = weightedScoreTotal / scored;
        // Clamped at zero: the identity is exact in real arithmetic, and in floating point a
        // report of identical scores can land a few ulps below it. A negative variance would
        // become NaN under the square root and print as a blank cell nobody could explain.
        double variance = Math.max(0, sumOfSquares / scored - mean * mean);

        return new ReportSummary(rows.size(), participants, scored, mean, Math.sqrt(variance),
                medianBucketOf(pooled, scored), min, max, passCount,
                (double) passCount / scored, pooled);
    }

    /**
     * @param pooled the pooled distribution
     * @param scored how many scores it accounts for
     * @return the bucket holding the {@code ⌈scored / 2⌉}-th lowest score
     */
    private static int medianBucketOf(List<Integer> pooled, int scored) {
        int middle = (scored + 1) / 2;
        int seen = 0;
        for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
            seen += pooled.get(bucket);
            if (seen >= middle) {
                return bucket;
            }
        }
        // Unreachable while the buckets sum to `scored`, which they do by construction. Falling
        // back to the top bucket rather than throwing keeps one malformed stored record off a
        // stack trace on a socket thread.
        return BUCKET_COUNT - 1;
    }

    /** @return {@code true} when this subject has no sitting to compare. */
    public boolean isEmpty() {
        return executions == 0;
    }

    /**
     * @return {@code true} when there is exactly one sitting. A comparison of one is a valid
     *         answer and a different sentence from a comparison of none (E15.5), and the screen
     *         says so rather than drawing a trend through a single point
     */
    public boolean isSingleExecution() {
        return executions == 1;
    }

    /** @return the pass rate as a percentage, for display only. */
    public double passPercent() {
        return passRate * 100;
    }

    /**
     * @return how many students sat these sittings without a marked paper behind the figures;
     *         never negative
     */
    public int unmarked() {
        return Math.max(0, participants - scored);
    }
}
