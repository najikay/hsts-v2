package server.db.projections;

import server.db.entities.BotSourceType;

import java.time.Instant;

/**
 * One study-bot source, without its bytes (E2.11 / E16.9).
 *
 * <p>The projection the manager's sources table is built from, and the reason it
 * exists is spelled out in {@code BotSource}'s own javadoc: {@code raw} is a
 * {@code MEDIUMBLOB} and a basic attribute, so it loads with the entity whatever
 * fetch hint is written next to it — this build does no bytecode enhancement, and
 * the annotation that would help is silently ignored. A list of ten sources built
 * from entities therefore drags ten PDFs into memory to draw ten rows.
 *
 * <p>{@code extracted_text} is not selected either, for the same reason at a
 * smaller scale; the table shows {@link #characters} instead, which the query
 * computes in the database.
 *
 * <p>Deliberately not {@code Serializable}: the wire type is
 * {@code common.dto.bot.BotSourceRow} and this maps into it. A projection that
 * could travel is a projection that eventually does.
 *
 * @param sourceId   the {@code bot_sources} row
 * @param botId      the bot it belongs to, so a caller can check ownership without
 *                   a second read
 * @param type       PDF, DOCX or TEXT
 * @param title      what the teacher called it
 * @param addedBy    the teacher's user id; mapped to a display name before it goes
 *                   on the wire, because nothing on that screen acts on the person
 * @param updatedAt  when it was last uploaded, UTC
 * @param version    the content version, bumped on re-upload
 * @param characters how much text was extracted
 */
public record BotSourceInfo(long sourceId,
                            long botId,
                            BotSourceType type,
                            String title,
                            long addedBy,
                            Instant updatedAt,
                            int version,
                            int characters) {

    public BotSourceInfo {
        title = title == null ? "" : title;
    }
}
