package common.dto.bot;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;

/**
 * Create the study bot for a course (Common tier, E16.11 — F12.1, S-30).
 *
 * <p>"Create" is a slight misnomer and the javadoc says so on purpose: one bot
 * per course is a unique key, so a second teacher of the same course sending this
 * does not get an error and does not get a rival bot. She gets the existing one
 * back and becomes a contributor to it — which is precisely what S-30 asks for
 * and what {@code BotAdminService} implements. A screen therefore never has to
 * ask "does this course already have a bot?" before offering the button; it
 * offers it, and the server answers with whichever bot the course now has.
 *
 * @param courseCode the course to create the bot for, normalised to upper case
 * @param name       what to call it; blank falls back to a name derived from the
 *                   course, because a bot with no name is a screen with a hole in
 *                   its header
 */
public record BotCreateRequest(String courseCode, String name) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The longest name the {@code bots.name} column takes (§5). */
    public static final int MAX_NAME = 100;

    public BotCreateRequest {
        Objects.requireNonNull(courseCode, "courseCode");
        courseCode = courseCode.trim().toUpperCase(Locale.ROOT);
        name = name == null ? "" : name.trim();
        if (name.length() > MAX_NAME) {
            name = name.substring(0, MAX_NAME);
        }
    }

    public boolean isWellFormed() {
        return !courseCode.isBlank();
    }
}
