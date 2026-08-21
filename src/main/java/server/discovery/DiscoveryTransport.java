package server.discovery;

import common.dto.discovery.DiscoveryProtocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

/**
 * The socket the discovery responder listens on, as an interface (Logic tier,
 * E19.8).
 *
 * <p>The seam exists so the responder's actual behaviour, which is entirely about
 * deciding what to answer and what to ignore, is tested without a socket. Every
 * fuzz case, every rate-limit case and every malformed-packet case runs against a
 * scripted transport in microseconds; a single integration test uses the real one
 * on loopback (E19.11) and can be skipped on a machine that forbids sockets.
 */
public interface DiscoveryTransport extends AutoCloseable {

    /** One received datagram. */
    record Received(byte[] data, int length, InetAddress from, int port) {
    }

    /**
     * Waits for the next datagram.
     *
     * @return the packet, or {@code null} when the wait timed out (which is
     *         normal and is how the responder notices it has been asked to stop)
     * @throws IOException when the socket failed or was closed
     */
    Received receive() throws IOException;

    /**
     * Sends a reply.
     *
     * @param data the bytes to send
     * @param to   the requester's address
     * @param port the requester's source port
     */
    void send(byte[] data, InetAddress to, int port) throws IOException;

    /** @return the port actually bound, which matters when the caller asked for 0 */
    int boundPort();

    @Override
    void close();

    /**
     * The real thing: a UDP socket bound to {@code port} on every interface.
     *
     * <p>Bound to the wildcard address on purpose. A discovery responder that
     * listened only on the address the console shows would be deaf on the very
     * interface a student's broadcast arrives on when the operator has overridden
     * the address by hand.
     *
     * @param port          the discovery port; 0 binds an ephemeral one, which is
     *                      what the integration test uses
     * @param timeoutMillis how long a {@link #receive()} waits before answering
     *                      {@code null}; this is the granularity at which a stop
     *                      request is noticed
     * @return an open transport
     * @throws IOException when the port is already in use, which on a demo machine
     *                     usually means a second server is already running
     */
    static DiscoveryTransport udp(int port, int timeoutMillis) throws IOException {
        DatagramSocket socket = new DatagramSocket(port);
        socket.setSoTimeout(timeoutMillis);
        socket.setBroadcast(true);
        return new DiscoveryTransport() {

            @Override
            public Received receive() throws IOException {
                byte[] buffer = new byte[DiscoveryProtocol.MAX_PACKET_BYTES];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException timeout) {
                    return null;
                }
                return new Received(packet.getData(), packet.getLength(),
                        packet.getAddress(), packet.getPort());
            }

            @Override
            public void send(byte[] data, InetAddress to, int replyPort) throws IOException {
                socket.send(new DatagramPacket(data, data.length, to, replyPort));
            }

            @Override
            public int boundPort() {
                return socket.getLocalPort();
            }

            @Override
            public void close() {
                socket.close();
            }
        };
    }
}
