package common.dto.bot;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Replace one source's title and content in place (Common tier, E16.11 — F12.3 ⚑, B-21).
 *
 * <p>The verb F12.3 asked for and nothing implemented. Until B-21 the sources list could add
 * and remove and nothing else, so a teacher correcting a typo in a pasted handout had to
 * delete the row and paste it again — losing the source id, its author, its {@code updated_at}
 * and its version, and losing them <b>silently</b>, because the remove notified her colleagues
 * as a removal and the re-add as an addition. One correction read as two unrelated events.
 *
 * <p><b>The same shape as {@link SourceAddRequest}, plus the row it addresses.</b> Deliberately
 * not a subtype and not a shared record: an add creates and an update replaces, they answer to
 * different rules on the server (an update consults the edit lock and can answer
 * {@code NOT_FOUND}), and one record with a nullable id is how a handler ends up doing the
 * wrong one of the two.
 *
 * <p>Carries the course as well as the row id, on the same rule the remove does: the server
 * checks that the caller teaches <em>that</em> course and that the source belongs to
 * <em>that</em> course's bot, so a source id from somebody else's course answers
 * {@code NOT_FOUND} rather than overwriting anything.
 *
 * <p>The bytes travel and the parsing does not, exactly as on the add path: PDFBox and POI run
 * on the server, extraction happens before the transaction opens, and a replacement that
 * cannot be read leaves the original row untouched rather than half-overwritten.
 *
 * @param courseCode the course whose bot owns the source
 * @param sourceId   the {@code bot_sources} row to replace
 * @param kind       what the new bytes are, so the server knows which parser to use
 * @param title      what to call it now; may differ from what it was called before
 * @param content    the new bytes, or UTF-8 text for {@link BotSourceKind#TEXT}
 */
public record SourceUpdateRequest(String courseCode,
                                  long sourceId,
                                  BotSourceKind kind,
                                  String title,
                                  byte[] content) implements Serializable {

    private static final long serialVersionUID = 1L;

    public SourceUpdateRequest {
        Objects.requireNonNull(courseCode, "courseCode");
        Objects.requireNonNull(kind, "kind");
        courseCode = courseCode.trim().toUpperCase(Locale.ROOT);
        title = title == null ? "" : title.trim();
        if (title.length() > SourceAddRequest.MAX_TITLE) {
            title = title.substring(0, SourceAddRequest.MAX_TITLE);
        }
        content = content == null ? new byte[0] : content.clone();
    }

    /** @return a copy; the array this record hands out is never the one it holds. */
    @Override
    public byte[] content() {
        return content.clone();
    }

    /** @return {@code true} when there is a course, a row, a title and something to parse. */
    public boolean isWellFormed() {
        return !courseCode.isBlank() && sourceId > 0 && !title.isBlank() && content.length > 0;
    }

    /** @return {@code true} when the replacement is within {@link SourceAddRequest#MAX_BYTES}. */
    public boolean isWithinSizeLimit() {
        return content.length <= SourceAddRequest.MAX_BYTES;
    }

    /** @return the replacement's size in bytes, without copying it. */
    public int sizeBytes() {
        return content.length;
    }

    /** Value equality over the bytes, because a record's generated one compares by reference. */
    @Override
    public boolean equals(Object other) {
        return other instanceof SourceUpdateRequest that
                && sourceId == that.sourceId
                && kind == that.kind
                && Objects.equals(courseCode, that.courseCode)
                && Objects.equals(title, that.title)
                && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(courseCode, sourceId, kind, title) * 31 + Arrays.hashCode(content);
    }

    /** Never prints the bytes — this record travels through log-adjacent code. */
    @Override
    public String toString() {
        return "SourceUpdateRequest{course=" + courseCode + ", source=" + sourceId
                + ", kind=" + kind + ", title=" + title + ", bytes=" + content.length + '}';
    }
}
