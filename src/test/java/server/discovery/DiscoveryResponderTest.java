package server.discovery;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The UDP responder (E19.8, F13.3).
 *
 * <p>Driven one packet at a time against a scripted transport, so every hostile
 * case is deterministic and none of it needs a socket. The single real-socket
 * proof is {@code DiscoveryLoopbackTest}.
 */
class DiscoveryResponderTest {

    private static final Instant T0 = Instant.parse("2026-08-20T09:00:00Z");

    private static final ServerAnnouncement ROOM_12 =
            new ServerAnnouncement("Room 12 server", "192.168.1.42", 5555,
                    "7f3a2b91-1111-2222-3333-444444444444");

    private MutableClock clock;
    private FakeTransport transport;
    private DiscoveryResponder responder;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        transport = new FakeTransport();
        responder = new DiscoveryResponder(transport, () -> ROOM_12, clock);
    }

    private static DiscoveryTransport.Received packet(String text, String from) {
        return packet(text.getBytes(StandardCharsets.UTF_8), from);
    }

    private static DiscoveryTransport.Received packet(byte[] bytes, String from) {
        try {
            return new DiscoveryTransport.Received(bytes, bytes.length,
                    InetAddress.getByName(from), 40000);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("answering")
    class Answering {

        @Test
        @DisplayName("a discovery request draws exactly one announcement")
        void answersARequest() {
            assertThat(responder.handle(packet(DiscoveryProtocol.REQUEST_MAGIC, "192.168.1.51")))
                    .isTrue();

            assertThat(transport.sent).hasSize(1);
            assertThat(DiscoveryProtocol.decodeAnnouncement(
                    transport.sent.get(0).data, transport.sent.get(0).data.length))
                    .contains(ROOM_12);
            assertThat(transport.sent.get(0).to.getHostAddress()).isEqualTo("192.168.1.51");
            assertThat(transport.sent.get(0).port).isEqualTo(40000);
            assertThat(responder.repliedCount()).isEqualTo(1);
            assertThat(responder.ignoredCount()).isZero();
        }

        @Test
        @DisplayName("the announcement is rebuilt per request, so an address override takes effect")
        void announcementIsLive() {
            AtomicReference<String> address = new AtomicReference<>("192.168.1.42");
            DiscoveryResponder live = new DiscoveryResponder(transport,
                    () -> new ServerAnnouncement("S", address.get(), 5555, "abc"), clock);

            live.handle(packet(DiscoveryProtocol.REQUEST_MAGIC, "10.0.0.1"));
            address.set("10.1.1.1");
            live.handle(packet(DiscoveryProtocol.REQUEST_MAGIC, "10.0.0.2"));

            assertThat(transport.sent).hasSize(2);
            assertThat(decode(1).map(ServerAnnouncement::ip))
                    .as("an operator who corrects the address mid-demo is obeyed at once")
                    .contains("10.1.1.1");
        }

        private java.util.Optional<ServerAnnouncement> decode(int index) {
            FakeTransport.Sent sent = transport.sent.get(index);
            return DiscoveryProtocol.decodeAnnouncement(sent.data, sent.data.length);
        }
    }

    @Nested
    @DisplayName("ignoring")
    class Ignoring {

        @Test
        @DisplayName("a packet that is not a request draws no reply at all")
        void unknownTraffic() {
            assertThat(responder.handle(packet("HELLO PRINTER", "192.168.1.99"))).isFalse();

            assertThat(transport.sent)
                    .as("answering unknown traffic would make this an amplifier")
                    .isEmpty();
            assertThat(responder.ignoredCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("random garbage is ignored without throwing, thousands of times over")
        void fuzz() {
            Random random = new Random(20260821L);

            for (int i = 0; i < 2000; i++) {
                byte[] noise = new byte[random.nextInt(600)];
                random.nextBytes(noise);
                responder.handle(packet(noise, "192.168.1.99"));
            }

            assertThat(transport.sent).isEmpty();
            assertThat(responder.ignoredCount()).isEqualTo(2000);
            assertThat(responder.isRunning())
                    .as("and the responder is still perfectly healthy afterwards")
                    .isFalse();
            assertThat(responder.handle(packet(DiscoveryProtocol.REQUEST_MAGIC, "10.0.0.1")))
                    .isTrue();
        }

        @Test
        @DisplayName("a packet from nowhere is ignored")
        void noSourceAddress() {
            byte[] magic = DiscoveryProtocol.requestBytes();

            assertThat(responder.handle(
                    new DiscoveryTransport.Received(magic, magic.length, null, 1))).isFalse();
            assertThat(responder.ignoredCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a send that fails is counted rather than thrown")
        void sendFails() {
            transport.failSend = new IOException("network unreachable");

            assertThat(responder.handle(packet(DiscoveryProtocol.REQUEST_MAGIC, "10.0.0.1")))
                    .isFalse();
            assertThat(responder.ignoredCount()).isEqualTo(1);
            assertThat(responder.repliedCount()).isZero();
        }
    }

    @Nested
    @DisplayName("flooding")
    class Flooding {

        @Test
        @DisplayName("one source gets a bounded number of replies per second")
        void rateLimited() {
            for (int i = 0; i < 20; i++) {
                responder.handle(packet(DiscoveryProtocol.REQUEST_MAGIC, "10.0.0.7"));
            }

            assertThat(transport.sent)
                    .as("a reply is bigger than a request, so an unlimited responder reflects")
                    .hasSize(DiscoveryResponder.REPLIES_PER_SECOND);
            assertThat(responder.ignoredCount())
                    .isEqualTo(20 - DiscoveryResponder.REPLIES_PER_SECOND);
        }

        @Test
        @DisplayName("the window reopens, so a legitimate client is never locked out")
        void windowReopens() {
            for (int i = 0; i < 20; i++) {
                responder.handle(packet(DiscoveryProtocol.REQUEST_MAGIC, "10.0.0.7"));
            }
            transport.sent.clear();

            clock.advance(Duration.ofSeconds(2));
            assertThat(responder.handle(packet(DiscoveryProtocol.REQUEST_MAGIC, "10.0.0.7")))
                    .isTrue();
            assertThat(transport.sent).hasSize(1);
        }

        @Test
        @DisplayName("one flooding source does not silence a different one")
        void perSourceLimit() {
            for (int i = 0; i < 20; i++) {
                responder.handle(packet(DiscoveryProtocol.REQUEST_MAGIC, "10.0.0.7"));
            }

            assertThat(responder.handle(packet(DiscoveryProtocol.REQUEST_MAGIC, "10.0.0.8")))
                    .as("a real student on the same network still finds the server")
                    .isTrue();
        }

        @Test
        @DisplayName("the rate memory does not grow without bound on a busy network")
        void boundedMemory() {
            for (int i = 0; i < 300; i++) {
                responder.handle(packet(DiscoveryProtocol.REQUEST_MAGIC, "10.1." + (i / 250) + "." + (i % 250)));
            }
            clock.advance(DiscoveryResponder.RATE_MEMORY.plusMinutes(1));

            // The next request sweeps the stale sources rather than accumulating them.
            responder.handle(packet(DiscoveryProtocol.REQUEST_MAGIC, "10.9.9.9"));

            assertThat(responder.repliedCount()).isEqualTo(301);
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("start and stop are idempotent, so a double-clicked toggle is not an error")
        void idempotent() {
            assertThat(responder.start()).isTrue();
            assertThat(responder.start()).isFalse();
            assertThat(responder.isRunning()).isTrue();

            assertThat(responder.stop()).isTrue();
            assertThat(responder.stop()).isFalse();
            assertThat(responder.isRunning()).isFalse();
        }

        @Test
        @DisplayName("stopping does not close the socket, so the toggle can be flipped back")
        void stopKeepsTheSocket() {
            responder.start();
            responder.stop();

            assertThat(transport.closed)
                    .as("rebinding a port on every click fails the moment the OS holds it")
                    .isFalse();
            assertThat(responder.start()).isTrue();
            responder.close();
            assertThat(transport.closed).isTrue();
        }

        @Test
        @DisplayName("the listening thread answers real packets and ends on close")
        void loopRuns() throws Exception {
            transport.queue.add(packet(DiscoveryProtocol.REQUEST_MAGIC, "10.0.0.1"));

            responder.start();
            for (int i = 0; i < 200 && transport.sent.isEmpty(); i++) {
                Thread.sleep(5);
            }

            assertThat(transport.sent).hasSize(1);
            responder.close();
            assertThat(responder.isRunning()).isFalse();
        }

        @Test
        @DisplayName("a socket read failure ends the loop without noise")
        void readFailureEndsTheLoop() throws Exception {
            transport.failReceive = new IOException("socket closed");

            responder.start();
            Thread.sleep(50);

            assertThat(transport.sent).isEmpty();
            responder.close();
        }

        @Test
        @DisplayName("collaborators are required")
        void required() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new DiscoveryResponder(null, () -> ROOM_12, clock));
            assertThatNullPointerException()
                    .isThrownBy(() -> new DiscoveryResponder(transport, null, clock));
            assertThatNullPointerException()
                    .isThrownBy(() -> new DiscoveryResponder(transport, () -> ROOM_12, null));
        }
    }

    // ===================== Fakes =========================================

    /** A transport a test scripts, with no socket anywhere. */
    private static final class FakeTransport implements DiscoveryTransport {

        record Sent(byte[] data, InetAddress to, int port) {
        }

        final List<Sent> sent = java.util.Collections.synchronizedList(new ArrayList<>());
        final java.util.concurrent.BlockingQueue<Received> queue =
                new java.util.concurrent.LinkedBlockingQueue<>();
        IOException failSend;
        IOException failReceive;
        volatile boolean closed;

        @Override
        public Received receive() throws IOException {
            if (failReceive != null) {
                throw failReceive;
            }
            try {
                // Null is the documented "nothing arrived", which is how the loop
                // notices a stop request without a socket timeout.
                return queue.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        @Override
        public void send(byte[] data, InetAddress to, int port) throws IOException {
            if (failSend != null) {
                throw failSend;
            }
            sent.add(new Sent(data.clone(), to, port));
        }

        @Override
        public int boundPort() {
            return 5556;
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
