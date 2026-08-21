package client.features.connect;

import common.dto.discovery.DiscoveryProtocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The client's side of the discovery socket (Presentation tier, E19.10).
 *
 * <p>An interface for the same reason the server has one: every rule in
 * {@link DiscoveryClient} is about which replies to keep and when to stop
 * waiting, and none of it should need a network to be tested. The scripted
 * implementation lives in the tests; the one below is the real socket, exercised
 * by a single loopback integration test that skips cleanly where sockets are
 * forbidden.
 */
public interface DiscoveryTransport extends AutoCloseable {

    /** One reply. */
    record Received(byte[] data, int length, InetAddress from) {
    }

    /**
     * Sends the discovery request to every broadcast address available.
     *
     * @param port the server's discovery port
     */
    void broadcast(int port) throws IOException;

    /**
     * Waits for the next reply.
     *
     * @param timeoutMillis how long to wait; must be positive
     * @return the reply, or {@code null} when the wait elapsed
     */
    Received receive(int timeoutMillis) throws IOException;

    @Override
    void close();

    /**
     * A real UDP socket, bound to an ephemeral port.
     *
     * <p>Broadcasts go to every interface's own broadcast address as well as to
     * {@code 255.255.255.255}, and that redundancy is deliberate: the global
     * broadcast is dropped outright by several common Windows and Linux
     * configurations, while a per-interface directed broadcast usually survives.
     * A discovery that works on the developer's laptop and not in the exam room is
     * the failure this list of addresses exists to avoid, and sending three tiny
     * datagrams instead of one costs nothing.
     *
     * @return an open transport
     * @throws IOException when no socket could be opened at all, which the caller
     *                     turns into the "nothing found" path rather than an error
     */
    static DiscoveryTransport udp() throws IOException {
        DatagramSocket socket = new DatagramSocket();
        socket.setBroadcast(true);
        return new DiscoveryTransport() {

            @Override
            public void broadcast(int port) throws IOException {
                byte[] request = DiscoveryProtocol.requestBytes();
                IOException lastFailure = null;
                boolean sentAny = false;
                for (InetAddress target : broadcastTargets()) {
                    try {
                        socket.send(new DatagramPacket(request, request.length, target, port));
                        sentAny = true;
                    } catch (IOException e) {
                        // One unreachable interface must not stop the others; a
                        // machine with a down VPN adapter is completely ordinary.
                        lastFailure = e;
                    }
                }
                if (!sentAny && lastFailure != null) {
                    throw lastFailure;
                }
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
                socket.close();
            }
        };
    }

    /**
     * @return {@code 255.255.255.255} plus each interface's own broadcast address
     */
    static List<InetAddress> broadcastTargets() {
        List<InetAddress> targets = new ArrayList<>();
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
        } catch (IOException ignored) {
            // Cannot happen for a literal, and if it somehow does, the per-interface
            // addresses below are the ones that actually work anyway.
        }
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    if (address.getBroadcast() != null) {
                        targets.add(address.getBroadcast());
                    }
                }
            }
        } catch (SocketException | RuntimeException e) {
            // A machine whose interfaces cannot be listed still gets the global
            // broadcast, and still has manual entry.
        }
        return List.copyOf(targets);
    }
}
