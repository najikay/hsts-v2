package client.net;

import common.protocol.Message;
import ocsf.client.AbstractClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * OCSF-backed implementation of {@link IClientConnection} (Adapter Pattern).
 *
 * <p>Wraps the native {@link AbstractClient} so the rest of the client never
 * sees OCSF. Inbound messages arrive on OCSF's background read thread and are
 * handed straight to the registered handler — in the running app that is
 * {@link RequestDispatcher#dispatchIncoming(Message)}, which completes futures
 * and forwards pushes. The hop onto the JavaFX Application Thread happens once,
 * later, in {@code client.events.FxThreadPoster} (ARCHITECTURE §6) rather than
 * here, so the dispatcher's correlation logic stays toolkit-free and testable.
 */
public class HSTSClient extends AbstractClient implements IClientConnection {

    private static final Logger log = LoggerFactory.getLogger(HSTSClient.class);

    /** Where inbound messages go (the dispatcher, in the running app). */
    private Consumer<Message> serverMessageHandler;

    /** Notified when the socket dies, so in-flight requests can be failed fast. */
    private Consumer<Throwable> connectionLostHandler;

    public HSTSClient(String host, int port) {
        super(host, port);
    }

    @Override
    public void connect() throws IOException {
        openConnection();
    }

    @Override
    public void send(Message msg) throws IOException {
        sendToServer(msg);
    }

    @Override
    public void disconnect() throws IOException {
        closeConnection();
    }

    @Override
    public boolean isConnectionOpen() {
        return isConnected();
    }

    @Override
    public void setServerMessageHandler(Consumer<Message> handler) {
        this.serverMessageHandler = handler;
    }

    /** Registers the "socket died" callback (E4.6 reconnect banner will use it too). */
    public void setConnectionLostHandler(Consumer<Throwable> handler) {
        this.connectionLostHandler = handler;
    }

    // ===== OCSF callbacks (background read thread) ========================

    @Override
    protected void handleMessageFromServer(Object msg) {
        if (!(msg instanceof Message message)) {
            log.warn("Ignoring non-Message object from server: {}",
                    msg == null ? "null" : msg.getClass().getName());
            return;
        }
        Consumer<Message> handler = this.serverMessageHandler;
        if (handler == null) {
            log.warn("Dropping {} - no message handler registered yet", message.getVerb());
            return;
        }
        handler.accept(message);
    }

    @Override
    protected void connectionEstablished() {
        log.info("Connection established to {}:{}", getHost(), getPort());
    }

    @Override
    protected void connectionClosed() {
        log.info("Connection closed.");
        notifyConnectionLost(new IOException("Connection to the server was closed."));
    }

    @Override
    protected void connectionException(Exception exception) {
        log.warn("Connection exception: {}", exception.toString());
        notifyConnectionLost(exception);
    }

    private void notifyConnectionLost(Throwable cause) {
        Consumer<Throwable> handler = this.connectionLostHandler;
        if (handler != null) {
            handler.accept(cause);
        }
    }
}
