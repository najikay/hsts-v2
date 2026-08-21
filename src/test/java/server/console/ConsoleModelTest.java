package server.console;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The console header's decisions (E19.2 / E19.5, F13.2).
 *
 * <p>Every string here ends up projected at forty points in front of a room, so
 * every one of them is asserted rather than eyeballed, and the no-network and
 * no-fingerprint cases are asserted too: those are the ones that would otherwise
 * render as {@code "null:5555"} on the day.
 */
class ConsoleModelTest {

    private static final String FINGERPRINT = "7f3a2b91-1234-4c5d-8e9f-000000000000";

    private static final NetworkAddress WIFI = new NetworkAddress("192.168.1.42", "Wi-Fi", true);
    private static final NetworkAddress VBOX =
            new NetworkAddress("192.168.56.1", "VirtualBox Host-Only Network", true);

    private ConsoleModel model;

    @BeforeEach
    void setUp() {
        model = new ConsoleModel(List.of(WIFI, VBOX), 5555, FINGERPRINT);
    }

    @Nested
    @DisplayName("header")
    class Header {

        @Test
        @DisplayName("shows the best address and the port, and copies exactly that")
        void headerAndClipboard() {
            assertThat(model.headerText()).isEqualTo("192.168.1.42:5555");
            assertThat(model.clipboardText())
                    .as("copying something other than what is on screen is a lie")
                    .isEqualTo(model.headerText());
            assertThat(model.selectedIp()).contains("192.168.1.42");
            assertThat(model.port()).isEqualTo(5555);
        }

        @Test
        @DisplayName("a machine with no network says so in words")
        void noAddress() {
            ConsoleModel empty = new ConsoleModel(List.of(), 5555, FINGERPRINT);

            assertThat(empty.headerText()).isEqualTo(ConsoleModel.NO_ADDRESS);
            assertThat(empty.selectedIp()).isEmpty();
            assertThat(empty.addressChoices()).isEmpty();
        }

        @Test
        @DisplayName("shows the discovery id in the grouped form F13.3 specifies")
        void fingerprint() {
            assertThat(model.shortFingerprint()).isEqualTo("7F3A-2B91");
            assertThat(model.fingerprintText()).isEqualTo("ID 7F3A-2B91");
        }

        @Test
        @DisplayName("with no discovery id the second line still says something")
        void missingFingerprint() {
            assertThat(new ConsoleModel(List.of(WIFI), 5555, null).fingerprintText())
                    .as("an empty gap under a big address reads as a rendering bug")
                    .isEqualTo("Discovery is not configured on this server");
            assertThat(new ConsoleModel(List.of(WIFI), 5555, "  ").fingerprintText())
                    .isEqualTo("Discovery is not configured on this server");
        }

        @Test
        @DisplayName("the detected list is required")
        void detectedRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ConsoleModel(null, 5555, FINGERPRINT));
        }
    }

    @Nested
    @DisplayName("manual override")
    class Override {

        @Test
        @DisplayName("a typed address replaces the header and joins the picker")
        void freeTextOverride() {
            assertThat(model.selectAddress("10.1.2.3")).isEmpty();

            assertThat(model.headerText()).isEqualTo("10.1.2.3:5555");
            assertThat(model.addressChoices())
                    .as("the picker must not show a different address from the header")
                    .first()
                    .satisfies(address -> {
                        assertThat(address.ip()).isEqualTo("10.1.2.3");
                        assertThat(address.interfaceName()).isEqualTo("entered by hand");
                    });
        }

        @Test
        @DisplayName("choosing a detected address does not duplicate it in the picker")
        void pickingADetectedAddress() {
            model.selectAddress("192.168.56.1");

            assertThat(model.addressChoices()).containsExactly(WIFI, VBOX);
            assertThat(model.headerText()).isEqualTo("192.168.56.1:5555");
        }

        @Test
        @DisplayName("resetting goes back to the best detected address")
        void reset() {
            model.selectAddress("10.1.2.3");

            model.resetAddress();

            assertThat(model.headerText()).isEqualTo("192.168.1.42:5555");
        }

        @Test
        @DisplayName("resetting on a machine with no network clears rather than throws")
        void resetWithNoNetwork() {
            ConsoleModel empty = new ConsoleModel(List.of(), 5555, FINGERPRINT);
            empty.selectAddress("10.1.2.3");

            empty.resetAddress();

            assertThat(empty.headerText()).isEqualTo(ConsoleModel.NO_ADDRESS);
        }

        @Test
        @DisplayName("every rejection names the fix")
        void rejections() {
            assertThat(ConsoleModel.validateAddress(null)).contains(ConsoleModel.ADDRESS_REQUIRED);
            assertThat(ConsoleModel.validateAddress("   ")).contains(ConsoleModel.ADDRESS_REQUIRED);
            assertThat(ConsoleModel.validateAddress("192.168.1.42:5555"))
                    .contains(ConsoleModel.ADDRESS_HAS_PORT);
            assertThat(ConsoleModel.validateAddress("192.168 .1.42"))
                    .contains(ConsoleModel.ADDRESS_HAS_SPACES);
            assertThat(ConsoleModel.validateAddress(" 192.168.1.42 ")).isEmpty();
        }

        @Test
        @DisplayName("a rejected override leaves the header alone")
        void rejectedOverrideChangesNothing() {
            assertThat(model.selectAddress("192.168.1.42:5555")).isPresent();

            assertThat(model.headerText()).isEqualTo("192.168.1.42:5555");
            assertThat(model.selectedIp()).contains("192.168.1.42");
        }

        @Test
        @DisplayName("no message anywhere in this class uses an em dash")
        void houseCopyRule() {
            assertThat(List.of(ConsoleModel.ADDRESS_REQUIRED, ConsoleModel.ADDRESS_HAS_PORT,
                            ConsoleModel.ADDRESS_HAS_SPACES, ConsoleModel.NO_ADDRESS,
                            model.listenStatusText(), model.discoveryStatusText()))
                    .allSatisfy(message -> assertThat(message).doesNotContain("—"));
        }
    }

    @Nested
    @DisplayName("listener and discovery state")
    class State {

        @Test
        @DisplayName("the button is a verb and the status says what stopping means")
        void listening() {
            assertThat(model.isListening()).isFalse();
            assertThat(model.listenButtonText()).isEqualTo("Start listening");
            assertThat(model.listenStatusText())
                    .as("an operator must not have to wonder whether live exams were lost")
                    .contains("Exams already in progress keep running");

            model.setListening(true);

            assertThat(model.listenButtonText()).isEqualTo("Stop listening");
            assertThat(model.listenStatusText()).contains("Listening on port 5555");
        }

        @Test
        @DisplayName("the discovery label says what being off costs")
        void discovery() {
            assertThat(model.isDiscoveryEnabled()).isTrue();
            assertThat(model.discoveryStatusText()).contains("find this server by themselves");

            model.setDiscoveryEnabled(false);

            assertThat(model.discoveryStatusText()).contains("typed in by hand");
        }
    }
}
