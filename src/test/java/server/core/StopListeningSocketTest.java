package server.core;

import client.features.connect.ConnectHandshake;
import client.net.HSTSClient;
import client.net.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the server console's Stop listening does to the port, over a real socket
 * (B-49).
 *
 * <h2>The claim under test</h2>
 *
 * <p>The console's button promises two things at once, and the whole bug was that
 * OCSF only delivered the second. New clients must be <b>refused</b>, promptly and
 * visibly; clients already connected must be <b>kept</b>, because one of them is a
 * student mid-exam and a button that discards attempts is a button somebody will
 * press by accident.
 *
 * <p>Before the fix, {@code stopListening()} raised a flag and left the
 * {@link ServerSocket} bound, so the operating system went on completing
 * handshakes into the backlog. A connecting client got a socket nobody would ever
 * read and hung. {@link #stoppedListeningRefusesANewConnect} is that case, and it
 * fails against the old implementation by connecting successfully.
 *
 * <p>No database: {@link HSTSServer#HSTSServer(int, SessionManager, MessageRouter)}
 * is the transport-only constructor, and transport is all this file is about.
 */
@Timeout(60)
class StopListeningSocketTest {

    /** Long enough for loopback, short enough that a hang is a failure not a wait. */
    private static final Duration WINDOW = Duration.ofSeconds(2);

    private HSTSServer server;
    private HSTSClient client;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        port = freePort();
        MessageRouter router = HelloResponder.registerOn(new MessageRouter(new SessionManager()));
        server = new HSTSServer(port, new SessionManager(), router);
        server.listen();
    }

    @AfterEach
    void stopServer() throws IOException {
        if (client != null) {
            try {
                client.disconnect();
            } catch (IOException ignored) {
                // Already gone in the tests that close it themselves.
            }
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("a listening server proves itself: connect, then HELLO comes back")
    void listeningServerAnswersTheHandshake() throws Exception {
        client = connectedClient();

        RequestDispatcher dispatcher = new RequestDispatcher(client);
        client.setServerMessageHandler(dispatcher::dispatchIncoming);

        ConnectHandshake.prove(dispatcher, WINDOW);   // returns, or the test fails

        assertThat(client.isConnectionOpen()).isTrue();
    }

    @Test
    @DisplayName("⚑ B-49: after Stop listening a new connect is refused, not swallowed")
    void stoppedListeningRefusesANewConnect() {
        server.stopListening();

        // A raw socket, deliberately: this asserts what the operating system does
        // with the port, with no OCSF or client code in the way to be blamed.
        assertThatThrownBy(() -> {
            try (Socket probe = new Socket()) {
                // Bound to its own fresh port first. Unbound, the kernel picks the source
                // port at random from the same ephemeral range freePort() draws from, and
                // when it lands ON the closed server port the connect "succeeds" by TCP
                // simultaneous open - a socket connected to itself. Seen once on CI; binding
                // makes it unrepresentable.
                probe.bind(new InetSocketAddress("127.0.0.1", freePort()));
                probe.connect(new InetSocketAddress("127.0.0.1", port), (int) WINDOW.toMillis());
            }
        })
                .as("the listening socket must be closed, so the kernel answers RST")
                .isInstanceOf(ConnectException.class);

        assertThat(server.isListening()).isFalse();
    }

    @Test
    @DisplayName("Stop listening keeps the clients that are already connected")
    void stoppedListeningKeepsExistingClients() throws Exception {
        client = connectedClient();
        RequestDispatcher dispatcher = new RequestDispatcher(client);
        client.setServerMessageHandler(dispatcher::dispatchIncoming);
        ConnectHandshake.prove(dispatcher, WINDOW);

        server.stopListening();

        assertThat(server.getNumberOfClients())
                .as("the exam in progress is on this socket")
                .isEqualTo(1);
        assertThat(client.isConnectionOpen()).isTrue();
        // Still attached is not enough; it has to still work.
        ConnectHandshake.prove(dispatcher, WINDOW);
    }

    @Test
    @DisplayName("Start listening after a Stop binds again and takes clients")
    void startListeningAfterStopWorks() throws Exception {
        server.stopListening();

        server.listen();

        client = connectedClient();
        RequestDispatcher dispatcher = new RequestDispatcher(client);
        client.setServerMessageHandler(dispatcher::dispatchIncoming);
        ConnectHandshake.prove(dispatcher, WINDOW);

        assertThat(server.isListening()).isTrue();
    }

    private HSTSClient connectedClient() throws IOException {
        HSTSClient fresh = new HSTSClient("127.0.0.1", port);
        fresh.setConnectTimeout((int) WINDOW.toMillis());
        fresh.connect();
        return fresh;
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
