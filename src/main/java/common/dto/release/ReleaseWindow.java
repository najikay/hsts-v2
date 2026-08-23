package common.dto.release;

/**
 * The four ways a release window can be wrong, and what each of them says (Common tier,
 * E9 — F5.2, PRD §4.1).
 *
 * <p>On the <b>wire</b> side rather than in a server message catalogue, and that is the
 * point: the create dialog validates as the teacher types, before anything is sent, and the
 * server refuses the same window with the same sentence when it arrives anyway. One
 * definition means the inline hint under the field and the error that comes back cannot
 * word the same problem two ways, which is what happens every time a client keeps its own
 * copy of a server rule.
 *
 * <p>Every sentence names the fix, because PRD §4.1 requires it and because "invalid dates"
 * is exactly the dead end that rule exists to stop.
 */
public enum ReleaseWindow {

    /** A date picker left empty. */
    MISSING("Pick both an opening time and a closing time for this exam."),

    /** close &le; open, the F5.2 rule stated the way it fails. */
    CLOSE_NOT_AFTER_OPEN("The closing time has to be after the opening time. Move one of them and try again."),

    /** A window so short nobody could sit the exam in it. */
    TOO_SHORT("The window has to be at least a minute long. Move the closing time later and try again."),

    /** An opening moment well behind the server's clock. */
    IN_THE_PAST("That opening time has already passed. Pick a time from now on and try again.");

    private final String sentence;

    ReleaseWindow(String sentence) {
        this.sentence = sentence;
    }

    /** @return the message shown to the teacher, identical on both tiers. */
    public String sentence() {
        return sentence;
    }
}
