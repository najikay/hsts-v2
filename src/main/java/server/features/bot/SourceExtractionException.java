package server.features.bot;

/**
 * A source could not be turned into text (Logic tier, E16.5 — F12.2).
 *
 * <p>Checked on purpose. F12.2 requires a parse failure to reach the uploader
 * immediately, with a sentence she can act on, and a checked exception is what
 * makes the compiler insist that {@code BotAdminService} decides what to say
 * rather than letting a runtime failure become the router's generic "something
 * went wrong".
 *
 * <p>{@link #getMessage()} is written for the teacher, not for the log: it is the
 * one exception message in this feature that goes straight onto the wire. The
 * underlying cause travels as {@code getCause()} and stays in the log.
 */
public class SourceExtractionException extends Exception {

    private static final long serialVersionUID = 1L;

    public SourceExtractionException(String message) {
        super(message);
    }

    public SourceExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
