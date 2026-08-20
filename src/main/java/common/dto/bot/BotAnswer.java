package common.dto.bot;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * What {@code BOT_ASK} answers with (Common tier, E16.11 — F12.5, S-32).
 *
 * <p>It carries the session id because the first question of a conversation is
 * what creates the session: the client sends {@code null} and gets back the id it
 * uses for every follow-up. It carries the question back as well as the answer so
 * a client rendering the exchange has both halves from one payload rather than
 * pairing an answer with what it hopes it asked.
 *
 * <p><b>It does not carry which provider answered.</b> That is deliberate and it
 * is the S-32 rule: DeepSeek falling over and Anthropic taking the question is
 * not the student's problem, and a "degraded" badge on her screen would make it
 * hers. The provider is recorded in {@code bot_messages.provider} and in one
 * structured log line per ask, which is where the people who can act on it look
 * (ADR-009).
 *
 * @param sessionId the conversation this exchange belongs to
 * @param question  what she asked, as the server stored it
 * @param answer    what the bot said; never blank, because a blank answer is the
 *                  {@link #S32_FALLBACK} case and the server substitutes it
 * @param askedAt   when, UTC
 */
public record BotAnswer(long sessionId,
                        String question,
                        String answer,
                        Instant askedAt) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The sentence S-32 requires when there is no usable answer (F12.7).
     *
     * <p>Word for word, and it lives here rather than in the server so the one
     * test that checks the copy rules and the one test that checks the fallback
     * path are looking at the same string. No em dash, and it says what to do
     * next — both PRD §4.1 rules, in the sentence a student is most likely to see
     * on a bad day.
     */
    public static final String S32_FALLBACK =
            "The bot could not answer that. Try rephrasing, or ask your teacher.";

    public BotAnswer {
        Objects.requireNonNull(askedAt, "askedAt");
        question = question == null ? "" : question;
        answer = answer == null || answer.isBlank() ? S32_FALLBACK : answer;
    }

    /** @return the S-32 answer for a question no provider could handle. */
    public static BotAnswer unanswered(long sessionId, String question, Instant at) {
        return new BotAnswer(sessionId, question, S32_FALLBACK, at);
    }

    /** @return {@code true} when this is the S-32 fallback rather than a real answer. */
    public boolean isFallback() {
        return S32_FALLBACK.equals(answer);
    }

    /** @return the exchange as the two turns a chat screen appends. */
    public BotTurn asked() {
        return BotTurn.asked(question, askedAt);
    }

    /** @return the bot's half of the exchange. */
    public BotTurn answered() {
        return BotTurn.answered(answer, askedAt);
    }
}
