package server.features.approval;

/**
 * Every sentence the approval workflow is allowed to say (Logic tier, E8 — PRD §4.1).
 *
 * <p>Written once, here, for the reasons {@code ExamMessages} and
 * {@code NotificationCatalog} exist: the copy rules are then enforceable by one test (no em
 * dashes, sentence case, and <b>every error says what to do next</b>), and the wording is
 * reviewed once rather than once per handler.
 *
 * <p>Each sentence below names the actual problem and the actual next step. The two that
 * matter most are the {@code CONFLICT} pair: a coordinator whose decision was refused because
 * the row moved has done nothing wrong, and telling her only "conflict" would leave her
 * pressing the same button on the same stale screen.
 */
public final class ApprovalMessages {

    private ApprovalMessages() {
    }

    // ===================== The supersede sentence ========================

    /**
     * The fixed reason stored on a version that a newer submission replaced (E8.2).
     *
     * <p><b>Exact and constant on purpose.</b> It is written into
     * {@code exam_versions.rejected_reason} of rows nobody typed a reason for, and the
     * teacher who reads it needs to be able to tell instantly that this was the system and
     * not her coordinator's opinion. A varying sentence would be indistinguishable from a
     * short human rejection; this one is recognisable, greppable in support, and the same in
     * every row it ever lands in.
     *
     * <p>It also has to survive being read on its own, with no context: the version number is
     * deliberately not interpolated, because the row it sits on already says which version
     * it is.
     */
    public static final String SUPERSEDED_REASON =
            "Superseded by a newer version. You submitted a newer version of this exam, "
                    + "so this one was withdrawn from the approval queue. "
                    + "Open the newest version to see where it stands.";

    // ===================== Refusals ======================================

    /** Payload of the wrong type, i.e. a client bug or a hostile peer. */
    public static final String MALFORMED_REQUEST =
            "That request could not be read. Open the approvals list again and try once more.";

    /** An exam version id nobody has. */
    public static final String VERSION_UNKNOWN =
            "That exam could not be found. Open the approvals list again to see what is waiting.";

    /** A decision on a version that is not waiting for one. */
    public static final String NOT_PENDING =
            "This exam is not waiting for approval any more. "
                    + "Open the approvals list again to see its current state.";

    /**
     * The decision was taken against a row that has since moved.
     *
     * <p>Says "open it again" rather than "try again", because retrying the same click would
     * fail identically: the screen is holding a version of the row that no longer exists, and
     * the only fix is a fresh read.
     */
    public static final String DECISION_RACED =
            "This exam changed while you were looking at it. "
                    + "Open it again to see the current version before you decide.";

    // A refusal for "not yours to review" is deliberately NOT declared here. The preview
    // path lets Authorization.requireCoordinatorOf throw its own message, which names the
    // subject the caller does not coordinate and says whom to ask. A second sentence here
    // would be a second thing to keep in step with the guard, and the vaguer of the two.

    // ===================== Log lines =====================================

    /**
     * The F4.3 self-approval marker, at the front of the WARN line (acceptance case 4.6).
     *
     * <p>A constant rather than an inline literal because it is an <b>interface</b>: PRD F4.3
     * permits a coordinator to approve her own exam on condition that it is recorded, and
     * acceptance case 4.6 checks the record by searching the server log for this token. A
     * reworded log line is therefore a broken acceptance test, and the way to make that
     * visible is to give the token a name and a test of its own
     * ({@code ApprovalServiceTest.selfApprovalIsLogged}).
     */
    public static final String SELF_APPROVAL_MARKER = "SELF-APPROVAL";
}
