package server.features.grading;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The statistics stored for one execution once it is fully graded (Logic tier, E12.4).
 *
 * <p>Implements the computation half of F8.5 / S-25: average, median, standard deviation,
 * min, max and the 0–100 decile distribution. It is deliberately a <b>pure function over
 * scores</b> — no entities, no repositories, no persistence. The service that loads the
 * grades and writes the result into {@code exam_executions.stats} wraps this; that
 * separation is what lets the numbers be unit-tested against a hand-computed fixture
 * (E12.4 is defence-critical) without a database.
 *
 * <p><b>Standard deviation is the POPULATION form</b> — divisor {@code n}, not
 * {@code n - 1}. An execution's participants <i>are</i> the whole population; nothing is
 * being estimated from a sample. Decided in the PR #2 review. For the seeded execution
 * 4821 this gives σ = √171 ≈ 13.08; the sample form would give ≈ 13.98, and a recomputation
 * reading high is a bug, not a rounding difference. E14 renders these values and must not
 * recompute them with a different divisor.
 *
 * <p><b>Pass rate is deliberately absent.</b> F8.5 and F9.2 both list it, but no passing
 * threshold is defined anywhere in the PRD, and neither is whether a timed-out attempt
 * scoring 0 belongs in the denominator. Guessing 55 or 60 here would bake an invisible
 * assumption into a stored, defence-critical number. It is added as one more component
 * once the threshold is decided — additive, and it changes nothing below.
 *
 * @param count             how many graded attempts the statistics cover
 * @param mean              arithmetic average of the final scores
 * @param median            middle score; the mean of the two middle values when count is even
 * @param standardDeviation population standard deviation (divisor {@code count})
 * @param min               lowest final score
 * @param max               highest final score
 * @param deciles           ten counts: index {@code i} holds scores in {@code [i*10, i*10+9]},
 *                          except index 9 which is {@code [90, 100]} so a perfect score lands
 *                          in the top bucket rather than an eleventh one
 */
public record ScoreStatistics(
        int count,
        double mean,
        double median,
        double standardDeviation,
        int min,
        int max,
        List<Integer> deciles) {

    /** Grades are 0..100 (`ck_grades_final_score`); anything else is a caller bug, not data. */
    private static final int MIN_SCORE = 0;
    private static final int MAX_SCORE = 100;

    private static final int DECILE_BUCKETS = 10;

    public ScoreStatistics {
        deciles = List.copyOf(deciles);
    }

    /**
     * Computes the statistics for one execution's final scores.
     *
     * <p>Returns {@link Optional#empty()} when there are no scores. That is not an error:
     * an execution nobody sat has no statistics, and F9.2 requires the UI to show an
     * insufficient-data state rather than zeros. Returning an Optional makes that case
     * impossible to forget at the call site — a {@code ScoreStatistics} full of zeros would
     * render as a real class that all scored 0.
     *
     * @param scores final scores of every graded attempt, each 0..100
     * @return the statistics, or empty when {@code scores} is empty
     * @throws NullPointerException     if {@code scores} or any element is null
     * @throws IllegalArgumentException if any score is outside 0..100
     */
    public static Optional<ScoreStatistics> of(List<Integer> scores) {
        if (scores == null) {
            throw new NullPointerException("scores");
        }
        if (scores.isEmpty()) {
            return Optional.empty();
        }

        List<Integer> sorted = new ArrayList<>(scores.size());
        for (Integer score : scores) {
            if (score == null) {
                throw new NullPointerException("scores contains a null score");
            }
            if (score < MIN_SCORE || score > MAX_SCORE) {
                throw new IllegalArgumentException(
                        "Score out of range 0..100: " + score);
            }
            sorted.add(score);
        }
        Collections.sort(sorted);

        int count = sorted.size();
        double mean = mean(sorted);

        return Optional.of(new ScoreStatistics(
                count,
                mean,
                median(sorted),
                populationStandardDeviation(sorted, mean),
                sorted.get(0),
                sorted.get(count - 1),
                deciles(sorted)));
    }

    private static double mean(List<Integer> scores) {
        long total = 0;
        for (int score : scores) {
            total += score;
        }
        return (double) total / scores.size();
    }

    /** {@code sorted} must already be ascending. */
    private static double median(List<Integer> sorted) {
        int count = sorted.size();
        int middle = count / 2;
        if (count % 2 == 1) {
            return sorted.get(middle);
        }
        // Average the two middle values — 8 scores with 78 and 83 in the middle give 80.5,
        // so the median is a double even though every score is an integer.
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    /** Divisor is {@code n} — see the class Javadoc on why this is not the sample form. */
    private static double populationStandardDeviation(List<Integer> scores, double mean) {
        double sumOfSquares = 0;
        for (int score : scores) {
            double deviation = score - mean;
            sumOfSquares += deviation * deviation;
        }
        return Math.sqrt(sumOfSquares / scores.size());
    }

    private static List<Integer> deciles(List<Integer> scores) {
        int[] buckets = new int[DECILE_BUCKETS];
        for (int score : scores) {
            // 100 would index an eleventh bucket; it belongs with the 90s.
            buckets[Math.min(score / 10, DECILE_BUCKETS - 1)]++;
        }
        List<Integer> distribution = new ArrayList<>(DECILE_BUCKETS);
        for (int bucket : buckets) {
            distribution.add(bucket);
        }
        return List.copyOf(distribution);
    }
}
