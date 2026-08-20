package client.features.bot;

/**
 * What the chat screen is doing, as one exhaustive answer (Presentation tier,
 * E16.13).
 *
 * <p>An enum rather than three booleans, for the reason {@code AsyncViewState} is
 * one: a screen renders from a state it switches on, and a switch over an enum
 * cannot have the "waiting and locked at the same time" case that a set of flags
 * eventually grows. PRD §4.1 forbids mystery states, and this is what makes the
 * absence of one checkable — there are five, every one of them has a rendering,
 * and {@code BotChatModelTest} exercises each.
 */
public enum ChatState {

    /** Ready for a question. */
    IDLE,

    /** A question is in flight; the typing indicator is showing. */
    THINKING,

    /**
     * The last ask failed in a way that is worth trying again — a dropped
     * connection, a timeout, an internal error. The question stays in the box.
     */
    RETRYABLE_ERROR,

    /**
     * The bot cannot be used at all right now: not enrolled, no bot, switched off,
     * or the C-4 same-course lockout. The banner carries the server's sentence and
     * the input is disabled, because offering a box that cannot send anything is
     * the mystery state §4.1 is about.
     */
    UNAVAILABLE,

    /**
     * The C-4 cross-course notice is waiting for an answer (ADR-018). The question
     * is held, the input is disabled, and exactly one of two things happens next:
     * she confirms and it is sent, or she declines and it goes back in the box.
     */
    NEEDS_ACKNOWLEDGEMENT;

    /** @return {@code true} when the user may type and send. */
    public boolean acceptsInput() {
        return this == IDLE || this == RETRYABLE_ERROR;
    }

    /** @return {@code true} when the typing indicator should be showing. */
    public boolean isThinking() {
        return this == THINKING;
    }

    /** @return {@code true} when a banner is showing instead of a usable composer. */
    public boolean isBlocked() {
        return this == UNAVAILABLE;
    }
}
