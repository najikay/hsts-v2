package common.dto.bot;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;

/**
 * Remove one source from a course's bot (Common tier, E16.11 — F12.3).
 *
 * <p>Carries the course as well as the row id, and not for convenience: the
 * server checks that the caller teaches <em>that</em> course and that the source
 * really belongs to <em>that</em> course's bot. A source id from somebody else's
 * course therefore answers {@code NOT_FOUND} rather than deleting anything, and
 * the check is a comparison rather than a lookup-then-trust.
 *
 * @param courseCode the course whose bot owns the source
 * @param sourceId   the {@code bot_sources} row to remove
 */
public record SourceRemoveRequest(String courseCode, long sourceId) implements Serializable {

    private static final long serialVersionUID = 1L;

    public SourceRemoveRequest {
        Objects.requireNonNull(courseCode, "courseCode");
        courseCode = courseCode.trim().toUpperCase(Locale.ROOT);
    }

    public boolean isWellFormed() {
        return !courseCode.isBlank() && sourceId > 0;
    }
}
