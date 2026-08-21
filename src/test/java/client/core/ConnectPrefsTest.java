package client.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link ConnectPrefs} — endpoint resolution, persistence and field validation
 * (E4.5, F1.5).
 */
class ConnectPrefsTest {

    private InMemoryPropertiesStore store;
    private ConnectPrefs prefs;

    @BeforeEach
    void setUp() {
        store = new InMemoryPropertiesStore();
        prefs = new ConnectPrefs(store);
    }

    @Nested
    @DisplayName("resolution precedence (F1.5)")
    class Resolution {

        @Test
        void prefersTheLastSuccessfullyUsedEndpoint() {
            prefs.remember("192.168.1.42", 6000);

            ServerEndpoint resolved = prefs.resolve(new ClientConfig.Settings("configured.host", 5555));

            assertThat(resolved.host()).isEqualTo("192.168.1.42");
            assertThat(resolved.port()).isEqualTo(6000);
        }

        @Test
        void fallsBackToClientPropertiesWhenNothingIsRemembered() {
            ServerEndpoint resolved = prefs.resolve(new ClientConfig.Settings("configured.host", 5555));

            assertThat(resolved.display()).isEqualTo("configured.host:5555");
        }

        @Test
        void fallsBackToLocalhostWhenThereIsNoConfigAtAll() {
            assertThat(prefs.resolve(null)).isEqualTo(ServerEndpoint.LOCALHOST);
        }

        @Test
        void fallsBackToLocalhostWhenClientPropertiesIsUnusable() {
            ServerEndpoint resolved = prefs.resolve(new ClientConfig.Settings("  ", 5555));

            assertThat(resolved).isEqualTo(ServerEndpoint.LOCALHOST);
        }

        @Test
        void ignoresACorruptRememberedEndpoint() {
            Properties corrupt = new Properties();
            corrupt.setProperty(ConnectPrefs.KEY_LAST_HOST, "still.valid");
            corrupt.setProperty(ConnectPrefs.KEY_LAST_PORT, "not-a-number");
            store.save(corrupt);

            assertThat(prefs.lastUsed()).isEmpty();
            assertThat(prefs.resolve(null)).isEqualTo(ServerEndpoint.LOCALHOST);
        }

        @Test
        void ignoresAHalfWrittenRememberedEndpoint() {
            Properties partial = new Properties();
            partial.setProperty(ConnectPrefs.KEY_LAST_HOST, "only.host");
            store.save(partial);

            assertThat(prefs.lastUsed()).isEmpty();
        }
    }

    @Nested
    @DisplayName("persistence")
    class Persistence {

        @Test
        void rememberWritesThroughTheStore() {
            prefs.remember(new ServerEndpoint("demo.local", 5555));

            assertThat(store.load().getProperty(ConnectPrefs.KEY_LAST_HOST)).isEqualTo("demo.local");
            assertThat(store.load().getProperty(ConnectPrefs.KEY_LAST_PORT)).isEqualTo("5555");
        }

        @Test
        void rememberSurvivesANewPrefsInstanceOverTheSameStore() {
            prefs.remember("demo.local", 5555);

            assertThat(new ConnectPrefs(store).lastUsed())
                    .contains(new ServerEndpoint("demo.local", 5555));
        }

        @Test
        void rememberOverwritesThePreviousValue() {
            prefs.remember("first.host", 1111);
            prefs.remember("second.host", 2222);

            assertThat(prefs.lastUsed()).contains(new ServerEndpoint("second.host", 2222));
        }

        @Test
        void rememberKeepsUnrelatedKeysInTheSameFile() {
            Properties existing = new Properties();
            existing.setProperty("some.other.setting", "keep me");
            store.save(existing);

            prefs.remember("demo.local", 5555);

            assertThat(store.load().getProperty("some.other.setting")).isEqualTo("keep me");
        }

        @Test
        void forgetClearsTheRememberedEndpoint() {
            prefs.remember("demo.local", 5555);

            prefs.forget();

            assertThat(prefs.lastUsed()).isEmpty();
        }

        @Test
        void rememberRejectsAnUnusableEndpoint() {
            assertThatIllegalArgumentException().isThrownBy(() -> prefs.remember("host", 0));
            assertThatIllegalArgumentException().isThrownBy(() -> prefs.remember("", 5555));
        }
    }

    @Nested
    @DisplayName("host validation")
    class HostValidation {

        @Test
        void acceptsHostnamesAndAddresses() {
            assertThat(ConnectPrefs.validateHost("localhost")).isEmpty();
            assertThat(ConnectPrefs.validateHost("192.168.1.42")).isEmpty();
            assertThat(ConnectPrefs.validateHost("  demo.local  ")).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        void rejectsBlank(String raw) {
            assertThat(ConnectPrefs.validateHost(raw)).contains("Enter the server's address");
        }

        @Test
        void rejectsNull() {
            assertThat(ConnectPrefs.validateHost(null)).isPresent();
        }

        @Test
        void rejectsEmbeddedSpaces() {
            assertThat(ConnectPrefs.validateHost("my server")).contains("Address cannot contain spaces");
        }

        @Test
        void explainsThatThePortBelongsInItsOwnField() {
            assertThat(ConnectPrefs.validateHost("192.168.1.42:5555"))
                    .contains("Enter the address only. The port goes in the next field");
        }
    }

    @Nested
    @DisplayName("port validation")
    class PortValidation {

        @Test
        void acceptsPortsInRange() {
            assertThat(ConnectPrefs.validatePort("1")).isEmpty();
            assertThat(ConnectPrefs.validatePort("5555")).isEmpty();
            assertThat(ConnectPrefs.validatePort(" 65535 ")).isEmpty();
        }

        @Test
        void rejectsBlankAndNull() {
            assertThat(ConnectPrefs.validatePort("")).contains("Enter the server's port");
            assertThat(ConnectPrefs.validatePort(null)).isPresent();
        }

        @Test
        void rejectsNonNumbers() {
            assertThat(ConnectPrefs.validatePort("abcd")).contains("Port must be a number");
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1", "65536", "99999"})
        void rejectsOutOfRange(String raw) {
            assertThat(ConnectPrefs.validatePort(raw))
                    .contains("Port must be between 1 and 65535");
        }
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        void isValidCombinesBothRules() {
            assertThat(ConnectPrefs.isValid("localhost", "5555")).isTrue();
            assertThat(ConnectPrefs.isValid("localhost", "0")).isFalse();
            assertThat(ConnectPrefs.isValid("", "5555")).isFalse();
        }

        @Test
        void parseTrimsAndBuildsAnEndpoint() {
            assertThat(ConnectPrefs.parse("  demo.local ", " 5555 "))
                    .isEqualTo(new ServerEndpoint("demo.local", 5555));
        }

        @Test
        void parseFailsWithTheHumanMessage() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ConnectPrefs.parse("localhost", "99999"))
                    .withMessage("Port must be between 1 and 65535");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ConnectPrefs.parse("", "5555"))
                    .withMessage("Enter the server's address");
        }
    }

    @Nested
    @DisplayName("ServerEndpoint")
    class Endpoint {

        @Test
        void displaysAsHostColonPort() {
            assertThat(new ServerEndpoint("demo.local", 5555).display()).isEqualTo("demo.local:5555");
            assertThat(new ServerEndpoint("demo.local", 5555)).hasToString("demo.local:5555");
        }

        @Test
        void trimsItsHost() {
            assertThat(new ServerEndpoint("  demo.local ", 5555).host()).isEqualTo("demo.local");
        }

        @Test
        void rejectsUnusableValues() {
            assertThatIllegalArgumentException().isThrownBy(() -> new ServerEndpoint("   ", 5555));
            assertThatIllegalArgumentException().isThrownBy(() -> new ServerEndpoint("h", 70000));
            assertThat(ServerEndpoint.LOCALHOST.display()).isEqualTo("localhost:5555");
        }
    }

    @Nested
    @DisplayName("trust-on-first-use pinning (E19.10, F13.4)")
    class Pinning {

        private static final String ID = "7f3a2b91-1111-2222-3333-444444444444";

        @Test
        @DisplayName("a fresh client trusts nobody")
        void nothingPinnedAtFirst() {
            assertThat(prefs.pinned()).isEmpty();
            assertThat(prefs.pinnedName()).isEmpty();
        }

        @Test
        @DisplayName("pinning stores the address, the port, the id and the name")
        void pinning() {
            prefs.pin(new ServerEndpoint("192.168.1.42", 5555), ID, "Room 12 server");

            assertThat(prefs.pinned()).hasValueSatisfying(pin -> {
                assertThat(pin.endpoint().display()).isEqualTo("192.168.1.42:5555");
                assertThat(pin.fingerprint())
                        .as("the full id is stored; the short form is for eyes only")
                        .isEqualTo(ID);
            });
            assertThat(prefs.pinnedName()).contains("Room 12 server");
        }

        @Test
        @DisplayName("re-pinning replaces the previous trust, which is what confirming a change means")
        void rePinning() {
            prefs.pin(new ServerEndpoint("192.168.1.42", 5555), ID, "Old");
            prefs.pin(new ServerEndpoint("192.168.1.42", 5555), "new-id", "New");

            assertThat(prefs.pinned()).hasValueSatisfying(pin ->
                    assertThat(pin.fingerprint()).isEqualTo("new-id"));
            assertThat(prefs.pinnedName()).contains("New");
        }

        @Test
        @DisplayName("a server with no name pins without one rather than storing a blank")
        void pinningWithoutAName() {
            prefs.pin(new ServerEndpoint("192.168.1.42", 5555), ID, "  ");

            assertThat(prefs.pinnedName()).isEmpty();
            assertThat(prefs.pinned()).isPresent();
        }

        @Test
        @DisplayName("unpinning drops the trust and keeps the remembered address")
        void unpinning() {
            prefs.remember("192.168.1.42", 5555);
            prefs.pin(new ServerEndpoint("192.168.1.42", 5555), ID, "Room 12 server");

            prefs.unpin();

            assertThat(prefs.pinned()).isEmpty();
            assertThat(prefs.pinnedName()).isEmpty();
            assertThat(prefs.lastUsed())
                    .as("what to pre-fill and what to trust are two different facts")
                    .isPresent();
        }

        @Test
        @DisplayName("an unusable stored pin is forgotten rather than half-trusted")
        void unusableStoredPin() {
            Properties props = store.load();
            props.setProperty(ConnectPrefs.KEY_PIN_HOST, "192.168.1.42");
            props.setProperty(ConnectPrefs.KEY_PIN_PORT, "not-a-port");
            props.setProperty(ConnectPrefs.KEY_PIN_FINGERPRINT, ID);
            store.save(props);

            assertThat(prefs.pinned()).isEmpty();
        }

        @Test
        @DisplayName("a pin missing any of its parts is no pin")
        void incompletePin() {
            Properties props = store.load();
            props.setProperty(ConnectPrefs.KEY_PIN_HOST, "192.168.1.42");
            props.setProperty(ConnectPrefs.KEY_PIN_PORT, "5555");
            store.save(props);
            assertThat(prefs.pinned()).isEmpty();

            props.setProperty(ConnectPrefs.KEY_PIN_FINGERPRINT, "   ");
            store.save(props);
            assertThat(prefs.pinned()).isEmpty();
        }

        @Test
        @DisplayName("the pin keys are additive, so an older connect.properties still loads")
        void fileCompatibility() {
            // Exactly what a pre-E19 client wrote: two keys, nothing else.
            Properties legacy = new Properties();
            legacy.setProperty(ConnectPrefs.KEY_LAST_HOST, "192.168.1.42");
            legacy.setProperty(ConnectPrefs.KEY_LAST_PORT, "5555");
            store.save(legacy);

            assertThat(prefs.lastUsed())
                    .as("the remembered endpoint still resolves")
                    .isPresent();
            assertThat(prefs.pinned())
                    .as("and the absent pin reads as \"never connected before\"")
                    .isEmpty();

            prefs.pin(new ServerEndpoint("192.168.1.42", 5555), ID, "Room 12 server");
            assertThat(store.load().getProperty(ConnectPrefs.KEY_LAST_HOST))
                    .as("pinning never rewrites the keys that were already there")
                    .isEqualTo("192.168.1.42");
        }

        @Test
        @DisplayName("arguments are required")
        void required() {
            assertThatNullPointerException()
                    .isThrownBy(() -> prefs.pin(null, ID, "n"));
            assertThatNullPointerException()
                    .isThrownBy(() -> prefs.pin(new ServerEndpoint("h", 1), null, "n"));
        }
    }
}
