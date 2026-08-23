package server.features.reports;

/**
 * Every sentence the report verbs can refuse with (Logic tier, E15.3).
 *
 * <p>The {@code ResultsMessages} discipline: refusals live in one file so the house rule can be
 * checked by reading it rather than by grepping handlers. Each one says what happened and what
 * to do next, because a principal who is told "no" and nothing else has a dead end (section 4.1)
 * and a support conversation.
 */
public final class ReportMessages {

    /** The payload was missing or of the wrong type: a client bug, phrased for a person. */
    public static final String MALFORMED_REQUEST =
            "That report request could not be read. Choose a report type and a subject again.";

    /**
     * The dimension is not one this server serves.
     *
     * <p>Only reachable when the two sides are different builds, so the sentence names that
     * rather than blaming the choice she just made in a picker this server drew.
     */
    public static final String UNKNOWN_DIMENSION =
            "This server does not have that report type. Update the client, or ask for the "
                    + "report type list again.";

    /**
     * The subject does not exist, or no longer does.
     *
     * <p>Not a permission refusal and not phrased as one: the principal reads school-wide
     * (F9.3), so the only way to reach this is an id that has been deleted, renamed, or typed by
     * something other than the picker.
     */
    public static final String NO_SUCH_SUBJECT =
            "That is not something this report can be run about any more. Pick a subject from "
                    + "the list and try again.";

    private ReportMessages() {
        // constants only - no instances
    }
}
