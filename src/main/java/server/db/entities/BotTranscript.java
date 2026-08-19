package server.db.entities;

import java.time.Instant;
import java.util.List;

/**
 * A study-bot conversation — the {@code transcript} JSON column of
 * {@code bot_sessions} (V6, §5, S-33, F12.9).
 *
 * <p>This is the copy the <em>student</em> sees: their own history, reopened and
 * continued (F12.10). It is deliberately not what analytics read — every turn is also
 * written as a normalised {@link BotMessage} row in the same transaction, and the
 * teacher aggregates of S-34 query that table. Keeping the two apart is what lets the
 * analytics DTOs carry no identity fields at all while the student still gets their
 * conversation back verbatim.
 *
 * @param turns the exchange in order, oldest first
 */
public record BotTranscript(List<Turn> turns) {

    public BotTranscript {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    /** An empty conversation, as a new session starts. */
    public static BotTranscript empty() {
        return new BotTranscript(List.of());
    }

    /**
     * One line of the conversation.
     *
     * @param role who spoke — {@code "student"} or {@code "bot"}
     * @param text what was said
     * @param at   when, UTC
     */
    public record Turn(String role, String text, Instant at) {
    }
}
