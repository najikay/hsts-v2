package client.features.connect;

import client.core.ServerEndpoint;
import client.net.HSTSClient;
import client.net.RequestDispatcher;
import common.protocol.Verb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-49, at the socket: a client must not believe a connect that nobody answered.
 *
 * <h2>What is actually being reproduced here</h2>
 *
 * <p>A {@link ServerSocket} that is bound and never accepted from. That is not a
 * contrivance; it is precisely what the server console's Stop listening used to
 * leave behind, and what a wedged or half-started server leaves behind too. The
 * operating system completes the TCP handshake for such a socket all by itself,
 * into the accept backlog, so every check a client can make about "did the
 * connect work" says yes.
 *
 * <p>Two layers now refuse it, and there is a test for each because they fail
 * different things:
 *
 * <ul>
 *   <li>{@link #boundButNeverAccepted} is the real bug. The stream handshake
 *       inside {@code openConnection()} never completes, because the server never
 *       writes its serialization header, and that call used to block forever with
 *       no timeout on it. It now times out.</li>
 *   <li>{@link #acceptedButNeverAnswered} is the layer above: a server that
 *       accepted the socket and completed the stream handshake but is not routing
 *       anything. The connect legitimately succeeds; {@link ConnectHandshake}
 *       is what refuses it.</li>
 * </ul>
 *
 * <p>Both end at the same place, which is the point: the sentence a user reads is
 * {@link ConnectFlow#UNREACHABLE_TIMEOUT}, and {@link #theSentenceIsProductCopy}
 * holds it to the B-37 rules.
 */
@Timeout(30)
class ConnectHandshakeTest {

    /** Short enough to keep the suite quick, long enough not to be flaky on a loaded box. */
    private static final Duration WINDOW = Duration.ofMillis(600);

    private static final int TIMEOUT_MS = (int) WINDOW.toMillis();

    private ServerSocket socket;
    private HSTSClient client;
    private Thread accepter;

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            try {
                client.disconnect();
            } catch (IOException ignored) {
                // The test's own client; nothing downstream cares.
            }
        }
        if (accepter != null) {
            accepter.interrupt();
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    @Nested
    @DisplayName("a socket that connects and answers nothing")
    class Silent {

        @Test
        @DisplayName("⚑ B-49: a bound-but-unaccepting server fails the connect instead of hanging")
        void boundButNeverAccepted() throws IOException {
            socket = new ServerSocket(0, 50);        // bound, and never accept()ed from
            client = clientFor(socket.getLocalPort());

            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> client.connect())
                    .as("the stream handshake must be bounded, not endless")
                    .isInstanceOf(SocketTimeoutException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                    .as("and bounded by roughly the configured window")
                    .isLessThan(Duration.ofSeconds(10));
        }

        @Test
        @DisplayName("a server that accepts and then goes quiet fails the HELLO proof")
        void acceptedButNeverAnswered() throws Exception {
            socket = new ServerSocket(0, 50);
            acceptAndWriteHeaderOnly();
            client = clientFor(socket.getLocalPort());

            client.connect();
            assertThat(client.isConnectionOpen())
                    .as("the socket really is open: that is exactly why it cannot be trusted")
                    .isTrue();

            RequestDispatcher dispatcher = new RequestDispatcher(client);
            client.setServerMessageHandler(dispatcher::dispatchIncoming);

            assertThatThrownBy(() -> ConnectHandshake.prove(dispatcher, WINDOW))
                    .isInstanceOf(TimeoutException.class);
        }
    }

    @Nested
    @DisplayName("the copy a user reads")
    class Copy {

        @Test
        @DisplayName("either failure produces the product's 'did not answer' sentence")
        void theSentenceIsProductCopy() {
            ServerEndpoint endpoint = new ServerEndpoint("192.168.1.5", 5555);

            for (Throwable failure : new Throwable[]{
                    new SocketTimeoutException("Read timed out"),
                    new client.net.RequestTimeoutException(Verb.HELLO, "r-1", WINDOW),
                    new java.util.concurrent.CompletionException(
                            new client.net.RequestTimeoutException(Verb.HELLO, "r-2", WINDOW))}) {

                assertThat(ConnectFlow.reasonFor(failure))
                        .as("%s", failure.getClass().getSimpleName())
                        .isEqualTo(ConnectFlow.UNREACHABLE_TIMEOUT);

                String message = ConnectFlow.afterFailedConnect(endpoint, failure).message();
                assertThat(message)
                        .contains("192.168.1.5:5555")
                        .contains("did not answer")
                        // B-37: no class name, no brackets, no em dash, ever.
                        .doesNotContain("Exception")
                        .doesNotContain("(")
                        .doesNotContain("[")
                        .doesNotContain("—");
            }
        }
    }

    private static HSTSClient clientFor(int port) {
        HSTSClient fresh = new HSTSClient("127.0.0.1", port);
        fresh.setConnectTimeout(TIMEOUT_MS);
        return fresh;
    }

    /**
     * Accepts one client, completes the serialization handshake, and then reads
     * nothing ever again.
     *
     * <p>Writing the {@link ObjectOutputStream} header is what lets the client's
     * {@code openConnection()} finish, which is the whole difference between this
     * fixture and the bound-only socket above.
     */
    private void acceptAndWriteHeaderOnly() {
        accepter = new Thread(() -> {
            try (Socket accepted = socket.accept()) {
                ObjectOutputStream out = new ObjectOutputStream(accepted.getOutputStream());
                out.flush();
                Thread.sleep(Duration.ofSeconds(20).toMillis());
            } catch (Exception ignored) {
                // Interrupted or closed at teardown; the test has its answer by then.
            }
        }, "silent-server");
        accepter.setDaemon(true);
        accepter.start();
    }
}
