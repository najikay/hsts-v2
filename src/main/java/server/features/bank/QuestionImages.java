package server.features.bank;

import common.dto.bank.QuestionImage;

import java.util.Optional;

/**
 * What the server accepts as a question illustration, and what it calls the bytes it accepted
 * (E6.6, NFR-18).
 *
 * <p>Separate from {@link QuestionValidator} on that class's own reasoning: its rules are about
 * fields, these are about bytes, and neither has anything to say about the other. Separate from
 * {@code QuestionService} because the read side needs the same sniff: {@code QUESTION_IMAGE_GET}
 * re-derives {@code contentType} from the leading bytes rather than from a column, so a single
 * implementation is the only way the type a teacher uploaded and the type a client is told can
 * never disagree.
 *
 * <h2>Why the bytes and not the name</h2>
 *
 * <p>A declared content type is whatever the client said, and the client is a file picker that
 * believes the extension. PNG and JPEG both open with unambiguous magic numbers, so reading them
 * costs eight bytes and answers the question the declaration only claims to answer. A teacher who
 * renames a HEIC to {@code .png} is refused here rather than storing bytes that no client can
 * render.
 *
 * <h2>Why size is checked before type</h2>
 *
 * <p>Order is not cosmetic. A 40MB PNG fails both rules, and "that image is larger than 2 MB" is
 * the one she can act on. Sniffing first would tell her the file is the wrong type, which is
 * false, and send her to convert a file that was never going to fit.
 */
public final class QuestionImages {

    /**
     * NFR-18's ceiling in bytes.
     *
     * <p>Below the column's own limit on purpose: {@code image MEDIUMBLOB} holds 16MB, so this
     * refuses long before an insert could fail. A limit the service does not mirror is a limit
     * whose enforcement is a stack trace, which is the same argument the length rules carry in
     * {@link QuestionValidator}.
     */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    /** PNG's 8-byte signature, including the CRLF pair that catches text-mode transfers. */
    private static final byte[] PNG_MAGIC = {
            (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
            (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A};

    /** JPEG's start-of-image marker followed by the first marker's prefix. */
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private QuestionImages() {
        // static rules - no instances
    }

    /**
     * What these bytes actually are.
     *
     * @param image the uploaded or stored bytes, possibly null
     * @return {@link QuestionImage#PNG} or {@link QuestionImage#JPEG}, or empty when the bytes are
     *         neither
     */
    public static Optional<String> sniff(byte[] image) {
        if (startsWith(image, PNG_MAGIC)) {
            return Optional.of(QuestionImage.PNG);
        }
        if (startsWith(image, JPEG_MAGIC)) {
            return Optional.of(QuestionImage.JPEG);
        }
        return Optional.empty();
    }

    /**
     * Whether an upload may be stored, in the shape the handlers' validation step wants.
     *
     * <p>A null or empty image is acceptable here and not a violation, because illustrating a
     * question is optional and most drafts carry no bytes at all.
     *
     * <p><b>That tolerance is right for a draft and wrong for a replacement</b>, and the
     * difference is the caller's to make rather than this method's. {@code BankHandlers.checkEdit}
     * refuses a {@code REPLACE} that carries no file before it gets here; a rule that lives in
     * this method instead would have to know which of the two it was answering, which is a
     * question it cannot see the answer to. {@code KEEP} and {@code REMOVE} never reach this
     * method at all.
     *
     * @param image the uploaded bytes, possibly null
     * @return the sentence to refuse with, or {@code null} when the image may be stored
     */
    public static String problemWith(byte[] image) {
        if (image == null || image.length == 0) {
            return null;
        }
        if (image.length > MAX_BYTES) {
            return BankMessages.IMAGE_TOO_LARGE;
        }
        return sniff(image).isPresent() ? null : BankMessages.IMAGE_WRONG_TYPE;
    }

    /**
     * @param image the bytes to test, possibly null
     * @param magic the signature to test for
     * @return whether the bytes open with that signature
     */
    private static boolean startsWith(byte[] image, byte[] magic) {
        if (image == null || image.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (image[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
