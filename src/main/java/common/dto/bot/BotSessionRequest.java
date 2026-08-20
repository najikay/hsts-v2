package common.dto.bot;

import java.io.Serializable;

/**
 * "Reopen this conversation of mine" (Common tier, E16.11 — F12.10).
 *
 * <p>The request payload of {@code BOT_SESSION_GET}, and the only student verb in
 * this feature that names a row. It is safe to name one because the read is
 * scoped to the caller in the query itself: a session id belonging to a classmate
 * answers {@code NOT_FOUND}, which is also what an id that never existed answers,
 * so the verb cannot be used to discover that somebody else's conversation exists.
 *
 * @param sessionId the conversation to reopen
 */
public record BotSessionRequest(long sessionId) implements Serializable {

    private static final long serialVersionUID = 1L;

    public boolean isWellFormed() {
        return sessionId > 0;
    }
}
