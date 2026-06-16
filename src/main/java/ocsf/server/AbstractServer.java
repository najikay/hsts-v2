package ocsf.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Streamlined, self-contained implementation of the OCSF {@code AbstractServer}.
 *
 * <p>Maintains the standard OCSF contract used by the HSTS prototype: a server
 * that listens on a port, accepts {@link ConnectionToClient} connections each on
 * their own thread, dispatches inbound objects to {@link #handleMessageFromClient}
 * and exposes the standard lifecycle hooks and broadcast helpers.
 *
 * <p>Subclasses must implement {@link #handleMessageFromClient(Object, ConnectionToClient)}
 * and may override the protected hook methods.
 */
public abstract class AbstractServer implements Runnable {

    /** The server socket clients connect to. */
    private ServerSocket serverSocket = null;

    /** The thread that accepts incoming connections. */
    private Thread connectionListener;

    /** The port number to listen on. */
    private int port;

    /** Read timeout (ms) for the accept socket so the loop can be interrupted. 0 = none. */
    private int timeout = 500;

    /** Live client connections. */
    private final List<ConnectionToClient> clientConnections = new ArrayList<>();

    /** True while the connection-accept loop should keep running. */
    private volatile boolean readyToStop = false;

    public AbstractServer(int port) {
        this.port = port;
    }

    // ===== Public lifecycle API ===========================================

    /**
     * Begins listening for client connections. Spawns the accept loop on its
     * own thread and returns immediately.
     */
    public final void listen() throws IOException {
        if (!isListening()) {
            if (serverSocket == null) {
                serverSocket = new ServerSocket(port);
            }
            serverSocket.setSoTimeout(timeout);
            readyToStop = false;
            connectionListener = new Thread(this);
            connectionListener.start();
        }
    }

    /** Stops accepting new connections (existing clients stay connected). */
    public final void stopListening() {
        readyToStop = true;
    }

    /**
     * Stops listening and closes the server socket and all client connections.
     */
    public final synchronized void close() throws IOException {
        stopListening();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } finally {
            serverSocket = null;
            synchronized (clientConnections) {
                for (ConnectionToClient c : new ArrayList<>(clientConnections)) {
                    try {
                        c.close();
                    } catch (Exception ignored) {
                    }
                }
                clientConnections.clear();
            }
            serverClosed();
        }
    }

    /** Sends a message object to every connected client. */
    public void sendToAllClients(Object msg) {
        synchronized (clientConnections) {
            for (ConnectionToClient c : new ArrayList<>(clientConnections)) {
                try {
                    c.sendToClient(msg);
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ===== Accessors ======================================================

    public final boolean isListening() {
        return connectionListener != null && connectionListener.isAlive() && !readyToStop;
    }

    public final int getPort() {
        return port;
    }

    public final void setPort(int port) {
        this.port = port;
    }

    public final void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    /** Snapshot array of the currently connected clients. */
    public final Thread[] getClientConnections() {
        synchronized (clientConnections) {
            return clientConnections.toArray(new ConnectionToClient[0]);
        }
    }

    public final int getNumberOfClients() {
        synchronized (clientConnections) {
            return clientConnections.size();
        }
    }

    // ===== Accept loop ====================================================

    /** Runs the connection-accept loop. Not called directly by user code. */
    @Override
    public final void run() {
        serverStarted();
        try {
            while (!readyToStop) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    synchronized (clientConnections) {
                        ConnectionToClient client =
                                new ConnectionToClient(this, clientSocket);
                        clientConnections.add(client);
                        clientConnected(client);
                        client.start();
                    }
                } catch (java.net.SocketTimeoutException ste) {
                    // expected — lets the loop re-check readyToStop
                } catch (IOException e) {
                    if (!readyToStop) {
                        listeningException(e);
                    }
                }
            }
        } finally {
            readyToStop = true;
            connectionListener = null;
            serverStopped();
        }
    }

    // ===== Internal callbacks from ConnectionToClient =====================

    /** Routes an inbound object from a client to the subclass handler. */
    final void receiveMessageFromClient(Object msg, ConnectionToClient client) {
        handleMessageFromClient(msg, client);
    }

    /** Invoked by a ConnectionToClient when it closes; removes it from the pool. */
    final void clientConnectionClosed(ConnectionToClient client) {
        synchronized (clientConnections) {
            clientConnections.remove(client);
        }
        clientDisconnected(client);
    }

    // ===== Abstract / hook methods ========================================

    /** Handle a message received from a client. Must be implemented. */
    protected abstract void handleMessageFromClient(Object msg, ConnectionToClient client);

    /** Called when the server starts listening. */
    protected void serverStarted() {}

    /** Called when the server stops listening. */
    protected void serverStopped() {}

    /** Called after the server socket is closed. */
    protected void serverClosed() {}

    /** Called when a new client connects. */
    protected void clientConnected(ConnectionToClient client) {}

    /** Called when a client disconnects. */
    protected synchronized void clientDisconnected(ConnectionToClient client) {}

    /** Called when an exception occurs in a client's read thread. */
    protected synchronized void clientException(ConnectionToClient client, Throwable exception) {}

    /** Called when an exception occurs while accepting connections. */
    protected void listeningException(Throwable exception) {}
}
