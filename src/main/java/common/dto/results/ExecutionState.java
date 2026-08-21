package common.dto.results;

/**
 * An execution's lifecycle state, on the wire (Common tier, E14.1).
 *
 * <p>Mirrors the stored {@code ExecutionStatus} one-for-one, and exists for the same reason
 * every other wire enum does: the entity enum is a server type, and putting it on the wire
 * would drag persistence into the client JAR.
 *
 * <p>{@code CANCELLED} is declared here and <b>never sent</b> by the results verbs: a
 * cancelled execution was never sat, so it is excluded from a teacher's results list and its
 * id answers {@code NOT_FOUND} (H15.2). It is in the enum anyway because leaving it out would
 * make a future additive change a breaking one, and because a reader comparing this against
 * the schema should find four states rather than three and a mystery.
 */
public enum ExecutionState {

    /** Released with a window that has not opened yet. */
    SCHEDULED,

    /** Open right now; students may be sitting it. */
    LIVE,

    /** The window is over. The only state that carries frozen statistics. */
    CLOSED,

    /** Called off. Never appears in results (H15.2). */
    CANCELLED
}
