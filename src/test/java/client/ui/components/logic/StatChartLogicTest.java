package client.ui.components.logic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link StatChartData} and {@link StatChartLogic} — the score histogram's whole brain
 * (E14.3 — F9.2).
 *
 * <p>The fixture is the <b>seeded</b> execution 1 from {@code docs/seed/SEED_CONTENT.md} §9.1,
 * not an invented one: final scores 45, 55, 60, 70, 75, 85, 90, 100, which the server freezes
 * as mean 72.5, median 72.5, population σ 17.5, deciles
 * {@code [0,0,0,0,1,1,1,2,1,2]} over 8 participants. Every number below is therefore
 * hand-checkable against the seed document, which is what makes this a regression test for the
 * chart rather than a restatement of its implementation.
 *
 * <p>The honesty rules get their own nested class, because they are the requirement: v1's
 * statistics view failed its defence on a truncated axis, and "the axis starts at zero and has
 * headroom" is the assertion that keeps this one from repeating it.
 */
class StatChartLogicTest {

    /** The frozen decile distribution of the seeded, fully graded execution. */
    private static final List<Integer> SEEDED_DECILES = List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2);

    private static StatChartData seeded() {
        return StatChartData.of(SEEDED_DECILES, 72.5, 72.5, 17.5, 8);
    }

    private static StatChartLogic logic() {
        return new StatChartLogic(seeded());
    }

    // ===================== StatChartData =================================

    @Nested
    @DisplayName("StatChartData")
    class Data {

        @Test
        @DisplayName("carries the seeded execution's frozen statistics unchanged")
        void carriesTheSeededStatistics() {
            StatChartData data = seeded();

            assertThat(data.buckets()).containsExactly(0, 0, 0, 0, 1, 1, 1, 2, 1, 2);
            assertThat(data.mean()).isEqualTo(72.5);
            assertThat(data.median()).isEqualTo(72.5);
            assertThat(data.standardDeviation()).isEqualTo(17.5);
            assertThat(data.participantCount()).isEqualTo(8);
        }

        @Test
        @DisplayName("the buckets account for exactly the participants")
        void bucketsAndParticipantsAgree() {
            assertThat(seeded().totalInBuckets()).isEqualTo(8);
            assertThat(seeded().isConsistent()).isTrue();
        }

        @Test
        @DisplayName("an inconsistent distribution is reported rather than silently skewing percentages")
        void inconsistencyIsVisible() {
            StatChartData wrong = StatChartData.of(SEEDED_DECILES, 72.5, 72.5, 17.5, 9);

            assertThat(wrong.isConsistent()).isFalse();
        }

        @Test
        @DisplayName("the tallest bucket is what the axis is scaled against")
        void tallestBucket() {
            assertThat(seeded().tallestBucket()).isEqualTo(2);
            assertThat(StatChartData.empty().tallestBucket()).isZero();
        }

        @Test
        @DisplayName("countIn reads one decile")
        void countIn() {
            assertThat(seeded().countIn(0)).isZero();
            assertThat(seeded().countIn(7)).isEqualTo(2);
            assertThat(seeded().countIn(9)).isEqualTo(2);
        }

        @Test
        @DisplayName("the sigma interval is exposed unclamped, so the clamping is the logic's decision")
        void sigmaEndsAreRaw() {
            assertThat(seeded().sigmaLow()).isEqualTo(55.0);
            assertThat(seeded().sigmaHigh()).isEqualTo(90.0);
        }

        @Test
        @DisplayName("empty() is ten empty buckets and no participants")
        void emptyIsEmpty() {
            StatChartData empty = StatChartData.empty();

            assertThat(empty.buckets()).hasSize(10).containsOnly(0);
            assertThat(empty.participantCount()).isZero();
            assertThat(empty.totalInBuckets()).isZero();
        }

        @Test
        @DisplayName("the bucket list is copied, so a caller's later mutation cannot reach the chart")
        void bucketsAreCopied() {
            List<Integer> mutable = new java.util.ArrayList<>(SEEDED_DECILES);
            StatChartData data = StatChartData.of(mutable, 72.5, 72.5, 17.5, 8);

            mutable.set(0, 99);

            assertThat(data.countIn(0)).isZero();
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 9, 11, 20})
        @DisplayName("a distribution that is not ten buckets is rejected at construction")
        void wrongBucketCountIsRejected(int size) {
            List<Integer> wrong = java.util.Collections.nCopies(size, 0);

            assertThatThrownBy(() -> StatChartData.of(wrong, 0, 0, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly 10 buckets");
        }

        @Test
        @DisplayName("null buckets, null counts and negative counts are all rejected")
        void malformedBucketsAreRejected() {
            assertThatThrownBy(() -> StatChartData.of(null, 0, 0, 0, 0))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> StatChartData.of(
                    java.util.Arrays.asList(0, 0, 0, 0, 0, 0, 0, 0, 0, null), 0, 0, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null count");
            assertThatThrownBy(() -> StatChartData.of(
                    List.of(-1, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0, 0, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot hold");
        }

        @Test
        @DisplayName("a statistic outside 0..100, a negative sigma or a negative headcount is rejected")
        void impossibleStatisticsAreRejected() {
            assertThatThrownBy(() -> StatChartData.of(SEEDED_DECILES, 101, 50, 1, 8))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mean");
            assertThatThrownBy(() -> StatChartData.of(SEEDED_DECILES, 50, -1, 1, 8))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("median");
            assertThatThrownBy(() -> StatChartData.of(SEEDED_DECILES, 50, 50, -0.5, 8))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("standardDeviation");
            assertThatThrownBy(() -> StatChartData.of(SEEDED_DECILES, 50, 50, 1, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("participantCount");
        }
    }

    // ===================== Empty / insufficient / ready ===================

    @Nested
    @DisplayName("the three states")
    class States {

        @Test
        @DisplayName("nobody graded yet is EMPTY, not a chart of zeros")
        void noParticipantsIsEmpty() {
            StatChartLogic empty = new StatChartLogic(StatChartData.empty());

            assertThat(empty.state()).isEqualTo(StatChartLogic.State.EMPTY);
            assertThat(empty.isChartable()).isFalse();
        }

        @Test
        @DisplayName("exactly one result is INSUFFICIENT: a distribution of one is meaningless")
        void oneParticipantIsInsufficient() {
            StatChartLogic single = new StatChartLogic(StatChartData.of(
                    List.of(0, 0, 0, 0, 0, 0, 0, 1, 0, 0), 75, 75, 0, 1));

            assertThat(single.state()).isEqualTo(StatChartLogic.State.INSUFFICIENT);
            assertThat(single.isChartable()).isFalse();
        }

        @Test
        @DisplayName("two results are enough to chart — that is the threshold")
        void twoParticipantsAreEnough() {
            StatChartLogic pair = new StatChartLogic(StatChartData.of(
                    List.of(0, 0, 0, 0, 0, 1, 0, 1, 0, 0), 62.5, 62.5, 12.5, 2));

            assertThat(pair.state()).isEqualTo(StatChartLogic.State.READY);
            assertThat(pair.isChartable()).isTrue();
        }

        @Test
        @DisplayName("the seeded execution is chartable")
        void seededIsReady() {
            assertThat(logic().state()).isEqualTo(StatChartLogic.State.READY);
        }

        @Test
        @DisplayName("the state copy names what is missing rather than only that something is")
        void stateCopyIsSpecific() {
            assertThat(StatChartLogic.emptyTitle()).isEqualTo("No results yet");
            assertThat(StatChartLogic.emptyHint()).contains("graded");
            assertThat(StatChartLogic.insufficientTitle()).isEqualTo("Not enough results to chart");
            assertThat(StatChartLogic.insufficientHint(1)).contains("1 graded attempt");
            assertThat(StatChartLogic.insufficientHint(0)).contains("0 graded attempts");
        }
    }

    // ===================== The honesty rules ==============================

    @Nested
    @DisplayName("honest axes (the v1 regression)")
    class Honesty {

        @Test
        @DisplayName("the y axis starts at zero, on both scales")
        void axisIsZeroBased() {
            for (StatChartLogic.Scale scale : StatChartLogic.Scale.values()) {
                List<StatChartLogic.Tick> ticks = logic().yTicks(scale, 200);

                assertThat(ticks.get(0).value())
                        .as("the lowest gridline on the %s axis", scale)
                        .isZero();
            }
        }

        @Test
        @DisplayName("the baseline is the bottom of the plot: value 0 maps to the full height")
        void zeroSitsOnTheBaseline() {
            assertThat(logic().yForValue(0, StatChartLogic.Scale.COUNT, 200)).isEqualTo(200);
        }

        @Test
        @DisplayName("the tallest bar never touches the frame — there is headroom above it")
        void thereIsHeadroom() {
            assertThat(logic().axisMax(StatChartLogic.Scale.COUNT))
                    .as("2 students, so the count axis must run past 2")
                    .isGreaterThan(2);
            assertThat(logic().axisMax(StatChartLogic.Scale.PERCENT))
                    .as("25%%, so the percent axis must run past 25")
                    .isGreaterThan(25);
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3, 4, 5, 8, 10, 17, 40, 99})
        @DisplayName("headroom survives every class size, and the axis never lands on the tallest bar")
        void headroomHoldsForEverySize(int tallest) {
            List<Integer> buckets = new java.util.ArrayList<>(
                    java.util.Collections.nCopies(10, 0));
            buckets.set(5, tallest);
            StatChartLogic sized = new StatChartLogic(
                    StatChartData.of(buckets, 55, 55, 5, tallest));

            assertThat(sized.axisMax(StatChartLogic.Scale.COUNT)).isGreaterThan(tallest);
        }

        @Test
        @DisplayName("the top gridline IS the axis maximum, so the frame is labelled")
        void topTickEqualsAxisMax() {
            for (StatChartLogic.Scale scale : StatChartLogic.Scale.values()) {
                List<StatChartLogic.Tick> ticks = logic().yTicks(scale, 200);

                assertThat(ticks.get(ticks.size() - 1).value())
                        .as("top gridline on the %s axis", scale)
                        .isEqualTo(logic().axisMax(scale));
                assertThat(ticks.get(ticks.size() - 1).y()).isZero();
            }
        }

        @Test
        @DisplayName("a percent axis stops at 100: a whole class in one bucket has no room above it")
        void percentAxisNeverExceedsOneHundred() {
            StatChartLogic everyone = new StatChartLogic(StatChartData.of(
                    List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 6), 95, 95, 3, 6));

            assertThat(everyone.axisMax(StatChartLogic.Scale.PERCENT)).isEqualTo(100);
            List<StatChartLogic.Tick> ticks = everyone.yTicks(StatChartLogic.Scale.PERCENT, 200);
            assertThat(ticks.get(ticks.size() - 1).value()).isEqualTo(100);
        }

        @Test
        @DisplayName("a count axis is stepped in whole people")
        void countStepsAreWhole() {
            for (int tallest = 1; tallest <= 40; tallest++) {
                List<Integer> buckets = new java.util.ArrayList<>(
                        java.util.Collections.nCopies(10, 0));
                buckets.set(3, tallest);
                StatChartLogic sized = new StatChartLogic(
                        StatChartData.of(buckets, 35, 35, 5, tallest));

                double step = sized.tickStep(StatChartLogic.Scale.COUNT);
                assertThat(step)
                        .as("axis step for %d students", tallest)
                        .isEqualTo(Math.rint(step));
            }
        }

        @Test
        @DisplayName("the seeded chart's ticks are the hand-computed ones")
        void seededTicks() {
            assertThat(logic().yTicks(StatChartLogic.Scale.COUNT, 300))
                    .extracting(StatChartLogic.Tick::label)
                    .containsExactly("0", "1", "2", "3");
            assertThat(logic().yTicks(StatChartLogic.Scale.PERCENT, 300))
                    .extracting(StatChartLogic.Tick::label)
                    .containsExactly("0%", "10%", "20%", "30%");
        }

        @Test
        @DisplayName("gridline positions descend from the baseline in even steps")
        void tickPositionsAreEven() {
            List<StatChartLogic.Tick> ticks = logic().yTicks(StatChartLogic.Scale.COUNT, 300);

            assertThat(ticks).hasSize(4);
            double[] expected = {300, 200, 100, 0};
            for (int i = 0; i < ticks.size(); i++) {
                assertThat(ticks.get(i).y())
                        .as("gridline %d", i)
                        .isCloseTo(expected[i], within(1e-9));
            }
        }

        @Test
        @DisplayName("a value above the axis is clamped rather than drawn outside the frame")
        void valuesClampToTheFrame() {
            assertThat(logic().yForValue(999, StatChartLogic.Scale.COUNT, 200)).isZero();
            assertThat(logic().yForValue(-5, StatChartLogic.Scale.COUNT, 200)).isEqualTo(200);
        }
    }

    // ===================== Bar geometry ===================================

    @Nested
    @DisplayName("bar geometry")
    class Bars {

        @Test
        @DisplayName("every decile gets a bar, empty ones included")
        void tenBarsAlways() {
            List<StatChartLogic.Bar> bars = logic().bars(500, 200, StatChartLogic.Scale.COUNT);

            assertThat(bars).hasSize(10);
            assertThat(bars.get(0).isEmpty()).as("nobody scored in the 0s").isTrue();
            assertThat(bars.get(0).height()).isZero();
        }

        @Test
        @DisplayName("bars fill the width in equal slots, gaps included, in ascending order")
        void barsTileTheWidth() {
            List<StatChartLogic.Bar> bars = logic().bars(500, 200, StatChartLogic.Scale.COUNT);
            double slot = 50;
            double expectedWidth = slot * (1 - StatChartLogic.BAR_GAP_FRACTION);

            assertThat(bars).allSatisfy(bar ->
                    assertThat(bar.width()).isCloseTo(expectedWidth, within(1e-9)));
            for (int i = 0; i < bars.size(); i++) {
                double inset = (slot - expectedWidth) / 2;
                assertThat(bars.get(i).x()).isCloseTo(i * slot + inset, within(1e-9));
            }
        }

        @Test
        @DisplayName("bars never overlap: each ends before the next begins")
        void barsDoNotOverlap() {
            List<StatChartLogic.Bar> bars = logic().bars(437, 200, StatChartLogic.Scale.COUNT);

            for (int i = 1; i < bars.size(); i++) {
                assertThat(bars.get(i).x())
                        .isGreaterThan(bars.get(i - 1).x() + bars.get(i - 1).width());
            }
        }

        @Test
        @DisplayName("a bar's height is its share of the axis, measured from the baseline")
        void heightsFollowTheAxis() {
            // Axis max is 3 students over 200 px, so a 2-student bar is two thirds of it.
            List<StatChartLogic.Bar> bars = logic().bars(500, 300, StatChartLogic.Scale.COUNT);

            assertThat(bars.get(7).count()).isEqualTo(2);
            assertThat(bars.get(7).height()).isCloseTo(200, within(1e-9));
            assertThat(bars.get(7).y()).isCloseTo(100, within(1e-9));
            assertThat(bars.get(4).count()).isEqualTo(1);
            assertThat(bars.get(4).height()).isCloseTo(100, within(1e-9));
        }

        @Test
        @DisplayName("the two scales draw the same shape, because both are honest")
        void countAndPercentAgreeOnShape() {
            List<StatChartLogic.Bar> counts = logic().bars(500, 300, StatChartLogic.Scale.COUNT);
            List<StatChartLogic.Bar> percents = logic().bars(500, 300, StatChartLogic.Scale.PERCENT);

            for (int i = 0; i < counts.size(); i++) {
                assertThat(counts.get(i).count()).isEqualTo(percents.get(i).count());
                if (counts.get(i).count() == 0) {
                    assertThat(percents.get(i).height()).isZero();
                }
            }
            // The tallest bar is the tallest on either scale, which is the only thing that
            // has to survive the unit change.
            assertThat(percents.get(7).height()).isGreaterThan(percents.get(4).height());
        }

        @Test
        @DisplayName("a plot with no room draws nothing rather than negative bars")
        void degenerateSizesDrawNothing() {
            assertThat(logic().bars(0, 200, StatChartLogic.Scale.COUNT)).isEmpty();
            assertThat(logic().bars(500, 0, StatChartLogic.Scale.COUNT)).isEmpty();
            assertThat(logic().bars(-10, -10, StatChartLogic.Scale.COUNT)).isEmpty();
        }

        @Test
        @DisplayName("slot width is the plot divided by the ten deciles")
        void slotWidth() {
            assertThat(logic().slotWidth(500)).isEqualTo(50);
        }
    }

    // ===================== The score axis and overlays ====================

    @Nested
    @DisplayName("the score axis, markers and the sigma band")
    class Overlays {

        @Test
        @DisplayName("the score axis and the bar layout share one ruler")
        void oneRulerForBarsAndMarkers() {
            // Score 60 must land exactly on the left edge of the 60-69 slot, or the mean
            // marker would be drawn against a different scale from the bars underneath it.
            assertThat(logic().xForScore(60, 500)).isCloseTo(300, within(1e-9));
            assertThat(logic().slotWidth(500) * 6).isCloseTo(300, within(1e-9));
        }

        @Test
        @DisplayName("0 and 100 are the two edges of the plot")
        void axisEnds() {
            assertThat(logic().xForScore(0, 500)).isZero();
            assertThat(logic().xForScore(100, 500)).isEqualTo(500);
        }

        @Test
        @DisplayName("a score outside 0..100 is clamped into the frame")
        void scoresClamp() {
            assertThat(logic().xForScore(-20, 500)).isZero();
            assertThat(logic().xForScore(140, 500)).isEqualTo(500);
        }

        @Test
        @DisplayName("mean and median sit where the seeded statistics say")
        void markersMatchTheSeed() {
            assertThat(logic().meanX(500)).isCloseTo(362.5, within(1e-9));
            assertThat(logic().medianX(500)).isCloseTo(362.5, within(1e-9));
        }

        @Test
        @DisplayName("the sigma band is mean +- one sigma")
        void sigmaBandIsTheSpread() {
            StatChartLogic.Interval band = logic().sigmaBand();

            assertThat(band.low()).isEqualTo(55.0);
            assertThat(band.high()).isEqualTo(90.0);
            assertThat(band.span()).isEqualTo(35.0);
        }

        @Test
        @DisplayName("a band that would run off the axis is clamped to it, both ends")
        void sigmaBandClamps() {
            StatChartLogic high = new StatChartLogic(StatChartData.of(
                    List.of(0, 0, 0, 0, 0, 0, 0, 0, 2, 4), 92, 95, 20, 6));
            StatChartLogic low = new StatChartLogic(StatChartData.of(
                    List.of(4, 2, 0, 0, 0, 0, 0, 0, 0, 0), 8, 5, 20, 6));

            assertThat(high.sigmaBand().high()).isEqualTo(100.0);
            assertThat(high.sigmaBand().low()).isEqualTo(72.0);
            assertThat(low.sigmaBand().low()).isEqualTo(0.0);
            assertThat(low.sigmaBand().high()).isEqualTo(28.0);
        }

        @Test
        @DisplayName("the band in pixels is a left edge and a width, never a negative one")
        void sigmaBandInPixels() {
            StatChartLogic.Interval pixels = logic().sigmaBandPixels(500);

            assertThat(pixels.low()).isCloseTo(275, within(1e-9));
            assertThat(pixels.high()).isCloseTo(175, within(1e-9));
        }

        @Test
        @DisplayName("a class with no spread at all produces a zero-width band, not a broken one")
        void zeroSigmaIsAZeroWidthBand() {
            StatChartLogic flat = new StatChartLogic(StatChartData.of(
                    List.of(0, 0, 0, 0, 0, 0, 0, 4, 0, 0), 75, 75, 0, 4));

            assertThat(flat.sigmaBandPixels(500).high()).isZero();
        }

        @Test
        @DisplayName("every overlay is labelled with its number")
        void overlaysAreLabelled() {
            assertThat(logic().meanLabel()).isEqualTo("Mean 72.5");
            assertThat(logic().medianLabel()).isEqualTo("Median 72.5");
            assertThat(logic().sigmaLabel()).isEqualTo("±1σ · 55 to 90");
        }
    }

    // ===================== Counts, percentages and copy ===================

    @Nested
    @DisplayName("counts, percentages and copy")
    class Copy {

        @Test
        @DisplayName("percentages divide by the participant count the stat cards print")
        void percentUsesTheStoredHeadcount() {
            assertThat(logic().toPercent(2)).isEqualTo(25.0);
            assertThat(logic().toPercent(1)).isEqualTo(12.5);
            assertThat(logic().toPercent(0)).isZero();
            assertThat(logic().toPercent(8)).isEqualTo(100.0);
        }

        @Test
        @DisplayName("percent converts back to whole people")
        void percentConvertsBack() {
            assertThat(logic().toCount(25)).isEqualTo(2);
            assertThat(logic().toCount(12.5)).isEqualTo(1);
            assertThat(logic().toCount(0)).isZero();
        }

        @Test
        @DisplayName("with nobody graded, both conversions are zero rather than a division by zero")
        void conversionsSurviveAnEmptyClass() {
            StatChartLogic empty = new StatChartLogic(StatChartData.empty());

            assertThat(empty.toPercent(3)).isZero();
            assertThat(empty.toCount(50)).isZero();
        }

        @ParameterizedTest
        @EnumSource(StatChartLogic.Scale.class)
        @DisplayName("valueOf reads a bucket on whichever scale is active")
        void valueOfFollowsTheScale(StatChartLogic.Scale scale) {
            double value = logic().valueOf(7, scale);

            assertThat(value).isEqualTo(scale == StatChartLogic.Scale.COUNT ? 2.0 : 25.0);
        }

        @Test
        @DisplayName("the top bucket is labelled 90-100, because it holds the perfect score")
        void topBucketIncludesOneHundred() {
            assertThat(StatChartLogic.bucketLabel(0)).isEqualTo("0-9");
            assertThat(StatChartLogic.bucketLabel(6)).isEqualTo("60-69");
            assertThat(StatChartLogic.bucketLabel(9)).isEqualTo("90-100");
        }

        @Test
        @DisplayName("a label for something that is not a decile is a programming error")
        void bucketLabelRejectsNonDeciles() {
            assertThatThrownBy(() -> StatChartLogic.bucketLabel(-1))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> StatChartLogic.bucketLabel(10))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("the tooltip answers range, how many and what share, in one sentence")
        void tooltipSaysAllThree() {
            assertThat(logic().tooltip(7)).isEqualTo("70-79 · 2 students · 25%");
            assertThat(logic().tooltip(4)).isEqualTo("40-49 · 1 student · 12.5%");
            assertThat(logic().tooltip(9)).isEqualTo("90-100 · 2 students · 25%");
        }

        @Test
        @DisplayName("an empty bucket says nobody rather than 0 students")
        void emptyBucketTooltip() {
            assertThat(logic().tooltip(0)).isEqualTo("0-9 · nobody");
        }

        @Test
        @DisplayName("every bar carries its own label and tooltip")
        void barsCarryTheirCopy() {
            List<StatChartLogic.Bar> bars = logic().bars(500, 200, StatChartLogic.Scale.COUNT);

            assertThat(bars.get(9).label()).isEqualTo("90-100");
            assertThat(bars.get(9).tooltip()).isEqualTo("90-100 · 2 students · 25%");
            assertThat(bars.get(9).percent()).isEqualTo(25.0);
        }

        @Test
        @DisplayName("the axis title says what is being measured")
        void axisTitles() {
            assertThat(StatChartLogic.Scale.COUNT.axisTitle()).isEqualTo("Students");
            assertThat(StatChartLogic.Scale.PERCENT.axisTitle()).isEqualTo("Share of class");
        }

        @Test
        @DisplayName("the caption restates the stat cards, so the two cannot disagree")
        void summaryCaption() {
            assertThat(logic().summaryCaption())
                    .isEqualTo("8 students · mean 72.5 · median 72.5 · σ 17.5");
        }

        @Test
        @DisplayName("an unchartable class gets a headcount and no statistics")
        void summaryCaptionWhenUnchartable() {
            StatChartLogic single = new StatChartLogic(StatChartData.of(
                    List.of(0, 0, 0, 0, 0, 0, 0, 1, 0, 0), 75, 75, 0, 1));

            assertThat(single.summaryCaption()).isEqualTo("1 student");
            assertThat(new StatChartLogic(StatChartData.empty()).summaryCaption())
                    .isEqualTo("0 students");
        }

        @Test
        @DisplayName("a whole number prints without a decimal; a half keeps it")
        void numberFormatting() {
            assertThat(StatChartLogic.number(90)).isEqualTo("90");
            assertThat(StatChartLogic.number(90.0)).isEqualTo("90");
            assertThat(StatChartLogic.number(72.5)).isEqualTo("72.5");
            assertThat(StatChartLogic.number(12.5)).isEqualTo("12.5");
            assertThat(StatChartLogic.number(17.46)).isEqualTo("17.5");
            assertThat(StatChartLogic.number(0)).isEqualTo("0");
        }

        @Test
        @DisplayName("tick labels carry the unit on the percent axis and not on the count axis")
        void tickLabelsCarryTheUnit() {
            assertThat(logic().tickLabel(3, StatChartLogic.Scale.COUNT)).isEqualTo("3");
            assertThat(logic().tickLabel(30, StatChartLogic.Scale.PERCENT)).isEqualTo("30%");
        }
    }

    // ===================== Guard rails ====================================

    @Nested
    @DisplayName("guard rails")
    class Guards {

        @Test
        @DisplayName("the logic refuses to exist without data")
        void dataIsRequired() {
            assertThatThrownBy(() -> new StatChartLogic(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a null scale is rejected everywhere a scale is asked for")
        void scaleIsRequired() {
            assertThatThrownBy(() -> logic().axisMax(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> logic().bars(500, 200, null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> logic().tickLabel(1, null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> logic().valueOf(0, null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> logic().tickStep(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the logic hands back the data it was built from")
        void exposesItsData() {
            StatChartData data = seeded();

            assertThat(new StatChartLogic(data).data()).isSameAs(data);
        }

        @Test
        @DisplayName("an all-empty distribution still produces a usable axis rather than dividing by zero")
        void emptyDistributionHasAnAxis() {
            StatChartLogic empty = new StatChartLogic(StatChartData.empty());

            assertThat(empty.axisMax(StatChartLogic.Scale.COUNT)).isEqualTo(1);
            assertThat(empty.axisMax(StatChartLogic.Scale.PERCENT)).isEqualTo(10);
            assertThat(empty.yTicks(StatChartLogic.Scale.COUNT, 100)).isNotEmpty();
            assertThat(empty.yForValue(0, StatChartLogic.Scale.COUNT, 100)).isEqualTo(100);
        }
    }
}
