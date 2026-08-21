package client.features.connect;

import common.dto.discovery.DiscoveryProtocol;
import common.dto.discovery.ServerAnnouncement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The client's discovery sweep (E19.10, F13.4).
 *
 * <p>No packet is sent anywhere in this class. The transport is scripted and the
 * collect window runs on a clock a test moves, which is what turns a two-second
 * wait into a loop and lets the "found nothing" and "found four" cases sit beside
 * each other in the same suite.
 */
class DiscoveryClientTest {

    private static final Instant T0 = Instant.parse("2026-08-20T09:00:00Z");

    private MutableClock clock;
    private FakeTransport transport;
    private DiscoveryClient client;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        transport = new FakeTransport(clock);
        client = new DiscoveryClient(() -> transport, 5556, clock);
    }

    private static byte[] announcement(String name, String ip, int port, String fingerprint) {
        return DiscoveryProtocol.encodeAnnouncement(
                new ServerAnnouncement(name, ip, port, fingerprint));
    }

    private static DiscoveryTransport.Received reply(byte[] data, String from) {
        try {
            return new DiscoveryTransport.Received(data, data.length, InetAddress.getByName(from));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("collecting")
    class Collecting {

        @Test
        @DisplayName("one broadcast goes out and the answers come back as picker rows")
        void oneServer() {
            transport.enqueue(reply(announcement("Room 12 server", "192.168.1.42", 5555,
                    "7f3a2b91-1111-2222-3333-444444444444"), "192.168.1.42"));

            List<DiscoveredServer> found = client.discover(Duration.ofSeconds(2));

            assertThat(transport.broadcastPorts).containsExactly(5556);
            assertThat(found).singleElement().satisfies(server -> {
                assertThat(server.name()).isEqualTo("Room 12 server");
                assertThat(server.endpoint().display()).isEqualTo("192.168.1.42:5555");
                assertThat(server.shortFingerprint()).isEqualTo("7F3A-2B91");
                assertThat(server.display())
                        .isEqualTo("Room 12 server · 192.168.1.42:5555 · ID 7F3A-2B91");
            });
        }

        @Test
        @DisplayName("the whole window is collected, so two servers are seen as two")
        void severalServers() {
            transport.enqueue(reply(announcement("A", "10.0.0.1", 5555, "aaaa1111"), "10.0.0.1"));
            transport.enqueue(reply(announcement("B", "10.0.0.2", 5555, "bbbb2222"), "10.0.0.2"));

            List<DiscoveredServer> found = client.discover(Duration.ofSeconds(2));

            assertThat(found)
                    .as("first-reply-wins would silently pick whichever answered a millisecond sooner")
                    .extracting(DiscoveredServer::name).containsExactly("A", "B");
        }

        @Test
        @DisplayName("one server answering several broadcasts is still one server")
        void duplicatesCollapse() {
            byte[] same = announcement("A", "10.0.0.1", 5555, "aaaa1111");
            transport.enqueue(reply(same, "10.0.0.1"));
            transport.enqueue(reply(same, "10.0.0.1"));
            transport.enqueue(reply(same, "10.0.0.1"));

            assertThat(client.discover(Duration.ofSeconds(2))).hasSize(1);
        }

        @Test
        @DisplayName("nothing answering is an empty list, which is a supported outcome")
        void nothingFound() {
            assertThat(client.discover(Duration.ofSeconds(2)))
                    .as("client-isolation networks are normal in schools")
                    .isEmpty();
        }

        @Test
        @DisplayName("the window ends the sweep, so the client never waits longer than promised")
        void windowIsACeiling() {
            transport.enqueue(reply(announcement("A", "10.0.0.1", 5555, "aaaa1111"), "10.0.0.1"));

            client.discover(Duration.ofSeconds(2));

            assertThat(clock.instant())
                    .as("two seconds is the longest, not a fixed cost")
                    .isBeforeOrEqualTo(T0.plusSeconds(2));
        }

        @Test
        @DisplayName("a flood of replies is bounded rather than collected without limit")
        void boundedCollection() {
            for (int i = 0; i < 200; i++) {
                transport.enqueue(reply(
                        announcement("S" + i, "10.0.0." + (i % 250), 5555, "id" + i), "10.0.0.1"));
            }

            assertThat(client.discover(Duration.ofSeconds(2)))
                    .hasSize(DiscoveryClient.DEFAULT_MAX_REPLIES);
        }

        @Test
        @DisplayName("the production client uses the standard window and port")
        void defaults() {
            assertThat(DiscoveryClient.DEFAULT_WINDOW).isEqualTo(Duration.ofSeconds(2));
            assertThat(new DiscoveryClient()).isNotNull();
        }

        @Test
        @DisplayName("the no-argument sweep uses the default window")
        void defaultWindow() {
            transport.enqueue(reply(announcement("A", "10.0.0.1", 5555, "aaaa1111"), "10.0.0.1"));

            assertThat(client.discover()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("failure is never fatal")
    class Failures {

        @Test
        @DisplayName("a socket that cannot be opened yields nothing, not an exception")
        void openFails() {
            DiscoveryClient broken = new DiscoveryClient(() -> {
                throw new IOException("permission denied");
            }, 5556, clock);

            assertThat(broken.discover(Duration.ofSeconds(2)))
                    .as("F1.5: discovery failing must never block the manual path")
                    .isEmpty();
        }

        @Test
        @DisplayName("a broadcast that is refused yields nothing")
        void broadcastFails() {
            transport.failBroadcast = new IOException("Network is unreachable");

            assertThat(client.discover(Duration.ofSeconds(2))).isEmpty();
        }

        @Test
        @DisplayName("a read failure keeps whatever already arrived")
        void receiveFailsPartway() {
            transport.enqueue(reply(announcement("A", "10.0.0.1", 5555, "aaaa1111"), "10.0.0.1"));
            transport.failReceiveAfter = 1;

            assertThat(client.discover(Duration.ofSeconds(2)))
                    .extracting(DiscoveredServer::name).containsExactly("A");
        }

        @Test
        @DisplayName("the socket is closed even when the sweep ends badly")
        void alwaysCloses() {
            transport.failBroadcast = new IOException("nope");

            client.discover(Duration.ofSeconds(2));

            assertThat(transport.closed).isTrue();
        }

        @Test
        @DisplayName("arguments are required")
        void required() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new DiscoveryClient(null, 5556, clock));
            assertThatNullPointerException()
                    .isThrownBy(() -> new DiscoveryClient(() -> transport, 5556, null));
            assertThatNullPointerException().isThrownBy(() -> client.discover(null));
        }
    }

    @Nested
    @DisplayName("hostile replies")
    class Hostile {

        @Test
        @DisplayName("garbage on the wire is dropped and the good reply still lands")
        void garbageIsDropped() {
            Random random = new Random(20260821L);
            for (int i = 0; i < 50; i++) {
                byte[] noise = new byte[random.nextInt(400) + 1];
                random.nextBytes(noise);
                transport.enqueue(reply(noise, "10.0.0.66"));
            }
            transport.enqueue(reply(announcement("A", "10.0.0.1", 5555, "aaaa1111"), "10.0.0.1"));

            assertThat(client.discover(Duration.ofSeconds(2)))
                    .extracting(DiscoveredServer::name).containsExactly("A");
        }

        @Test
        @DisplayName("a well-formed reply with an unusable endpoint is dropped")
        void unusableEndpoint() {
            byte[] zeroPort = "{\"n\":\"S\",\"i\":\"10.0.0.1\",\"p\":0,\"f\":\"abc\"}"
                    .getBytes(StandardCharsets.UTF_8);
            transport.enqueue(reply(zeroPort, "10.0.0.1"));

            assertThat(client.discover(Duration.ofSeconds(2))).isEmpty();
        }

        @Test
        @DisplayName("an unnamed server still shows a usable picker row")
        void unnamedServer() {
            byte[] noName = "{\"i\":\"10.0.0.1\",\"p\":5555,\"f\":\"abcdef01\"}"
                    .getBytes(StandardCharsets.UTF_8);
            transport.enqueue(reply(noName, "10.0.0.1"));

            assertThat(client.discover(Duration.ofSeconds(2))).singleElement()
                    .extracting(DiscoveredServer::name).isEqualTo(DiscoveredServer.UNNAMED);
        }

        @Test
        @DisplayName("accept() drops what it cannot use and keeps the first of each id")
        void acceptDirectly() {
            java.util.Map<String, DiscoveredServer> collected = new java.util.LinkedHashMap<>();

            DiscoveryClient.accept(reply(new byte[] {1, 2, 3}, "10.0.0.1"), collected);
            DiscoveryClient.accept(
                    reply(announcement("first", "10.0.0.1", 5555, "same"), "10.0.0.1"), collected);
            DiscoveryClient.accept(
                    reply(announcement("second", "10.0.0.2", 5555, "same"), "10.0.0.2"), collected);

            assertThat(collected).hasSize(1);
            assertThat(collected.values()).singleElement()
                    .extracting(DiscoveredServer::name).isEqualTo("first");
        }
    }

    @Nested
    @DisplayName("the picker row")
    class Row {

        @Test
        @DisplayName("names, addresses and ids are what a person tells servers apart by")
        void display() {
            DiscoveredServer server = DiscoveredServer.from(
                    new ServerAnnouncement("Lab B", "10.0.0.9", 5555, "abcd1234-0000"));

            assertThat(server.display()).isEqualTo("Lab B · 10.0.0.9:5555 · ID ABCD-1234");
            assertThat(server).hasToString(server.display());
        }

        @Test
        @DisplayName("fingerprint comparison is on the full value, ignoring case")
        void fingerprintComparison() {
            DiscoveredServer server = new DiscoveredServer("S",
                    new client.core.ServerEndpoint("10.0.0.1", 5555), "ABC-def");

            assertThat(server.hasFingerprint(" abc-DEF ")).isTrue();
            assertThat(server.hasFingerprint("abc-deg")).isFalse();
            assertThat(server.hasFingerprint(null)).isFalse();
        }

        @Test
        @DisplayName("required parts are required")
        void required() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new DiscoveredServer("S", null, "f"));
            assertThatNullPointerException().isThrownBy(() ->
                    new DiscoveredServer("S", new client.core.ServerEndpoint("h", 1), null));
            assertThatNullPointerException().isThrownBy(() -> DiscoveredServer.from(null));
        }
    }

    // ===================== Fakes =========================================

    /** A transport that hands back scripted replies and moves the clock as it waits. */
    private static final class FakeTransport implements DiscoveryTransport {

        private final MutableClock clock;
        private final Deque<Received> replies = new ArrayDeque<>();
        final List<Integer> broadcastPorts = new ArrayList<>();
        IOException failBroadcast;
        int failReceiveAfter = -1;
        boolean closed;
        private int received;

        FakeTransport(MutableClock clock) {
            this.clock = clock;
        }

        void enqueue(Received reply) {
            replies.add(reply);
        }

        @Override
        public void broadcast(int port) throws IOException {
            if (failBroadcast != null) {
                throw failBroadcast;
            }
            broadcastPorts.add(port);
        }

        @Override
        public Received receive(int timeoutMillis) throws IOException {
            if (failReceiveAfter >= 0 && received >= failReceiveAfter) {
                throw new IOException("socket closed");
            }
            if (replies.isEmpty()) {
                // Nothing waiting: burn the slice, which is how the real socket's
                // timeout advances the window towards its deadline.
                clock.advance(Duration.ofMillis(timeoutMillis));
                return null;
            }
            received++;
            clock.advance(Duration.ofMillis(1));
            return replies.poll();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A clock a test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
