package server.features.bank;

import common.dto.bank.QuestionImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QuestionImages} - what the server will accept as an illustration (E6.6, NFR-18).
 *
 * <p>Every test here is written so that deleting the rule it guards makes it fail. The two that
 * matter most are the ordering test and the renamed-file test: the first because a wrong order
 * sends a teacher to convert a file that was never going to fit, and the second because a
 * declared content type is the thing this class exists not to trust.
 */
class QuestionImagesTest {

    private static final byte[] PNG_HEADER = {
            (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
            (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A};

    private static final byte[] JPEG_HEADER = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /** A HEIC file's opening bytes: {@code ....ftypheic}. Renaming it to .png changes nothing. */
    private static final byte[] HEIC_HEADER = {
            0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};

    /**
     * @param header the signature to lead with
     * @param total  how many bytes the whole file should be
     * @return a file of that size opening with that signature
     */
    private static byte[] fileOf(byte[] header, int total) {
        byte[] bytes = new byte[total];
        System.arraycopy(header, 0, bytes, 0, Math.min(header.length, total));
        return bytes;
    }

    @Nested
    @DisplayName("sniff")
    class Sniffing {

        @Test
        @DisplayName("reads PNG from its 8-byte signature")
        void readsPng() {
            assertThat(QuestionImages.sniff(fileOf(PNG_HEADER, 64)))
                    .contains(QuestionImage.PNG);
        }

        @Test
        @DisplayName("reads JPEG from its start-of-image marker")
        void readsJpeg() {
            assertThat(QuestionImages.sniff(fileOf(JPEG_HEADER, 64)))
                    .contains(QuestionImage.JPEG);
        }

        @Test
        @DisplayName("refuses a HEIC whatever its name ends with")
        void refusesHeic() {
            assertThat(QuestionImages.sniff(fileOf(HEIC_HEADER, 64))).isEmpty();
        }

        @Test
        @DisplayName("refuses bytes too short to carry a signature")
        void refusesTruncated() {
            // The first three PNG bytes only. A length check that ran after the comparison
            // would read past the end here rather than answering.
            assertThat(QuestionImages.sniff(Arrays.copyOf(PNG_HEADER, 3))).isEmpty();
        }

        @Test
        @DisplayName("refuses a PNG signature with one byte wrong")
        void refusesNearMiss() {
            byte[] almost = fileOf(PNG_HEADER, 64);
            // The CRLF pair, which is the half of the signature that catches a text-mode
            // transfer. A sniff checking only the first four bytes would pass this.
            almost[4] = 0x0A;
            assertThat(QuestionImages.sniff(almost)).isEmpty();
        }

        @Test
        @DisplayName("answers empty for no bytes at all")
        void refusesNull() {
            assertThat(QuestionImages.sniff(null)).isEmpty();
            assertThat(QuestionImages.sniff(new byte[0])).isEmpty();
        }
    }

    @Nested
    @DisplayName("problemWith")
    class Acceptance {

        @Test
        @DisplayName("accepts a PNG inside the ceiling")
        void acceptsPng() {
            assertThat(QuestionImages.problemWith(fileOf(PNG_HEADER, 1024))).isNull();
        }

        @Test
        @DisplayName("accepts no image, because illustrations are optional")
        void acceptsNothing() {
            assertThat(QuestionImages.problemWith(null)).isNull();
            assertThat(QuestionImages.problemWith(new byte[0])).isNull();
        }

        @Test
        @DisplayName("accepts a PNG of exactly 2MB, because the ceiling is inclusive")
        void acceptsTheBoundary() {
            assertThat(QuestionImages.problemWith(fileOf(PNG_HEADER, QuestionImages.MAX_BYTES)))
                    .isNull();
        }

        @Test
        @DisplayName("refuses one byte over the ceiling")
        void refusesOverTheBoundary() {
            assertThat(QuestionImages.problemWith(fileOf(PNG_HEADER, QuestionImages.MAX_BYTES + 1)))
                    .isEqualTo(BankMessages.IMAGE_TOO_LARGE);
        }

        @Test
        @DisplayName("refuses a well-formed file of the wrong type")
        void refusesWrongType() {
            assertThat(QuestionImages.problemWith(fileOf(HEIC_HEADER, 1024)))
                    .isEqualTo(BankMessages.IMAGE_WRONG_TYPE);
        }

        @Test
        @DisplayName("an oversized file of the wrong type is refused for its SIZE, not its type")
        void sizeIsCheckedFirst() {
            // The ordering rule. A 40MB HEIC breaks both rules and only one of them is the
            // one she can act on: told it is the wrong type, she converts a file that was
            // never going to fit and is refused a second time. Swap the two checks in
            // QuestionImages and this is the test that fails.
            String problem = QuestionImages.problemWith(
                    fileOf(HEIC_HEADER, QuestionImages.MAX_BYTES + 1));

            assertThat(problem).isEqualTo(BankMessages.IMAGE_TOO_LARGE);
        }
    }
}
