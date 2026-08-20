package client.features.exam;

/**
 * Where the two-step entry flow has got to (Presentation tier, E10.9 — F6.1).
 *
 * <p>Two screens and two endings. The split is not decoration: S-18 makes the identity
 * entry the moment the clock starts, so the paper must not exist on the client during
 * {@link #CODE}, and the student must be told, on {@link #IDENTITY}, that continuing costs
 * her time.
 */
public enum EntryPhase {

    /** Typing the 4-character code the teacher read out (C-1, S-17). */
    CODE,

    /** Confirming her own ID number, which starts the clock (S-18). */
    IDENTITY,

    /** The attempt is open: the form takes over. */
    STARTED,

    /**
     * The code was accepted but there is nothing to sit: she has already handed this exam
     * in, or the server force-submitted it (F6.7). A dead end with an explanation, which is
     * different from a dead end.
     */
    BLOCKED
}
