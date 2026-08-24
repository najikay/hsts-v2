package client.ui.components.logic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@link SparkbarSpec} — the ten-bar distribution on the teacher's last-closed
 * card (UI wave 2).
 *
 * <p>Two decisions here are worth more than they look. The first is what counts
 * as the mode: an accent bar tells the reader "this is where the class landed",
 * and a flat distribution has no such place, so saying it anyway would be the
 * card inventing a story from noise. The second is that a bucket with one
 * student in it must not round to nothing — one student and no students are
 * different facts about a class.
 */
class SparkbarSpecTest {

    private static List<Integer> deciles(int... counts) {
        List<Integer> list = new ArrayList<>();
        for (int count : counts) {
            list.add(count);
        }
        return list;
    }

    @Test
    @DisplayName("one bar per stored bucket, in order")
    void tenBucketsTenBars() {
        List<SparkbarSpec> bars = SparkbarSpec.of(deciles(0, 0, 1, 2, 3, 4, 3, 2, 1, 0));

        assertThat(bars).hasSize(10);
        assertThat(bars).extracting(SparkbarSpec::index)
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(bars).extracting(SparkbarSpec::count)
                .containsExactly(0, 0, 1, 2, 3, 4, 3, 2, 1, 0);
    }

    @Test
    @DisplayName("heights are relative to the tallest bar")
    void heightsAreRelative() {
        List<SparkbarSpec> bars = SparkbarSpec.of(deciles(0, 0, 0, 0, 0, 2, 4, 0, 0, 0));

        assertThat(bars.get(6).fraction()).isEqualTo(1.0);
        assertThat(bars.get(5).fraction()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("⚑ one student is visible; none is not")
    void aSingleStudentStillGetsABar() {
        // 1 out of 40 is 2.5% of the tallest bar, which rounds to a bar a
        // reader cannot see. Empty and nearly-empty must stay different.
        List<SparkbarSpec> bars = SparkbarSpec.of(deciles(1, 0, 0, 0, 0, 0, 0, 40, 0, 0));

        assertThat(bars.get(0).fraction())
                .isGreaterThanOrEqualTo(SparkbarSpec.MINIMUM_VISIBLE);
        assertThat(bars.get(1).fraction()).isZero();
        assertThat(bars.get(1).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("the fullest bucket is the mode")
    void theFullestIsTheMode() {
        List<SparkbarSpec> bars = SparkbarSpec.of(deciles(0, 0, 1, 2, 3, 9, 3, 2, 1, 0));

        assertThat(bars).filteredOn(SparkbarSpec::modal).extracting(SparkbarSpec::index)
                .containsExactly(5);
    }

    @Test
    @DisplayName("⚑ two buckets tied at the top are not a mode")
    void aTieIsNotAMode() {
        // An accent bar says "this is where the class landed". Painting two of
        // them says it twice about a distribution that does not say it at all.
        List<SparkbarSpec> bars = SparkbarSpec.of(deciles(0, 0, 0, 0, 5, 5, 0, 0, 0, 0));

        assertThat(bars).noneMatch(SparkbarSpec::modal);
    }

    @Test
    @DisplayName("a sitting nobody sat has no mode and no heights")
    void allZeroIsFlat() {
        List<SparkbarSpec> bars = SparkbarSpec.of(deciles(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));

        assertThat(bars).noneMatch(SparkbarSpec::modal);
        assertThat(bars).allSatisfy(bar -> assertThat(bar.fraction()).isZero());
    }

    @Test
    @DisplayName("a null bucket is treated as empty rather than throwing on a dashboard")
    void nullBucketsAreSurvivable() {
        List<Integer> withNull = Arrays.asList(1, null, 2, 0, 0, 0, 0, 0, 0, 0);

        assertThat(SparkbarSpec.of(withNull).get(1).count()).isZero();
    }

    @Test
    @DisplayName("bands are labelled the way the server bucketed them, 90 to 100 inclusive")
    void theLastBandReachesAHundred() {
        List<SparkbarSpec> bars = SparkbarSpec.of(deciles(1, 0, 0, 0, 0, 0, 0, 0, 0, 0));

        assertThat(bars.get(0).rangeLabel()).isEqualTo("0 to 9");
        assertThat(bars.get(9).rangeLabel()).isEqualTo("90 to 100");
    }

    @Test
    @DisplayName("a negative index is a programming error and says so")
    void negativeIndexIsRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new SparkbarSpec(-1, 0, 0, false));
    }

    @Test
    @DisplayName("a fraction outside the scale is clamped rather than drawn off the card")
    void fractionsAreClamped() {
        assertThat(new SparkbarSpec(0, 1, 4.2, false).fraction()).isEqualTo(1);
        assertThat(new SparkbarSpec(0, 1, -2, false).fraction()).isZero();
    }
}
