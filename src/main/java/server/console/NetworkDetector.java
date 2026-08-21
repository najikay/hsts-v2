package server.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Works out which address to tell the students to connect to (Logic tier,
 * E19.1 / F13.2).
 *
 * <h2>Why this is not one line</h2>
 *
 * <p>{@code InetAddress.getLocalHost()} is the one-line version and it is wrong
 * often enough to lose a demo. On the machines this product runs on it commonly
 * answers {@code 127.0.1.1}, or the address of a Hyper-V switch, or whichever
 * interface the JDK enumerated first. What the operator needs is the address a
 * laptop on the same Wi-Fi can actually reach, shown big enough to read from the
 * back of a room.
 *
 * <h2>The ranking</h2>
 *
 * <p>Every non-loopback IPv4 on every interface that is up gets a tier, and the
 * list is sorted by it. The tiers, best first:
 *
 * <ol start="0">
 *   <li><b>The default-route address.</b> If the operating system would use this
 *       address to reach the internet, it is the address the LAN uses too. This
 *       is the only tier that consults the machine's routing table, and it is
 *       decisive when it fires.</li>
 *   <li><b>Site-local on a physical-looking interface.</b> RFC 1918 on something
 *       called "Wi-Fi" or "Ethernet": the ordinary answer.</li>
 *   <li><b>Site-local on a virtual-looking interface.</b> Also RFC 1918, but on a
 *       Hyper-V switch, a VirtualBox host-only adapter, a Docker bridge or a VPN
 *       tap. These are real addresses that no other machine in the room can
 *       reach, and on a Windows development laptop there are usually two or three
 *       of them sitting in front of the one that works. Demoting them by name is
 *       a heuristic, and it is the heuristic that fixes the actual failure.</li>
 *   <li><b>Anything else routable.</b> A public address, or a range this class
 *       does not recognise.</li>
 *   <li><b>Link-local (169.254/16).</b> Last, always. An APIPA address means DHCP
 *       did not answer, so it is evidence of a network problem rather than a way
 *       around one.</li>
 * </ol>
 *
 * <p>Ties keep the order the enumeration produced, so the answer does not shuffle
 * between two refreshes on an unchanged machine.
 *
 * <h2>The default-route probe</h2>
 *
 * <p>{@link #probeDefaultRoute()} opens a {@link DatagramSocket} and
 * {@code connect}s it to a documentation address. <b>No packet is sent.</b>
 * Connecting a UDP socket only fixes its peer, and the side effect this exploits
 * is that the kernel must consult the routing table at that moment to choose a
 * source address, which {@code getLocalAddress()} then reports. It needs no
 * network to be present and reaches nothing: a machine with no route at all
 * fails the connect, which is answered as "no hint" rather than as an error.
 *
 * <h2>Testing</h2>
 *
 * <p>Both sources are constructor seams. {@link #rank} is a pure function over
 * injected interface data, so every tier and every tie is asserted without a
 * network interface being involved, and {@link #system()} is the one wiring that
 * touches the machine.
 */
public final class NetworkDetector {

    private static final Logger log = LoggerFactory.getLogger(NetworkDetector.class);

    /**
     * TEST-NET-3 (RFC 5737), reserved for documentation and guaranteed never to
     * host anything. Nothing is sent to it; it exists to make the kernel pick the
     * default route. A real address such as a public DNS resolver would work
     * identically and would look, to anyone reading this code or a firewall log,
     * like the server phoning home.
     */
    static final String ROUTE_PROBE_HOST = "203.0.113.1";

    /** Discard port (RFC 863). Also never contacted. */
    static final int ROUTE_PROBE_PORT = 9;

    /** Interface-name fragments that mean "this address is real to nobody else". */
    private static final List<String> VIRTUAL_HINTS = List.of(
            "vethernet", "virtualbox", "vmware", "vmnet", "docker", "veth", "hyper-v",
            "loopback", "tap", "tun", "wsl", "bluetooth", "vpn", "zerotier", "tailscale");

    /** Where the candidate addresses come from. Injected so ranking is testable. */
    @FunctionalInterface
    public interface Interfaces {
        /** @return every non-loopback IPv4 currently configured, in any order */
        List<NetworkAddress> candidates();
    }

    /** The routing-table hint. Injected so the decisive tier is testable. */
    @FunctionalInterface
    public interface RouteHint {
        /** A hint that knows nothing; every ranking falls back to the name tiers. */
        RouteHint NONE = Optional::empty;

        /** @return the address the OS would use to reach the outside world */
        Optional<String> preferredIp();
    }

    private final Interfaces interfaces;
    private final RouteHint routeHint;

    /**
     * @param interfaces where the addresses come from
     * @param routeHint  the default-route probe
     */
    public NetworkDetector(Interfaces interfaces, RouteHint routeHint) {
        this.interfaces = Objects.requireNonNull(interfaces, "interfaces");
        this.routeHint = Objects.requireNonNull(routeHint, "routeHint");
    }

    /** @return a detector reading this machine's real interfaces and routing table. */
    public static NetworkDetector system() {
        return new NetworkDetector(NetworkDetector::enumerate, NetworkDetector::probeDefaultRoute);
    }

    /**
     * Every candidate, best first.
     *
     * @return the ranked list; empty on a machine with no usable IPv4 at all,
     *         which is a real state (cable out, Wi-Fi off) and not an error
     */
    public List<NetworkAddress> all() {
        return rank(interfaces.candidates(), routeHint.preferredIp().orElse(null));
    }

    /**
     * @return the address to show big on the console, empty when the machine has
     *         no non-loopback IPv4
     */
    public Optional<NetworkAddress> best() {
        List<NetworkAddress> ranked = all();
        return ranked.isEmpty() ? Optional.empty() : Optional.of(ranked.get(0));
    }

    /**
     * The ranking itself: pure, total, and the only place the tiers are decided.
     *
     * @param raw    candidates in enumeration order
     * @param hintIp the default-route address, or {@code null} when unknown
     * @return a new list, best first, ties in input order
     */
    static List<NetworkAddress> rank(List<NetworkAddress> raw, String hintIp) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        String hint = hintIp == null ? null : hintIp.trim();
        List<NetworkAddress> ranked = new ArrayList<>(raw);
        // Stable by contract, which is what keeps two consecutive refreshes on an
        // unchanged machine from disagreeing about the order of equal candidates.
        ranked.sort(Comparator.comparingInt(address -> tierOf(address, hint)));
        return List.copyOf(ranked);
    }

    /** @return the tier of one address; lower is better. See the class javadoc. */
    static int tierOf(NetworkAddress address, String hintIp) {
        if (hintIp != null && hintIp.equals(address.ip())) {
            return 0;
        }
        if (isLinkLocal(address.ip())) {
            return 4;
        }
        if (address.siteLocal()) {
            return looksVirtual(address.interfaceName()) ? 2 : 1;
        }
        return 3;
    }

    /** @return {@code true} for 169.254/16, the "DHCP did not answer" range. */
    static boolean isLinkLocal(String ip) {
        return ip.startsWith("169.254.");
    }

    /**
     * @return {@code true} when the interface name suggests a switch, a hypervisor
     *         or a tunnel rather than a cable or a radio
     */
    static boolean looksVirtual(String interfaceName) {
        String name = interfaceName.toLowerCase(Locale.ROOT);
        return VIRTUAL_HINTS.stream().anyMatch(name::contains);
    }

    // ===================== The machine ===================================

    /**
     * Reads this machine's interfaces.
     *
     * <p>Interfaces that are down, loopback or unreadable are skipped rather than
     * reported: an address on an interface that is not up cannot be connected to,
     * and offering it in the console's picker would only invite an operator to
     * choose it.
     *
     * @return every non-loopback IPv4 found, in enumeration order
     */
    static List<NetworkAddress> enumerate() {
        List<NetworkAddress> found = new ArrayList<>();
        try {
            for (NetworkInterface nic : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                collectFrom(nic, found);
            }
        } catch (SocketException e) {
            // A machine whose interface list cannot be read still has a console and
            // still has a manual override, so this is a degraded answer, not a stop.
            log.warn("Could not enumerate network interfaces: {}. "
                    + "Type the address by hand in the console header.", e.getMessage());
        }
        return List.copyOf(found);
    }

    private static void collectFrom(NetworkInterface nic, List<NetworkAddress> found) {
        try {
            if (!nic.isUp() || nic.isLoopback()) {
                return;
            }
        } catch (SocketException e) {
            log.debug("Skipping interface {}: {}", nic.getName(), e.getMessage());
            return;
        }
        String name = nic.getDisplayName() == null ? nic.getName() : nic.getDisplayName();
        for (InetAddress address : java.util.Collections.list(nic.getInetAddresses())) {
            if (address instanceof Inet4Address ipv4 && !ipv4.isLoopbackAddress()) {
                found.add(new NetworkAddress(ipv4.getHostAddress(), name, ipv4.isSiteLocalAddress()));
            }
        }
    }

    /**
     * Asks the routing table which address it would use, by connecting a UDP
     * socket and never sending anything through it. See the class javadoc.
     *
     * @return the source address the kernel chose, empty when the machine has no
     *         default route (or refuses the socket, which some hardened
     *         environments do)
     */
    static Optional<String> probeDefaultRoute() {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(new InetSocketAddress(InetAddress.getByName(ROUTE_PROBE_HOST), ROUTE_PROBE_PORT));
            InetAddress local = probe.getLocalAddress();
            if (local == null || local.isAnyLocalAddress() || local.isLoopbackAddress()) {
                return Optional.empty();
            }
            return Optional.of(local.getHostAddress());
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            log.debug("No default-route hint available: {}", e.toString());
            return Optional.empty();
        }
    }
}
