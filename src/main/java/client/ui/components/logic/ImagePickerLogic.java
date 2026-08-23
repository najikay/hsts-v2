package client.ui.components.logic;

import common.dto.bank.ImageAction;
import common.dto.bank.QuestionImage;

import java.util.Locale;
import java.util.Optional;

/**
 * The illustration picker's whole brain: what the teacher chose, and what that instructs the
 * server to do (Presentation tier, for E6.10 — F2.1, E6.6).
 *
 * <h2>The defect this class exists to make unrepresentable</h2>
 *
 * <p>{@link ImageAction} has three states rather than two because a nullable {@code image}
 * cannot tell "I did not touch the picture" apart from "I pressed remove". The server side of
 * that already got its fix: {@code BankHandlers.checkEdit} refuses a {@code REPLACE} that
 * carries no file, with {@code BankMessages.IMAGE_REPLACE_WITHOUT_FILE}, instead of quietly
 * resolving the ambiguity the destructive way.
 *
 * <p>A refusal is the right server behaviour and the wrong client behaviour: a teacher who
 * opened the file chooser, thought better of it and pressed Cancel has done nothing wrong and
 * should see nothing at all. So the rule here is stronger than "refuse it" — <b>the client
 * cannot express it</b>:
 *
 * <ul>
 *   <li>{@link #choose} is the only way into {@link ImageAction#REPLACE}, and it moves there
 *       only once bytes have passed every check. A cancelled chooser hands it {@code null},
 *       which is answered with {@link Result#UNCHANGED} and no state change whatsoever.</li>
 *   <li>{@link #remove} is the only way into {@link ImageAction#REMOVE}, and it is reachable
 *       only from the Remove button. Nothing else in the class can clear a picture.</li>
 *   <li>There is therefore no path on which a cancel, a rejected file or a read error turns
 *       into a removal, and none on which {@code REPLACE} is reported without bytes to back
 *       it. Both are properties, not conventions, and both are asserted.</li>
 * </ul>
 *
 * <h2>Removing nothing is not a removal</h2>
 *
 * <p>{@link #remove} on a question that never had an illustration leaves the action at
 * {@link ImageAction#KEEP}, because {@code KEEP} of nothing already <i>is</i> "no picture".
 * Reporting {@code REMOVE} there would ask the server to strip an illustration that does not
 * exist, which writes a version whose only content is a lie about what changed.
 *
 * <h2>Why the checks are duplicated from the server</h2>
 *
 * <p>{@code server.features.bank.QuestionImages} is the authority and stays so: the Presentation
 * tier never imports the Logic tier, and a client that skipped the local check would still be
 * refused. This is the courtesy copy that turns a round trip into an instant sentence, and it
 * deliberately mirrors the server's <b>order</b> — size before type — for the reason stated
 * there: a 40MB PNG fails both rules, and "larger than 2 MB" is the one she can act on.
 * {@link QuestionImage#MAX_BYTES} is imported rather than restated, so the two limits cannot
 * drift apart.
 *
 * <p>FX-free, like {@link CountdownLogic} and {@link StatChartLogic}: the state machine, the
 * validation and the size formatting are unit tested with plain byte arrays, and
 * {@code ImagePicker} is left with nothing to get wrong but where it puts the nodes.
 */
public final class ImagePickerLogic {

    /** PNG's 8-byte signature, including the CRLF pair that catches text-mode transfers. */
    private static final byte[] PNG_MAGIC = {
            (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
            (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A};

    /** JPEG's start-of-image marker followed by the first marker's prefix. */
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /** What the picker shows when there is no illustration and none was removed. */
    public static final String EMPTY_TITLE = "No illustration";

    /** What the picker shows once Remove has been pressed on a question that had one. */
    public static final String REMOVED_TITLE = "Illustration removed";

    /** The consequence of the removed state, stated rather than implied. */
    public static final String REMOVED_HINT =
            "Saving this question stores the next version without a picture.";

    /** NFR-18's ceiling, in the units the teacher reads off her own file. */
    public static final String TOO_LARGE =
            "That image is larger than 2 MB. Save a smaller copy and choose that instead.";

    /**
     * The renamed-HEIC case: the name says image, the bytes do not.
     *
     * <p>Says "we looked inside the file", because a teacher who renamed one would otherwise
     * read this as the app being broken.
     */
    public static final String WRONG_CONTENT =
            "That file is named like an image, but its contents are not a PNG or a JPG. Save it "
                    + "as a PNG and choose it again.";

    /** A file the chooser's filter did not catch, refused by name before its bytes are read. */
    public static final String WRONG_EXTENSION =
            "Illustrations must be PNG or JPG files. Choose one of those instead.";

    /** The disk read after a successful pick failed; the picker keeps whatever it had. */
    public static final String UNREADABLE =
            "That file could not be read from disk. Choose it again.";

    /** How a chosen file was answered. */
    public enum Result {

        /** The bytes are now the illustration; the action moved to {@link ImageAction#REPLACE}. */
        ACCEPTED,

        /** Nothing happened, and nothing is wrong. The cancelled-chooser answer. */
        UNCHANGED,

        /** The file cannot be used, and {@link Outcome#message()} says why. */
        REJECTED
    }

    /**
     * What a call to {@link #choose} or {@link #remove} did.
     *
     * @param result  which of the three things happened
     * @param message the sentence to show, or empty when there is nothing to say
     */
    public record Outcome(Result result, String message) {

        private static final Outcome UNCHANGED = new Outcome(Result.UNCHANGED, "");
        private static final Outcome ACCEPTED = new Outcome(Result.ACCEPTED, "");

        public Outcome {
            java.util.Objects.requireNonNull(result, "result");
            message = message == null ? "" : message;
            if (result == Result.REJECTED && message.isBlank()) {
                throw new IllegalArgumentException("A rejected file must say why");
            }
        }

        /** @return the "nothing happened, nothing is wrong" answer. */
        public static Outcome unchanged() {
            return UNCHANGED;
        }

        /** @return the "it worked" answer. */
        public static Outcome accepted() {
            return ACCEPTED;
        }

        /** @param why the sentence to show the teacher; never a stack trace, never blank */
        public static Outcome rejected(String why) {
            return new Outcome(Result.REJECTED, why);
        }

        public boolean isAccepted() {
            return result == Result.ACCEPTED;
        }

        public boolean isUnchanged() {
            return result == Result.UNCHANGED;
        }

        public boolean isRejected() {
            return result == Result.REJECTED;
        }

        /** @return whether the picker should show a message row at all. */
        public boolean hasMessage() {
            return !message.isEmpty();
        }
    }

    private byte[] original;
    private String originalType;
    private byte[] chosen;
    private String chosenType;
    private ImageAction action = ImageAction.KEEP;

    /** Builds a picker for a question that has no illustration yet (the create path, F2.1). */
    public ImagePickerLogic() {
    }

    /**
     * Builds a picker already holding the version's illustration (the edit path, F2.3).
     *
     * @param existing the bytes {@code QUESTION_IMAGE_GET} answered with, or {@code null}
     * @return a picker sitting at {@link ImageAction#KEEP} on those bytes
     */
    public static ImagePickerLogic of(byte[] existing) {
        ImagePickerLogic picker = new ImagePickerLogic();
        picker.loadExisting(existing);
        return picker;
    }

    /**
     * Installs the illustration this version already has, and resets to {@link ImageAction#KEEP}.
     *
     * <p>The load step, not an edit: call it once, with the answer to {@code QUESTION_IMAGE_GET},
     * before the picker is put in front of anybody. Calling it later discards whatever she had
     * chosen, which is correct for a reload and wrong for anything else.
     *
     * @param existing the stored bytes, or {@code null} for a version with no picture
     */
    public void loadExisting(byte[] existing) {
        this.original = existing == null || existing.length == 0 ? null : existing.clone();
        this.originalType = original == null ? null : sniff(original).orElse(null);
        this.chosen = null;
        this.chosenType = null;
        this.action = ImageAction.KEEP;
    }

    // ===================== The state machine ==============================

    /**
     * Takes a file the teacher picked.
     *
     * <p>The whole cancel story is the first branch: a chooser that returned nothing hands this
     * {@code null}, and {@code null} means "she changed her mind", which is
     * {@link Result#UNCHANGED} and not a single field touched. That is why the parameter is
     * nullable rather than guarded by a {@code requireNonNull} — the sloppy call site is the
     * one that has to stay safe.
     *
     * <p>A rejected file is equally inert: the previous illustration, the previous action and
     * the previous choice all survive, so a teacher who picks a 5MB photo by mistake still has
     * the picture she started with.
     *
     * @param bytes    the file's contents, or {@code null} when the chooser was cancelled
     * @param fileName the file's name, used for the extension check only; may be {@code null}
     * @return what happened, and the sentence to show when something did not
     */
    public Outcome choose(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            return Outcome.unchanged();
        }
        Checked checked = check(bytes, fileName);
        if (checked.problem() != null) {
            return Outcome.rejected(checked.problem());
        }
        this.chosen = bytes.clone();
        this.chosenType = checked.contentType();
        this.action = ImageAction.REPLACE;
        return Outcome.accepted();
    }

    /**
     * Clears the illustration.
     *
     * <p>Ends at {@link ImageAction#REMOVE} when there is a stored picture to take off, and at
     * {@link ImageAction#KEEP} when there is not — see the class javadoc on why removing
     * nothing is not a removal. Either way any pending choice is dropped, because Remove is a
     * statement about the saved question rather than about the last thing she clicked.
     *
     * @return {@link Result#ACCEPTED} when something actually changed, {@link Result#UNCHANGED}
     *         when the question already had no picture and none was pending
     */
    public Outcome remove() {
        ImageAction before = action;
        boolean hadChoice = chosen != null;
        this.chosen = null;
        this.chosenType = null;
        this.action = original == null ? ImageAction.KEEP : ImageAction.REMOVE;
        return before == action && !hadChoice ? Outcome.unchanged() : Outcome.accepted();
    }

    /**
     * @return the instruction to put in {@link common.dto.bank.QuestionEdit#imageAction()};
     *         never {@code null}, and {@link ImageAction#REPLACE} only when
     *         {@link #chosenBytes()} has bytes to go with it
     */
    public ImageAction action() {
        return action;
    }

    /**
     * The bytes that travel with the payload.
     *
     * <p>The same value serves both verbs: {@code QUESTION_CREATE} puts it straight into
     * {@link common.dto.bank.QuestionDraft#image()}, and {@code QUESTION_UPDATE} pairs it with
     * {@link #action()}. Non-{@code null} exactly when the action is {@link ImageAction#REPLACE}.
     *
     * @return a copy of the chosen file, or {@code null} when nothing was chosen
     */
    public byte[] chosenBytes() {
        return chosen == null ? null : chosen.clone();
    }

    /** @return whether this version arrived with an illustration. */
    public boolean hasOriginal() {
        return original != null;
    }

    // ===================== What the view draws ============================

    /**
     * @return the bytes to put in the thumbnail: the new file when one was chosen, the stored
     *         one while it is being kept, and {@code null} once it has been removed
     */
    public byte[] previewBytes() {
        return switch (action) {
            case REPLACE -> chosen == null ? null : chosen.clone();
            case KEEP -> original == null ? null : original.clone();
            case REMOVE -> null;
        };
    }

    /** @return whether there is anything to draw a thumbnail from. */
    public boolean hasPreview() {
        return previewBytes() != null;
    }

    /** @return whether the picker is showing the "removed" state rather than the empty one. */
    public boolean isRemoved() {
        return action == ImageAction.REMOVE;
    }

    /** @return whether Remove would do anything; the view disables the button when it would not. */
    public boolean canRemove() {
        return hasPreview();
    }

    /**
     * @return the caption under the thumbnail, e.g. {@code "PNG, 148 KB"}, or an empty string
     *         when there is no thumbnail
     */
    public String previewLabel() {
        byte[] preview = previewBytes();
        if (preview == null) {
            return "";
        }
        String type = action == ImageAction.REPLACE ? chosenType : originalType;
        String name = shortTypeName(type);
        return name.isEmpty() ? formatSize(preview.length)
                : name + ", " + formatSize(preview.length);
    }

    /** @return the standing hint next to the Choose button, stating both limits at once. */
    public static String capLabel() {
        return "PNG or JPG, up to " + formatSize(QuestionImage.MAX_BYTES);
    }

    // ===================== The rules ======================================

    /**
     * Whether a file may be used, without using it.
     *
     * <p>Exposed for a caller that wants to check before it commits (and for the tests that
     * pin the order); {@link #choose} runs exactly these checks.
     *
     * @param bytes    the file's contents, possibly {@code null}
     * @param fileName the file's name, possibly {@code null}
     * @return the sentence to refuse with, or {@code null} when the file may be used
     */
    public static String problemWith(byte[] bytes, String fileName) {
        return check(bytes, fileName).problem();
    }

    /**
     * Whether a file is over the ceiling, from its size alone.
     *
     * <p>Exists so the view can refuse on a directory entry rather than after reading a
     * multi-gigabyte file into the heap to reach the same answer.
     *
     * @param byteCount the file's length in bytes
     * @return whether it exceeds {@link QuestionImage#MAX_BYTES}
     */
    public static boolean exceedsCap(long byteCount) {
        return byteCount > QuestionImage.MAX_BYTES;
    }

    /**
     * @param fileName a file name, possibly {@code null}
     * @return whether it ends in one of the three accepted suffixes, case-insensitively
     */
    public static boolean hasAcceptedExtension(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    /**
     * What these bytes actually are, read from their leading bytes.
     *
     * @param image the bytes to identify, possibly {@code null}
     * @return {@link QuestionImage#PNG} or {@link QuestionImage#JPEG}, or empty for neither
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
     * A file size a teacher can compare against "2 MB".
     *
     * <p>Whole megabytes lose the pointless {@code .0}: the cap reads "2 MB" rather than
     * "2.0 MB", which is what the sentence refusing an oversized file says too.
     *
     * @param bytes a byte count; negatives are clamped to zero
     * @return the size in B, KB or MB
     */
    public static String formatSize(long bytes) {
        long size = Math.max(0, bytes);
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024L * 1024L) {
            return Math.round(size / 1024.0) + " KB";
        }
        double megabytes = size / (1024.0 * 1024.0);
        double rounded = Math.round(megabytes * 10) / 10.0;
        return rounded == Math.floor(rounded)
                ? (long) rounded + " MB"
                : String.format(Locale.ROOT, "%.1f MB", rounded);
    }

    /**
     * @param contentType a MIME type, possibly {@code null}
     * @return the badge text for it ({@code PNG} / {@code JPG}), or an empty string
     */
    public static String shortTypeName(String contentType) {
        if (QuestionImage.PNG.equals(contentType)) {
            return "PNG";
        }
        // "JPG", not "JPEG": it is what the file's own suffix says and what she picked.
        return QuestionImage.JPEG.equals(contentType) ? "JPG" : "";
    }

    /** The verdict on one file: why not, or what it is. Exactly one of the two is set. */
    private record Checked(String problem, String contentType) {
    }

    /**
     * Size, then name, then contents.
     *
     * <p>The order is the server's and the reason is the server's: a 40MB photo fails every
     * rule there is, and the only one she can act on is the size. Name before contents so a
     * document picked around the chooser's filter gets the short sentence rather than the one
     * about magic numbers.
     */
    private static Checked check(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            return new Checked(WRONG_CONTENT, null);
        }
        if (bytes.length > QuestionImage.MAX_BYTES) {
            return new Checked(TOO_LARGE, null);
        }
        if (!hasAcceptedExtension(fileName)) {
            return new Checked(WRONG_EXTENSION, null);
        }
        return sniff(bytes)
                .map(type -> new Checked(null, type))
                .orElseGet(() -> new Checked(WRONG_CONTENT, null));
    }

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
