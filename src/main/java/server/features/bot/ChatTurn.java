package server.features.bot;

import java.util.Objects;

/**
 * One prior exchange handed to a provider as conversation history (Logic tier,
 * E16.1).
 *
 * <p>Deliberately a server-side type rather than the wire's
 * {@code common.dto.bot.BotTurn}, and deliberately without a timestamp. A
 * provider is being told what was already said so its next answer follows on; it
 * has no use for when, and every field a prompt does not need is a field that
 * could end up in one by accident.
 *
 * <p>{@link #user} / {@link #assistant} name the two roles the OpenAI-compatible
 * and Anthropic message formats both use, so neither adapter has to translate a
 * vocabulary of ours into theirs.
 *
 * @param fromStudent {@code true} when the student said it, {@code false} for the bot
 * @param text        what was said
 */
public record ChatTurn(boolean fromStudent, String text) {

    public ChatTurn {
        text = text == null ? "" : text;
    }

    /** @return a turn the student spoke. */
    public static ChatTurn user(String text) {
        return new ChatTurn(true, text);
    }

    /** @return a turn the bot spoke. */
    public static ChatTurn assistant(String text) {
        return new ChatTurn(false, text);
    }

    /** @return the role name both provider APIs use for this speaker. */
    public String role() {
        return fromStudent ? "user" : "assistant";
    }

    /** @return {@code true} when this turn carries nothing worth sending. */
    public boolean isBlank() {
        return text.isBlank();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ChatTurn that
                && fromStudent == that.fromStudent
                && Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromStudent, text);
    }
}
