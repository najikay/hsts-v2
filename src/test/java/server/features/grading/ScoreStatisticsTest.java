package server.features.grading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link ScoreStatistics} — E12.4, which the TODO marks defence-critical with the words
 * "values unit-tested against hand-computed fixtures".
 *
 * <p>The fixture is the seed dataset, not an invention: execution <b>4821</b> in
 * {@code docs/seed/SEED_CONTENT.md} §9.1 is eight Algebra students whose statistics are
 * frozen in the seed as mean 72.5, median 72.5, σ 17.5. Those numbers are what the
 * histogram (F9.2) and the report engine (F9.4) display, so the arithmetic here and the
 * arithmetic in the seed document must agree exactly — if they ever drift, one of the two
 * is lying to the defence panel.
 *
 * <p>σ here is exact rather than rounded: √(2450/8) is precisely 17.5, so every figure in the
 * fixture is hand-checkable. The sample form would be √(2450/7) ≈ 18.71, and
 * {@link #sampleStandardDeviationWouldBeWrong()} exists purely to fail loudly if someone
 * "fixes" the divisor to {@code n - 1}.
 *
 * <p>These eight scores are also <b>reachable</b>: exam 1 v2 is six 15-point questions plus one
 * worth 10, and §9.1.1 gives the per-question selections that produce each of them. An earlier
 * seed used scores no combination of that exam could yield — fine as demo text, wrong the moment
 * {@code AutoGrader} recomputes it.
 */
class ScoreStatisticsTest {

    /** Seed §9.1, execution 4821 — the eight final scores, in the document's own order. */
    private static final List<Integer> SEEDED_4821 =
            List.of(100, 90, 85, 75, 70, 60, 55, 45);

    /** Σ(score − mean)² for {@link #SEEDED_4821}, computed by hand: 2450. */
    private static final double SEEDED_SUM_OF_SQUARES = 2450;

    private static ScoreStatistics compute(List<Integer> scores) {
        return ScoreStatistics.of(scores).orElseThrow();
    }

    @Nested
    @DisplayName("the seeded execution 4821 fixture")
    class SeededFixture {

        @Test
        @DisplayName("every statistic matches the value frozen in SEED_CONTENT.md §9.1")
        void matchesTheSeed() {
            ScoreStatistics stats = compute(SEEDED_4821);

            assertThat(stats.count()).isEqualTo(8);
            assertThat(stats.mean()).isEqualTo(72.5);
            assertThat(stats.median()).isEqualTo(72.5);
            assertThat(stats.standardDeviation()).isEqualTo(17.5);
            assertThat(stats.min()).isEqualTo(45);
            assertThat(stats.max()).isEqualTo(100);
        }

        @Test
        @DisplayName("pass rate is 7/8 — omer.katz timed out and is the one genuine fail")
        void passRateOfTheSeededFixture() {
            ScoreStatistics stats = compute(SEEDED_4821);

            assertThat(stats.passCount()).isEqualTo(7);
            assertThat(stats.passRate()).isEqualTo(0.875);
        }

        @Test
        @DisplayName("the seeded override moved yael.azulay from fail to pass: auto 51 -> final 55")
        void theOverrideChangedTheOutcome() {
            // Seed §9.1: her AUTO score was 45, below the mark; the justified override
            // to 55 (the mark exactly) is what makes her a pass. Grading on the auto
            // scores gives 6/8, so the override demo visibly changes the statistic.
            List<Integer> autoScores = List.of(100, 90, 85, 75, 70, 60, 45, 45);

            assertThat(compute(autoScores).passCount()).isEqualTo(6);
            assertThat(compute(SEEDED_4821).passCount()).isEqualTo(7);
        }

        @Test
        @DisplayName("σ is exactly √(2450/8) = 17.5 — the population form, divisor n")
        void populationStandardDeviation() {
            ScoreStatistics stats = compute(SEEDED_4821);

            double expected = Math.sqrt(SEEDED_SUM_OF_SQUARES / 8);
            assertThat(stats.standardDeviation()).isEqualTo(expected);
        }

        @Test
        @DisplayName("the sample form would give a different number — guard against a 'fix' to n-1")
        void sampleStandardDeviationWouldBeWrong() {
            ScoreStatistics stats = compute(SEEDED_4821);

            double sampleForm = Math.sqrt(SEEDED_SUM_OF_SQUARES / 7);
            assertThat(sampleForm).isCloseTo(18.71, org.assertj.core.data.Offset.offset(0.005));
            assertThat(stats.standardDeviation()).isNotEqualTo(sampleForm);
            assertThat(stats.standardDeviation()).isLessThan(sampleForm);
        }

        @Test
        @DisplayName("deciles match the seed: 40s:1 50s:1 60s:1 70s:2 80s:1 90s:2, six buckets populated")
        void decilesMatchTheSeed() {
            ScoreStatistics stats = compute(SEEDED_4821);

            // index: 0-9 10s 20s 30s 40s 50s 60s 70s 80s 90-100
            assertThat(stats.deciles())
                    .containsExactly(0, 0, 0, 0, 1, 1, 1, 2, 1, 2);
            assertThat(stats.deciles().stream().filter(count -> count > 0)).hasSize(6);
            assertThat(stats.deciles().stream().mapToInt(Integer::intValue).sum()).isEqualTo(8);
        }

        @Test
        @DisplayName("the order scores arrive in does not change any statistic")
        void orderIndependent() {
            List<Integer> shuffled = new ArrayList<>(SEEDED_4821);
            Collections.shuffle(shuffled);

            assertThat(compute(shuffled)).isEqualTo(compute(SEEDED_4821));
        }
    }

    @Nested
    @DisplayName("edge cases from the hardening plan")
    class EdgeCases {

        @Test
        @DisplayName("H14.1 — an execution nobody sat has no statistics, and that is not an error")
        void noParticipants() {
            assertThat(ScoreStatistics.of(List.of())).isEmpty();
        }

        @Test
        @DisplayName("H14.2 — a single participant: median equals the mean and σ is 0")
        void singleParticipant() {
            ScoreStatistics stats = compute(List.of(73));

            assertThat(stats.count()).isEqualTo(1);
            assertThat(stats.mean()).isEqualTo(73.0);
            assertThat(stats.median()).isEqualTo(73.0);
            assertThat(stats.standardDeviation()).isZero();
            assertThat(stats.min()).isEqualTo(73);
            assertThat(stats.max()).isEqualTo(73);
            assertThat(stats.passRate()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("H14.3 — every student scored the same: σ is 0 and one bucket holds them all")
        void identicalScores() {
            ScoreStatistics stats = compute(List.of(80, 80, 80, 80));

            assertThat(stats.mean()).isEqualTo(80.0);
            assertThat(stats.median()).isEqualTo(80.0);
            assertThat(stats.standardDeviation()).isZero();
            assertThat(stats.deciles()).containsExactly(0, 0, 0, 0, 0, 0, 0, 0, 4, 0);
        }

        @Test
        @DisplayName("the pass mark is inclusive: 55 passes, 54 fails")
        void passMarkBoundary() {
            assertThat(ScoreStatistics.PASS_MARK).isEqualTo(55);

            ScoreStatistics stats = compute(List.of(54, 55));

            assertThat(stats.passCount()).isEqualTo(1);
            assertThat(stats.passRate()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("forced-submit zeros stay in the denominator — they were sat and failed")
        void zerosCountInTheDenominator() {
            // Four attempts, two of them forced-submit zeros. Dropping the zeros would
            // report 100% and flatter the execution; the rule is every attempt with a
            // final score counts (F8.5).
            ScoreStatistics stats = compute(List.of(0, 0, 80, 90));

            assertThat(stats.count()).isEqualTo(4);
            assertThat(stats.passCount()).isEqualTo(2);
            assertThat(stats.passRate()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("nobody passing is 0.0, not an empty or undefined rate")
        void everybodyFailed() {
            ScoreStatistics stats = compute(List.of(10, 20, 54));

            assertThat(stats.passCount()).isZero();
            assertThat(stats.passRate()).isZero();
        }

        @Test
        @DisplayName("passRate is a fraction in [0,1], never a percentage")
        void passRateIsAFraction() {
            assertThat(compute(List.of(60, 60, 60, 10)).passRate()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("a timed-out attempt scoring 0 is a participant, not a missing row")
        void zeroScoreCounts() {
            ScoreStatistics stats = compute(List.of(0, 100));

            assertThat(stats.count()).isEqualTo(2);
            assertThat(stats.mean()).isEqualTo(50.0);
            assertThat(stats.min()).isZero();
            assertThat(stats.max()).isEqualTo(100);
            assertThat(stats.deciles().get(0)).isEqualTo(1);
            assertThat(stats.passCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a perfect 100 lands in the top decile, not an eleventh bucket")
        void perfectScoreBucketing() {
            ScoreStatistics stats = compute(List.of(90, 99, 100));

            assertThat(stats.deciles()).hasSize(10);
            assertThat(stats.deciles().get(9)).isEqualTo(3);
        }

        @Test
        @DisplayName("an odd count takes the middle value, not an average")
        void oddCountMedian() {
            assertThat(compute(List.of(10, 50, 90)).median()).isEqualTo(50.0);
        }
    }

    @Nested
    @DisplayName("input the caller should never send")
    class Guards {

        @Test
        @DisplayName("null list")
        void nullList() {
            assertThatNullPointerException().isThrownBy(() -> ScoreStatistics.of(null));
        }

        @Test
        @DisplayName("null score inside the list")
        void nullScore() {
            List<Integer> withNull = Arrays.asList(80, null, 90);
            assertThatNullPointerException().isThrownBy(() -> ScoreStatistics.of(withNull));
        }

        @Test
        @DisplayName("a score above 100 or below 0 is a caller bug — grades are CHECK-constrained 0..100")
        void outOfRange() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ScoreStatistics.of(List.of(80, 101)))
                    .withMessageContaining("101");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ScoreStatistics.of(List.of(-1, 80)))
                    .withMessageContaining("-1");
        }

        @Test
        @DisplayName("the deciles list is immutable — stored statistics must not be edited in place")
        void decilesAreImmutable() {
            ScoreStatistics stats = compute(SEEDED_4821);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> stats.deciles().set(0, 99));
        }
    }

    @Nested
    @DisplayName("the caller cannot silently lose the no-participants case")
    class OptionalContract {

        @Test
        @DisplayName("empty input returns empty, never a zero-filled ScoreStatistics")
        void emptyIsNotZeros() {
            Optional<ScoreStatistics> stats = ScoreStatistics.of(List.of());

            assertThat(stats).isEmpty();
            // A zero-filled record would render as "a class where everyone scored 0" (F9.2).
        }
    }
}
