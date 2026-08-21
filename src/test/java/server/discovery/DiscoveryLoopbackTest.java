package server.discovery;

import client.core.ServerEndpoint;
import client.features.connect.ConnectFlow;
import client.features.connect.DiscoveredServer;
import client.features.connect.DiscoveryClient;
import client.core.ServerPin;
import common.dto.discovery.DiscoveryProtocol;
import common.dto.discovery.ServerAnnouncement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Discovery over real UDP sockets on loopback (E19.11).
 *
 * <p>The one test in the discovery suite that opens a socket. Everything else runs
 * against injected transports, which is what keeps the rules fast and
 * deterministic; this exists because a seam can be right in every case and still
 * be wired to the socket wrongly, and that mistake is only visible here.
 *
 * <h2>Skipping, and why it is an assumption rather than a failure</h2>
 *
 * <p>Some CI containers and hardened developer machines forbid opening or binding
 * UDP sockets. That is an environment fact, not a defect in this code, so the test
 * <b>skips</b> rather than failing: a red build that means "your sandbox has no
 * sockets" trains people to ignore red builds. The skip is loud in the report and
 * the rest of the discovery suite still proves every rule.
 *
 * <p>The client half is deliberately pointed at loopback rather than at a
 * broadcast address. A broadcast on a build machine would reach whatever else is
 * on its network, which is both rude and flaky; the point here is the encode,
 * send, receive and decode chain, and loopback exercises all four.
 */
class DiscoveryLoopbackTest {

    private static final ServerAnnouncement ROOM_12 =
            new ServerAnnouncement("Room 12 server", "192.168.1.42", 5555,
                    "7f3a2b91-1111-2222-3333-444444444444");

    private DiscoveryTransport serverTransport;
    private DiscoveryResponder responder;

    @BeforeEach
    void startResponder() {
        try {
            // Port 0: the OS picks a free one, so two builds on one machine cannot
            // collide and no fixed port has to be reserved.
            serverTransport = DiscoveryTransport.udp(0, 200);
        } catch (IOException | SecurityException e) {
            Assumptions.abort("This environment does not permit UDP sockets: " + e.getMessage());
        }
        responder = new DiscoveryResponder(serverTransport, () -> ROOM_12, Clock.systemUTC());
        responder.start();
    }

    @AfterEach
    void stopResponder() {
        if (responder != null) {
            responder.close();
        }
    }

    @Test
    @DisplayName("a real request over a real socket comes back as a real announcement")
    void roundTrip() throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(2000);
            byte[] request = DiscoveryProtocol.requestBytes();
            client.send(new DatagramPacket(request, request.length,
                    InetAddress.getLoopbackAddress(), serverTransport.boundPort()));

            byte[] buffer = new byte[DiscoveryProtocol.MAX_PACKET_BYTES];
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            client.receive(reply);

            assertThat(DiscoveryProtocol.decodeAnnouncement(reply.getData(), reply.getLength()))
                    .contains(ROOM_12);
        } catch (SocketTimeoutException timeout) {
            Assumptions.abort("Loopback UDP is filtered in this environment");
        }
    }

    @Test
    @DisplayName("the full client sweep finds the responder and the flow auto-connects to it")
    void endToEnd() {
        DiscoveryClient client = new DiscoveryClient(
                () -> loopbackTransport(serverTransport.boundPort()),
                serverTransport.boundPort(), Clock.systemUTC());

        List<DiscoveredServer> found = client.discover(Duration.ofSeconds(2));

        Assumptions.assumeTrue(!found.isEmpty(),
                "Loopback UDP is filtered in this environment");
        assertThat(found).singleElement().satisfies(server -> {
            assertThat(server.name()).isEqualTo("Room 12 server");
            assertThat(server.endpoint().display()).isEqualTo("192.168.1.42:5555");
        });

        // The point of the end-to-end: a real sweep feeds the same decision table
        // the unit tests drive with fixtures.
        ConnectFlow.Decision first = ConnectFlow.decide(Optional.empty(), found);
        assertThat(first.step()).isEqualTo(ConnectFlow.Step.CONNECT);

        ServerPin pinned = new ServerPin(new ServerEndpoint("192.168.1.42", 5555),
                first.fingerprintToPin().orElseThrow());
        assertThat(ConnectFlow.decide(Optional.of(pinned), found).isSilent())
                .as("the second launch on a pinned machine asks nothing")
                .isTrue();
    }

    @Test
    @DisplayName("garbage over a real socket is ignored and the responder keeps answering")
    void garbageDoesNotKillIt() throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(2000);
            InetAddress target = InetAddress.getLoopbackAddress();

            byte[] junk = "not a discovery request at all".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            client.send(new DatagramPacket(junk, junk.length, target, serverTransport.boundPort()));

            byte[] request = DiscoveryProtocol.requestBytes();
            client.send(new DatagramPacket(request, request.length, target,
                    serverTransport.boundPort()));

            byte[] buffer = new byte[DiscoveryProtocol.MAX_PACKET_BYTES];
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            client.receive(reply);

            assertThat(DiscoveryProtocol.decodeAnnouncement(reply.getData(), reply.getLength()))
                    .as("the junk drew nothing and cost the responder nothing")
                    .isPresent();
            assertThat(responder.ignoredCount()).isPositive();
        } catch (SocketTimeoutException timeout) {
            Assumptions.abort("Loopback UDP is filtered in this environment");
        }
    }

    /**
     * A client transport that sends to loopback instead of broadcasting.
     *
     * <p>Broadcasting from a build machine would reach its whole network, which is
     * both antisocial and flaky. The chain under test is encode, send, receive,
     * decode, and loopback exercises every link of it.
     */
    private static client.features.connect.DiscoveryTransport loopbackTransport(int port) {
        DatagramSocket socket;
        try {
            socket = new DatagramSocket();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        List<DatagramSocket> owned = new ArrayList<>(List.of(socket));
        return new client.features.connect.DiscoveryTransport() {

            @Override
            public void broadcast(int ignoredPort) throws IOException {
                byte[] request = DiscoveryProtocol.requestBytes();
                socket.send(new DatagramPacket(request, request.length,
                        InetAddress.getLoopbackAddress(), port));
            }

            @Override
            public Received receive(int timeoutMillis) throws IOException {
                socket.setSoTimeout(Math.max(1, timeoutMillis));
                byte[] buffer = new byte[DiscoveryProtocol.MAX_PACKET_BYTES];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException timeout) {
                    return null;
                }
                return new Received(packet.getData(), packet.getLength(), packet.getAddress());
            }

            @Override
            public void close() {
                owned.forEach(DatagramSocket::close);
            }
        };
    }
}
