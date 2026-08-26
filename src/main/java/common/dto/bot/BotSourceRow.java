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
 * @param text       the pasted text of a {@link BotSourceKind#TEXT} source, so the
 *                   Edit dialog opens on what is actually stored (B-21); {@code null}
 *                   for PDF and Word rows, deliberately — see below
 */
public record BotSourceRow(long sourceId,
                           BotSourceKind kind,
                           String title,
                           String addedBy,
                           Instant updatedAt,
                           int version,
                           int characters,
                           String text) implements Serializable {

    private static final long serialVersionUID = 2L;

    public BotSourceRow {
        Objects.requireNonNull(kind, "kind");
        title = title == null || title.isBlank() ? "Untitled source" : title;
        addedBy = addedBy == null ? "" : addedBy;
        characters = Math.max(0, characters);
        version = Math.max(1, version);
        // Only a TEXT source carries its body. The class javadoc's "no bytes" rule is intact:
        // this is a human-typed paste, not a parse artefact, and a PDF's extracted text would
        // put megabytes on a table's wire for a dialog it cannot usefully open anyway.
        if (kind != BotSourceKind.TEXT) {
            text = null;
        }
    }

    /**
     * The pre-B-21 shape: a row with no editable body.
     *
     * <p>Retained so every construction site written before the edit verb keeps compiling and
     * keeps meaning what it meant — a table row, and nothing an editor could open.
     *
     * @param sourceId   the row id
     * @param kind       PDF, Word or free text
     * @param title      what the teacher called it
     * @param addedBy    the display name of the teacher who added it
     * @param updatedAt  when it was last uploaded, UTC
     * @param version    the content version
     * @param characters how much text was extracted
     */
    public BotSourceRow(long sourceId, BotSourceKind kind, String title, String addedBy,
                        Instant updatedAt, int version, int characters) {
        this(sourceId, kind, title, addedBy, updatedAt, version, characters, null);
    }

    /**
     * @return {@code true} when this row can be opened in the Edit dialog (B-21 ⚑).
     *
     * <p><b>Free text only, and stated rather than hidden.</b> Editing a pasted source is
     * fixing a typo in something a human wrote, and the dialog can open on exactly what is
     * stored. A PDF or a Word file has no such body: what the row holds is the parse, not the
     * document, so an "edit" of one could only ever mean choosing a replacement file — which
     * is what {@code BOT_SOURCE_UPDATE} would do, and which is indistinguishable from removing
     * it and adding the new one except that it keeps the id. That is a real difference and it
     * is a smaller one than it sounds, so file kinds keep Remove and Add and the affordance
     * that would imply in-place editing is not offered for them.
     */
    public boolean isEditable() {
        return kind == BotSourceKind.TEXT;
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
