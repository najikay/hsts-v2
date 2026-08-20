package common.dto.bot;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One row of the manager's sources table (Common tier, E16.11 — F12.3).
 *
 * <p><b>No bytes.</b> The uploaded PDF stays on the server: the table shows what
 * the source is, who added it and how much text came out of it, and nothing on
 * this record could carry a megabyte. That is deliberate rather than incidental —
 * {@code BotSource.raw} is a {@code MEDIUMBLOB} that loads with its row, so a
 * list built from entities would ship the whole library to draw a table (see the
 * entity's own javadoc). The server reads a scalar projection instead, and this
 * is its wire shape.
 *
 * @param sourceId   the row id, which {@code BOT_SOURCE_REMOVE} addresses
 * @param kind       PDF, Word or free text (F12.2)
 * @param title      what the teacher called it
 * @param addedBy    the display name of the teacher who added it, so co-teachers
 *                   can see whose material is whose (F12.3). A name, not an id:
 *                   nothing on this screen acts on the person
 * @param updatedAt  when it was last uploaded or re-uploaded, UTC
 * @param version    the content version, bumped on re-upload (§5)
 * @param characters how much text was extracted; the manager shows it so an
 *                   almost-empty parse is visible before a student finds it
 */
public record BotSourceRow(long sourceId,
                           BotSourceKind kind,
                           String title,
                           String addedBy,
                           Instant updatedAt,
                           int version,
                           int characters) implements Serializable {

    private static final long serialVersionUID = 1L;

    public BotSourceRow {
        Objects.requireNonNull(kind, "kind");
        title = title == null || title.isBlank() ? "Untitled source" : title;
        addedBy = addedBy == null ? "" : addedBy;
        characters = Math.max(0, characters);
        version = Math.max(1, version);
    }

    /**
     * @return the size line the table shows, in whole thousands once it is worth
     *         rounding. "1,240 characters" is noise in a column; "1.2k characters"
     *         is the fact the teacher actually wants
     */
    public String sizeLabel() {
        if (characters < 1000) {
            return characters + " characters";
        }
        return String.format(java.util.Locale.ROOT, "%.1fk characters", characters / 1000.0);
    }
}
