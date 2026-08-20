package common.dto.bot;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Add one piece of material to a course's bot (Common tier, E16.11 — F12.2/F12.3).
 *
 * <p>The bytes travel; the parsing does not. The client reads the file and sends
 * it, and PDFBox and POI run on the server — which is what makes "parse failures
 * are reported immediately" (F12.2) a server answer the uploader sees rather than
 * a client-side guess, and what keeps a broken PDF from producing a source row
 * that contributes nothing to a prompt (see {@code BotSource}: both columns are
 * NOT NULL by design).
 *
 * <p>For {@link BotSourceKind#TEXT} the content is the pasted text in UTF-8, and
 * the server stores it as both the raw bytes and the extracted text. That is the
 * lead's E2 decision, taken so {@code raw} never has to be nullable for one kind.
 *
 * @param courseCode the course whose bot gains the source
 * @param kind       what the bytes are, so the server knows which parser to use
 * @param title      what to call it in the sources table
 * @param content    the file's bytes, or UTF-8 text for {@link BotSourceKind#TEXT}
 */
public record SourceAddRequest(String courseCode,
                               BotSourceKind kind,
                               String title,
                               byte[] content) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The longest title the {@code bot_sources.title} column takes (§5). */
    public static final int MAX_TITLE = 200;

    /**
     * The largest upload accepted, in bytes.
     *
     * <p>Below the {@code MEDIUMBLOB} ceiling on purpose: the row also has to hold
     * the extracted text, and a 16MB PDF would be refused by the database after
     * the whole thing had crossed the network. Refusing it up front is the same
     * message a second earlier.
     */
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    public SourceAddRequest {
        Objects.requireNonNull(courseCode, "courseCode");
        Objects.requireNonNull(kind, "kind");
        courseCode = courseCode.trim().toUpperCase(Locale.ROOT);
        title = title == null ? "" : title.trim();
        if (title.length() > MAX_TITLE) {
            title = title.substring(0, MAX_TITLE);
        }
        content = content == null ? new byte[0] : content.clone();
    }

    /** @return a copy; the array this record hands out is never the one it holds. */
    @Override
    public byte[] content() {
        return content.clone();
    }

    /** @return {@code true} when there is a course, a title and something to parse. */
    public boolean isWellFormed() {
        return !courseCode.isBlank() && !title.isBlank() && content.length > 0;
    }

    /** @return {@code true} when the upload is within {@link #MAX_BYTES}. */
    public boolean isWithinSizeLimit() {
        return content.length <= MAX_BYTES;
    }

    /** @return the upload's size in bytes, without copying it. */
    public int sizeBytes() {
        return content.length;
    }

    /** Value equality over the bytes, because a record's generated one compares the array by reference. */
    @Override
    public boolean equals(Object other) {
        return other instanceof SourceAddRequest that
                && kind == that.kind
                && Objects.equals(courseCode, that.courseCode)
                && Objects.equals(title, that.title)
                && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(courseCode, kind, title) * 31 + Arrays.hashCode(content);
    }

    /** Never prints the bytes — this record travels through log-adjacent code. */
    @Override
    public String toString() {
        return "SourceAddRequest{course=" + courseCode + ", kind=" + kind
                + ", title=" + title + ", bytes=" + content.length + '}';
    }
}
