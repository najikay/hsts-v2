package client.ui.components.logic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RadioGroupLogic} — where an arrow key lands in a single-select group (F2.1, C-8).
 *
 * <p>The fixture throughout is F2.1's group: four answers, one of which is correct. The cases
 * that matter are the ones a hand-rolled loop at a call site gets wrong — the wrap, the
 * disabled options, the group nobody has touched yet, and the group where everything is
 * disabled and a naive scan never terminates.
 */
class RadioGroupLogicTest {

    /** F2.1's group: four options, all usable. */
    private static final int ANSWERS = 4;

    private static final IntPredicate ALL = index -> true;
    private static final IntPredicate NONE_ENABLED = index -> false;

    private static IntPredicate allExcept(Integer... disabled) {
        Set<Integer> off = Set.of(disabled);
        return index -> !off.contains(index);
    }

    // ===================== step ==========================================

    @Nested
    @DisplayName("step")
    class Step {

        @Test
        @DisplayName("vertical arrows mean the same thing in both writing directions")
        void verticalArrowsNeverMirror() {
            assertThat(RadioGroupLogic.step(RadioGroupLogic.Arrow.UP, false)).isEqualTo(-1);
            assertThat(RadioGroupLogic.step(RadioGroupLogic.Arrow.UP, true)).isEqualTo(-1);
            assertThat(RadioGroupLogic.step(RadioGroupLogic.Arrow.DOWN, false)).isEqualTo(1);
            assertThat(RadioGroupLogic.step(RadioGroupLogic.Arrow.DOWN, true)).isEqualTo(1);
        }

        @Test
        @DisplayName("horizontal arrows mirror under RTL, so Hebrew answers read the right way")
        void horizontalArrowsMirror() {
            assertThat(RadioGroupLogic.step(RadioGroupLogic.Arrow.RIGHT, false)).isEqualTo(1);
            assertThat(RadioGroupLogic.step(RadioGroupLogic.Arrow.LEFT, false)).isEqualTo(-1);

            assertThat(RadioGroupLogic.step(RadioGroupLogic.Arrow.RIGHT, true))
                    .as("with the group laid out right to left, Right walks backwards")
                    .isEqualTo(-1);
            assertThat(RadioGroupLogic.step(RadioGroupLogic.Arrow.LEFT, true)).isEqualTo(1);
        }

        @ParameterizedTest
        @EnumSource(RadioGroupLogic.Arrow.class)
        @DisplayName("every arrow travels exactly one option, in one direction or the other")
        void everyArrowIsOneStep(RadioGroupLogic.Arrow arrow) {
            assertThat(RadioGroupLogic.step(arrow, false)).isIn(-1, 1);
            assertThat(RadioGroupLogic.step(arrow, true)).isIn(-1, 1);
        }

        @Test
        @DisplayName("a null arrow is a programming error, not a silent no-op")
        void nullArrowThrows() {
            assertThatThrownBy(() -> RadioGroupLogic.step(null, false))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ===================== nextIndex =====================================

    @Nested
    @DisplayName("nextIndex")
    class Next {

        @ParameterizedTest
        @CsvSource({"0,1", "1,2", "2,3"})
        @DisplayName("moves to the neighbour in an unobstructed group")
        void movesForward(int from, int expected) {
            assertThat(RadioGroupLogic.nextIndex(from, ANSWERS, 1, ALL)).isEqualTo(expected);
        }

        @Test
        @DisplayName("wraps at the end rather than walking out of the group")
        void wrapsForward() {
            assertThat(RadioGroupLogic.nextIndex(3, ANSWERS, 1, ALL))
                    .as("Down on the last answer returns to the first, never to the topic field")
                    .isZero();
        }

        @Test
        @DisplayName("wraps at the start too")
        void wrapsBackward() {
            assertThat(RadioGroupLogic.nextIndex(0, ANSWERS, -1, ALL)).isEqualTo(3);
        }

        @Test
        @DisplayName("skips a disabled option instead of landing on it")
        void skipsDisabled() {
            assertThat(RadioGroupLogic.nextIndex(0, ANSWERS, 1, allExcept(1)))
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("skips a run of disabled options, wrapping through the end if it has to")
        void skipsARunAndWraps() {
            assertThat(RadioGroupLogic.nextIndex(0, ANSWERS, 1, allExcept(1, 2, 3)))
                    .as("everything after answer 1 is unusable, so the scan comes back to it")
                    .isZero();
        }

        @Test
        @DisplayName("from cold, a forward arrow lands on the first option")
        void coldForwardStartsAtTheTop() {
            assertThat(RadioGroupLogic.nextIndex(RadioGroupLogic.NONE, ANSWERS, 1, ALL)).isZero();
        }

        @Test
        @DisplayName("from cold, a backward arrow lands on the last option")
        void coldBackwardStartsAtTheBottom() {
            assertThat(RadioGroupLogic.nextIndex(RadioGroupLogic.NONE, ANSWERS, -1, ALL))
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("from cold with the first options disabled, it still finds a usable one")
        void coldSkipsDisabled() {
            assertThat(RadioGroupLogic.nextIndex(RadioGroupLogic.NONE, ANSWERS, 1, allExcept(0, 1)))
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("an entirely disabled group answers NONE rather than spinning forever")
        void allDisabledTerminates() {
            assertThat(RadioGroupLogic.nextIndex(0, ANSWERS, 1, NONE_ENABLED))
                    .isEqualTo(RadioGroupLogic.NONE);
            assertThat(RadioGroupLogic.nextIndex(RadioGroupLogic.NONE, ANSWERS, -1, NONE_ENABLED))
                    .isEqualTo(RadioGroupLogic.NONE);
        }

        @Test
        @DisplayName("a group with one usable option stays on it rather than moving nowhere")
        void singleUsableOptionStaysPut() {
            assertThat(RadioGroupLogic.nextIndex(2, ANSWERS, 1, allExcept(0, 1, 3)))
                    .isEqualTo(2);
            assertThat(RadioGroupLogic.nextIndex(2, ANSWERS, -1, allExcept(0, 1, 3)))
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("an empty group has nowhere to go")
        void emptyGroup() {
            assertThat(RadioGroupLogic.nextIndex(0, 0, 1, ALL)).isEqualTo(RadioGroupLogic.NONE);
        }

        @Test
        @DisplayName("a zero step is refused rather than treated as forward")
        void zeroStep() {
            assertThat(RadioGroupLogic.nextIndex(0, ANSWERS, 0, ALL))
                    .isEqualTo(RadioGroupLogic.NONE);
        }

        @Test
        @DisplayName("four downs return to where they started")
        void aFullLapIsIdentity() {
            int index = 0;
            for (int hop = 0; hop < ANSWERS; hop++) {
                index = RadioGroupLogic.nextIndex(index, ANSWERS, 1, ALL);
            }
            assertThat(index).isZero();
        }
    }

    // ===================== focusIndex ====================================

    @Nested
    @DisplayName("focusIndex")
    class Focus {

        @Test
        @DisplayName("tabbing into a half-filled form lands on the answer already chosen")
        void prefersTheSelection() {
            assertThat(RadioGroupLogic.focusIndex(2, ANSWERS, ALL)).isEqualTo(2);
        }

        @Test
        @DisplayName("with nothing chosen it lands on the first option")
        void fallsBackToTheFirst() {
            assertThat(RadioGroupLogic.focusIndex(RadioGroupLogic.NONE, ANSWERS, ALL)).isZero();
        }

        @Test
        @DisplayName("a selection that has since been disabled does not keep the focus")
        void skipsADisabledSelection() {
            assertThat(RadioGroupLogic.focusIndex(2, ANSWERS, allExcept(0, 2)))
                    .as("focus must land somewhere the keyboard can act on")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an entirely disabled group takes no focus at all")
        void allDisabled() {
            assertThat(RadioGroupLogic.focusIndex(1, ANSWERS, NONE_ENABLED))
                    .isEqualTo(RadioGroupLogic.NONE);
        }

        @Test
        @DisplayName("an out-of-range selection is treated as no selection, not as an error")
        void outOfRangeSelection() {
            assertThat(RadioGroupLogic.focusIndex(99, ANSWERS, ALL)).isZero();
            assertThat(RadioGroupLogic.focusIndex(-7, ANSWERS, ALL)).isZero();
        }
    }

    // ===================== firstEnabled ==================================

    @Test
    @DisplayName("firstEnabled finds the lowest usable index, or NONE")
    void firstEnabled() {
        assertThat(RadioGroupLogic.firstEnabled(ANSWERS, ALL)).isZero();
        assertThat(RadioGroupLogic.firstEnabled(ANSWERS, allExcept(0, 1))).isEqualTo(2);
        assertThat(RadioGroupLogic.firstEnabled(ANSWERS, NONE_ENABLED))
                .isEqualTo(RadioGroupLogic.NONE);
        assertThat(RadioGroupLogic.firstEnabled(0, ALL)).isEqualTo(RadioGroupLogic.NONE);
    }

    @Test
    @DisplayName("a missing predicate is a programming error on every entry point")
    void nullPredicateThrows() {
        assertThatThrownBy(() -> RadioGroupLogic.nextIndex(0, ANSWERS, 1, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RadioGroupLogic.firstEnabled(ANSWERS, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RadioGroupLogic.focusIndex(0, ANSWERS, null))
                .isInstanceOf(NullPointerException.class);
    }
}
