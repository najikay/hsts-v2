package ocsf.client;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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

    public AbstractClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    // ===== Public API =====================================================

    /** Opens the connection to the server and starts the read thread. */
    public final void openConnection() throws IOException {
        if (isConnected()) {
            return;
        }
        clientSocket = new Socket(host, port);
        output = new ObjectOutputStream(clientSocket.getOutputStream());
        output.flush();
        input = new ObjectInputStream(clientSocket.getInputStream());
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
