package client.net;

import common.protocol.Message;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Adapter contract for client-side networking (Presentation tier).
 *
 * <p>The UI talks to the server ONLY through this interface — it never touches
 * OCSF directly. Today the sole implementation is {@link HSTSClient} (an OCSF
 * adapter); a future protocol migration (REST, gRPC, WebSocket) would supply a
 * different implementation without changing any UI code. This is the documented
 * Adapter Pattern boundary.
 */
public interface IClientConnection {

    /** Opens the connection to the server. */
    void connect() throws IOException;

    /** Sends a protocol {@link Message} to the server. */
    void send(Message msg) throws IOException;

    /** Closes the connection to the server. */
    void disconnect() throws IOException;

    /** @return true if the connection is currently open. */
    boolean isConnectionOpen();

    /** @return configured server hostname or IP. */
    String getHost();

    /** @return configured server port. */
    int getPort();

    /**
     * Registers the handler that receives server responses. Implementations
     * guarantee the handler is invoked on the JavaFX Application Thread.
     */
    void setServerMessageHandler(Consumer<Message> handler);
}
