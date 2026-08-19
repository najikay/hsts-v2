package client.ui.anim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link Motion} — the animation parameter arithmetic (E4.20).
 *
 * <p>PRD §4.1's "nothing longer than 250ms" is enforced here rather than by
 * review, so these tests are the actual guarantee.
 */
class MotionTest {

    @Nested
    @DisplayName("the 250ms budget")
    class Budget {

        @Test
        void housesDurationsAreAllWithinBudget() {
            assertThat(Motion.FAST_MS).isLessThanOrEqualTo(Motion.MAX_MS);
            assertThat(Motion.BASE_MS).isLessThanOrEqualTo(Motion.MAX_MS);
            assertThat(Motion.SLOW_MS).isLessThanOrEqualTo(Motion.MAX_MS);
            assertThat(Motion.MAX_MS).isEqualTo(250);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 120, 180, 249, 250})
        void inBudgetDurationsPassThroughUnchanged(int millis) {
            assertThat(Motion.clampMillis(millis)).isEqualTo(millis);
        }

        @ParameterizedTest
        @ValueSource(ints = {251, 400, 1000, Integer.MAX_VALUE})
        void overBudgetDurationsAreClampedNotHonoured(int millis) {
            assertThat(Motion.clampMillis(millis)).isEqualTo(Motion.MAX_MS);
            assertThat(Motion.exceedsBudget(millis)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, -250, Integer.MIN_VALUE})
        void negativeDurationsBecomeInstant(int millis) {
            assertThat(Motion.clampMillis(millis)).isZero();
        }

        @Test
        void inBudgetDurationsAreNotReportedAsExceeding() {
            assertThat(Motion.exceedsBudget(Motion.MAX_MS)).isFalse();
            assertThat(Motion.exceedsBudget(0)).isFalse();
        }
    }

    @Nested
    @DisplayName("stagger")
    class Stagger {

        @Test
        void theFirstItemHasNoDelay() {
            assertThat(Motion.staggerDelay(0)).isZero();
        }

        @Test
        void delayGrowsByOneStepPerItem() {
            assertThat(Motion.staggerDelay(1)).isEqualTo(Motion.STAGGER_STEP_MS);
            assertThat(Motion.staggerDelay(3)).isEqualTo(3 * Motion.STAGGER_STEP_MS);
        }

        @Test
        void aLongListStopsTricklingAtTheCap() {
            assertThat(Motion.staggerDelay(200)).isEqualTo(Motion.STAGGER_CAP_MS);
            assertThat(Motion.staggerDelay(Integer.MAX_VALUE)).isEqualTo(Motion.STAGGER_CAP_MS);
        }

        @Test
        void negativeIndicesAreTreatedAsTheFirstItem() {
            assertThat(Motion.staggerDelay(-5)).isZero();
        }

        @ParameterizedTest
        @CsvSource({"5, -10, 250, 0", "5, 20, -1, 0", "4, 10, 25, 25"})
        void negativeParametersDegradeToZeroInsteadOfMisbehaving(
                int index, int step, int cap, int expected) {
            assertThat(Motion.staggerDelay(index, step, cap)).isEqualTo(expected);
        }

        @Test
        void aLongListStillFinishesWithinTheCapPlusOneItemDuration() {
            int total = Motion.staggerTotalMillis(500, Motion.BASE_MS);

            assertThat(total).isEqualTo(Motion.STAGGER_CAP_MS + Motion.BASE_MS);
            assertThat(total).isLessThanOrEqualTo(Motion.STAGGER_CAP_MS + Motion.MAX_MS);
        }

        @Test
        void anEmptyListTakesNoTime() {
            assertThat(Motion.staggerTotalMillis(0, Motion.BASE_MS)).isZero();
            assertThat(Motion.staggerTotalMillis(-3, Motion.BASE_MS)).isZero();
        }

        @Test
        void aSingleItemTakesOnlyItsOwnDuration() {
            assertThat(Motion.staggerTotalMillis(1, 100)).isEqualTo(100);
        }

        @Test
        void anOverBudgetItemDurationIsClampedInTheTotalToo() {
            assertThat(Motion.staggerTotalMillis(1, 999)).isEqualTo(Motion.MAX_MS);
        }
    }

    @Nested
    @DisplayName("slide offsets")
    class Slide {

        @Test
        void enteringFromTheLeadingEdgeIsANegativeOffset() {
            assertThat(Motion.slideOffset(20, true)).isEqualTo(-20);
            assertThat(Motion.slideOffset(true)).isEqualTo(-Motion.SLIDE_DISTANCE);
        }

        @Test
        void enteringFromTheTrailingEdgeIsAPositiveOffset() {
            assertThat(Motion.slideOffset(20, false)).isEqualTo(20);
            assertThat(Motion.slideOffset(false)).isEqualTo(Motion.SLIDE_DISTANCE);
        }

        @Test
        void aNegativeDistanceStillRespectsTheRequestedDirection() {
            assertThat(Motion.slideOffset(-20, true)).isEqualTo(-20);
            assertThat(Motion.slideOffset(-20, false)).isEqualTo(20);
        }

        @Test
        void theHouseDistanceSitsOnTheFourPixelGrid() {
            assertThat(Motion.SLIDE_DISTANCE % 4).isZero();
        }
    }

    @Nested
    @DisplayName("clamping")
    class Clamping {

        @ParameterizedTest
        @CsvSource({"0.0, 0.0", "0.5, 0.5", "1.0, 1.0", "-0.5, 0.0", "1.7, 1.0"})
        void opacityStaysWithinZeroAndOne(double input, double expected) {
            assertThat(Motion.clampOpacity(input)).isEqualTo(expected, within(1e-9));
        }

        @Test
        void notANumberOpacityBecomesTransparentRatherThanBreakingJavaFx() {
            assertThat(Motion.clampOpacity(Double.NaN)).isZero();
        }

        @ParameterizedTest
        @CsvSource({"1.0, 1.0", "1.06, 1.06", "0.5, 0.5", "1.5, 1.5", "3.0, 1.5", "0.1, 0.5"})
        void scaleStaysWithinRecognisableBounds(double input, double expected) {
            assertThat(Motion.clampScale(input)).isEqualTo(expected, within(1e-9));
        }

        @Test
        void nonsenseScaleBecomesNeutral() {
            assertThat(Motion.clampScale(Double.NaN)).isEqualTo(1);
            assertThat(Motion.clampScale(0)).isEqualTo(1);
            assertThat(Motion.clampScale(-2)).isEqualTo(1);
        }

        @Test
        void thePopScaleIsSubtle() {
            assertThat(Motion.POP_SCALE).isEqualTo(Motion.clampScale(Motion.POP_SCALE));
            assertThat(Motion.POP_SCALE).isBetween(1.0, 1.15);
        }
    }

    @Nested
    @DisplayName("pulse cycles")
    class Pulses {

        @Test
        void fitsWholeCyclesIntoABudget() {
            assertThat(Motion.pulseCycles(1000, 200)).isEqualTo(5);
            assertThat(Motion.pulseCycles(1000, 300)).isEqualTo(3);
        }

        @Test
        void aBudgetShorterThanOneCycleStillGivesOneCycle() {
            assertThat(Motion.pulseCycles(50, 200)).isEqualTo(1);
        }

        @Test
        void noBudgetMeansNoPulse() {
            assertThat(Motion.pulseCycles(0, 200)).isZero();
            assertThat(Motion.pulseCycles(-100, 200)).isZero();
            assertThat(Motion.pulseCycles(1000, 0)).isZero();
            assertThat(Motion.pulseCycles(1000, -5)).isZero();
        }
    }
}
