package common.dto.release;

/**
 * The two ways a teacher-chosen execution code can be refused (Common tier, E9 — C-1, S-16).
 *
 * <p>On the wire beside {@link ReleaseWindow}, and for the same reason: the create dialog
 * validates as she types and the server refuses the same code when it arrives anyway, so one
 * definition means the inline hint and the error that comes back cannot word the same problem
 * two ways.
 *
 * <p><b>The two are checked in different places, on purpose.</b> {@link #MALFORMED} is a rule
 * about the string, so both tiers run it and the client can grey out the button before
 * anything is sent. {@link #TAKEN} is a rule about the database and is <b>server-only</b>: §5
 * makes code uniqueness a service rule because the constraint is partial (a code is free again
 * once its sitting is over) and MySQL has no partial unique index, so the only honest place to
 * answer it is inside the transaction that inserts. A client that pre-checked it would be
 * answering from a picture that can change before the button is pressed.
 *
 * <p>Both sentences name the way out, and the way out is the same one: pick another, or leave
 * the field blank and let the server choose.
 */
public enum ReleaseCodeIssue {

    /** Not four letters or digits (C-1's shape, checked by both tiers). */
    MALFORMED("An exam code is 4 letters or digits. Change it, or leave it blank to generate one."),

    /** Four good characters, but a sitting students could still enter is holding them. */
    TAKEN("That code is in use by a live or scheduled sitting. Pick another or leave it blank "
            + "to generate one.");

    private final String sentence;

    ReleaseCodeIssue(String sentence) {
        this.sentence = sentence;
    }

    /** @return the message shown to the teacher, identical on both tiers. */
    public String sentence() {
        return sentence;
    }
}
