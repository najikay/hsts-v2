package client.net;

import common.protocol.Message;
import javafx.application.Platform;
import ocsf.client.AbstractClient;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * OCSF-backed implementation of {@link IClientConnection} (Adapter Pattern).
 *
 * <p>Wraps the native {@link AbstractClient} so the rest of the client never
 * sees OCSF. Inbound server messages arrive on OCSF's background read thread;
 * this adapter marshals them onto the JavaFX Application Thread via
 * {@link Platform#runLater} before handing them to the registered UI handler —
 * so the UI controller can safely mutate the scene graph.
 */
public class HSTSClient extends AbstractClient implements IClientConnection {

    /** The registered UI controller callback (e.g. QuestionsView::onServerMessage). */
    private Consumer<Message> serverMessageHandler;

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

    // ===== OCSF callbacks (background thread) =============================

    @Override
    protected void handleMessageFromServer(Object msg) {
        System.out.println("[HSTSClient] Received from server: " + msg);
        if (!(msg instanceof Message)) {
            System.err.println("[HSTSClient] Ignoring non-Message object: " + msg);
            return;
        }
        final Message response = (Message) msg;

        // CRITICAL: route onto the JavaFX Application Thread before any UI work.
        Platform.runLater(() -> {
            if (serverMessageHandler != null) {
                serverMessageHandler.accept(response);
            }
        });
    }

    @Override
    protected void connectionEstablished() {
        System.out.println("[HSTSClient] Connection established to "
                + getHost() + ":" + getPort());
    }

    @Override
    protected void connectionClosed() {
        System.out.println("[HSTSClient] Connection closed.");
    }

    @Override
    protected void connectionException(Exception exception) {
        System.err.println("[HSTSClient] Connection exception: " + exception.getMessage());
    }
}
