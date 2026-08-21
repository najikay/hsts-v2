package server.discovery;

import common.dto.discovery.DiscoveryProtocol;
import common.dto.discovery.ServerAnnouncement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Answers client discovery broadcasts (Logic tier, E19.8, F13.3).
 *
 * <p>The whole protocol: listen on a UDP port, and when a datagram containing
 * {@link DiscoveryProtocol#REQUEST_MAGIC} arrives, send one JSON announcement
 * straight back to whoever sent it. No multicast group to join, no membership to
 * maintain, and no state between packets.
 *
 * <h2>Everything that is not a request is ignored</h2>
 *
 * <p>This socket is writable by every machine on the subnet, so hostile and
 * merely wrong input is the normal case rather than the exceptional one. Three
 * things are therefore true of the loop below, and each of them is a test:
 *
 * <ul>
 *   <li><b>A packet that is not the magic string draws no reply at all.</b> Not an
 *       error packet, not a log line at warn, nothing. Answering unknown traffic
 *       would turn this into an amplifier and would fill the console's log pane
 *       with whatever else is broadcasting on the network.</li>
 *   <li><b>A flood is rate limited per source.</b> One address gets at most
 *       {@link #REPLIES_PER_SECOND} replies a second; the rest are counted and
 *       dropped. A discovery reply is bigger than a discovery request, so an
 *       unlimited responder is a reflector that multiplies an attacker's traffic
 *       towards a spoofed victim. The limit is what makes that not worth doing,
 *       and it costs a legitimate client nothing: it sends one packet.</li>
 *   <li><b>Nothing thrown by one packet ends the loop.</b> A responder that died
 *       on a malformed datagram would be a denial of service anyone could perform
 *       by accident.</li>
 * </ul>
 *
 * <p>All of it is logged at debug. An operator watching the console's log pane
 * during a demo must not see a wall of warnings because a printer is broadcasting
 * on the same port, and a genuine flood is visible in {@link #ignoredCount()} on
 * the console rather than by scrolling.
 *
 * <h2>The announcement is built per request</h2>
 *
 * <p>Through a {@link Supplier}, not a stored value, because the address the
 * console shows is editable while the server runs (E19.5). A responder holding a
 * snapshot taken at boot would keep telling clients to connect to the address the
 * operator had just corrected.
 */
public final class DiscoveryResponder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryResponder.class);

    /** How many replies one source address may draw per second. */
    public static final int REPLIES_PER_SECOND = 5;

    /** The window {@link #REPLIES_PER_SECOND} is measured over. */
    static final Duration RATE_WINDOW = Duration.ofSeconds(1);

    /**
     * How long a source address is remembered for rate-limiting purposes before
     * its entry is dropped. Long enough to bound a flood, short enough that the
     * map cannot grow without limit on a busy network.
     */
    static final Duration RATE_MEMORY = Duration.ofMinutes(5);

    /** One source's recent reply count. */
    private record Bucket(Instant windowStart, int replies) {
    }

    private final DiscoveryTransport transport;
    private final Supplier<ServerAnnouncement> announcement;
    private final Clock clock;

    private final Map<InetAddress, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong replied = new AtomicLong();
    private final AtomicLong ignored = new AtomicLong();

    private volatile Thread worker;

    /**
     * @param transport    the socket to listen on
     * @param announcement what to answer, evaluated per request
     * @param clock        the rate limiter's time source; a test clock in tests, so
     *                     a flood is a loop rather than a wait
     */
    public DiscoveryResponder(DiscoveryTransport transport,
                              Supplier<ServerAnnouncement> announcement,
                              Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.announcement = Objects.requireNonNull(announcement, "announcement");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Starts the listening thread.
     *
     * <p>A daemon thread: a responder must never be the reason a shut-down JVM
     * stays alive, and discovery has nothing to flush on the way out.
     *
     * @return {@code true} when this call started it, {@code false} when it was
     *         already running (a double-clicked toggle is not an error)
     */
    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        Thread thread = new Thread(this::loop, "hsts-discovery-responder");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        log.info("Discovery responder listening on UDP port {}", transport.boundPort());
        return true;
    }

    /**
     * Stops answering.
     *
     * <p>Does not close the transport: the console's toggle stops and starts the
     * same responder repeatedly, and a socket rebound on every toggle would fail
     * the moment the operating system held the old port open a moment longer than
     * the click.
     *
     * @return {@code true} when this call stopped it
     */
    public boolean stop() {
        if (!running.compareAndSet(true, false)) {
            return false;
        }
        log.info("Discovery responder stopped. Clients now need the address typed in by hand.");
        return true;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** @return how many announcements have been sent since the process started. */
    public long repliedCount() {
        return replied.get();
    }

    /**
     * @return how many datagrams were dropped: malformed, unrecognised, or over
     *         the rate limit. Shown on the console beside the discovery toggle,
     *         where a number climbing fast is the visible form of a flood
     */
    public long ignoredCount() {
        return ignored.get();
    }

    @Override
    public void close() {
        stop();
        Thread thread = worker;
        transport.close();
        if (thread != null) {
            thread.interrupt();
        }
    }

    // ===================== The loop ======================================

    private void loop() {
        while (running.get()) {
            try {
                DiscoveryTransport.Received packet = transport.receive();
                if (packet != null) {
                    handle(packet);
                }
            } catch (IOException e) {
                if (running.get()) {
                    log.debug("Discovery socket read failed: {}", e.toString());
                }
                // A closed socket is how close() ends this thread; anything else is
                // transient. Either way the loop condition decides, not the throw.
                return;
            } catch (RuntimeException e) {
                // One bad packet must never end discovery for the whole demo.
                ignored.incrementAndGet();
                log.debug("Ignoring a discovery packet that could not be handled: {}", e.toString());
            }
        }
    }

    /**
     * Handles exactly one datagram. Public to the package so every fuzz case is a
     * direct call rather than a race against a thread.
     *
     * @return {@code true} when a reply was sent
     */
    boolean handle(DiscoveryTransport.Received packet) {
        if (!DiscoveryProtocol.isRequest(packet.data(), packet.length())) {
            ignored.incrementAndGet();
            log.debug("Ignoring {} byte(s) from {} that are not a discovery request",
                    packet.length(), packet.from());
            return false;
        }
        if (!allow(packet.from())) {
            ignored.incrementAndGet();
            log.debug("Rate limiting discovery requests from {}", packet.from());
            return false;
        }
        try {
            transport.send(DiscoveryProtocol.encodeAnnouncement(announcement.get()),
                    packet.from(), packet.port());
            replied.incrementAndGet();
            log.debug("Answered a discovery request from {}", packet.from());
            return true;
        } catch (IOException | RuntimeException e) {
            ignored.incrementAndGet();
            log.debug("Could not answer {}: {}", packet.from(), e.toString());
            return false;
        }
    }

    /**
     * The per-source rate limit.
     *
     * @return {@code true} when this source may have a reply now
     */
    private boolean allow(InetAddress source) {
        if (source == null) {
            return false;
        }
        Instant now = clock.instant();
        sweepRateMemory(now);
        Bucket updated = buckets.compute(source, (address, current) -> {
            if (current == null || Duration.between(current.windowStart(), now).compareTo(RATE_WINDOW) >= 0) {
                return new Bucket(now, 1);
            }
            return new Bucket(current.windowStart(), current.replies() + 1);
        });
        return updated.replies() <= REPLIES_PER_SECOND;
    }

    /** Drops sources nobody has heard from in a while, so the map stays bounded. */
    private void sweepRateMemory(Instant now) {
        if (buckets.size() < 256) {
            return;
        }
        buckets.entrySet().removeIf(entry ->
                Duration.between(entry.getValue().windowStart(), now).compareTo(RATE_MEMORY) > 0);
    }
}
