package server.features.results;

import common.dto.results.ResultStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.entities.ExecutionStats;

import java.util.List;
import java.util.Optional;

/**
 * The one place stored statistics become wire statistics (Logic tier, E14.1 — F8.5).
 *
 * <p><b>Nothing here computes a statistic.</b> Every number on the teacher's results screen
 * was frozen into {@code exam_executions.stats} when the execution's last grade was approved,
 * and this class copies those numbers across. That is the whole point of the class existing:
 * one mapping, in one file, with tests pinning it, so there is nowhere for a second σ or a
 * second pass mark to appear.
 *
 * <h2>The two components the column does not store</h2>
 *
 * <p>{@link ExecutionStats} keeps average, median, σ, min, max, pass <em>rate</em> and the
 * decile distribution. {@code ScoreStatistics} — the shape the wire mirrors — also carries
 * {@code count} and {@code passCount}, and those two are <b>reconstituted</b> here rather than
 * recalculated from the grades:
 *
 * <ul>
 *   <li>{@code count} is the sum of the deciles. The distribution is, by construction, one
 *       bucket per scored attempt, so its total <em>is</em> the population the other figures
 *       were computed over. Counting the grade rows instead would be a second source of truth,
 *       and it would drift the moment a grade was added after the freeze.</li>
 *   <li>{@code passCount} is {@code round(passRate × count)}. This is arithmetic on two stored
 *       numbers, not a recomputation: re-applying the pass mark of 55 to the rows travelling
 *       beside it is exactly the forbidden move (F8.5), because a threshold applied twice can
 *       disagree with itself while both answers look plausible. The rate stays authoritative
 *       and the count is only ever the same fact phrased as a numerator.</li>
 * </ul>
 *
 * <p>For the seeded execution 4821 that gives count 8 and passCount 7 from a stored rate of
 * 0.875, which is what the acceptance table expects to read as "7 of 8 (87.5%)".
 *
 * <h2>A malformed column is a calm screen and a loud log</h2>
 *
 * <p>A stored record whose distribution is not ten buckets, whose population is zero, or whose
 * mean lies outside 0..100 is a server-side data fault. It is answered with
 * {@link Optional#empty()} — the same "grading is not finished" state a genuinely unfrozen
 * execution gets — and an ERROR line naming the execution. The alternative, letting it through,
 * ends either in an exception on a socket thread or in a chart drawing something untrue; a
 * teacher deserves neither, and the log is where the fault should be found.
 *
 * <h2>Two callers, one mapping (E15.3)</h2>
 *
 * <p>Public rather than package-private since E15, because the principal's report engine reads
 * the same column for the same reason. It was tempting to give the reports package its own
 * three-line mapper - it is only a constructor call - and that is exactly how a second pass
 * mark or a second decile-width check gets into the build. The malformed-column rule, the
 * reconstituted {@code count}, and the {@code passCount} arithmetic are decisions, and they are
 * decisions this class was created to hold in one place. A report and a teacher's histogram
 * showing different figures for the same sitting is the failure both of them exist to prevent.
 */
public final class FrozenStatistics {

    private static final Logger log = LoggerFactory.getLogger(FrozenStatistics.class);

    private static final int MIN_SCORE = 0;
    private static final int MAX_SCORE = 100;

    private FrozenStatistics() {
        // static helper - no instances
    }

    /**
     * Maps one execution's stored statistics onto the wire.
     *
     * @param executionId the execution, for the log line when the column is unusable
     * @param stored      the stored statistics, or {@code null} when grading is unfinished
     * @return the wire statistics, or empty when there are none or the stored ones are
     *         unusable
     */
    public static Optional<ResultStatistics> toWire(long executionId, ExecutionStats stored) {
        if (stored == null) {
            return Optional.empty();
        }
        List<Integer> deciles = stored.deciles();
        if (deciles.size() != ResultStatistics.BUCKET_COUNT) {
            return unusable(executionId,
                    "its decile distribution has " + deciles.size() + " buckets, not "
                            + ResultStatistics.BUCKET_COUNT);
        }

        // Nulls cannot reach here: ExecutionStats copies its distribution with List.copyOf,
        // which rejects them. A negative count can, if the column was written by hand.
        int count = 0;
        for (int bucket : deciles) {
            if (bucket < 0) {
                return unusable(executionId, "a decile bucket is " + bucket);
            }
            count += bucket;
        }
        if (count == 0) {
            return unusable(executionId, "its distribution accounts for nobody");
        }
        if (isNotAScore(stored.average()) || isNotAScore(stored.median())) {
            return unusable(executionId,
                    "mean " + stored.average() + " / median " + stored.median()
                            + " is outside 0..100");
        }
        if (stored.stdDev() < 0 || Double.isNaN(stored.stdDev())) {
            return unusable(executionId, "sigma is " + stored.stdDev());
        }

        double passRate = clampRate(stored.passRate());
        int passCount = Math.min(count, Math.max(0, (int) Math.round(passRate * count)));

        return Optional.of(new ResultStatistics(
                count,
                stored.average(),
                stored.median(),
                stored.stdDev(),
                stored.min(),
                stored.max(),
                passCount,
                passRate,
                deciles));
    }

    private static Optional<ResultStatistics> unusable(long executionId, String why) {
        log.error("Execution {} has statistics that cannot be served: {}. "
                + "The screen will report grading as unfinished.", executionId, why);
        return Optional.empty();
    }

    private static boolean isNotAScore(double value) {
        return Double.isNaN(value) || value < MIN_SCORE || value > MAX_SCORE;
    }

    /** A rate outside 0..1 is meaningless; clamping keeps one bad column off every screen. */
    private static double clampRate(double rate) {
        if (Double.isNaN(rate)) {
            return 0;
        }
        return Math.min(1, Math.max(0, rate));
    }
}
