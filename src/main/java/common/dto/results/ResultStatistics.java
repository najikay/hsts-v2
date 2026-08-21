package common.dto.results;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * One execution's frozen statistics, on the wire (Common tier, E14.1 — F8.5, F9.2).
 *
 * <p>A one-for-one mapping of {@code server.features.grading.ScoreStatistics}, which is the
 * shape stored in {@code exam_executions.stats} when the last grade of an execution is
 * approved. Every component here is <b>read</b> from that column. Nothing in the results path
 * — not the server handler, not the stat cards, not the histogram — recomputes any of it.
 *
 * <h2>Why that is a rule and not a preference</h2>
 *
 * <ul>
 *   <li>{@code standardDeviation} is the <b>population</b> form, divisor {@code n}: an
 *       execution's participants are the whole population, not a sample of one. The seeded
 *       execution 4821 has σ = 17.5; the sample divisor gives 18.71. A screen recomputing it
 *       would disagree with the stored value by about a point, which reads as a rendering bug
 *       rather than as a convention (H14.4 ⚑).</li>
 *   <li>{@code passCount} and {@code passRate} use a pass mark of <b>55</b> with every scored
 *       attempt in the denominator, forced-submit zeros included. E14 renders the stored
 *       figure and never re-applies the threshold: a pass rate computed twice from two
 *       thresholds is the same class of bug as a σ from two divisors, and harder to spot
 *       because both numbers look reasonable.</li>
 *   <li>{@code deciles} is the distribution the histogram draws, unchanged. The chart never
 *       re-buckets: bars that binned raw scores themselves would eventually disagree with the
 *       stat cards printed directly above them.</li>
 * </ul>
 *
 * <p>{@code passRate} is a fraction in {@code [0, 1]}, not a percentage, so no reader has to
 * guess whether {@code 0.875} means 0.875% or 87.5%; {@code passCount} travels beside it so
 * the numerator is read rather than inferred by multiplying.
 *
 * @param count             graded attempts these statistics cover; the denominator of every
 *                          percentage on the screen, and the count the histogram divides by
 * @param mean              stored arithmetic average, 0..100
 * @param median            stored middle score, 0..100
 * @param standardDeviation stored population σ, divisor {@code count}; never negative
 * @param min               lowest final score awarded
 * @param max               highest final score awarded
 * @param passCount         how many attempts scored at least 55
 * @param passRate          {@code passCount / count} as a fraction in {@code [0, 1]}
 * @param deciles           exactly ten counts: index {@code i} is {@code [i*10, i*10+9]},
 *                          except index 9, which is {@code [90, 100]} so a perfect score lands
 *                          in the top bucket rather than in an eleventh one
 */
public record ResultStatistics(int count,
                               double mean,
                               double median,
                               double standardDeviation,
                               int min,
                               int max,
                               int passCount,
                               double passRate,
                               List<Integer> deciles) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The decile distribution is ten buckets wide, exactly as {@code ScoreStatistics} produces. */
    public static final int BUCKET_COUNT = 10;

    /**
     * The pass mark these figures were frozen with (F8.5). Declared so the screen can say
     * "pass mark 55" out loud without a second constant drifting from the server's.
     */
    public static final int PASS_MARK = 55;

    /**
     * @throws NullPointerException     when {@code deciles} or one of its counts is null
     * @throws IllegalArgumentException when the distribution is not {@value #BUCKET_COUNT}
     *                                  buckets. A stored distribution of any other width is a
     *                                  server-side data fault, and failing here is what stops
     *                                  it reaching a chart that would draw it wrong
     */
    public ResultStatistics {
        Objects.requireNonNull(deciles, "deciles");
        if (deciles.size() != BUCKET_COUNT) {
            throw new IllegalArgumentException(
                    "A decile distribution has exactly " + BUCKET_COUNT + " buckets, got "
                            + deciles.size());
        }
        for (Integer bucket : deciles) {
            Objects.requireNonNull(bucket, "deciles contains a null count");
        }
        deciles = List.copyOf(deciles);
    }

    /** @return the pass rate as a percentage, for display only. */
    public double passPercent() {
        return passRate * 100;
    }

    /**
     * @return {@code true} when there are enough results for a histogram to mean anything.
     *         One score has no spread, no meaningful σ and a mean equal to itself; the chart
     *         renders its insufficient-data state for it rather than a single bar
     */
    public boolean isChartable() {
        return count >= 2;
    }
}
