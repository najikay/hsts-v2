package common.dto.bot;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Everything the teacher's Bot Manager screen renders (Common tier, E16.11 —
 * F12.1/F12.3).
 *
 * <p>One round trip, one payload: the bot card and its sources arrive together,
 * because a screen that fetched them separately would have a frame in which the
 * card says "3 sources" above an empty table. Every mutating bot verb answers
 * with a fresh one of these for the same reason the notification verbs answer
 * with a whole page (E17.4): the screen re-renders from the server's own read
 * rather than patching a row it hopes it got right.
 *
 * <p>{@link #bot()} is {@code null} for a course that has no bot yet, which is a
 * state the screen has to draw rather than an error — that is the empty state
 * with the "Create the study bot" button, and it is the only way F12.1 can ever
 * start.
 *
 * @param bot     the course's bot, or {@code null} when it has not been created
 * @param sources its material, oldest first; empty when there is no bot
 */
public record BotManagerPage(BotProfile bot, List<BotSourceRow> sources) implements Serializable {

    private static final long serialVersionUID = 1L;

    public BotManagerPage {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** @return the page a course with no bot gets: nothing to show, everything to offer. */
    public static BotManagerPage none() {
        return new BotManagerPage(null, List.of());
    }

    /** @return a page for an existing bot. */
    public static BotManagerPage of(BotProfile bot, List<BotSourceRow> sources) {
        return new BotManagerPage(Objects.requireNonNull(bot, "bot"), sources);
    }

    /** @return {@code true} when this course already has a bot (S-30). */
    public boolean exists() {
        return bot != null;
    }

    /** @return how many pieces of material the bot answers from. */
    public int sourceCount() {
        return sources.size();
    }
}
