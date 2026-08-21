package common.dto.bank;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * The answer to {@code QUESTION_IMAGE_GET}: one version's illustration (Common tier, E6.6 —
 * F2.4).
 *
 * <p>The only payload in this package that carries bytes, which is the whole point of it being
 * its own verb: the list and the detail stay small, and a picture crosses the wire once, when
 * somebody is looking at it.
 *
 * <h2>{@code contentType} is derived, not stored</h2>
 *
 * <p>There is no {@code image_content_type} column and this contract deliberately does not add
 * one. The type is re-sniffed from the leading bytes on read, using the same sniff the upload
 * check already needs (E6.6): PNG and JPEG have unambiguous magic numbers, and a nullable column
 * that can disagree with its own blob is worse than a derivation that cannot.
 *
 * @param displayId5  the question
 * @param versionNo   the version this picture belongs to
 * @param contentType {@link #PNG} or {@link #JPEG}, sniffed from {@code bytes}
 * @param bytes       the image itself; never {@code null}, defensively copied
 */
public record QuestionImage(String displayId5,
                            int versionNo,
                            String contentType,
                            byte[] bytes) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The larger of the two accepted formats, and the one a screenshot arrives as. */
    public static final String PNG = "image/png";

    /** The other accepted format (E6.6). */
    public static final String JPEG = "image/jpeg";

    /** Upload ceiling, 2MB (E6.6). Enforced by the handler, stated here so both sides agree. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    /** Defensive copy in, so the array this record holds is not the caller's. */
    public QuestionImage {
        Objects.requireNonNull(displayId5, "displayId5");
        Objects.requireNonNull(contentType, "contentType");
        bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    /** @return the image; a copy, so a caller cannot corrupt it. */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /** @return how many bytes the picture is, without copying it to find out. */
    public int byteCount() {
        return bytes.length;
    }

    /** @return whether there is anything to render at all. */
    public boolean isEmpty() {
        return bytes.length == 0;
    }

    /** Compares by value, bytes included; see {@link QuestionDraft#equals(Object)}. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof QuestionImage that
                && versionNo == that.versionNo
                && Objects.equals(displayId5, that.displayId5)
                && Objects.equals(contentType, that.contentType)
                && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(displayId5, versionNo, contentType) * 31 + Arrays.hashCode(bytes);
    }

    /** Keeps a megabyte of picture out of every log line this record could land in. */
    @Override
    public String toString() {
        return "QuestionImage{displayId5=" + displayId5
                + ", versionNo=" + versionNo
                + ", contentType=" + contentType
                + ", bytes=" + bytes.length + '}';
    }
}
