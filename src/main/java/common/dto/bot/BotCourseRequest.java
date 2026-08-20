package common.dto.bot;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;

/**
 * "Tell me about this course's bot" (Common tier, E16.11).
 *
 * <p>The request payload of {@code BOT_MANAGER_GET}, {@code BOT_SESSIONS_GET}
 * and {@code BOT_ANALYTICS_GET}. One record for three verbs because they all ask
 * the same question and differ only in what they are allowed to answer, which is
 * the server's business and not the payload's.
 *
 * <p><b>There is no user id here, and there is no bot id either.</b> The caller is
 * the session bound to the socket (P-5), and the bot is whichever one belongs to
 * this course (S-30) — so neither could be anything but redundant or someone
 * else's. The one place this feature does put a row id on the wire is
 * {@link BotSessionRequest}, where the student is naming one of her own past
 * conversations.
 *
 * @param courseCode the 2-character course code, normalised to upper case
 */
public record BotCourseRequest(String courseCode) implements Serializable {

    private static final long serialVersionUID = 1L;

    public BotCourseRequest {
        Objects.requireNonNull(courseCode, "courseCode");
        courseCode = courseCode.trim().toUpperCase(Locale.ROOT);
    }

    /** @return {@code true} when the code could plausibly be a course code. */
    public boolean isWellFormed() {
        return !courseCode.isBlank();
    }
}
