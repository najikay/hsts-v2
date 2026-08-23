package server.features.release;

import server.db.entities.ExecutionStatus;

/**
 * Every sentence the release manager is allowed to say (Logic tier, E9 — PRD §4.1).
 *
 * <p>In one class for the same three reasons {@code ExamMessages} and
 * {@code NotificationCatalog} are: the copy rules become enforceable by one scan (no em
 * dashes, sentence case, and <b>every error says what to do next</b>), the wording is
 * reviewed once rather than once per verb, and the client has a single source of truth on
 * the side that decides the refusal.
 *
 * <p>The window sentences are <b>not</b> here. They live on
 * {@link common.dto.release.ReleaseWindow}, on the wire, because the create dialog validates
 * as the teacher types and the server refuses the same window when it arrives anyway; two
 * copies of one rule in two tiers is how one of them ends up wrong.
 *
 * <h2>Why the two refusals of a live release are different sentences</h2>
 *
 * <p>"Cancel a live exam" and "close a scheduled exam early" are the two mistakes this
 * screen invites, and each has a different right answer: one teacher wants the other
 * button, the other wants to wait. A shared "that is not allowed" would leave both of them
 * looking at a screen with two buttons and no idea which.
 */
public final class ReleaseMessages {

    private ReleaseMessages() {
    }

    // ===================== Creating a release ============================

    /**
     * The F5.1 sentence: an unapproved version cannot be released (S-14).
     *
     * <p>The picker never offers one, so a teacher only ever reaches this by holding a
     * dialog open while her coordinator sent the exam back, or by sending the verb by hand.
     * The first of those is a real person having a normal afternoon, which is why the
     * sentence explains the rule and names who unblocks it rather than saying "refused".
     */
    public static final String VERSION_NOT_APPROVED =
            "Only an approved exam can be released. Ask your subject coordinator to approve "
                    + "this version, then release it.";

    /** An exam version id nobody has, or one outside the courses she teaches. */
    public static final String VERSION_UNKNOWN =
            "That exam could not be found in your courses. Open the list again and pick one of "
                    + "your approved exams.";

    /** The generator could not find a free code, which needs a colliding school. */
    public static final String CODE_EXHAUSTED =
            "A code could not be generated just now. Close a finished exam and try again, or "
                    + "ask for help.";

    // ===================== Acting on a release ===========================

    /**
     * An execution id that is not hers, and one that does not exist.
     *
     * <p><b>One sentence for both, deliberately</b>, and this is where E9 diverges from
     * E11's {@code NOT_YOUR_EXECUTION}. The monitor answers {@code FORBIDDEN} because a
     * teacher who reached that screen already knows the execution exists and telling her
     * whose it is, is what lets her go and ask the right colleague. The release manager
     * lists exactly the releases she may act on, so an id that is not in her own list did
     * not come from this screen, and answering it as a probe rather than as a colleague's
     * work is the right default. The two verbs therefore answer {@code NOT_FOUND}
     * indistinguishably.
     */
    public static final String RELEASE_UNKNOWN =
            "That release could not be found. Open your releases again and pick one of yours.";

    /** Cancelling something that has already opened (F5.5). */
    public static final String CANCEL_NOT_SCHEDULED =
            "This exam has already opened, so it cannot be cancelled. Use close early to end it "
                    + "now.";

    /** Cancelling something that is already over. */
    public static final String CANCEL_ALREADY_OVER =
            "This exam is already over, so there is nothing to cancel. Open your releases to see "
                    + "its results.";

    /** Closing early something that never opened (F5.5). */
    public static final String CLOSE_NOT_LIVE =
            "Only a live exam can be closed early. Cancel it instead if you want to call it off.";

    /** Two teachers acting on the same release at the same moment. */
    public static final String RELEASE_RACED =
            "Someone else changed this release a moment ago. Open your releases again to see "
                    + "where it stands.";

    /** Payload of the wrong type, i.e. a client bug or a hostile peer. */
    public static final String MALFORMED_REQUEST =
            "That request could not be read. Open your releases again and try again.";

    // ===================== Helpers =======================================

    /**
     * The right refusal for a cancel that arrived too late.
     *
     * @param status what state the release is actually in
     * @return the sentence to send
     */
    public static String cannotCancel(ExecutionStatus status) {
        return status == ExecutionStatus.LIVE ? CANCEL_NOT_SCHEDULED : CANCEL_ALREADY_OVER;
    }
}
