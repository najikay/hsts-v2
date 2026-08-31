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

    @Nested
    @DisplayName("the dial bound and the handshake bound are separate (the two-machine regression)")
    class TwoBounds {

        @Test
        @DisplayName("\u2691 a server whose header arrives late still connects: the dial bound does not cut the handshake short")
        void slowHeaderStillConnects() throws Exception {
            socket = new ServerSocket(0, 50);
            acceptAndWriteHeaderAfter(Duration.ofMillis(900));

            client = new HSTSClient("127.0.0.1", socket.getLocalPort());
            // The regression in miniature: one knob used to bound BOTH halves, so
            // a dial bound tight enough for loopback strangled a header that
            // needed a moment longer. Scaled down from the field numbers (5 s
            // bound, ~7 s first LAN connect through a firewall prompt).
            client.setDialTimeout(300);
            client.setHandshakeTimeout(2_000);

            client.connect();   // times out on the old single-bound behaviour

            assertThat(client.isConnectionOpen()).isTrue();
        }

        @Test
        @DisplayName("\u2691 a server that recovers after one handshake window connects on the silent retry")
        void aBusyServerConnectsOnTheRetry() throws Exception {
            socket = new ServerSocket(0, 50);
            serveEveryoneAfter(Duration.ofMillis(900));

            client = new HSTSClient("127.0.0.1", socket.getLocalPort());
            // Scaled down from the field case: bounds of 600 ms standing in for
            // 5 s, a server busy for 900 ms standing in for one busy for 7 s.
            // Attempt one times out at 600 ms; the silent retry lands in the
            // backlog and the recovered server serves it inside the second
            // window. The old behaviour failed the whole connect at the first
            // timeout.
            client.setConnectTimeout(600);

            client.connect();

            assertThat(client.isConnectionOpen()).isTrue();
        }

        @Test
        @DisplayName("the defaults: a patient dial, a tight handshake")
        void theDefaultsAreSplit() {
            // 12-20 s dial: outlasts a firewall prompt and TCP's 1/3/7 s SYN
            // retransmission ladder. 5 s handshake: a live server writes its
            // header in milliseconds, so only a wedged one takes longer.
            assertThat(ocsf.client.AbstractClient.DEFAULT_DIAL_TIMEOUT_MS)
                    .isBetween(12_000, 20_000);
            assertThat(ocsf.client.AbstractClient.DEFAULT_HANDSHAKE_TIMEOUT_MS)
                    .isEqualTo(5_000);

            HSTSClient fresh = new HSTSClient("127.0.0.1", 5555);
            assertThat(fresh.getDialTimeout())
                    .isEqualTo(ocsf.client.AbstractClient.DEFAULT_DIAL_TIMEOUT_MS);
            assertThat(fresh.getHandshakeTimeout())
                    .isEqualTo(ocsf.client.AbstractClient.DEFAULT_HANDSHAKE_TIMEOUT_MS);
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

    /**
     * Accepts one client at once (the kernel already completed the handshake),
     * writes the serialization header only after {@code delay}, and then answers
     * every request with a correlated OK: a slow but healthy server, which is
     * what a first LAN connect through a firewall prompt looks like.
     */
    private void acceptAndWriteHeaderAfter(Duration delay) {
        accepter = new Thread(() -> {
            try (Socket accepted = socket.accept()) {
                Thread.sleep(delay.toMillis());
                ObjectOutputStream out = new ObjectOutputStream(accepted.getOutputStream());
                out.flush();
                java.io.ObjectInputStream in =
                        new java.io.ObjectInputStream(accepted.getInputStream());
                while (true) {
                    Object msg = in.readObject();
                    if (msg instanceof common.protocol.Message request) {
                        out.writeObject(common.protocol.Message.ok(request, null));
                        out.flush();
                        out.reset();
                    }
                }
            } catch (Exception ignored) {
                // Interrupted or closed at teardown; the test has its answer by then.
            }
        }, "slow-header-server");
        accepter.setDaemon(true);
        accepter.start();
    }

    /**
     * A server that is busy for {@code delay} and then serves every queued
     * connection properly: the kernel completed their handshakes into the
     * backlog all along, so recovery means accepting and answering them all.
     */
    private void serveEveryoneAfter(Duration delay) {
        accepter = new Thread(() -> {
            try {
                Thread.sleep(delay.toMillis());
                while (true) {
                    Socket accepted = socket.accept();
                    Thread serving = new Thread(() -> {
                        try {
                            ObjectOutputStream out = new ObjectOutputStream(accepted.getOutputStream());
                            out.flush();
                            java.io.ObjectInputStream in =
                                    new java.io.ObjectInputStream(accepted.getInputStream());
                            while (true) {
                                Object msg = in.readObject();
                                if (msg instanceof common.protocol.Message request) {
                                    out.writeObject(common.protocol.Message.ok(request, null));
                                    out.flush();
                                    out.reset();
                                }
                            }
                        } catch (Exception ignored) {
                            // The client under test hangs up; that ends the service.
                        }
                    }, "recovered-server-worker");
                    serving.setDaemon(true);
                    serving.start();
                }
            } catch (Exception ignored) {
                // Interrupted or closed at teardown; the test has its answer by then.
            }
        }, "busy-then-recovered-server");
        accepter.setDaemon(true);
        accepter.start();
    }
}
