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
 * frozen in the seed as mean 78.0, median 80.5, σ 13.08. Those numbers are what the
 * histogram (F9.2) and the report engine (F9.4) display, so the arithmetic here and the
 * arithmetic in the seed document must agree exactly — if they ever drift, one of the two
 * is lying to the defence panel.
 *
 * <p>σ is checked against √171 rather than the rounded 13.08, because the rounding is a
 * display concern and the stored value is not rounded. √171 is the population form; the
 * sample form would be √(1368/7) ≈ 13.98, and {@link #sampleStandardDeviationWouldBeWrong()}
 * exists purely to fail loudly if someone "fixes" the divisor to {@code n - 1}.
 */
class ScoreStatisticsTest {

    /** Seed §9.1, execution 4821 — the eight final scores, in the document's own order. */
    private static final List<Integer> SEEDED_4821 =
            List.of(92, 78, 85, 64, 71, 96, 55, 83);

    /** Σ(score − mean)² for {@link #SEEDED_4821}, computed by hand: 1368. */
    private static final double SEEDED_SUM_OF_SQUARES = 1368;

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
            assertThat(stats.mean()).isEqualTo(78.0);
            assertThat(stats.median()).isEqualTo(80.5);
            assertThat(stats.standardDeviation()).isCloseTo(13.08, org.assertj.core.data.Offset.offset(0.005));
            assertThat(stats.min()).isEqualTo(55);
            assertThat(stats.max()).isEqualTo(96);
        }

        @Test
        @DisplayName("σ is exactly √(1368/8) — the population form, divisor n")
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
            assertThat(sampleForm).isCloseTo(13.98, org.assertj.core.data.Offset.offset(0.005));
            assertThat(stats.standardDeviation()).isNotEqualTo(sampleForm);
            assertThat(stats.standardDeviation()).isLessThan(sampleForm);
        }

        @Test
        @DisplayName("deciles match the seed: 50s:1 60s:1 70s:2 80s:2 90s:2, five buckets populated")
        void decilesMatchTheSeed() {
            ScoreStatistics stats = compute(SEEDED_4821);

            // index: 0-9 10s 20s 30s 40s 50s 60s 70s 80s 90-100
            assertThat(stats.deciles())
                    .containsExactly(0, 0, 0, 0, 0, 1, 1, 2, 2, 2);
            assertThat(stats.deciles().stream().filter(count -> count > 0)).hasSize(5);
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
        @DisplayName("a timed-out attempt scoring 0 is a participant, not a missing row")
        void zeroScoreCounts() {
            ScoreStatistics stats = compute(List.of(0, 100));

            assertThat(stats.count()).isEqualTo(2);
            assertThat(stats.mean()).isEqualTo(50.0);
            assertThat(stats.min()).isZero();
            assertThat(stats.max()).isEqualTo(100);
            assertThat(stats.deciles().get(0)).isEqualTo(1);
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
