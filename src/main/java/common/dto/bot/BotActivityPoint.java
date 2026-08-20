package common.dto.bot;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One bar of the teacher's questions-over-time chart (Common tier, E16.11 —
 * F12.11, S-34).
 *
 * <p>A day and a count, and structurally nothing else. S-34 forbids student
 * identities anywhere in the analytics view <em>or its DTOs</em>, and this record
 * is one of the three types that make that a shape rather than a promise: there
 * is nowhere to put a name, an id or a session, so no mapper can add one by
 * accident and no reviewer has to check that none did.
 *
 * @param day   the day, in the server's UTC calendar
 * @param count how many questions were asked that day
 */
public record BotActivityPoint(LocalDate day, int count) implements Serializable {

    private static final long serialVersionUID = 1L;

    public BotActivityPoint {
        Objects.requireNonNull(day, "day");
        count = Math.max(0, count);
    }
}
