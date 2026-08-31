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
            // A closed socket counts as no socket: stopListening() closes the one
            // it had, so Start listening after a Stop must bind a fresh one rather
            // than call setSoTimeout on a corpse (B-49).
            if (serverSocket == null || serverSocket.isClosed()) {
                // Reuse-address, because Stop listening leaves the port with the previous
                // clients' connections in TIME_WAIT, and a Start moments later must not be
                // refused "Address already in use" for it (seen on the CI runner).
                ServerSocket fresh = new ServerSocket();
                fresh.setReuseAddress(true);
                fresh.bind(new java.net.InetSocketAddress(port));
                serverSocket = fresh;
            }
            serverSocket.setSoTimeout(timeout);
            // Register the replacement BEFORE clearing the stop flag. A Stop followed at
            // once by a Start races the outgoing thread's finally block: registered first,
            // that block's own guard sees it has been superseded and touches nothing;
            // flag first, the old thread could still pass the guard between the two writes
            // and raise the stop flag under the listener that just started (seen as
            // StopListeningSocketTest.startListeningAfterStopWorks timing out).
            connectionListener = new Thread(this);
            readyToStop = false;
            connectionListener.start();
        }
    }

    /**
     * Stops accepting new connections and closes the listening socket, leaving
     * every already-connected client attached.
     *
     * <p><b>The socket is closed here, and that is the fix ⚑ (B-49).</b> This
     * used to raise the stop flag and nothing else. The accept loop did end, but
     * the {@link ServerSocket} stayed bound, so the operating system went on
     * completing the TCP handshake for new clients into the accept backlog. A
     * client dialling a "stopped" server therefore connected successfully and
     * then waited forever for a server that was never going to read that socket:
     * the console said stopped, the client said {@code Connecting...}, and only
     * closing the console window — which calls {@link #close()} — ever told the
     * client the truth. Closing the socket turns "stopped listening" from a flag
     * the server keeps to itself into a refusal the client sees on its first
     * round trip.
     *
     * <p><b>Client connections are deliberately untouched.</b> That is the whole
     * difference between this method and {@link #close()}, and it is the promise
     * the server console's Stop listening button makes to an operator: new
     * clients are refused, and a student already sitting an exam keeps their
     * socket, their attempt and their deadline.
     */
    public final void stopListening() {
        readyToStop = true;
        ServerSocket listening = serverSocket;
        if (listening != null) {
            try {
                listening.close();
            } catch (IOException e) {
                // Nothing to recover and nobody to tell: the socket is being
                // discarded either way, the accept loop is already told to stop,
                // and listen() rebinds a fresh one because it treats a closed
                // socket as no socket.
            }
        }
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
        // The socket this thread owns ⚑. A listener is bound to one socket for the
        // whole of its life: stopListening() closes it and listen() binds a fresh
        // one, so a thread that finds the field pointing elsewhere has been
        // superseded by a restart and must stop rather than go on accepting
        // clients on its successor's socket.
        final ServerSocket mine = serverSocket;
        serverStarted();
        try {
            while (!readyToStop && mine != null && serverSocket == mine) {
                try {
                    Socket clientSocket = mine.accept();
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
                    // A close during a stop or a restart is how this loop is meant
                    // to end, not a fault to report.
                    if (!readyToStop && serverSocket == mine) {
                        listeningException(e);
                    }
                }
            }
        } finally {
            // Only the thread that is still the registered listener may declare
            // this server stopped ⚑. Stop listening now closes the socket, so a
            // Stop immediately followed by a Start can leave this thread finishing
            // after listen() has already registered a replacement — and raising
            // the stop flag then would kill the listener that just started, while
            // clearing the field would make isListening() deny one that is
            // running. A superseded thread has nothing left to announce.
            if (connectionListener == Thread.currentThread() && serverSocket == mine) {
                readyToStop = true;
                connectionListener = null;
                serverStopped();
            }
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
