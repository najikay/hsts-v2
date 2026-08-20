package common.dto.bot;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One row of the student's own history list (Common tier, E16.11 — F12.10).
 *
 * <p>A summary, not a transcript: the list shows when a conversation happened,
 * how many questions were in it and the first one, and the whole exchange arrives
 * only when she opens it ({@code BOT_SESSION_GET}). A history screen for a term's
 * worth of studying otherwise ships every answer the bot ever gave in order to
 * draw a list of dates.
 *
 * @param sessionId     the conversation, which reopening addresses
 * @param startedAt     when it began, UTC
 * @param updatedAt     the last exchange in it, UTC; what the list sorts on
 * @param questionCount how many questions she asked
 * @param preview       her first question, truncated; the line that makes a row
 *                      recognisable a fortnight later
 */
public record BotSessionRow(long sessionId,
                            Instant startedAt,
                            Instant updatedAt,
                            int questionCount,
                            String preview) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** How much of the first question the list shows. */
    public static final int PREVIEW_LENGTH = 90;

    public BotSessionRow {
        Objects.requireNonNull(startedAt, "startedAt");
        updatedAt = updatedAt == null ? startedAt : updatedAt;
        questionCount = Math.max(0, questionCount);
        preview = truncate(preview);
    }

    /**
     * Cuts a first question down to a list row.
     *
     * <p>Whitespace is collapsed first: a question typed across three lines is one
     * sentence, and a preview that renders its newlines makes every row in the
     * list a different height.
     */
    private static String truncate(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (flat.length() <= PREVIEW_LENGTH) {
            return flat;
        }
        return flat.substring(0, PREVIEW_LENGTH).trim() + "…";
    }

    /** @return the count as the list labels it, singular when it is one. */
    public String questionLabel() {
        return questionCount + (questionCount == 1 ? " question" : " questions");
    }
}
