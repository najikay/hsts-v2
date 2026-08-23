package client.ui.components.logic;

import common.dto.bank.ImageAction;
import common.dto.bank.QuestionImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ImagePickerLogic} — the illustration picker's state machine and its rules (E6.6,
 * F2.1).
 *
 * <p>The nested {@code Cancelling} class is the point of the file. The server side of this
 * feature had to be fixed for a {@code REPLACE} that carried no bytes quietly clearing a
 * picture; this class is where the client's version of that defect is asserted not to exist,
 * from every state and by every route that could reach it. Those cases are worth more than all
 * the formatting ones put together, so they are grouped rather than scattered.
 */
class ImagePickerLogicTest {

    /** A real PNG signature followed by filler; enough for a sniff, not for a decoder. */
    private static final byte[] PNG = png(64);

    /** A real JPEG signature followed by filler. */
    private static final byte[] JPEG = jpeg(64);

    private static byte[] png(int length) {
        byte[] bytes = new byte[length];
        byte[] magic = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        return bytes;
    }

    private static byte[] jpeg(int length) {
        byte[] bytes = new byte[length];
        byte[] magic = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        return bytes;
    }

    /** A picker for a question that already has an illustration (the edit path). */
    private static ImagePickerLogic loaded() {
        return ImagePickerLogic.of(PNG);
    }

    /** A picker for a brand new question (the create path). */
    private static ImagePickerLogic fresh() {
        return new ImagePickerLogic();
    }

    // ===================== Starting state ================================

    @Nested
    @DisplayName("starting state")
    class Start {

        @Test
        @DisplayName("a new question keeps nothing and offers nothing to remove")
        void freshPicker() {
            ImagePickerLogic picker = fresh();

            assertThat(picker.action()).isEqualTo(ImageAction.KEEP);
            assertThat(picker.chosenBytes()).isNull();
            assertThat(picker.hasOriginal()).isFalse();
            assertThat(picker.hasPreview()).isFalse();
            assertThat(picker.isRemoved()).isFalse();
            assertThat(picker.canRemove()).isFalse();
            assertThat(picker.previewLabel()).isEmpty();
        }

        @Test
        @DisplayName("a loaded question keeps its own picture and can show it")
        void loadedPicker() {
            ImagePickerLogic picker = loaded();

            assertThat(picker.action()).isEqualTo(ImageAction.KEEP);
            assertThat(picker.hasOriginal()).isTrue();
            assertThat(picker.hasPreview()).isTrue();
            assertThat(picker.previewBytes()).isEqualTo(PNG);
            assertThat(picker.canRemove()).isTrue();
            assertThat(picker.previewLabel()).isEqualTo("PNG, 64 B");
        }

        @Test
        @DisplayName("a version with no illustration loads as an empty picker, not a broken one")
        void loadingNullIsTheSameAsNothing() {
            ImagePickerLogic picker = ImagePickerLogic.of(null);

            assertThat(picker.hasOriginal()).isFalse();
            assertThat(picker.action()).isEqualTo(ImageAction.KEEP);

            picker.loadExisting(new byte[0]);
            assertThat(picker.hasOriginal())
                    .as("zero bytes is no picture, not a picture of zero bytes")
                    .isFalse();
        }

        @Test
        @DisplayName("the picker holds its own copy of the bytes it was handed")
        void defensiveCopyOnLoad() {
            byte[] mutable = png(64);
            ImagePickerLogic picker = ImagePickerLogic.of(mutable);
            mutable[20] = 42;

            assertThat(picker.previewBytes())
                    .as("a caller reusing its buffer cannot corrupt the preview")
                    .isEqualTo(PNG);
        }
    }

    // ===================== Choosing ======================================

    @Nested
    @DisplayName("choosing a file")
    class Choosing {

        @Test
        @DisplayName("a valid PNG becomes the illustration and the action becomes REPLACE")
        void acceptsPng() {
            ImagePickerLogic picker = loaded();

            ImagePickerLogic.Outcome outcome = picker.choose(JPEG, "diagram.jpg");

            assertThat(outcome.isAccepted()).isTrue();
            assertThat(outcome.hasMessage()).isFalse();
            assertThat(picker.action()).isEqualTo(ImageAction.REPLACE);
            assertThat(picker.chosenBytes()).isEqualTo(JPEG);
            assertThat(picker.previewBytes())
                    .as("the preview shows the new file, not the one being replaced")
                    .isEqualTo(JPEG);
            assertThat(picker.previewLabel()).isEqualTo("JPG, 64 B");
        }

        @Test
        @DisplayName("REPLACE is never reported without bytes to back it")
        void replaceAlwaysCarriesBytes() {
            ImagePickerLogic picker = loaded();
            picker.choose(PNG, "diagram.png");

            assertThat(picker.action()).isEqualTo(ImageAction.REPLACE);
            assertThat(picker.chosenBytes())
                    .as("the pairing the server refuses is unreachable from here")
                    .isNotNull()
                    .isNotEmpty();
        }

        @Test
        @DisplayName("choosing on a brand new question fills the draft's image field")
        void createPath() {
            ImagePickerLogic picker = fresh();
            picker.choose(PNG, "sketch.PNG");

            assertThat(picker.chosenBytes()).isEqualTo(PNG);
            assertThat(picker.action()).isEqualTo(ImageAction.REPLACE);
        }

        @Test
        @DisplayName("a second choice replaces the first, not the stored picture twice")
        void secondChoiceWins() {
            ImagePickerLogic picker = loaded();
            picker.choose(PNG, "one.png");
            picker.choose(JPEG, "two.jpeg");

            assertThat(picker.chosenBytes()).isEqualTo(JPEG);
            assertThat(picker.action()).isEqualTo(ImageAction.REPLACE);
        }

        @Test
        @DisplayName("the chosen bytes are copied in and copied out")
        void defensiveCopies() {
            byte[] mutable = png(64);
            ImagePickerLogic picker = fresh();
            picker.choose(mutable, "diagram.png");
            mutable[30] = 7;

            assertThat(picker.chosenBytes()).isEqualTo(PNG);
            byte[] handed = picker.chosenBytes();
            handed[31] = 9;
            assertThat(picker.chosenBytes()).isEqualTo(PNG);
        }
    }

    // ===================== The cancel path ===============================

    @Nested
    @DisplayName("cancelling the chooser")
    class Cancelling {

        @Test
        @DisplayName("a cancelled pick on an illustrated question stays KEEP, never REMOVE")
        void cancelFromKeep() {
            ImagePickerLogic picker = loaded();

            ImagePickerLogic.Outcome outcome = picker.choose(null, null);

            assertThat(outcome.isUnchanged()).isTrue();
            assertThat(outcome.hasMessage())
                    .as("changing her mind is not an error and gets no sentence")
                    .isFalse();
            assertThat(picker.action()).isEqualTo(ImageAction.KEEP);
            assertThat(picker.previewBytes())
                    .as("and the picture she started with is still there")
                    .isEqualTo(PNG);
        }

        @Test
        @DisplayName("a cancelled pick after choosing keeps the file she had already chosen")
        void cancelFromReplace() {
            ImagePickerLogic picker = loaded();
            picker.choose(JPEG, "new.jpg");

            assertThat(picker.choose(null, null).isUnchanged()).isTrue();
            assertThat(picker.action()).isEqualTo(ImageAction.REPLACE);
            assertThat(picker.chosenBytes()).isEqualTo(JPEG);
        }

        @Test
        @DisplayName("a cancelled pick after removing does not un-remove, and does not re-clear")
        void cancelFromRemove() {
            ImagePickerLogic picker = loaded();
            picker.remove();

            assertThat(picker.choose(null, null).isUnchanged()).isTrue();
            assertThat(picker.action()).isEqualTo(ImageAction.REMOVE);
        }

        @Test
        @DisplayName("an empty byte array is a cancel too, not a clear")
        void emptyBytesAreACancel() {
            ImagePickerLogic picker = loaded();

            assertThat(picker.choose(new byte[0], "diagram.png").isUnchanged()).isTrue();
            assertThat(picker.action()).isEqualTo(ImageAction.KEEP);
            assertThat(picker.previewBytes()).isEqualTo(PNG);
        }

        @Test
        @DisplayName("no rejected file can reach REMOVE from any state")
        void rejectionsNeverRemove() {
            byte[][] bad = {
                    "not an image".getBytes(StandardCharsets.UTF_8),
                    png(QuestionImage.MAX_BYTES + 1)};
            String[] names = {"diagram.png", "diagram.png", "notes.txt"};

            for (byte[] bytes : bad) {
                for (String name : names) {
                    ImagePickerLogic picker = loaded();
                    picker.choose(bytes, name);
                    assertThat(picker.action())
                            .as("refused file %s must leave the picture alone", name)
                            .isEqualTo(ImageAction.KEEP);
                    assertThat(picker.previewBytes()).isEqualTo(PNG);
                }
            }
        }
    }

    // ===================== Removing ======================================

    @Nested
    @DisplayName("removing")
    class Removing {

        @Test
        @DisplayName("removing a stored picture is REMOVE, and the preview goes away")
        void removeFromKeep() {
            ImagePickerLogic picker = loaded();

            ImagePickerLogic.Outcome outcome = picker.remove();

            assertThat(outcome.isAccepted()).isTrue();
            assertThat(picker.action()).isEqualTo(ImageAction.REMOVE);
            assertThat(picker.hasPreview()).isFalse();
            assertThat(picker.previewBytes()).isNull();
            assertThat(picker.isRemoved()).isTrue();
            assertThat(picker.chosenBytes())
                    .as("a removal carries no bytes")
                    .isNull();
        }

        @Test
        @DisplayName("removing nothing is not a removal: it stays KEEP")
        void removeFromNothing() {
            ImagePickerLogic picker = fresh();

            ImagePickerLogic.Outcome outcome = picker.remove();

            assertThat(outcome.isUnchanged())
                    .as("there was nothing to take off, so nothing happened")
                    .isTrue();
            assertThat(picker.action())
                    .as("REMOVE here would ask the server to strip a picture that never existed")
                    .isEqualTo(ImageAction.KEEP);
            assertThat(picker.isRemoved()).isFalse();
        }

        @Test
        @DisplayName("removing a file chosen for a new question drops it and stays KEEP")
        void removeAChoiceOnANewQuestion() {
            ImagePickerLogic picker = fresh();
            picker.choose(PNG, "sketch.png");

            assertThat(picker.remove().isAccepted()).isTrue();
            assertThat(picker.action()).isEqualTo(ImageAction.KEEP);
            assertThat(picker.chosenBytes()).isNull();
            assertThat(picker.hasPreview()).isFalse();
        }

        @Test
        @DisplayName("removing after choosing a replacement means the question ends with none")
        void removeAfterReplace() {
            ImagePickerLogic picker = loaded();
            picker.choose(JPEG, "new.jpg");

            picker.remove();

            assertThat(picker.action())
                    .as("Remove is a statement about the saved question, not an undo")
                    .isEqualTo(ImageAction.REMOVE);
            assertThat(picker.chosenBytes()).isNull();
        }

        @Test
        @DisplayName("removing twice is idempotent and says so the second time")
        void removeTwice() {
            ImagePickerLogic picker = loaded();
            picker.remove();

            assertThat(picker.remove().isUnchanged()).isTrue();
            assertThat(picker.action()).isEqualTo(ImageAction.REMOVE);
        }

        @Test
        @DisplayName("a picture can be chosen again after a removal")
        void chooseAfterRemove() {
            ImagePickerLogic picker = loaded();
            picker.remove();

            assertThat(picker.choose(JPEG, "second-thoughts.jpg").isAccepted()).isTrue();
            assertThat(picker.action()).isEqualTo(ImageAction.REPLACE);
            assertThat(picker.previewBytes()).isEqualTo(JPEG);
        }

        @Test
        @DisplayName("reloading the version puts the picker back where it started")
        void reloadResets() {
            ImagePickerLogic picker = loaded();
            picker.remove();

            picker.loadExisting(JPEG);

            assertThat(picker.action()).isEqualTo(ImageAction.KEEP);
            assertThat(picker.previewBytes()).isEqualTo(JPEG);
            assertThat(picker.chosenBytes()).isNull();
        }
    }

    // ===================== Validation ====================================

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("the ceiling is the DTO's, not a second copy of the number")
        void capComesFromTheContract() {
            assertThat(ImagePickerLogic.exceedsCap(QuestionImage.MAX_BYTES)).isFalse();
            assertThat(ImagePickerLogic.exceedsCap(QuestionImage.MAX_BYTES + 1L)).isTrue();
        }

        @Test
        @DisplayName("a file one byte over the ceiling is refused, by size and by name")
        void tooLarge() {
            ImagePickerLogic picker = loaded();

            ImagePickerLogic.Outcome outcome =
                    picker.choose(png(QuestionImage.MAX_BYTES + 1), "huge.png");

            assertThat(outcome.isRejected()).isTrue();
            assertThat(outcome.message()).isEqualTo(ImagePickerLogic.TOO_LARGE);
            assertThat(outcome.message()).contains("2 MB");
        }

        @Test
        @DisplayName("a file exactly at the ceiling is accepted")
        void exactlyAtTheCeiling() {
            ImagePickerLogic picker = fresh();

            assertThat(picker.choose(png(QuestionImage.MAX_BYTES), "big.png").isAccepted())
                    .isTrue();
        }

        @Test
        @DisplayName("size is checked before type, because size is the one she can act on")
        void sizeBeatsType() {
            byte[] hugeAndWrong = new byte[QuestionImage.MAX_BYTES + 1];

            assertThat(ImagePickerLogic.problemWith(hugeAndWrong, "notes.txt"))
                    .as("a 40MB document fails every rule; only one of them is useful")
                    .isEqualTo(ImagePickerLogic.TOO_LARGE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"notes.txt", "photo.heic", "diagram", "diagram.png.exe", ".png "})
        @DisplayName("a file the chooser's filter did not catch is refused by name")
        void wrongExtension(String fileName) {
            assertThat(ImagePickerLogic.problemWith(PNG, fileName))
                    .isEqualTo(ImagePickerLogic.WRONG_EXTENSION);
        }

        @ParameterizedTest
        @ValueSource(strings = {"a.png", "a.PNG", "a.jpg", "a.JPG", "a.jpeg", "a.Jpeg"})
        @DisplayName("the three accepted suffixes are matched case-insensitively")
        void acceptedExtensions(String fileName) {
            assertThat(ImagePickerLogic.hasAcceptedExtension(fileName)).isTrue();
        }

        @Test
        @DisplayName("a nameless file is refused rather than guessed at")
        void nullFileName() {
            assertThat(ImagePickerLogic.hasAcceptedExtension(null)).isFalse();
            assertThat(ImagePickerLogic.problemWith(PNG, null))
                    .isEqualTo(ImagePickerLogic.WRONG_EXTENSION);
        }

        @Test
        @DisplayName("a renamed HEIC is refused on its contents, and the sentence says so")
        void renamedFile() {
            byte[] notAnImage = "ftypheic and then some".getBytes(StandardCharsets.UTF_8);

            assertThat(ImagePickerLogic.problemWith(notAnImage, "diagram.png"))
                    .isEqualTo(ImagePickerLogic.WRONG_CONTENT);
            assertThat(ImagePickerLogic.WRONG_CONTENT)
                    .as("she needs to know we looked inside the file, or this reads as a bug")
                    .contains("contents");
        }

        @Test
        @DisplayName("a PNG named .jpg is accepted, because the server accepts it too")
        void extensionAndContentMayDisagree() {
            ImagePickerLogic picker = fresh();

            assertThat(picker.choose(PNG, "screenshot.jpg").isAccepted())
                    .as("refusing here would refuse a file the server would have stored")
                    .isTrue();
            assertThat(picker.previewLabel())
                    .as("and the caption reports what the bytes are, not what the name claims")
                    .startsWith("PNG");
        }

        @Test
        @DisplayName("a file too short to carry a signature is not an image")
        void truncated() {
            assertThat(ImagePickerLogic.sniff(new byte[] {(byte) 0x89, 0x50})).isEmpty();
            assertThat(ImagePickerLogic.sniff(null)).isEmpty();
            assertThat(ImagePickerLogic.problemWith(null, "a.png"))
                    .isEqualTo(ImagePickerLogic.WRONG_CONTENT);
        }

        @Test
        @DisplayName("both signatures are recognised, and nothing else is")
        void sniff() {
            assertThat(ImagePickerLogic.sniff(PNG)).contains(QuestionImage.PNG);
            assertThat(ImagePickerLogic.sniff(JPEG)).contains(QuestionImage.JPEG);
            assertThat(ImagePickerLogic.sniff("GIF89a".getBytes(StandardCharsets.UTF_8)))
                    .isEmpty();
        }

        @Test
        @DisplayName("every refusal is a sentence for a teacher, never a class name")
        void messagesArePhrasedForTeachers() {
            for (String message : new String[] {ImagePickerLogic.TOO_LARGE,
                    ImagePickerLogic.WRONG_CONTENT, ImagePickerLogic.WRONG_EXTENSION,
                    ImagePickerLogic.UNREADABLE}) {
                assertThat(message).doesNotContain("Exception").doesNotContain("null");
                assertThat(message)
                        .as("house rule: no em dashes in anything a user reads")
                        .doesNotContain("—");
                assertThat(message).endsWith(".");
            }
        }
    }

    // ===================== Copy ==========================================

    @Nested
    @DisplayName("copy and formatting")
    class Copy {

        @ParameterizedTest
        @CsvSource({
                "0,0 B",
                "1,1 B",
                "1023,1023 B",
                "1024,1 KB",
                "1536,2 KB",
                "151552,148 KB",
                "1048576,1 MB",
                "1572864,1.5 MB",
                "2097152,2 MB"})
        @DisplayName("sizes read the way a teacher would say them")
        void formatSize(long bytes, String expected) {
            assertThat(ImagePickerLogic.formatSize(bytes)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a whole megabyte loses its pointless decimal, so the cap reads 2 MB")
        void wholeMegabytesAreClean() {
            assertThat(ImagePickerLogic.formatSize(QuestionImage.MAX_BYTES)).isEqualTo("2 MB");
            assertThat(ImagePickerLogic.capLabel())
                    .as("the hint states both limits before she hits either")
                    .isEqualTo("PNG or JPG, up to 2 MB");
        }

        @Test
        @DisplayName("a negative size is clamped rather than printed")
        void negativeSize() {
            assertThat(ImagePickerLogic.formatSize(-1)).isEqualTo("0 B");
        }

        @Test
        @DisplayName("the badge says JPG, which is what the file's own suffix says")
        void shortTypeNames() {
            assertThat(ImagePickerLogic.shortTypeName(QuestionImage.PNG)).isEqualTo("PNG");
            assertThat(ImagePickerLogic.shortTypeName(QuestionImage.JPEG)).isEqualTo("JPG");
            assertThat(ImagePickerLogic.shortTypeName("image/gif")).isEmpty();
            assertThat(ImagePickerLogic.shortTypeName(null)).isEmpty();
        }

        @Test
        @DisplayName("the removed state has its own words, not the empty state's")
        void removedIsNotEmpty() {
            assertThat(ImagePickerLogic.REMOVED_TITLE)
                    .isNotEqualTo(ImagePickerLogic.EMPTY_TITLE);
            assertThat(ImagePickerLogic.REMOVED_HINT)
                    .as("the consequence of removing is stated, not implied")
                    .contains("without a picture");
        }
    }

    // ===================== Outcome =======================================

    @Nested
    @DisplayName("Outcome")
    class Outcomes {

        @Test
        @DisplayName("a rejection without a reason is a programming error")
        void rejectionNeedsAReason() {
            assertThatThrownBy(() -> ImagePickerLogic.Outcome.rejected("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("why");
        }

        @Test
        @DisplayName("the three results are mutually exclusive")
        void exclusive() {
            assertThat(ImagePickerLogic.Outcome.accepted().isAccepted()).isTrue();
            assertThat(ImagePickerLogic.Outcome.accepted().isRejected()).isFalse();
            assertThat(ImagePickerLogic.Outcome.accepted().isUnchanged()).isFalse();
            assertThat(ImagePickerLogic.Outcome.unchanged().isUnchanged()).isTrue();
            assertThat(ImagePickerLogic.Outcome.rejected("no").isRejected()).isTrue();
            assertThat(ImagePickerLogic.Outcome.rejected("no").hasMessage()).isTrue();
        }

        @Test
        @DisplayName("a null message is an empty one, and a null result is refused")
        void nullHandling() {
            assertThat(new ImagePickerLogic.Outcome(ImagePickerLogic.Result.UNCHANGED, null)
                    .message()).isEmpty();
            assertThatThrownBy(() -> new ImagePickerLogic.Outcome(null, ""))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
