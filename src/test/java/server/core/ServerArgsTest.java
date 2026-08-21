package server.core;

import common.dto.discovery.DiscoveryProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The server's command-line switches (E19.5, F13.2).
 *
 * <p>The {@code --headless} case is the one that matters most, and it is the one
 * the whole console feature rests on: the promise is that the flag runs the server
 * exactly as it ran before E19. That promise is a test, not an inspection, which
 * is why the parsing lives here rather than inside {@link ServerMain}.
 */
class ServerArgsTest {

    @Nested
    @DisplayName("--headless")
    class Headless {

        @Test
        @DisplayName("the flag is off by default, so a double-clicked JAR opens the console")
        void defaultsToConsole() {
            ServerArgs args = ServerArgs.parse(new String[0]);

            assertThat(args.headless()).isFalse();
            assertThat(args.port()).isEqualTo(5555);
            assertThat(args.discoveryPort()).isEqualTo(DiscoveryProtocol.DEFAULT_DISCOVERY_PORT);
            assertThat(args.discoveryEnabled()).isTrue();
            assertThat(args.isClean()).isTrue();
        }

        @Test
        @DisplayName("the flag turns the window off and changes nothing else")
        void headlessChangesOnlyTheWindow() {
            ServerArgs args = ServerArgs.parse(new String[] {"--headless"});

            assertThat(args.headless()).isTrue();
            assertThat(args.port())
                    .as("headless must be the pre-E19 server, same port, same everything")
                    .isEqualTo(ServerArgs.defaults().port());
            assertThat(args.discoveryEnabled()).isEqualTo(ServerArgs.defaults().discoveryEnabled());
            assertThat(args.isClean()).isTrue();
        }

        @Test
        @DisplayName("case and surrounding space do not matter")
        void tolerant() {
            assertThat(ServerArgs.parse(new String[] {"  --HEADLESS  "}).headless()).isTrue();
        }

        @Test
        @DisplayName("headless combines with the other switches")
        void combined() {
            ServerArgs args = ServerArgs.parse(
                    new String[] {"--headless", "--port", "6000", "--no-discovery"});

            assertThat(args.headless()).isTrue();
            assertThat(args.port()).isEqualTo(6000);
            assertThat(args.discoveryEnabled()).isFalse();
            assertThat(args.isClean()).isTrue();
        }
    }

    @Nested
    @DisplayName("ports")
    class Ports {

        @Test
        @DisplayName("both spellings of --port are accepted")
        void bothSpellings() {
            assertThat(ServerArgs.parse(new String[] {"--port", "6001"}).port()).isEqualTo(6001);
            assertThat(ServerArgs.parse(new String[] {"--port=6002"}).port()).isEqualTo(6002);
        }

        @Test
        @DisplayName("a bare number is still the port, as the pre-E19 server accepted")
        void barePort() {
            ServerArgs args = ServerArgs.parse(new String[] {"5556"});

            assertThat(args.port())
                    .as("somebody's shortcut still passes the port positionally")
                    .isEqualTo(5556);
            assertThat(args.isClean()).isTrue();
        }

        @Test
        @DisplayName("the discovery port has its own switch")
        void discoveryPort() {
            assertThat(ServerArgs.parse(new String[] {"--discovery-port", "7000"}).discoveryPort())
                    .isEqualTo(7000);
            assertThat(ServerArgs.parse(new String[] {"--discovery-port=7001"}).discoveryPort())
                    .isEqualTo(7001);
            assertThat(ServerArgs.parse(new String[] {"--discovery-port=7001"}).port())
                    .as("the two ports must not be confused with each other")
                    .isEqualTo(5555);
        }

        @Test
        @DisplayName("a port that is not a number warns and falls back rather than exiting")
        void notANumber() {
            ServerArgs args = ServerArgs.parse(new String[] {"--port", "five-thousand"});

            assertThat(args.port()).isEqualTo(5555);
            assertThat(args.warnings()).singleElement()
                    .asString()
                    .as("recoverable in ten seconds before a defence, which refusing to start is not")
                    .contains("is not a number")
                    .contains("Using 5555");
        }

        @Test
        @DisplayName("a port outside the legal range warns and falls back")
        void outOfRange() {
            assertThat(ServerArgs.parse(new String[] {"--port", "70000"}).warnings())
                    .singleElement().asString().contains("outside 1 to 65535");
            assertThat(ServerArgs.parse(new String[] {"--port", "0"}).port()).isEqualTo(5555);
        }

        @Test
        @DisplayName("--port with nothing after it warns")
        void missingValue() {
            ServerArgs args = ServerArgs.parse(new String[] {"--headless", "--port"});

            assertThat(args.headless()).isTrue();
            assertThat(args.port()).isEqualTo(5555);
            assertThat(args.warnings()).singleElement().asString()
                    .contains("needs a number after it");
        }

        @Test
        @DisplayName("--port=  with an empty value warns")
        void emptyValue() {
            assertThat(ServerArgs.parse(new String[] {"--port="}).warnings())
                    .singleElement().asString().contains("needs a number after it");
        }
    }

    @Nested
    @DisplayName("everything else")
    class Other {

        @Test
        @DisplayName("an unknown option is named, along with the ones that exist")
        void unknownOption() {
            ServerArgs args = ServerArgs.parse(new String[] {"--verbose"});

            assertThat(args.warnings()).singleElement().asString()
                    .contains("Ignored unknown option '--verbose'")
                    .contains("--headless")
                    .contains("--port");
        }

        @Test
        @DisplayName("a near miss on an option name suggests the real one")
        void nearMiss() {
            assertThat(ServerArgs.parse(new String[] {"--portt", "1"}).warnings())
                    .anySatisfy(warning -> assertThat(warning).contains("Did you mean --port"));
        }

        @Test
        @DisplayName("null, blank and empty arguments are skipped rather than fatal")
        void emptyAndNull() {
            ServerArgs args = ServerArgs.parse(new String[] {null, "  ", "--headless"});

            assertThat(args.headless()).isTrue();
            assertThat(args.isClean()).isTrue();
            assertThat(ServerArgs.parse((String[]) null).headless()).isFalse();
            assertThatNullPointerException().isThrownBy(() -> ServerArgs.parse((List<String>) null));
        }

        @Test
        @DisplayName("--no-discovery starts the server with the responder off")
        void noDiscovery() {
            assertThat(ServerArgs.parse(new String[] {"--no-discovery"}).discoveryEnabled())
                    .isFalse();
        }

        @Test
        @DisplayName("warnings are an immutable copy")
        void immutableWarnings() {
            ServerArgs args = new ServerArgs(false, 1, 2, true, null);

            assertThat(args.warnings()).isEmpty();
            assertThat(ServerArgs.defaults().warnings()).isEmpty();
        }

        @Test
        @DisplayName("the last of two conflicting ports wins, without a warning")
        void lastPortWins() {
            ServerArgs args = ServerArgs.parse(new String[] {"--port", "6000", "--port", "6001"});

            assertThat(args.port()).isEqualTo(6001);
            assertThat(args.isClean()).isTrue();
        }
    }
}
