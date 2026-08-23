package client.ui.components.logic;

import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * Roving-focus arithmetic for a single-select option group (Presentation tier, for E6.10 and
 * E6.11 — F2.1, C-8).
 *
 * <p>Small on purpose. {@code RadioGroup} is mostly a wrapper over {@link
 * javafx.scene.control.ToggleGroup}, which already guarantees the one rule C-8 cares about:
 * at most one option can be selected at a time. Wrapping that in a hand-written "logic" class
 * would be inventing work. What {@code ToggleGroup} does <b>not</b> know is where an arrow key
 * should land, and that turns out to be the one genuinely fiddly thing in the component:
 *
 * <ol>
 *   <li><b>It wraps.</b> Down on the last option goes to the first, not nowhere. JavaFX's own
 *       directional traversal instead walks out of the group and into whatever node happens to
 *       sit below it, which in a question editor is the topic field.</li>
 *   <li><b>It skips disabled options.</b> A disabled option cannot take focus, so an arrow that
 *       landed on one would strand the keyboard user.</li>
 *   <li><b>It terminates.</b> An entirely disabled group has nowhere to go, and the naive
 *       "keep stepping until you find an enabled one" loop spins forever on it. Every scan here
 *       is bounded by the option count.</li>
 *   <li><b>It mirrors under RTL.</b> With Hebrew answers the group renders right-to-left, and
 *       <i>left</i> then means "towards the next option". Vertical arrows never mirror: a
 *       stack is a stack in both directions.</li>
 * </ol>
 *
 * <p>FX-free and static, on {@link ChipCatalog}'s reasoning rather than {@link
 * CountdownLogic}'s: there is no state to hold, only rules to apply, and they are unit tested
 * with plain integers instead of a booted toolkit and a synthetic key press.
 */
public final class RadioGroupLogic {

    /** No option can be focused or selected, because none is enabled. */
    public static final int NONE = -1;

    /** The four arrow keys, named without dragging {@code javafx.scene.input} in here. */
    public enum Arrow {
        /** Towards the previous option. */
        UP,
        /** Towards the next option. */
        DOWN,
        /** Towards the previous option, unless the group renders right-to-left. */
        LEFT,
        /** Towards the next option, unless the group renders right-to-left. */
        RIGHT
    }

    private RadioGroupLogic() {
        // static rules - no instances
    }

    /**
     * Which way an arrow key travels.
     *
     * @param arrow        the key pressed
     * @param rightToLeft  whether the group's effective node orientation is right-to-left
     * @return {@code +1} towards the next option, {@code -1} towards the previous one
     */
    public static int step(Arrow arrow, boolean rightToLeft) {
        Objects.requireNonNull(arrow, "arrow");
        return switch (arrow) {
            case UP -> -1;
            case DOWN -> 1;
            case LEFT -> rightToLeft ? 1 : -1;
            case RIGHT -> rightToLeft ? -1 : 1;
        };
    }

    /**
     * Where an arrow key lands.
     *
     * <p>Wraps at both ends and skips whatever {@code enabled} refuses. Two cases are worth
     * stating because they are the ones that bite:
     *
     * <ul>
     *   <li><b>Nothing selected yet</b> ({@code current} outside {@code [0, size)}): a
     *       forward arrow lands on the first enabled option and a backward arrow on the last,
     *       so the group is reachable by keyboard from cold.</li>
     *   <li><b>Only the current option is enabled</b>: the scan comes all the way round and
     *       returns {@code current}. The selection stays put rather than moving to a disabled
     *       neighbour or to {@link #NONE}.</li>
     * </ul>
     *
     * @param current the currently focused option index, or any out-of-range value for none
     * @param size    how many options there are
     * @param step    {@code +1} or {@code -1}, from {@link #step}
     * @param enabled whether the option at an index can be focused
     * @return the index to move to, or {@link #NONE} when no option is enabled
     */
    public static int nextIndex(int current, int size, int step, IntPredicate enabled) {
        Objects.requireNonNull(enabled, "enabled");
        if (size <= 0 || step == 0) {
            return NONE;
        }
        // From cold, start just outside the end the arrow is travelling away from, so the
        // first hop lands on index 0 going forward and size-1 going backward.
        int probe = inRange(current, size) ? current : (step > 0 ? size - 1 : 0);
        for (int hop = 0; hop < size; hop++) {
            probe = Math.floorMod(probe + step, size);
            if (enabled.test(probe)) {
                return probe;
            }
        }
        return NONE;
    }

    /**
     * @param size    how many options there are
     * @param enabled whether the option at an index can be focused
     * @return the lowest enabled index, or {@link #NONE} when there is none
     */
    public static int firstEnabled(int size, IntPredicate enabled) {
        Objects.requireNonNull(enabled, "enabled");
        for (int index = 0; index < size; index++) {
            if (enabled.test(index)) {
                return index;
            }
        }
        return NONE;
    }

    /**
     * Which option the group hands focus to when it is tabbed into.
     *
     * <p>The selected one, so returning to a half-filled form puts the cursor on the answer
     * already chosen rather than back at the top. A selection that has since been disabled
     * falls through to the first option that is not, because focus must land somewhere the
     * keyboard can act on.
     *
     * @param selected the selected option index, or any out-of-range value for none
     * @param size     how many options there are
     * @param enabled  whether the option at an index can be focused
     * @return the index to focus, or {@link #NONE} when no option is enabled
     */
    public static int focusIndex(int selected, int size, IntPredicate enabled) {
        Objects.requireNonNull(enabled, "enabled");
        if (inRange(selected, size) && enabled.test(selected)) {
            return selected;
        }
        return firstEnabled(size, enabled);
    }

    private static boolean inRange(int index, int size) {
        return index >= 0 && index < size;
    }
}
