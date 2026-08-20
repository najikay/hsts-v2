package common.dto.bot;

import java.io.Serializable;
import java.util.Objects;

/**
 * A course's study bot, as a screen needs to introduce it (Common tier, E16.11 —
 * F12.1, F12.4).
 *
 * <p>One per course (S-30), so the course code identifies it just as well as the
 * row id does — and every verb in this feature is addressed by course code for
 * exactly that reason. The id travels anyway because notifications navigate to it
 * ({@code NotificationCatalog.botSourceChanged}) and because the manager screen
 * shows it in its header.
 *
 * @param botId      the row id
 * @param courseCode the 2-character course code (S-30: one bot per course)
 * @param courseName the course's display name, which is what the header shows
 * @param name       the bot's name, chosen by the teacher who created it
 * @param active     whether students may use it right now (F12.4)
 */
public record BotProfile(long botId,
                         String courseCode,
                         String courseName,
                         String name,
                         boolean active) implements Serializable {

    private static final long serialVersionUID = 1L;

    public BotProfile {
        Objects.requireNonNull(courseCode, "courseCode");
        courseName = courseName == null || courseName.isBlank() ? courseCode : courseName;
        name = name == null || name.isBlank() ? courseName + " study bot" : name;
    }

    /** @return the header line a screen shows above the conversation. */
    public String heading() {
        return name + " · " + courseName;
    }
}
