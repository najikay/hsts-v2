package server.features.results;

import common.dto.results.ResultStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.entities.ExecutionStats;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FrozenStatistics} — the one place stored statistics become wire statistics (E14.1).
 *
 * <p>Two families of assertion. The first is that the mapping copies rather than computes: the
 * seeded execution's stored record must arrive on the wire with every figure identical, and the
 * two components the column does not store must be reconstituted from the stored ones rather
 * than from anything else. The second is that an unusable column produces the calm
 * "grading is not finished" state instead of an exception on a socket thread.
 */
class FrozenStatisticsTest {

    private static final long EXECUTION = 4821;

    /** §9.1's frozen record: finals 45, 55, 60, 70, 75, 85, 90, 100. */
    private static final ExecutionStats SEEDED = new ExecutionStats(
            72.5, 72.5, 17.5, 45, 100, 0.875, List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));

    @Test
    @DisplayName("⚑ every stored figure crosses unchanged")
    void copiesEveryStoredFigure() {
        ResultStatistics wire = FrozenStatistics.toWire(EXECUTION, SEEDED).orElseThrow();

        assertThat(wire.mean()).isEqualTo(72.5);
        assertThat(wire.median()).isEqualTo(72.5);
        assertThat(wire.standardDeviation())
                .as("population sigma, divisor n; the sample form would be 18.71")
                .isEqualTo(17.5);
        assertThat(wire.min()).isEqualTo(45);
        assertThat(wire.max()).isEqualTo(100);
        assertThat(wire.passRate()).isEqualTo(0.875);
        assertThat(wire.deciles()).containsExactly(0, 0, 0, 0, 1, 1, 1, 2, 1, 2);
    }

    @Test
    @DisplayName("the population is the distribution's own total")
    void countComesFromTheDeciles() {
        assertThat(FrozenStatistics.toWire(EXECUTION, SEEDED).orElseThrow().count()).isEqualTo(8);
    }

    @Test
    @DisplayName("the pass numerator is the stored rate against that population, never a re-count")
    void passCountIsReconstituted() {
        assertThat(FrozenStatistics.toWire(EXECUTION, SEEDED).orElseThrow().passCount())
                .isEqualTo(7);
    }

    @Test
    @DisplayName("a rate that does not divide evenly still yields a whole numerator")
    void passCountRoundsToAWholeStudent() {
        // 5 of 7 is 0.714285..., stored as a double. Multiplying back must land on 5.
        ExecutionStats sevenStudents = new ExecutionStats(66, 65, 12.4, 40, 95, 5 / 7.0,
                List.of(0, 0, 0, 0, 1, 1, 2, 2, 1, 0));

        ResultStatistics wire = FrozenStatistics.toWire(EXECUTION, sevenStudents).orElseThrow();

        assertThat(wire.count()).isEqualTo(7);
        assertThat(wire.passCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("no statistics at all is empty, not a record of zeros")
    void nullStoredRecord() {
        assertThat(FrozenStatistics.toWire(EXECUTION, null)).isEmpty();
    }

    @Test
    @DisplayName("a distribution that is not ten buckets is refused")
    void wrongBucketCount() {
        assertThat(FrozenStatistics.toWire(EXECUTION,
                new ExecutionStats(72.5, 72.5, 17.5, 45, 100, 0.875, List.of(1, 2, 3))))
                .isEmpty();
        assertThat(FrozenStatistics.toWire(EXECUTION,
                new ExecutionStats(72.5, 72.5, 17.5, 45, 100, 0.875, null)))
                .as("the entity normalises a null distribution to an empty list")
                .isEmpty();
    }

    @Test
    @DisplayName("a distribution accounting for nobody is refused")
    void emptyPopulation() {
        assertThat(FrozenStatistics.toWire(EXECUTION, new ExecutionStats(0, 0, 0, 0, 0, 0,
                List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0))))
                .isEmpty();
    }

    @Test
    @DisplayName("a negative bucket is refused rather than subtracted from the population")
    void negativeBucket() {
        assertThat(FrozenStatistics.toWire(EXECUTION, new ExecutionStats(72.5, 72.5, 17.5, 45, 100,
                0.875, List.of(0, 0, 0, 0, -1, 1, 1, 2, 1, 2))))
                .isEmpty();
    }

    @Test
    @DisplayName("a mean or median outside 0..100 is refused before it can reach the chart")
    void impossibleAverages() {
        assertThat(FrozenStatistics.toWire(EXECUTION, new ExecutionStats(140, 72.5, 17.5, 45, 100,
                0.875, List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2))))
                .isEmpty();
        assertThat(FrozenStatistics.toWire(EXECUTION, new ExecutionStats(72.5, -3, 17.5, 45, 100,
                0.875, List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2))))
                .isEmpty();
    }

    @Test
    @DisplayName("a negative or unreal sigma is refused")
    void impossibleSigma() {
        assertThat(FrozenStatistics.toWire(EXECUTION, new ExecutionStats(72.5, 72.5, -1, 45, 100,
                0.875, List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2))))
                .isEmpty();
        assertThat(FrozenStatistics.toWire(EXECUTION, new ExecutionStats(72.5, 72.5, Double.NaN,
                45, 100, 0.875, List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2))))
                .isEmpty();
    }

    @Test
    @DisplayName("a pass rate outside 0..1 is clamped rather than allowed to invent students")
    void ratesAreClamped() {
        Optional<ResultStatistics> tooHigh = FrozenStatistics.toWire(EXECUTION,
                new ExecutionStats(72.5, 72.5, 17.5, 45, 100, 1.4,
                        List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2)));
        Optional<ResultStatistics> negative = FrozenStatistics.toWire(EXECUTION,
                new ExecutionStats(72.5, 72.5, 17.5, 45, 100, -0.2,
                        List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2)));

        assertThat(tooHigh.orElseThrow().passCount())
                .as("never more passes than students")
                .isEqualTo(8);
        assertThat(tooHigh.orElseThrow().passRate()).isEqualTo(1);
        assertThat(negative.orElseThrow().passCount()).isZero();
        assertThat(negative.orElseThrow().passRate()).isZero();
    }
}
