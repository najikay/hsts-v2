package client.features.connect;

import client.core.ServerEndpoint;
import client.events.ClientEventBus;
import client.events.ConnectionLostEvent;
import client.events.PushEventBridge;
import client.net.HSTSClient;
import client.net.IClientConnection;
import client.net.RequestDispatcher;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Builds the network stack for a chosen endpoint (Presentation tier, E4.5).
 *
 * <p>Extracted from {@code ConnectView} because the endpoint is no longer known
 * at startup: the user types it, and pressing Connect a second time with a
 * different address must produce a <b>fresh</b> {@link HSTSClient} bound to it,
 * with the dispatcher and push bridge re-attached. Doing that inline in the view
 * mixed three responsibilities in one method; here it is one obvious sequence —
 * and one testable without a socket, because nothing connects until the caller
 * says so.
 *
 * <p>The wiring itself is the one from ARCHITECTURE §3: every inbound message
 * goes to the {@link RequestDispatcher}, which either completes a pending
 * request future or hands a push to {@link PushEventBridge}, which posts it on
 * the event bus — and the bus is where the single FX-thread hop happens.
 *
 * <p>A lost connection is fanned out to <b>both</b> consumers that need it
 * (E5.7): the dispatcher fails every in-flight request immediately, and a
 * {@link ConnectionLostEvent} goes on the bus for the shell's reconnect banner.
 * Doing this in one place is what stops the two from ever disagreeing about
 * whether the connection is up.
 */
public final class ConnectWiring {

    /**
     * The two objects a connected screen needs: the adapter to talk through and
     * the correlator to talk with.
     */
    public record Wiring(IClientConnection client, RequestDispatcher dispatcher) {
    }

    private ConnectWiring() {
    }

    /**
     * Creates and wires a client for {@code endpoint}.
     *
     * @param eventBus where pushes and connection-lost events are published
     * @return the wired pair; the connection is <b>not</b> open yet — the caller
     *         opens it off the FX thread and reports the outcome
     */
    public static Wiring forEndpoint(ServerEndpoint endpoint, ClientEventBus eventBus) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(eventBus, "eventBus");

        HSTSClient client = new HSTSClient(endpoint.host(), endpoint.port());

        RequestDispatcher dispatcher = new RequestDispatcher(client);
        dispatcher.setPushListener(new PushEventBridge(eventBus));
        client.setServerMessageHandler(dispatcher::dispatchIncoming);
        client.setConnectionLostHandler(connectionLostHandler(endpoint, eventBus, dispatcher));

        return new Wiring(client, dispatcher);
    }

    /**
     * What happens when the socket dies, as a value rather than a lambda buried
     * in a constructor — the fan-out is a rule (fail the futures <b>and</b> tell
     * the shell), and rules get tested.
     */
    static Consumer<Throwable> connectionLostHandler(ServerEndpoint endpoint,
                                                     ClientEventBus eventBus,
                                                     RequestDispatcher dispatcher) {
        return cause -> {
            // Order matters: screens waiting on a future must fail before the
            // banner invites the user to do anything about it.
            dispatcher.failAllPending(cause == null
                    ? new java.io.IOException("Connection lost") : cause);
            eventBus.post(new ConnectionLostEvent(endpoint.display(), describe(cause)));
        };
    }

    private static String describe(Throwable cause) {
        if (cause == null) {
            return "";
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
