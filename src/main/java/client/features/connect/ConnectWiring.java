package client.features.connect;

import client.core.ScreenManager;
import client.core.ServerEndpoint;
import client.events.PushEventBridge;
import client.net.HSTSClient;
import client.net.IClientConnection;
import client.net.RequestDispatcher;

/**
 * Builds the network stack for a chosen endpoint (Presentation tier, E4.5).
 *
 * <p>Extracted from {@code ConnectView} because the endpoint is no longer known
 * at startup: the user types it, and pressing Connect a second time with a
 * different address must produce a <b>fresh</b> {@link HSTSClient} bound to it,
 * with the dispatcher and push bridge re-attached. Doing that inline in the view
 * mixed three responsibilities in one method; here it is one obvious sequence.
 *
 * <p>The wiring itself is the one from ARCHITECTURE §3: every inbound message
 * goes to the {@link RequestDispatcher}, which either completes a pending
 * request future or hands a push to {@link PushEventBridge}, which posts it on
 * the event bus — and the bus is where the single FX-thread hop happens.
 */
public final class ConnectWiring {

    private ConnectWiring() {
    }

    /**
     * Creates and wires a client for {@code endpoint}, registering the dispatcher
     * on the given manager.
     *
     * @return the new connection adapter, not yet connected
     */
    public static IClientConnection newClient(ServerEndpoint endpoint, ScreenManager manager) {
        HSTSClient client = new HSTSClient(endpoint.host(), endpoint.port());

        RequestDispatcher dispatcher = new RequestDispatcher(client);
        dispatcher.setPushListener(new PushEventBridge(manager.eventBus()));
        client.setServerMessageHandler(dispatcher::dispatchIncoming);
        client.setConnectionLostHandler(dispatcher::failAllPending);

        manager.setDispatcher(dispatcher);
        return client;
    }
}
