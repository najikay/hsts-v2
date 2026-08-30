package client.features.connect;

import client.net.RequestDispatcher;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Proves a freshly opened socket is talking to a live server (Presentation tier,
 * B-49).
 *
 * <h2>Why a connect that succeeded is not enough ⚑</h2>
 *
 * <p>{@code openConnection()} returning without throwing means the TCP handshake
 * completed. It does <b>not</b> mean a server read anything: the operating system
 * completes handshakes into a listening socket's accept backlog all by itself, so
 * a server that is bound but not accepting hands the client a perfectly healthy
 * socket that nobody will ever read. That is exactly what the server console's
 * Stop listening used to produce, and the client duly believed it: the button
 * said {@code Connecting...} forever, and the only thing that ever freed it was
 * an operator closing the console window, which closed the socket properly and
 * finally gave the client an error to report.
 *
 * <p>So the client stops trusting the connect and asks a question instead. One
 * {@link Verb#HELLO}, one bounded wait. Something on the far end has to read a
 * request and write a response, which no kernel will do on a server's behalf.
 *
 * <h2>Any answer counts, including a refusal ⚑</h2>
 *
 * <p>{@link #prove} does not look at the response. An {@code OK} proves a server
 * is alive; so does {@code BAD_REQUEST: unsupported verb}, which is what a server
 * built before this verb existed will send back, and so would an
 * {@code UNAUTHORIZED}. The client is not asking the server anything. It is
 * asking <em>whether there is a server</em>, and every one of those answers says
 * yes. Only silence is a failure. Treating an error reply as a failed connect
 * would make this class a compatibility break with every older server jar,
 * bought for no safety at all.
 *
 * <p>The failure is a {@link TimeoutException}, which
 * {@link ConnectFlow#reasonFor} maps to {@link ConnectFlow#UNREACHABLE_TIMEOUT}.
 * No class name reaches the screen (B-37).
 */
public final class ConnectHandshake {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectHandshake.class);

    /**
     * How long a live server has to answer {@code HELLO}.
     *
     * <p>Five seconds. Long enough for a loaded server on classroom Wi-Fi, short
     * enough that a student watching a spinner gets an answer and an address
     * field back rather than an evening.
     */
    public static final Duration TIMEOUT = Duration.ofSeconds(5);

    /**
     * Extra time allowed for the dispatcher's own timer before this class stops
     * waiting on its own account.
     *
     * <p>{@link RequestDispatcher} already fails the future at {@link #TIMEOUT}
     * and that is the timer under test. This second bound exists because a
     * connect screen that hangs is the bug being fixed here, and a fix whose
     * whole value depends on one scheduler firing deserves a backstop.
     */
    private static final Duration GRACE = Duration.ofSeconds(2);

    private ConnectHandshake() {
    }

    /** Asks {@code HELLO} with the standard {@link #TIMEOUT}. */
    public static void prove(RequestDispatcher dispatcher) throws IOException, TimeoutException {
        prove(dispatcher, TIMEOUT);
    }

    /**
     * Asks {@code HELLO} and waits for any answer at all.
     *
     * <p><b>Blocks, and must not be called on the FX thread.</b> The two callers
     * are {@code ConnectView}'s {@code hsts-connect} worker and
     * {@code Reconnector}'s {@code hsts-reconnect} worker, both of which already
     * hop back through an {@code FxThreadPoster} to report the outcome
     * (ARCHITECTURE §6).
     *
     * @param dispatcher the dispatcher bound to the socket that just opened
     * @param window     how long the server has to answer; the shorter window a
     *                   test needs is the only reason this is a parameter
     * @throws TimeoutException when nothing came back inside {@code window}. The
     *                          socket is open and useless: the caller must fail
     *                          the connect and close it
     * @throws IOException      when the request could not be sent, or the socket
     *                          died while the answer was outstanding
     */
    public static void prove(RequestDispatcher dispatcher, Duration window)
            throws IOException, TimeoutException {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(window, "window");

        try {
            Message answer = dispatcher.send(Verb.HELLO, null, window)
                    .orTimeout(window.plus(GRACE).toMillis(), TimeUnit.MILLISECONDS)
                    .get();
            LOG.debug("Connection proved by a {} answer to HELLO",
                    answer == null ? "missing" : String.valueOf(answer.getStatus()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("The connection check was interrupted.", e);
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    /**
     * Turns the future's wrapper into the exception the connect screen should see.
     *
     * <p>A timeout and an {@code IOException} both already mean something
     * {@link ConnectFlow#reasonFor} has a sentence for, so they travel unchanged.
     * Anything else becomes an {@code IOException} carrying the original as its
     * cause, so the cause chain {@code reasonFor} walks stays intact and the log
     * keeps the whole thing.
     */
    private static RuntimeException unwrap(ExecutionException wrapper)
            throws IOException, TimeoutException {
        Throwable cause = wrapper.getCause() == null ? wrapper : wrapper.getCause();
        if (cause instanceof TimeoutException timedOut) {
            throw timedOut;
        }
        if (cause instanceof IOException failed) {
            throw failed;
        }
        throw new IOException("The server did not answer the connection check.", cause);
    }
}
