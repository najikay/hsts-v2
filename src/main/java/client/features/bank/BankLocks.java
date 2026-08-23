package client.features.bank;

import common.dto.lock.EntityRef;

/**
 * The one place a question becomes a lock key (E6.14 — E18.5, F10.3).
 *
 * <p>Edit locks are generic: {@link EntityRef} is a {@code (type, long id)} pair and the lock
 * service holds no domain knowledge at all. So somebody has to decide which number identifies a
 * question, and <b>there are two candidates</b>: the {@code questions} primary key, which the
 * legacy screen uses, and the five-digit display id, which is the only identifier the versioned
 * bank's wire carries. {@code BankQuestionRow} and {@code QuestionDetail} have no PK on them.
 *
 * <h2>The lead's ruling, and why it is a ruling rather than a preference ⚑</h2>
 *
 * <p>Two numbering schemes under one {@code entityType} means two teachers editing one question
 * can hold two different locks and never see each other. Asked on 2026-08-23 whether to add a
 * second {@code entityType} for the versioned bank, the lead ruled <b>no</b>: a second type would
 * keep that hazard alive for exactly as long as both screens exist, which is the window we would
 * be demoing in. Instead the legacy retirement folds into E6's last PR, and after it
 * {@code EntityRef.QUESTION} numbers by {@code displayId5} with no second scheme anywhere.
 *
 * <p><b>Which means this class is deliberately unsafe to use before that retirement lands</b>, and
 * saying so is the point of it existing: one method, one javadoc, one place to change if the
 * ruling ever moves. While the legacy screen is still on the rail, a teacher editing question
 * 11005 there and another editing it here are keyed differently and will not collide.
 *
 * @see QuestionEditorView the only caller today
 */
final class BankLocks {

    private BankLocks() {
    }

    /**
     * The lock key for a question.
     *
     * <p>{@code displayId5} is course(2) + serial(3), always five digits and always unique
     * ({@code uq_questions_display_id}), so it parses to a stable {@code long} and no two
     * questions can collide. A soft-deleted question keeps its id, which is what makes the key
     * safe across a delete.
     *
     * @param displayId5 the five-digit id from the wire
     * @return the lock reference
     * @throws IllegalArgumentException if the id is not the five digits the wire promises, which
     *         would be a protocol defect rather than a user's doing
     */
    static EntityRef of(String displayId5) {
        if (displayId5 == null || displayId5.isBlank()) {
            throw new IllegalArgumentException("a question needs a display id to be locked");
        }
        String trimmed = displayId5.strip();
        try {
            return EntityRef.question(Long.parseLong(trimmed));
        } catch (NumberFormatException notANumber) {
            // Deliberately loud. The ids come from the server, so a non-numeric one means the
            // wire disagrees with S-8 and every lock on this screen is keyed on nothing.
            throw new IllegalArgumentException(
                    "question display id '" + trimmed + "' is not numeric, so it cannot key a "
                            + "lock (S-8 says five digits)", notANumber);
        }
    }
}
