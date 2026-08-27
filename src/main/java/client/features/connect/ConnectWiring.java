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

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ConnectWiring.class);

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
     * <p>A {@code null} bus is tolerated rather than fatal, and that is a
     * deliberate backstop (UI wave 1, item 0). This method is reached from a
     * {@code runLater} posted by {@code ConnectView}'s discovery sweep, which
     * runs on a daemon thread and can land after the world it belongs to has
     * been torn down — in tests, after {@code ScreenManager.resetForTests()}
     * has replaced the manager with an uninitialised one whose bus is
     * {@code null}. Throwing there produces an exception on the FX thread with
     * no test in the stack to attribute it to, so JUnit blames whichever test
     * runs next. Logging and wiring to a detached bus instead keeps the failure
     * where it belongs: in the log, next to the reason.
     *
     * <p>Nothing is lost by the substitution. This method opens no socket, so a
     * wiring nobody uses is inert, and events posted to a bus nobody listens to
     * simply go nowhere. {@code endpoint} stays a hard requirement: there is no
     * sensible client without one.
     *
     * @param eventBus where pushes and connection-lost events are published;
     *                 {@code null} means the app is being torn down
     * @return the wired pair; the connection is <b>not</b> open yet — the caller
     *         opens it off the FX thread and reports the outcome
     */
    public static Wiring forEndpoint(ServerEndpoint endpoint, ClientEventBus eventBus) {
        Objects.requireNonNull(endpoint, "endpoint");

        ClientEventBus bus = eventBus;
        if (bus == null) {
            LOG.warn("Wiring {} with no event bus: the app was torn down while this "
                    + "connection was being prepared. Events from it go nowhere.",
                    endpoint.display());
            bus = detachedBus();
        }
        return wire(endpoint, bus);
    }

    /**
     * A bus with no subscribers, for the torn-down case above. Built the same
     * way the real one is so nothing downstream has to know the difference.
     */
    private static ClientEventBus detachedBus() {
        return new ClientEventBus(ClientEventBus.newBus(), Runnable::run);
    }

    private static Wiring wire(ServerEndpoint endpoint, ClientEventBus eventBus) {
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

    /**
     * The technical reason, <b>for the log only</b> ⚑ (B-37).
     *
     * <p>This computes the same class-name fallback {@code ConnectView.onFailed} used to compute,
     * and it is still here on purpose: its output goes into {@link ConnectionLostEvent#detail()},
     * which has exactly one consumer — {@code ConnectionWatcher}'s {@code log.warn} — and the
     * banner it raises takes no detail parameter at all
     * ({@code ReconnectBanner.showDisconnected(String serverLabel)}).
     *
     * <p><b>If a future change renders this on screen, it becomes B-37 again.</b> The connect
     * screen's own copy is {@code ConnectFlow.reasonFor}, which maps causes to product sentences
     * and never leaks a class name; use that, not this.
     */
    private static String describe(Throwable cause) {
        if (cause == null) {
            return "";
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
