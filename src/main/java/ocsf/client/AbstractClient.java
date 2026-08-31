package ocsf.client;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Streamlined, self-contained implementation of the OCSF {@code AbstractClient}.
 *
 * <p>Maintains the standard OCSF contract used by the HSTS prototype: open a
 * connection to a server, send objects with {@link #sendToServer(Object)}, and
 * receive objects on a background thread that are dispatched to the subclass via
 * {@link #handleMessageFromServer(Object)}.
 *
 * <p>Subclasses must implement {@link #handleMessageFromServer(Object)} and may
 * override the protected connection hooks.
 */
public abstract class AbstractClient implements Runnable {

    private Socket clientSocket;
    private ObjectOutputStream output;
    private ObjectInputStream input;
    private Thread clientReader;

    private String host;
    private int port;
    private volatile boolean readyToStop = false;

    /**
     * How long the TCP dial may take, in milliseconds. {@code 0} waits forever,
     * which is what this class used to do unconditionally.
     */
    private volatile int dialTimeout = DEFAULT_DIAL_TIMEOUT_MS;

    /**
     * How long the serialization stream handshake may take once the dial
     * succeeded, in milliseconds. {@code 0} waits forever.
     */
    private volatile int handshakeTimeout = DEFAULT_HANDSHAKE_TIMEOUT_MS;

    /**
     * The default bound on the TCP dial: 15 seconds.
     *
     * <h2>Why it is three times the handshake bound ⚑ (B-49 regression, 2026-08-31)</h2>
     *
     * <p>B-49 first bounded the whole open at 5 seconds, and that broke the
     * two-machine classroom connect that had always worked. A first connect
     * across a real LAN is routinely slow for reasons that end well: Windows
     * Firewall showing its allow prompt while SYNs go unanswered, Defender
     * scanning a first-launched jar, ARP resolution on sleepy Wi-Fi. TCP
     * retransmits the SYN at roughly 1, 3 and 7 seconds, so a connect that
     * succeeds on the third SYN lands at about seven seconds - dead under a
     * 5 second dial bound, fine under the old infinite wait, and fine again
     * under this one. A truly dead address now costs 15 seconds instead of a
     * spinner that never ends, and the connect screen says it is still trying.
     */
    public static final int DEFAULT_DIAL_TIMEOUT_MS = 15_000;

    /**
     * The default bound on the stream handshake: 5 seconds, deliberately tight.
     *
     * <p>This is the half that detects a stopped server (B-49): the kernel has
     * already completed the TCP handshake into the backlog, so the dial cost
     * nothing, and a live server writes its serialization header within
     * milliseconds of accepting. Only a server that accepted and then went
     * silent takes longer, and 5 seconds is generous for "the process is there
     * but busy" without gluing a student to a dead spinner.
     */
    public static final int DEFAULT_HANDSHAKE_TIMEOUT_MS = 5_000;

    public AbstractClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    // ===== Public API =====================================================

    /**
     * Sets both bounds on the next {@link #openConnection()} at once - the
     * single-knob form the tests use, where dial and handshake are equally
     * instant on loopback.
     *
     * @param millis milliseconds; {@code 0} means wait forever, negatives are
     *               treated as {@code 0}
     */
    public final void setConnectTimeout(int millis) {
        int bound = Math.max(0, millis);
        this.dialTimeout = bound;
        this.handshakeTimeout = bound;
    }

    /** @return the current TCP dial bound in milliseconds. */
    public final int getConnectTimeout() {
        return dialTimeout;
    }

    /**
     * Sets the TCP dial bound alone, leaving the handshake bound tight.
     *
     * @param millis milliseconds; {@code 0} means wait forever, negatives are
     *               treated as {@code 0}
     */
    public final void setDialTimeout(int millis) {
        this.dialTimeout = Math.max(0, millis);
    }

    /** @return the current TCP dial bound in milliseconds. */
    public final int getDialTimeout() {
        return dialTimeout;
    }

    /**
     * Sets the stream handshake bound alone.
     *
     * @param millis milliseconds; {@code 0} means wait forever, negatives are
     *               treated as {@code 0}
     */
    public final void setHandshakeTimeout(int millis) {
        this.handshakeTimeout = Math.max(0, millis);
    }

    /** @return the current stream handshake bound in milliseconds. */
    public final int getHandshakeTimeout() {
        return handshakeTimeout;
    }

    /**
     * Opens the connection to the server and starts the read thread.
     *
     * <h2>Both halves of the open are bounded, and bounded separately ⚑ (B-49)</h2>
     *
     * <p>This method used to be {@code new Socket(host, port)} followed by
     * {@code new ObjectInputStream(...)}, and the <b>second</b> of those is where
     * a client talking to a stopped server hung. Constructing an
     * {@link ObjectInputStream} reads the peer's serialization stream header
     * before it returns, and the server writes that header only once it has
     * accepted the socket and built its own {@link ObjectOutputStream}. A server
     * that is bound but not accepting never gets that far, while the operating
     * system cheerfully completes the TCP handshake into the accept backlog on its
     * behalf. So the socket connected, the header never came, and
     * {@code openConnection} blocked with no timeout on it at all: the connect
     * screen said {@code Connecting...} until somebody closed the server console.
     *
     * <p>The dial is bounded by {@link #setDialTimeout(int)} through
     * {@link Socket#connect(java.net.SocketAddress, int)}, and the header read by
     * {@link #setHandshakeTimeout(int)} through {@code SO_TIMEOUT}, which is
     * cleared again the moment the handshake is done so the read loop below goes
     * on blocking indefinitely as it always has. The two bounds are different
     * numbers on purpose: a slow <em>dial</em> is usually a first connect fighting
     * a firewall prompt or a retransmitted SYN and deserves patience (the
     * 2026-08-31 two-machine regression), while a silent <em>handshake</em> means
     * a server that accepted and will never answer, which deserves none. A server
     * that never answers costs a {@link java.net.SocketTimeoutException} rather
     * than a thread, and a dial that fails leaves no socket behind.
     *
     * @throws java.net.SocketTimeoutException when the dial or the stream
     *                                         handshake outlasts its bound
     */
    public final void openConnection() throws IOException {
        if (isConnected()) {
            return;
        }
        try {
            openOnce();
        } catch (java.net.SocketTimeoutException headerLate) {
            if (!isHandshakeTimeout(headerLate)) {
                throw headerLate;
            }
            // One silent retry, for the handshake only ⚑ (B-49 regression). A
            // header that never came usually means a server that accepted late:
            // the process was busy (a first launch being scanned, a stalled
            // accept loop) while the kernel completed TCP into the backlog on
            // its behalf. Dialling again gives that server one more handshake
            // window, which is what lets a slow-but-healthy server that
            // recovers at seven seconds connect instead of failing at five. A
            // server that is genuinely wedged costs two windows and then the
            // same SocketTimeoutException and the same product sentence; a
            // STOPPED server is not this case at all, because Stop listening
            // closes the socket and the dial is refused in milliseconds. The
            // dial itself is never retried: it already carries the patient
            // bound, and doubling it would double the wait on a dead address.
            openOnce();
        }
    }

    /** The two-phase open: bounded dial, bounded stream handshake, reader start. */
    private void openOnce() throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), dialTimeout);
            // Bounds the header read inside the ObjectInputStream constructor below.
            socket.setSoTimeout(handshakeTimeout);
        } catch (IOException dialFailed) {
            // The half-dialled socket owns a file descriptor; a failed dial must
            // not leak one per Connect press.
            try {
                socket.close();
            } catch (IOException closing) {
                dialFailed.addSuppressed(closing);
            }
            throw dialFailed;
        }
        clientSocket = socket;
        boolean handshaking = true;
        try {
            output = new ObjectOutputStream(socket.getOutputStream());
            output.flush();
            input = new ObjectInputStream(socket.getInputStream());
            handshaking = false;
        } catch (IOException e) {
            // A half-open connection is worse than none: the caller is about to be
            // told this failed, and must not be left holding a live socket.
            try {
                closeAll();
            } catch (IOException closing) {
                e.addSuppressed(closing);
            }
            if (e instanceof java.net.SocketTimeoutException) {
                throw markHandshakeTimeout((java.net.SocketTimeoutException) e);
            }
            throw e;
        }
        // The handshake is over; the read loop waits as long as the session lasts.
        socket.setSoTimeout(0);
        readyToStop = false;
        clientReader = new Thread(this);
        clientReader.start();
        connectionEstablished();
    }

    /** Marker distinguishing a handshake timeout from a dial timeout for the retry above. */
    private static final String HANDSHAKE_TIMEOUT_NOTE = "stream handshake timed out";

    private static java.net.SocketTimeoutException markHandshakeTimeout(
            java.net.SocketTimeoutException original) {
        java.net.SocketTimeoutException marked =
                new java.net.SocketTimeoutException(HANDSHAKE_TIMEOUT_NOTE);
        marked.initCause(original);
        return marked;
    }

    private static boolean isHandshakeTimeout(java.net.SocketTimeoutException e) {
        return HANDSHAKE_TIMEOUT_NOTE.equals(e.getMessage());
    }

    /**
     * Sends a message object to the server.
     *
     * <p>{@code synchronized}: callers on different threads (a screen on the FX
     * thread, the reconnect handshake on its worker, the liveness probe on the
     * timeout pool) share this one {@link ObjectOutputStream}, and interleaved
     * {@code writeObject} calls corrupt the stream for both.
     */
    public final synchronized void sendToServer(Object msg) throws IOException {
        if (clientSocket == null || output == null) {
            throw new IOException("socket does not exist");
        }
        output.writeObject(msg);
        output.flush();
        output.reset();
    }

    /** Closes the connection to the server. */
    public final void closeConnection() throws IOException {
        readyToStop = true;
        try {
            closeAll();
        } finally {
            connectionClosed();
        }
    }

    public final boolean isConnected() {
        return clientSocket != null && clientSocket.isConnected() && !clientSocket.isClosed();
    }

    public final String getHost() {
        return host;
    }

    public final void setHost(String host) {
        this.host = host;
    }

    public final int getPort() {
        return port;
    }

    public final void setPort(int port) {
        this.port = port;
    }

    // ===== Read loop ======================================================

    /** Runs the server-message read loop. Not called directly by user code. */
    @Override
    public final void run() {
        try {
            Object msg;
            while (!readyToStop) {
                try {
                    msg = input.readObject();
                } catch (Exception ex) {
                    if (!readyToStop) {
                        connectionException(ex);
                    }
                    break;
                }
                if (msg != null) {
                    handleMessageFromServer(msg);
                }
            }
        } finally {
            readyToStop = true;
        }
    }

    private void closeAll() throws IOException {
        try { if (output != null) output.close(); } catch (IOException ignored) {}
        try { if (input != null) input.close(); } catch (IOException ignored) {}
        try { if (clientSocket != null) clientSocket.close(); }
        finally {
            output = null;
            input = null;
            clientSocket = null;
        }
    }

    // ===== Abstract / hook methods ========================================

    /** Handle a message received from the server. Must be implemented. */
    protected abstract void handleMessageFromServer(Object msg);

    /** Called after a connection to the server is established. */
    protected void connectionEstablished() {}

    /** Called after the connection to the server is closed. */
    protected void connectionClosed() {}

    /** Called when an exception occurs in the read thread. */
    protected void connectionException(Exception exception) {}
}
