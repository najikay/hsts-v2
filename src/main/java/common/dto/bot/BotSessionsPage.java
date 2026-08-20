package common.dto.bot;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * The student's own conversations with one course's bot (Common tier, E16.11 —
 * F12.10).
 *
 * <p>The answer to {@code BOT_SESSIONS_GET}. "Own" is a property of the query
 * behind it, not of a filter applied afterwards: the rows are selected
 * {@code WHERE student_id = :caller}, the same silent scoping the notifications
 * feature uses, so another student's conversation is not something this verb can
 * return whatever the payload says.
 *
 * @param courseCode the course
 * @param courseName its display name, for the header
 * @param sessions   her conversations, most recently used first
 */
public record BotSessionsPage(String courseCode,
                              String courseName,
                              List<BotSessionRow> sessions) implements Serializable {

    private static final long serialVersionUID = 1L;

    public BotSessionsPage {
        Objects.requireNonNull(courseCode, "courseCode");
        courseName = courseName == null || courseName.isBlank() ? courseCode : courseName;
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
    }

    /** @return a page for a student who has never used this bot. */
    public static BotSessionsPage empty(String courseCode, String courseName) {
        return new BotSessionsPage(courseCode, courseName, List.of());
    }

    public boolean isEmpty() {
        return sessions.isEmpty();
    }
}
