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

    // ===================== UI wave 2 =====================================

    @Nested
    @DisplayName("the wave-2 motion spec")
    class WaveTwoSpec {

        @Test
        @DisplayName("every transition in the spec is inside the 250ms budget")
        void everyTransitionIsInBudget() {
            assertThat(Motion.ROUTE_MS).isLessThanOrEqualTo(Motion.MAX_MS);
            assertThat(Motion.POPOVER_OPEN_MS).isLessThanOrEqualTo(Motion.MAX_MS);
            assertThat(Motion.POPOVER_CLOSE_MS).isLessThanOrEqualTo(Motion.MAX_MS);
            assertThat(Motion.DIALOG_MS).isLessThanOrEqualTo(Motion.MAX_MS);
            assertThat(Motion.CARD_HOVER_MS).isLessThanOrEqualTo(Motion.MAX_MS);
            assertThat(Motion.ROW_HOVER_MS).isLessThanOrEqualTo(Motion.MAX_MS);
            assertThat(Motion.NUMBER_ROLL_MS).isLessThanOrEqualTo(Motion.MAX_MS);
            assertThat(Motion.REDUCED_FADE_MS).isLessThanOrEqualTo(Motion.MAX_MS);
        }

        @Test
        @DisplayName("⚑ the two ambient loops are the ONLY things over budget, and both are loops")
        void onlyTheAmbientLoopsExceedTheBudget() {
            // These are exempt on the same terms Animations.shimmer is: ambience
            // rather than a transition, nothing waits on either, and both stop
            // when what they annotate goes away. The exemption is asserted here
            // so a third one cannot be added quietly by copying a constant.
            assertThat(Motion.BREATHE_MS).isGreaterThan(Motion.MAX_MS);
            assertThat(Motion.LIVE_PULSE_MS).isGreaterThan(Motion.MAX_MS);
            assertThat(Motion.BREATHE_SCALE)
                    .as("breathing, not pulsing")
                    .isEqualTo(Motion.clampScale(Motion.BREATHE_SCALE))
                    .isLessThan(1.1);
        }

        @Test
        @DisplayName("leaving is quicker than arriving")
        void dismissalIsShorterThanEntrance() {
            assertThat(Motion.POPOVER_CLOSE_MS).isLessThan(Motion.POPOVER_OPEN_MS);
        }

        @Test
        @DisplayName("a dialog starts near its final size, so it settles rather than pops")
        void theDialogEntranceIsSubtle() {
            assertThat(Motion.DIALOG_FROM_SCALE).isBetween(0.95, 1.0);
        }

        @Test
        @DisplayName("card stagger grows one step per card")
        void cardStaggerGrowsPerCard() {
            assertThat(Motion.cardStaggerDelay(0)).isZero();
            assertThat(Motion.cardStaggerDelay(1)).isEqualTo(Motion.CARD_STAGGER_STEP_MS);
            assertThat(Motion.cardStaggerDelay(3)).isEqualTo(3 * Motion.CARD_STAGGER_STEP_MS);
        }

        @Test
        @DisplayName("⚑ past six cards the rest share the last slot")
        void cardStaggerCapsByCount() {
            // Capped by COUNT rather than by time, unlike a list stagger: past
            // half a dozen items nobody is reading in order any more, they are
            // waiting.
            int last = Motion.cardStaggerDelay(Motion.CARD_STAGGER_MAX - 1);
            assertThat(Motion.cardStaggerDelay(Motion.CARD_STAGGER_MAX)).isEqualTo(last);
            assertThat(Motion.cardStaggerDelay(200)).isEqualTo(last);
        }

        @Test
        @DisplayName("row stagger is finer than card stagger and still capped in time")
        void rowStaggerIsBoundedInTime() {
            assertThat(Motion.ROW_STAGGER_STEP_MS).isLessThan(Motion.CARD_STAGGER_STEP_MS);
            assertThat(Motion.rowStaggerDelay(0)).isZero();
            assertThat(Motion.rowStaggerDelay(2)).isEqualTo(2 * Motion.ROW_STAGGER_STEP_MS);
            // A 400-row data browser still finishes arriving within a blink.
            assertThat(Motion.rowStaggerDelay(400)).isEqualTo(Motion.STAGGER_CAP_MS);
        }

        @Test
        @DisplayName("negative indices are treated as the first item")
        void negativeIndicesAreSafe() {
            assertThat(Motion.cardStaggerDelay(-3)).isZero();
            assertThat(Motion.rowStaggerDelay(-3)).isZero();
        }
    }

    @Nested
    @DisplayName("reduced motion")
    class ReducedMotion {

        @org.junit.jupiter.api.AfterEach
        void restore() {
            // A static switch: leaving it on would silently disarm every other
            // animation assertion in the build.
            Motion.setReducedMotion(false);
        }

        @Test
        @DisplayName("off by default, so nothing about the app changes until it is asked for")
        void offByDefault() {
            assertThat(Motion.isReducedMotion()).isFalse();
        }

        @Test
        @DisplayName("⚑ every duration collapses to one short fade")
        void everyDurationCollapses() {
            Motion.setReducedMotion(true);

            for (int requested : new int[]{0, 40, Motion.ROUTE_MS, Motion.DIALOG_MS,
                    Motion.NUMBER_ROLL_MS, 5000}) {
                assertThat(Motion.effectiveMillis(requested))
                        .as("a reader who asked for less motion gets one duration, not a scale")
                        .isEqualTo(Motion.REDUCED_FADE_MS);
            }
        }

        @Test
        @DisplayName("⚑ travel becomes zero: a fade has no distance")
        void travelCollapses() {
            Motion.setReducedMotion(true);

            assertThat(Motion.effectiveDistance(Motion.RISE_DISTANCE)).isZero();
            assertThat(Motion.effectiveDistance(Motion.SLIDE_DISTANCE)).isZero();
        }

        @Test
        @DisplayName("⚑ staggers collapse: a list arrives at once rather than trickling")
        void staggersCollapse() {
            Motion.setReducedMotion(true);

            assertThat(Motion.cardStaggerDelay(4)).isZero();
            assertThat(Motion.rowStaggerDelay(9)).isZero();
            assertThat(Motion.effectiveDelay(400)).isZero();
        }

        @Test
        @DisplayName("⚑ the ambient loops do not run at all")
        void loopsDoNotStart() {
            // The breathing empty state and the live halo never end on their own,
            // which makes them the two a reader who asked for less motion would
            // notice most. Shortening them is not the answer; not starting is.
            Motion.setReducedMotion(true);
            assertThat(Motion.ambientLoopsAllowed()).isFalse();

            Motion.setReducedMotion(false);
            assertThat(Motion.ambientLoopsAllowed()).isTrue();
        }

        @Test
        @DisplayName("turning it off restores the spec exactly")
        void itIsReversible() {
            Motion.setReducedMotion(true);
            Motion.setReducedMotion(false);

            assertThat(Motion.effectiveMillis(Motion.ROUTE_MS)).isEqualTo(Motion.ROUTE_MS);
            assertThat(Motion.effectiveDistance(Motion.RISE_DISTANCE))
                    .isEqualTo(Motion.RISE_DISTANCE);
            assertThat(Motion.cardStaggerDelay(2)).isEqualTo(2 * Motion.CARD_STAGGER_STEP_MS);
        }

        @Test
        @DisplayName("the budget still applies when reduced motion is off")
        void theBudgetSurvives() {
            assertThat(Motion.effectiveMillis(5000)).isEqualTo(Motion.MAX_MS);
            assertThat(Motion.effectiveMillis(-20)).isZero();
        }

        @Test
        @DisplayName("the system property that turns it on is named, so a demo can start calm")
        void thePropertyIsNamed() {
            assertThat(Motion.REDUCED_MOTION_PROPERTY).isEqualTo("hsts.motion.reduced");
        }
    }
}
