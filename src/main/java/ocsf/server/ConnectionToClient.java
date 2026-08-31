package ocsf.server;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * Streamlined, self-contained implementation of the OCSF
 * {@code ConnectionToClient}. Represents the server side of a single client
 * connection. Each instance runs on its own thread, reading serialized objects
 * from the socket and handing them to the owning {@link AbstractServer}.
 */
public class ConnectionToClient extends Thread {

    private final AbstractServer server;
    private Socket clientSocket;
    private ObjectInputStream input;
    private ObjectOutputStream output;
    private volatile boolean running = false;

    /** Arbitrary per-connection info (e.g. logged-in user), keyed by name. */
    private final Map<String, Object> savedInfo = new HashMap<>();

    ConnectionToClient(AbstractServer server, Socket clientSocket) throws IOException {
        this.server = server;
        this.clientSocket = clientSocket;
        // The stream handshake is bounded ⚑ (B-49 follow-up). Constructing the
        // ObjectInputStream blocks until the CLIENT's serialization header
        // arrives, and this constructor runs on the accept-loop thread. A socket
        // that connected and never wrote a byte - a port scanner, a health
        // check, a client that died mid-open - used to park the accept loop
        // here forever, which refused every later client silently.
        clientSocket.setSoTimeout(server.handshakeTimeout());
        // Output stream must be created (and flushed) before the input stream
        // to avoid the classic ObjectStream header deadlock.
        this.output = new ObjectOutputStream(clientSocket.getOutputStream());
        this.output.flush();
        this.input = new ObjectInputStream(clientSocket.getInputStream());
        // Handshake done: reads may now block for as long as the session lasts.
        clientSocket.setSoTimeout(0);
        this.running = true;
    }

    /**
     * Sends a message object to this client.
     *
     * <p>{@code synchronized}: a router response goes out on this connection's
     * own read thread while a {@code PushGateway} broadcast for the same user
     * can arrive from any other thread (another client's read thread, the lock
     * sweeper, an exam timer). Interleaved {@code writeObject} calls on one
     * {@link ObjectOutputStream} corrupt the stream for the client, which then
     * reads garbage and drops the connection.
     */
    public synchronized void sendToClient(Object msg) throws IOException {
        if (clientSocket == null || output == null) {
            throw new IOException("socket does not exist");
        }
        output.writeObject(msg);
        output.flush();
        output.reset();
    }

    /** Closes the connection to this client. */
    public void close() throws IOException {
        running = false;
        try {
            if (clientSocket != null) {
                clientSocket.close();
            }
        } finally {
            closeAll();
        }
    }

    @Override
    public void run() {
        try {
            Object msg;
            while (running) {
                try {
                    msg = input.readObject();
                } catch (Exception ex) {
                    if (running) {
                        server.clientException(this, ex);
                    }
                    break;
                }
                if (msg != null) {
                    server.receiveMessageFromClient(msg, this);
                }
            }
        } finally {
            running = false;
            closeAll();
            server.clientConnectionClosed(this);
        }
    }

    private void closeAll() {
        try { if (output != null) output.close(); } catch (IOException ignored) {}
        try { if (input != null) input.close(); } catch (IOException ignored) {}
        try { if (clientSocket != null) clientSocket.close(); } catch (IOException ignored) {}
        output = null;
        input = null;
        clientSocket = null;
    }

    // ===== Per-connection info & accessors =================================

    public void setInfo(String key, Object value) {
        savedInfo.put(key, value);
    }

    public Object getInfo(String key) {
        return savedInfo.get(key);
    }

    public InetAddress getInetAddress() {
        return clientSocket == null ? null : clientSocket.getInetAddress();
    }

    @Override
    public String toString() {
        return clientSocket == null ? "null" : clientSocket.getInetAddress().getHostName()
                + " (" + clientSocket.getInetAddress().getHostAddress() + ")";
    }
}
