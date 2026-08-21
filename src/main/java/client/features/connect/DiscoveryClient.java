package client.features.connect;

import common.dto.discovery.DiscoveryProtocol;
import common.dto.discovery.ServerAnnouncement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Finds HSTS servers on the local network (Presentation tier, E19.10, F13.4).
 *
 * <p>Broadcast once, collect for about two seconds, answer with what replied.
 * That is the entire algorithm, and its shape follows from what it is for: a
 * student opening a client in a classroom should not have to do anything, and
 * should not have to wait either.
 *
 * <h2>Why a collect window rather than first-reply-wins</h2>
 *
 * <p>Taking the first reply would be faster and would be wrong in the one case
 * that matters. Two servers in a room is a real configuration during a defence
 * (one presenting, one spare), and first-reply-wins would silently connect to
 * whichever machine happened to answer a millisecond sooner. Collecting the whole
 * window means the client <em>knows</em> there were two and can ask.
 *
 * <p>{@link #DEFAULT_WINDOW} is two seconds. Long enough for a reply to cross a
 * congested classroom Wi-Fi and back, short enough that nobody experiences it as
 * a loading screen, and it is a ceiling rather than a floor: the window is the
 * longest the client waits, not the shortest.
 *
 * <h2>Duplicates and hostile replies</h2>
 *
 * <p>A broadcast goes out on several addresses (see {@link DiscoveryTransport}),
 * so one server commonly answers more than once. Replies are therefore keyed by
 * fingerprint and the first one wins, which also means a machine answering twice
 * with two different ids appears as two servers, which is exactly what it is.
 *
 * <p>Anything that does not decode is dropped without a word above debug. This
 * socket receives whatever the subnet sends it, and a client that logged a
 * warning per stray packet would produce a wall of them on a school network.
 * {@link #DEFAULT_MAX_REPLIES} bounds the collection so that a flood cannot make
 * the client allocate without limit before the window closes.
 *
 * <p>Every failure answers an empty list, never an exception. Discovery is an
 * optimisation over typing an address, and F1.5 is explicit that it must never be
 * able to block connecting: a client on a network that forbids broadcast finds
 * nothing, which is a supported outcome with its own screen.
 */
public final class DiscoveryClient {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryClient.class);

    /** How long the client listens after broadcasting. */
    public static final Duration DEFAULT_WINDOW = Duration.ofSeconds(2);

    /** The most servers one sweep will collect before it stops listening. */
    public static final int DEFAULT_MAX_REPLIES = 32;

    /** How long a single receive blocks before the window is re-checked. */
    static final int RECEIVE_SLICE_MILLIS = 200;

    /** Opens the socket. A supplier, so a failed open is one more empty result. */
    @FunctionalInterface
    public interface TransportFactory {
        DiscoveryTransport open() throws IOException;
    }

    private final TransportFactory transports;
    private final int discoveryPort;
    private final Clock clock;

    /** Production wiring: a real UDP socket on the default discovery port. */
    public DiscoveryClient() {
        this(DiscoveryTransport::udp, DiscoveryProtocol.DEFAULT_DISCOVERY_PORT, Clock.systemUTC());
    }

    /**
     * @param transports    how to open a socket
     * @param discoveryPort the port servers answer on
     * @param clock         the window's time source; a test clock makes the whole
     *                      collect loop deterministic instead of a two-second sleep
     */
    public DiscoveryClient(TransportFactory transports, int discoveryPort, Clock clock) {
        this.transports = Objects.requireNonNull(transports, "transports");
        this.discoveryPort = discoveryPort;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @return the servers that answered within {@link #DEFAULT_WINDOW}. */
    public List<DiscoveredServer> discover() {
        return discover(DEFAULT_WINDOW);
    }

    /**
     * Broadcasts and collects.
     *
     * <p>Blocking, and meant to be: the caller runs it off the FX thread and posts
     * the result back through the documented hop.
     *
     * @param window how long to listen
     * @return the distinct servers that answered, in the order they answered;
     *         empty when none did, when broadcast is not permitted, or when the
     *         socket could not be opened at all
     */
    public List<DiscoveredServer> discover(Duration window) {
        Objects.requireNonNull(window, "window");
        Map<String, DiscoveredServer> byFingerprint = new LinkedHashMap<>();
        try (DiscoveryTransport transport = transports.open()) {
            transport.broadcast(discoveryPort);
            collect(transport, window, byFingerprint);
        } catch (IOException | RuntimeException e) {
            // Never fatal: F1.5 requires that discovery failing cannot block the
            // manual path. Whatever arrived before the failure is still returned.
            log.debug("Discovery sweep ended early: {}", e.toString());
        }
        List<DiscoveredServer> found = List.copyOf(new ArrayList<>(byFingerprint.values()));
        log.info("Discovery found {} server(s) on the local network", found.size());
        return found;
    }

    private void collect(DiscoveryTransport transport, Duration window,
                         Map<String, DiscoveredServer> byFingerprint) throws IOException {
        Instant deadline = clock.instant().plus(window);
        while (clock.instant().isBefore(deadline) && byFingerprint.size() < DEFAULT_MAX_REPLIES) {
            long remaining = Duration.between(clock.instant(), deadline).toMillis();
            DiscoveryTransport.Received reply =
                    transport.receive((int) Math.min(RECEIVE_SLICE_MILLIS, Math.max(1, remaining)));
            if (reply == null) {
                continue;
            }
            accept(reply, byFingerprint);
        }
    }

    /** Decodes one reply and files it, or drops it. Visible for testing. */
    static void accept(DiscoveryTransport.Received reply, Map<String, DiscoveredServer> byFingerprint) {
        Optional<ServerAnnouncement> announcement =
                DiscoveryProtocol.decodeAnnouncement(reply.data(), reply.length());
        if (announcement.isEmpty()) {
            log.debug("Ignoring {} byte(s) from {} that are not a discovery reply",
                    reply.length(), reply.from());
            return;
        }
        try {
            DiscoveredServer server = DiscoveredServer.from(announcement.get());
            // First reply per id wins: one server commonly answers several of our
            // broadcast addresses, and it is still one server.
            byFingerprint.putIfAbsent(server.fingerprint(), server);
        } catch (IllegalArgumentException e) {
            // A well-formed JSON object that still is not a usable endpoint, for
            // example a port of zero. Same treatment as garbage.
            log.debug("Ignoring an unusable discovery reply from {}: {}", reply.from(), e.getMessage());
        }
    }
}
