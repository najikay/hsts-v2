package client.features.bot;

import common.dto.bot.BotSpeaker;
import common.dto.bot.BotTurn;

import java.time.Instant;
import java.util.Objects;

/**
 * One bubble in the chat (Presentation tier, E16.13).
 *
 * <p>A view-side type rather than the wire's {@link BotTurn}, because the screen
 * needs one thing the wire does not have: {@link #pending}. A student's question
 * appears the instant she presses send, before the server has heard of it, and it
 * is drawn muted until the answer comes back. Without that flag the message list
 * would sit still for as long as the model takes to think, which reads as a broken
 * button.
 *
 * @param speaker who said it
 * @param text    what was said
 * @param at      when, UTC; the client renders it local
 * @param pending {@code true} while this is on screen but not yet acknowledged by
 *                the server
 */
public record ChatEntry(BotSpeaker speaker, String text, Instant at, boolean pending) {

    public ChatEntry {
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(at, "at");
        text = text == null ? "" : text;
    }

    /** @return the student's question, drawn muted until the server confirms it. */
    public static ChatEntry pendingQuestion(String text, Instant at) {
        return new ChatEntry(BotSpeaker.STUDENT, text, at, true);
    }

    /** @return a confirmed line of the conversation. */
    public static ChatEntry of(BotTurn turn) {
        Objects.requireNonNull(turn, "turn");
        return new ChatEntry(turn.speaker(), turn.text(), turn.at(), false);
    }

    /** @return this entry, no longer pending. */
    public ChatEntry confirmed() {
        return pending ? new ChatEntry(speaker, text, at, false) : this;
    }

    /** @return {@code true} when the student said it. */
    public boolean isFromStudent() {
        return speaker == BotSpeaker.STUDENT;
    }
}
