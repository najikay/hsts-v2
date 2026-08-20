package common.dto.bot;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One line of a conversation (Common tier, E16.11 — S-33, F12.9/F12.10).
 *
 * <p>The wire shape of a stored transcript turn. It carries a timestamp because
 * the history screen shows when a conversation happened, and because "every Q/A
 * pair persisted with a timestamp" is the requirement itself (F12.9).
 *
 * @param speaker who said it
 * @param text    what was said
 * @param at      when, UTC
 */
public record BotTurn(BotSpeaker speaker, String text, Instant at) implements Serializable {

    private static final long serialVersionUID = 1L;

    public BotTurn {
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(at, "at");
        text = text == null ? "" : text;
    }

    /** @return a student's question at {@code at}. */
    public static BotTurn asked(String text, Instant at) {
        return new BotTurn(BotSpeaker.STUDENT, text, at);
    }

    /** @return the bot's answer at {@code at}. */
    public static BotTurn answered(String text, Instant at) {
        return new BotTurn(BotSpeaker.BOT, text, at);
    }

    /** @return {@code true} when this is the student's own line. */
    public boolean isFromStudent() {
        return speaker == BotSpeaker.STUDENT;
    }
}
