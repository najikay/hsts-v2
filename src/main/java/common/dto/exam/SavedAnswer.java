package common.dto.exam;

import java.io.Serializable;

/**
 * One choice the server is holding for this attempt (Common tier, E10.6 — F6.3).
 *
 * <p>What comes back on resume, so a student whose laptop died mid-exam finds the paper
 * exactly as she left it. There is one of these per question she has actually touched;
 * untouched questions are simply absent rather than present with a null, because "not
 * answered" and "answered with nothing" are the same thing to everyone who reads this.
 *
 * @param questionVersionId which question, by the pinned version the paper asks
 * @param selected          the chosen option, 1..4
 */
public record SavedAnswer(long questionVersionId, int selected) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Lowest selectable option. */
    public static final int MIN_OPTION = 1;

    /** Highest selectable option (C-7: exactly four answers). */
    public static final int MAX_OPTION = 4;

    public SavedAnswer {
        if (selected < MIN_OPTION || selected > MAX_OPTION) {
            // Constructing an impossible choice is a programming error on either tier; the
            // student-facing rejection of a bad selection happens in the handler, with a
            // sentence, long before anything reaches this constructor.
            throw new IllegalArgumentException(
                    "A saved answer is " + MIN_OPTION + ".." + MAX_OPTION + ", got " + selected);
        }
    }

    /** @return {@code true} when {@code option} is a selectable answer index. */
    public static boolean isSelectable(int option) {
        return option >= MIN_OPTION && option <= MAX_OPTION;
    }
}
