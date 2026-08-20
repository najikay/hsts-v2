package common.dto.bot;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;

/**
 * Switch a course's bot on or off (Common tier, E16.11 — F12.4, S-31).
 *
 * <p>An absolute target rather than a toggle: the request says what the state
 * should <em>be</em>, not that it should flip. Two co-teachers clicking the same
 * switch a second apart therefore agree with each other instead of undoing each
 * other, which a "toggle" verb cannot promise.
 *
 * @param courseCode the course whose bot to switch
 * @param active     the state to put it in
 */
public record BotActiveRequest(String courseCode, boolean active) implements Serializable {

    private static final long serialVersionUID = 1L;

    public BotActiveRequest {
        Objects.requireNonNull(courseCode, "courseCode");
        courseCode = courseCode.trim().toUpperCase(Locale.ROOT);
    }

    public boolean isWellFormed() {
        return !courseCode.isBlank();
    }
}
