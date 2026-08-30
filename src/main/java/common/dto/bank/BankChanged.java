package common.dto.bank;

import java.io.Serializable;
import java.util.Objects;

/**
 * The payload of {@code PUSH_BANK_CHANGED}: one course's question bank moved (Common tier,
 * E6 — U-63, NFR-18, amendment A4).
 *
 * <h2>A notice, not a delta ⚑</h2>
 *
 * <p><b>This record deliberately does not carry the question.</b> It would have been easy to
 * ship the {@link BankQuestionRow} that was just written, and every screen would then have had
 * to decide whether that row belongs on the page it is showing — under its course filter, its
 * topic filter, its difficulty filter, its search text, its page number and the server's own
 * ordering. Six clients would each have re-implemented the server's {@code BankQuery}, badly,
 * and the first filter combination nobody thought about would silently show a row that a
 * {@code BANK_LIST} would never have returned. So the push says <em>something in this course
 * changed</em> and every subscriber answers by re-reading its own list with its own request.
 * That is the only shape in which a teacher filtered to Algebra and a coordinator filtered to
 * nothing are both correct afterwards.
 *
 * <p>It is the opposite call to {@code PUSH_EXECUTION_STATUS}, which does carry a whole row,
 * and the two are consistent rather than in conflict: that list has one shape for every
 * recipient, so a row is unambiguous there. This one does not.
 *
 * <h2>What the fields are for</h2>
 *
 * <p>{@link #courseCode()} is the only field a subscriber must act on: it lets a screen already
 * narrowed to another course ignore the push instead of re-reading a list that cannot have
 * changed. {@link #displayId5()} and {@link #change()} exist for the open detail pane and for
 * the log line; a screen is free to ignore both, and one that only re-reads its list is still
 * correct.
 *
 * <h2>It discloses nothing</h2>
 *
 * <p>The push goes only to people who can read that course, so the course code, a five-digit
 * id and the word "created" tell a recipient nothing a {@code BANK_LIST} would have withheld.
 * No text, no topic, no answer key: a notice needs none of them, and a payload that carried
 * them would be a second, ungated path to the bank's contents.
 *
 * @param courseCode the two-character course whose bank moved; never blank
 * @param displayId5 the five-digit id of the question that changed, or {@code null} when the
 *                   producer has no single id to name
 * @param change     what happened to it
 */
public record BankChanged(String courseCode, String displayId5, Change change)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Which of the three bank writes this was.
     *
     * <p>Named rather than boolean-flagged so a fourth kind cannot arrive as an overloaded
     * meaning of an existing one. The client uses it for one decision only: a
     * {@link #DELETED} question that is the one open in the detail pane is gone rather than
     * revised, and re-reading it will fail rather than return a newer version.
     */
    public enum Change {

        /** A new question and its first version (E6.1). */
        CREATED,

        /** Version n+1 of a question that was already there (E6.3). */
        UPDATED,

        /** A soft delete: the question leaves every list (E6.4). */
        DELETED
    }

    public BankChanged {
        Objects.requireNonNull(courseCode, "courseCode");
        Objects.requireNonNull(change, "change");
        // Course codes are CHAR(2) under a PAD SPACE collation, so a code read back from MySQL
        // can carry trailing space that the code a client compares it against does not. Both
        // ends are stripped here, once, rather than in every subscriber that compares them.
        courseCode = courseCode.strip();
        if (courseCode.isEmpty()) {
            throw new IllegalArgumentException("courseCode must not be blank");
        }
        displayId5 = displayId5 == null || displayId5.isBlank() ? null : displayId5.strip();
    }

    /**
     * @param courseCode the course whose bank moved
     * @param displayId5 the question created
     * @return the notice for a create
     */
    public static BankChanged created(String courseCode, String displayId5) {
        return new BankChanged(courseCode, displayId5, Change.CREATED);
    }

    /**
     * @param courseCode the course whose bank moved
     * @param displayId5 the question revised
     * @return the notice for an update
     */
    public static BankChanged updated(String courseCode, String displayId5) {
        return new BankChanged(courseCode, displayId5, Change.UPDATED);
    }

    /**
     * @param courseCode the course whose bank moved
     * @param displayId5 the question removed
     * @return the notice for a delete
     */
    public static BankChanged deleted(String courseCode, String displayId5) {
        return new BankChanged(courseCode, displayId5, Change.DELETED);
    }

    /**
     * @param code a course code a screen is currently narrowed to, or {@code null} for "every
     *             course I can reach"
     * @return whether a screen filtered that way has to re-read. A screen with no course filter
     *         always does; a screen narrowed to another course never does. Trailing space is
     *         stripped from the argument for the reason the compact constructor gives
     */
    public boolean concerns(String code) {
        return code == null || code.isBlank() || courseCode.equals(code.strip());
    }
}
