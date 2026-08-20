package common.dto.bot;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One of the student's own past conversations, reopened (Common tier, E16.11 —
 * F12.10, S-33).
 *
 * <p>The answer to {@code BOT_SESSION_GET}: the stored transcript, word for word,
 * so "reopen and continue" is a render rather than a reconstruction. The turns
 * come from {@code bot_sessions.transcript} and not from {@code bot_messages} —
 * the two are dual-written in one transaction (F12.9) and they exist for
 * different readers, this one being the student's.
 *
 * @param sessionId  the conversation
 * @param courseCode the course it belongs to
 * @param courseName that course's display name, for the header
 * @param startedAt  when it began, UTC
 * @param updatedAt  the last exchange in it, UTC
 * @param turns      the whole exchange, oldest first
 */
public record BotConversation(long sessionId,
                              String courseCode,
                              String courseName,
                              Instant startedAt,
                              Instant updatedAt,
                              List<BotTurn> turns) implements Serializable {

    private static final long serialVersionUID = 1L;

    public BotConversation {
        Objects.requireNonNull(courseCode, "courseCode");
        Objects.requireNonNull(startedAt, "startedAt");
        courseName = courseName == null || courseName.isBlank() ? courseCode : courseName;
        updatedAt = updatedAt == null ? startedAt : updatedAt;
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    /** @return how many questions the student asked in it. */
    public int questionCount() {
        return (int) turns.stream().filter(BotTurn::isFromStudent).count();
    }
}
