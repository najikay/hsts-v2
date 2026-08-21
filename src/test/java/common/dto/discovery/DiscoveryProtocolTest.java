package common.dto.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The discovery wire format (E19.8, F13.3) and its treatment of hostile input.
 *
 * <p>This is the one socket in the product that unauthenticated strangers on the
 * subnet can write to, so "decodes garbage without throwing" is not a nice
 * property here, it is the contract. The fuzz section below feeds it random bytes,
 * truncated packets, other people's protocols and deliberately malformed JSON, and
 * asserts one thing about all of it: {@link Optional#empty()}, never an exception.
 */
class DiscoveryProtocolTest {

    private static final ServerAnnouncement ROOM_12 =
            new ServerAnnouncement("Room 12 server", "192.168.1.42", 5555,
                    "7f3a2b91-1111-2222-3333-444444444444");

    private static Optional<ServerAnnouncement> decode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return DiscoveryProtocol.decodeAnnouncement(bytes, bytes.length);
    }

    @Nested
    @DisplayName("the request")
    class Request {

        @Test
        @DisplayName("a request round-trips and a stray packet does not look like one")
        void recognised() {
            byte[] request = DiscoveryProtocol.requestBytes();

            assertThat(DiscoveryProtocol.isRequest(request, request.length)).isTrue();
            assertThat(DiscoveryProtocol.isRequest("HELLO".getBytes(StandardCharsets.UTF_8), 5))
                    .as("a fixed magic string is why a printer's broadcast draws no reply")
                    .isFalse();
        }

        @Test
        @DisplayName("surrounding whitespace is tolerated, a truncated magic is not")
        void tolerance() {
            byte[] padded = ("  " + DiscoveryProtocol.REQUEST_MAGIC + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            assertThat(DiscoveryProtocol.isRequest(padded, padded.length)).isTrue();

            byte[] magic = DiscoveryProtocol.requestBytes();
            assertThat(DiscoveryProtocol.isRequest(magic, magic.length - 3)).isFalse();
        }

        @Test
        @DisplayName("nothing, and too much, are both refused")
        void bounds() {
            assertThat(DiscoveryProtocol.isRequest(null, 5)).isFalse();
            assertThat(DiscoveryProtocol.isRequest(new byte[10], 0)).isFalse();
            assertThat(DiscoveryProtocol.isRequest(new byte[10], -1)).isFalse();
            assertThat(DiscoveryProtocol.isRequest(new byte[1024],
                    DiscoveryProtocol.MAX_PACKET_BYTES + 1)).isFalse();
        }
    }

    @Nested
    @DisplayName("the announcement")
    class Announcement {

        @Test
        @DisplayName("round-trips every field")
        void roundTrip() {
            byte[] encoded = DiscoveryProtocol.encodeAnnouncement(ROOM_12);

            assertThat(DiscoveryProtocol.decodeAnnouncement(encoded, encoded.length))
                    .contains(ROOM_12);
        }

        @Test
        @DisplayName("fits comfortably in one datagram")
        void compact() {
            byte[] encoded = DiscoveryProtocol.encodeAnnouncement(ROOM_12);

            assertThat(encoded.length)
                    .as("one-character keys exist so this stays well inside the cap")
                    .isLessThan(DiscoveryProtocol.MAX_PACKET_BYTES / 2);
        }

        @Test
        @DisplayName("Hebrew and quotes in a server name survive the trip")
        void awkwardNames() {
            ServerAnnouncement awkward = new ServerAnnouncement(
                    "כיתה 12 \"הראשי\" \\ backup", "10.0.0.1", 5555, "abc");
            byte[] encoded = DiscoveryProtocol.encodeAnnouncement(awkward);

            assertThat(DiscoveryProtocol.decodeAnnouncement(encoded, encoded.length))
                    .contains(awkward);
        }

        @Test
        @DisplayName("an unknown extra field is ignored, so a newer server still answers an older client")
        void forwardCompatible() {
            assertThat(decode("{\"n\":\"S\",\"i\":\"10.0.0.1\",\"p\":5555,\"f\":\"abc\",\"z\":\"future\"}"))
                    .isPresent();
        }

        @Test
        @DisplayName("a missing name is allowed and falls back on the client side")
        void nameOptional() {
            assertThat(decode("{\"i\":\"10.0.0.1\",\"p\":5555,\"f\":\"abc\"}"))
                    .hasValueSatisfying(announcement ->
                            assertThat(announcement.name()).isEmpty());
        }

        @Test
        @DisplayName("the endpoint is the pair a client would dial")
        void endpoint() {
            assertThat(ROOM_12.endpoint()).isEqualTo("192.168.1.42:5555");
        }

        @Test
        @DisplayName("an over-long name or id is truncated rather than refused")
        void truncation() {
            ServerAnnouncement long1 = new ServerAnnouncement("x".repeat(200), "10.0.0.1", 1,
                    "y".repeat(400));

            assertThat(long1.name()).hasSize(ServerAnnouncement.MAX_NAME_LENGTH);
            assertThat(long1.fingerprint()).hasSize(ServerAnnouncement.MAX_FINGERPRINT_LENGTH);
        }

        @Test
        @DisplayName("an announcement without an address or an id cannot be built")
        void requiredFields() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ServerAnnouncement(null, "1.2.3.4", 1, "f"));
            assertThatNullPointerException()
                    .isThrownBy(() -> new ServerAnnouncement("n", null, 1, "f"));
            assertThatNullPointerException()
                    .isThrownBy(() -> new ServerAnnouncement("n", "1.2.3.4", 1, null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ServerAnnouncement("n", "  ", 1, "f"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ServerAnnouncement("n", "1.2.3.4", 1, " "));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ServerAnnouncement("n", "1.2.3.4", 0, "f"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ServerAnnouncement("n", "1.2.3.4", 70000, "f"));
        }
    }

    @Nested
    @DisplayName("hostile input")
    class Fuzz {

        @Test
        @DisplayName("random bytes decode to nothing and never throw")
        void randomBytes() {
            Random random = new Random(20260820L);

            for (int i = 0; i < 2000; i++) {
                byte[] noise = new byte[random.nextInt(DiscoveryProtocol.MAX_PACKET_BYTES)];
                random.nextBytes(noise);

                assertThat(DiscoveryProtocol.decodeAnnouncement(noise, noise.length))
                        .as("a subnet writes whatever it likes to this port")
                        .isEmpty();
                assertThat(DiscoveryProtocol.isRequest(noise, noise.length)).isFalse();
            }
        }

        @Test
        @DisplayName("a truncated valid announcement decodes to nothing")
        void truncated() {
            byte[] full = DiscoveryProtocol.encodeAnnouncement(ROOM_12);

            for (int length = 1; length < full.length; length++) {
                assertThat(DiscoveryProtocol.decodeAnnouncement(full, length))
                        .as("cut at byte " + length)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("structurally wrong JSON decodes to nothing")
        void wrongShapes() {
            assertThat(decode("[]")).isEmpty();
            assertThat(decode("\"a string\"")).isEmpty();
            assertThat(decode("42")).isEmpty();
            assertThat(decode("null")).isEmpty();
            assertThat(decode("{")).isEmpty();
            assertThat(decode("")).isEmpty();
        }

        @Test
        @DisplayName("fields of the wrong type decode to nothing")
        void wrongTypes() {
            assertThat(decode("{\"i\":123,\"p\":5555,\"f\":\"abc\"}")).isEmpty();
            assertThat(decode("{\"i\":\"10.0.0.1\",\"p\":\"5555\",\"f\":\"abc\"}")).isEmpty();
            assertThat(decode("{\"i\":\"10.0.0.1\",\"p\":5555.5,\"f\":\"abc\"}")).isEmpty();
            assertThat(decode("{\"i\":\"10.0.0.1\",\"p\":5555,\"f\":{}}")).isEmpty();
            assertThat(decode("{\"i\":{\"nested\":1},\"p\":5555,\"f\":\"abc\"}")).isEmpty();
        }

        @Test
        @DisplayName("missing required fields decode to nothing")
        void missingFields() {
            assertThat(decode("{\"p\":5555,\"f\":\"abc\"}")).isEmpty();
            assertThat(decode("{\"i\":\"10.0.0.1\",\"f\":\"abc\"}")).isEmpty();
            assertThat(decode("{\"i\":\"10.0.0.1\",\"p\":5555}")).isEmpty();
            assertThat(decode("{}")).isEmpty();
        }

        @Test
        @DisplayName("a well-formed object with an impossible port decodes to nothing")
        void impossiblePort() {
            assertThat(decode("{\"i\":\"10.0.0.1\",\"p\":0,\"f\":\"abc\"}")).isEmpty();
            assertThat(decode("{\"i\":\"10.0.0.1\",\"p\":-1,\"f\":\"abc\"}")).isEmpty();
            assertThat(decode("{\"i\":\"10.0.0.1\",\"p\":999999,\"f\":\"abc\"}")).isEmpty();
        }

        @Test
        @DisplayName("blank required values decode to nothing")
        void blankValues() {
            assertThat(decode("{\"i\":\"  \",\"p\":5555,\"f\":\"abc\"}")).isEmpty();
            assertThat(decode("{\"i\":\"10.0.0.1\",\"p\":5555,\"f\":\"\"}")).isEmpty();
        }

        @Test
        @DisplayName("an oversized packet is refused before it is parsed")
        void oversized() {
            byte[] huge = new byte[4096];
            java.util.Arrays.fill(huge, (byte) 'a');

            assertThat(DiscoveryProtocol.decodeAnnouncement(huge, huge.length)).isEmpty();
        }

        @Test
        @DisplayName("null and non-positive lengths decode to nothing")
        void bounds() {
            assertThat(DiscoveryProtocol.decodeAnnouncement(null, 10)).isEmpty();
            assertThat(DiscoveryProtocol.decodeAnnouncement(new byte[10], 0)).isEmpty();
            assertThat(DiscoveryProtocol.decodeAnnouncement(new byte[10], -5)).isEmpty();
        }

        @Test
        @DisplayName("invalid UTF-8 decodes to nothing rather than throwing")
        void invalidUtf8() {
            byte[] broken = {(byte) 0xC3, (byte) 0x28, (byte) 0xA0, (byte) 0xA1};

            assertThat(DiscoveryProtocol.decodeAnnouncement(broken, broken.length)).isEmpty();
        }
    }

    @Nested
    @DisplayName("fingerprint display")
    class Display {

        @Test
        @DisplayName("the short form is grouped, uppercase and stable")
        void shortForm() {
            assertThat(Fingerprints.shortForm("7f3a2b91-1111-2222-3333-444444444444"))
                    .isEqualTo("7F3A-2B91");
            assertThat(Fingerprints.shortForm("abc")).isEqualTo("ABC");
            assertThat(Fingerprints.shortForm("abcde")).isEqualTo("ABCD-E");
        }

        @Test
        @DisplayName("an absent id renders as a word, not as an empty gap")
        void unknown() {
            assertThat(Fingerprints.shortForm(null)).isEqualTo(Fingerprints.UNKNOWN);
            assertThat(Fingerprints.shortForm("   ")).isEqualTo(Fingerprints.UNKNOWN);
            assertThat(Fingerprints.shortForm("---")).isEqualTo(Fingerprints.UNKNOWN);
        }

        @Test
        @DisplayName("comparison is on the full id and ignores case and space")
        void comparison() {
            assertThat(Fingerprints.sameFingerprint("ABC-def", " abc-DEF ")).isTrue();
            assertThat(Fingerprints.sameFingerprint("abc", "abd")).isFalse();
            assertThat(Fingerprints.sameFingerprint(null, "abc")).isFalse();
            assertThat(Fingerprints.sameFingerprint("abc", null)).isFalse();
        }

        @Test
        @DisplayName("two ids sharing a short form are still different ids")
        void shortFormIsNotAnIdentity() {
            String left = "7f3a2b91-aaaa-0000-0000-000000000000";
            String right = "7f3a2b91-bbbb-0000-0000-000000000000";

            assertThat(Fingerprints.shortForm(left)).isEqualTo(Fingerprints.shortForm(right));
            assertThat(Fingerprints.sameFingerprint(left, right))
                    .as("which is exactly why pinning compares the full value")
                    .isFalse();
        }
    }
}
