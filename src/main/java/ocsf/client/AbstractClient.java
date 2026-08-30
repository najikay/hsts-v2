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
     * How long {@link #openConnection()} may spend dialling and shaking hands, in
     * milliseconds. {@code 0} waits forever, which is what this class used to do
     * unconditionally.
     */
    private volatile int connectTimeout = DEFAULT_CONNECT_TIMEOUT_MS;

    /** The default bound on a connect: long enough for a loaded server, short enough to answer a user. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;

    public AbstractClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    // ===== Public API =====================================================

    /**
     * Sets the bound on the next {@link #openConnection()}.
     *
     * @param millis milliseconds; {@code 0} means wait forever, negatives are
     *               treated as {@code 0}
     */
    public final void setConnectTimeout(int millis) {
        this.connectTimeout = Math.max(0, millis);
    }

    /** @return the current connect and handshake bound in milliseconds. */
    public final int getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Opens the connection to the server and starts the read thread.
     *
     * <h2>Both halves of the open are bounded, and that is the fix ⚑ (B-49)</h2>
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
     * <p>The socket now carries {@link #setConnectTimeout(int)} across both steps.
     * The dial gets it through {@link Socket#connect(java.net.SocketAddress, int)},
     * and the header read gets it through {@code SO_TIMEOUT}, which is cleared
     * again the moment the handshake is done so the read loop below goes on
     * blocking indefinitely as it always has. A server that never answers now
     * costs a {@link java.net.SocketTimeoutException} rather than a thread.
     *
     * @throws java.net.SocketTimeoutException when the dial or the stream
     *                                         handshake outlasts the timeout
     */
    public final void openConnection() throws IOException {
        if (isConnected()) {
            return;
        }
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeout);
        // Bounds the header read inside the ObjectInputStream constructor below.
        socket.setSoTimeout(connectTimeout);
        clientSocket = socket;
        try {
            output = new ObjectOutputStream(socket.getOutputStream());
            output.flush();
            input = new ObjectInputStream(socket.getInputStream());
        } catch (IOException e) {
            // A half-open connection is worse than none: the caller is about to be
            // told this failed, and must not be left holding a live socket.
            try {
                closeAll();
            } catch (IOException closing) {
                e.addSuppressed(closing);
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

    /** Sends a message object to the server. */
    public final void sendToServer(Object msg) throws IOException {
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
