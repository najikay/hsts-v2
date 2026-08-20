package common.dto.bot;

import java.io.Serializable;
import java.util.Objects;

/**
 * A question the bot is asked a lot (Common tier, E16.11 — F12.11, S-34).
 *
 * <p>The teacher's most useful row: "eleven people asked what a foreign key is"
 * is a lesson plan. The text is a <em>normalised grouping key</em> rather than any
 * one student's wording — case folded, whitespace collapsed, trailing punctuation
 * dropped — which is what lets the same question asked eleven ways count as one,
 * and is also why this row cannot be traced back to whoever typed it.
 *
 * <p>Like {@link BotActivityPoint}, it has nowhere to put an identity. That is the
 * S-34 guarantee expressed as a type.
 *
 * @param question the normalised question text
 * @param count    how many times it was asked
 */
public record BotTopQuestion(String question, int count) implements Serializable {

    private static final long serialVersionUID = 1L;

    public BotTopQuestion {
        question = question == null ? "" : question;
        count = Math.max(0, count);
    }

    /** @return the count as the list labels it, singular when it is one. */
    public String timesLabel() {
        return count + (count == 1 ? " time" : " times");
    }
}
