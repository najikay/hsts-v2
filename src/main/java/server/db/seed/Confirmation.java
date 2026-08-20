package server.db.seed;

/**
 * How the loader asks a human before destroying data (E2.15).
 *
 * <p>{@link SeedMode#RESEED} deletes every row in the database before reloading. That is the
 * right behaviour for a demo machine and catastrophic on any other, so it is never reached
 * without an answer from whoever asked for it. Both entry points supply their own
 * implementation of this interface: the command line reads a keystroke, and the E19.6 server
 * console button shows a dialog. The rule that a destructive reseed must be confirmed lives
 * here, once, rather than in each of them.
 *
 * <p>The prompt text is supplied by the loader rather than the caller, so the two entry
 * points cannot describe the same action differently.
 */
@FunctionalInterface
public interface Confirmation {

    /**
     * @param prompt what is about to happen, phrased for a human
     * @return {@code true} to proceed, {@code false} to cancel and change nothing
     */
    boolean confirm(String prompt);

    /**
     * A confirmation that always answers yes.
     *
     * <p><b>For tests and explicitly non-interactive callers only.</b> Wiring this into an
     * entry point a person can reach would remove the only guard between a stray click and an
     * empty database. If a caller genuinely has no way to ask, that is a reason to refuse the
     * reseed, not a reason to assume consent.
     *
     * @return a confirmation that grants without asking
     */
    static Confirmation preApproved() {
        return prompt -> true;
    }

    /**
     * A confirmation that always answers no, for testing the cancel path.
     *
     * @return a confirmation that refuses without asking
     */
    static Confirmation refused() {
        return prompt -> false;
    }
}
