package server.console;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The address ranking (E19.1, F13.2).
 *
 * <p>Every case runs against injected interface data, which is the whole reason
 * the detector takes its two sources as seams: the failure this ranking exists to
 * prevent is a Windows laptop offering a VirtualBox host-only address to a room
 * full of students, and that machine is not available to the test suite. Injected,
 * it is four records.
 */
class NetworkDetectorTest {

    private static final NetworkAddress WIFI =
            new NetworkAddress("192.168.1.42", "Wi-Fi", true);
    private static final NetworkAddress ETHERNET =
            new NetworkAddress("10.0.0.5", "Ethernet", true);
    private static final NetworkAddress VIRTUALBOX =
            new NetworkAddress("192.168.56.1", "VirtualBox Host-Only Network", true);
    private static final NetworkAddress HYPERV =
            new NetworkAddress("172.20.16.1", "vEthernet (Default Switch)", true);
    private static final NetworkAddress APIPA =
            new NetworkAddress("169.254.7.7", "Ethernet 2", true);
    private static final NetworkAddress PUBLIC =
            new NetworkAddress("93.184.216.34", "ppp0", false);

    private static NetworkDetector detector(List<NetworkAddress> found, String hint) {
        return new NetworkDetector(() -> found,
                () -> Optional.ofNullable(hint));
    }

    @Nested
    @DisplayName("ranking")
    class Ranking {

        @Test
        @DisplayName("the default-route address wins outright")
        void routeHintWins() {
            List<NetworkAddress> ranked =
                    NetworkDetector.rank(List.of(VIRTUALBOX, WIFI, ETHERNET), "10.0.0.5");

            assertThat(ranked.get(0)).isEqualTo(ETHERNET);
        }

        @Test
        @DisplayName("a virtual adapter's address is demoted below a real one")
        void virtualAdaptersAreDemoted() {
            List<NetworkAddress> ranked =
                    NetworkDetector.rank(List.of(VIRTUALBOX, HYPERV, WIFI), null);

            assertThat(ranked)
                    .as("the address a laptop in the room can actually reach comes first")
                    .containsExactly(WIFI, VIRTUALBOX, HYPERV);
        }

        @Test
        @DisplayName("a link-local address is always last")
        void linkLocalIsLast() {
            List<NetworkAddress> ranked =
                    NetworkDetector.rank(List.of(APIPA, PUBLIC, VIRTUALBOX, WIFI), null);

            assertThat(ranked).last()
                    .as("169.254 means DHCP did not answer, which is evidence of a problem")
                    .isEqualTo(APIPA);
        }

        @Test
        @DisplayName("a site-local address beats a routable public one")
        void siteLocalBeatsPublic() {
            assertThat(NetworkDetector.rank(List.of(PUBLIC, WIFI), null))
                    .containsExactly(WIFI, PUBLIC);
        }

        @Test
        @DisplayName("equal candidates keep the order they were enumerated in")
        void tiesAreStable() {
            List<NetworkAddress> ranked = NetworkDetector.rank(List.of(WIFI, ETHERNET), null);

            assertThat(ranked)
                    .as("two refreshes on an unchanged machine must not disagree")
                    .containsExactly(WIFI, ETHERNET);
            assertThat(NetworkDetector.rank(List.of(ETHERNET, WIFI), null))
                    .containsExactly(ETHERNET, WIFI);
        }

        @Test
        @DisplayName("the route hint beats even a virtual-looking interface")
        void routeHintBeatsTheNameHeuristic() {
            List<NetworkAddress> ranked =
                    NetworkDetector.rank(List.of(WIFI, HYPERV), "172.20.16.1");

            assertThat(ranked.get(0))
                    .as("the routing table knows more than an interface name does")
                    .isEqualTo(HYPERV);
        }

        @Test
        @DisplayName("no candidates is an empty list, not a failure")
        void emptyInput() {
            assertThat(NetworkDetector.rank(List.of(), "10.0.0.1")).isEmpty();
            assertThat(NetworkDetector.rank(null, null)).isEmpty();
        }

        @Test
        @DisplayName("tiers are what the class javadoc says they are")
        void tiers() {
            assertThat(NetworkDetector.tierOf(WIFI, "192.168.1.42")).isZero();
            assertThat(NetworkDetector.tierOf(WIFI, null)).isEqualTo(1);
            assertThat(NetworkDetector.tierOf(VIRTUALBOX, null)).isEqualTo(2);
            assertThat(NetworkDetector.tierOf(PUBLIC, null)).isEqualTo(3);
            assertThat(NetworkDetector.tierOf(APIPA, null)).isEqualTo(4);
        }

        @Test
        @DisplayName("the virtual-name heuristic catches the adapters it was written for")
        void virtualNames() {
            assertThat(NetworkDetector.looksVirtual("vEthernet (WSL)")).isTrue();
            assertThat(NetworkDetector.looksVirtual("Docker Bridge")).isTrue();
            assertThat(NetworkDetector.looksVirtual("VMware Network Adapter VMnet8")).isTrue();
            assertThat(NetworkDetector.looksVirtual("Tailscale")).isTrue();
            assertThat(NetworkDetector.looksVirtual("Wi-Fi")).isFalse();
            assertThat(NetworkDetector.looksVirtual("Ethernet")).isFalse();
        }

        @Test
        @DisplayName("link-local detection is the 169.254 block and nothing else")
        void linkLocalDetection() {
            assertThat(NetworkDetector.isLinkLocal("169.254.1.1")).isTrue();
            assertThat(NetworkDetector.isLinkLocal("169.253.1.1")).isFalse();
            assertThat(NetworkDetector.isLinkLocal("10.0.0.1")).isFalse();
        }
    }

    @Nested
    @DisplayName("the detector")
    class Detector {

        @Test
        @DisplayName("best() is the top of all()")
        void bestIsFirst() {
            NetworkDetector detector = detector(List.of(VIRTUALBOX, WIFI), null);

            assertThat(detector.all()).containsExactly(WIFI, VIRTUALBOX);
            assertThat(detector.best()).contains(WIFI);
        }

        @Test
        @DisplayName("a machine with no usable address answers empty rather than failing")
        void noAddresses() {
            NetworkDetector detector = detector(List.of(), null);

            assertThat(detector.all()).isEmpty();
            assertThat(detector.best()).isEmpty();
        }

        @Test
        @DisplayName("a route hint that names an address nobody has changes nothing")
        void staleHint() {
            NetworkDetector detector = detector(List.of(WIFI, VIRTUALBOX), "8.8.8.8");

            assertThat(detector.all()).containsExactly(WIFI, VIRTUALBOX);
        }

        @Test
        @DisplayName("both sources are required")
        void sourcesRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new NetworkDetector(null, NetworkDetector.RouteHint.NONE));
            assertThatNullPointerException()
                    .isThrownBy(() -> new NetworkDetector(List::of, null));
        }

        @Test
        @DisplayName("the no-hint route source answers nothing")
        void noneHint() {
            assertThat(NetworkDetector.RouteHint.NONE.preferredIp()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the machine's own interfaces")
    class Machine {

        @Test
        @DisplayName("enumeration answers a usable list on whatever machine this runs on")
        void enumerateDoesNotThrow() {
            List<NetworkAddress> found = NetworkDetector.enumerate();

            assertThat(found)
                    .as("every entry is a real IPv4 on a named interface")
                    .allSatisfy(address -> {
                        assertThat(address.ip()).isNotBlank().doesNotContain(":");
                        assertThat(address.interfaceName()).isNotBlank();
                    });
        }

        @Test
        @DisplayName("the default-route probe answers a site address or nothing, never loopback")
        void routeProbeIsHonest() {
            Optional<String> hint = NetworkDetector.probeDefaultRoute();

            hint.ifPresent(ip -> assertThat(ip)
                    .as("a loopback or wildcard answer is no answer, and is reported as none")
                    .doesNotStartWith("127.")
                    .isNotEqualTo("0.0.0.0"));
        }

        @Test
        @DisplayName("the probe target is a documentation address, so nothing is ever contacted")
        void probeTargetIsReserved() {
            assertThat(NetworkDetector.ROUTE_PROBE_HOST)
                    .as("RFC 5737 TEST-NET-3, guaranteed to host nothing")
                    .isEqualTo("203.0.113.1");
            assertThat(NetworkDetector.ROUTE_PROBE_PORT).isEqualTo(9);
        }

        @Test
        @DisplayName("the system detector wires both real sources")
        void systemDetector() {
            assertThat(NetworkDetector.system().all()).isNotNull();
        }
    }

    @Nested
    @DisplayName("NetworkAddress")
    class Address {

        @Test
        @DisplayName("the picker's row names the interface, not only the number")
        void display() {
            assertThat(WIFI.display()).isEqualTo("192.168.1.42 (Wi-Fi)");
            assertThat(WIFI).hasToString("192.168.1.42 (Wi-Fi)");
        }

        @Test
        @DisplayName("a missing interface name degrades rather than throws")
        void missingInterfaceName() {
            assertThat(new NetworkAddress("10.0.0.1", "  ", true).interfaceName())
                    .isEqualTo("unknown interface");
            assertThat(new NetworkAddress("10.0.0.1", null, true).interfaceName())
                    .isEqualTo("unknown interface");
        }

        @Test
        @DisplayName("an address is required")
        void ipRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new NetworkAddress(null, "Wi-Fi", true));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new NetworkAddress("  ", "Wi-Fi", true));
        }
    }
}
