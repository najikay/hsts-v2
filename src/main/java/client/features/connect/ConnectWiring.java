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
 *
 * <p><b>The client is new every time; the dispatcher is not ⚑.</b> Pass the
 * dispatcher the app already holds and it is
 * {@linkplain RequestDispatcher#rebind rebound} to the new socket and handed
 * back — the same instance, so every screen built against it keeps working.
 * Only the very first connection of a process creates one.
 *
 * <p>2026-08-29, manual round 2, U-17. Before that, a second connection built a
 * second dispatcher, and the cached login screen went on sending down the first
 * one's dead socket: the status row said Connected and Sign in answered "could
 * not reach the server" until the window was restarted.
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
     * <p>This is the <b>first</b> connection of a process: it creates a
     * dispatcher. Every later one must go through
     * {@link #forEndpoint(ServerEndpoint, ClientEventBus, RequestDispatcher)}
     * and hand over the dispatcher the app already has, or it strands the
     * screens holding it (⚑ U-17).
     *
     * @param eventBus where pushes and connection-lost events are published;
     *                 {@code null} means the app is being torn down
     * @return the wired pair; the connection is <b>not</b> open yet — the caller
     *         opens it off the FX thread and reports the outcome
     */
    public static Wiring forEndpoint(ServerEndpoint endpoint, ClientEventBus eventBus) {
        return forEndpoint(endpoint, eventBus, null);
    }

    /**
     * Creates and wires a client for {@code endpoint}, reusing the dispatcher
     * the app already has (E4.5 ⚑ U-17).
     *
     * <p>This is the overload every reconnect goes through. The client is always
     * new — it is bound to a host and a port and cannot be re-pointed — but the
     * dispatcher is the app's one long-lived correlator, and the screens holding
     * it must not be left behind. Pass {@code null} only for the first
     * connection of the process, when there is nothing to keep.
     *
     * @param existing the dispatcher to rebind, or {@code null} to create one
     * @return the wired pair; {@code dispatcher()} is {@code existing} whenever
     *         one was supplied
     */
    public static Wiring forEndpoint(ServerEndpoint endpoint, ClientEventBus eventBus,
                                     RequestDispatcher existing) {
        Objects.requireNonNull(endpoint, "endpoint");

        ClientEventBus bus = busFor(endpoint, eventBus);
        HSTSClient client = new HSTSClient(endpoint.host(), endpoint.port());
        Wiring wiring = attach(client, endpoint, bus, existing);

        // Re-registered against the dispatcher that is live now, which after a
        // rebind is the same object as before: the handler must fail the futures
        // the screens are actually waiting on.
        client.setConnectionLostHandler(
                connectionLostHandler(endpoint, bus, wiring.dispatcher()));
        return wiring;
    }

    /**
     * The half of the wiring that does not care where the connection came from:
     * bind the dispatcher to it, in both directions.
     *
     * <p>Separate from {@link #forEndpoint} so a test can drive the real
     * reconnect decision with a {@link client.net.FakeClientConnection} instead
     * of a socket. The connection-lost handler is not registered here because it
     * is not on {@link IClientConnection} — it belongs to the real client, and
     * {@code forEndpoint} adds it there.
     *
     * @param existing the dispatcher to rebind, or {@code null} to create one
     * @return the wired pair
     */
    public static Wiring attach(IClientConnection client, ServerEndpoint endpoint,
                                ClientEventBus eventBus, RequestDispatcher existing) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(endpoint, "endpoint");
        ClientEventBus bus = busFor(endpoint, eventBus);

        RequestDispatcher dispatcher;
        if (existing == null) {
            dispatcher = new RequestDispatcher(client);
        } else {
            existing.rebind(client);
            dispatcher = existing;
        }

        dispatcher.setPushListener(new PushEventBridge(bus));
        client.setServerMessageHandler(dispatcher::dispatchIncoming);
        return new Wiring(client, dispatcher);
    }

    /**
     * Throws away a socket that opened but did not prove itself (B-49).
     *
     * <p>{@link ConnectHandshake} can leave the caller holding a live socket that
     * is provably useless. It must be closed, or the client leaks a connection
     * every time somebody presses Connect at a stopped server, and the server
     * leaks the matching entry in its backlog.
     *
     * <p><b>The connection-lost handler is silenced first, and that is the point
     * of the method ⚑.</b> Closing an {@code HSTSClient} fires
     * {@code connectionClosed()}, which posts a {@link ConnectionLostEvent} and
     * raises the shell's reconnect banner. On this path that banner would be a
     * lie twice over: nothing was lost, because nothing was ever established, and
     * the screen is already showing the connect screen's own sentence about the
     * same failure. Doing the silencing here rather than in the two callers keeps
     * the cast to the concrete client in the class that created it.
     *
     * @param client the connection to discard; {@code null} is tolerated
     */
    public static void abandon(IClientConnection client) {
        if (client == null) {
            return;
        }
        if (client instanceof HSTSClient real) {
            real.setConnectionLostHandler(null);
        }
        try {
            client.disconnect();
        } catch (Exception e) {
            // Closing a socket we have already given up on cannot fail in a way
            // anybody can act on, and the user is being told about the connect.
            LOG.debug("Could not close the unproven connection: {}", e.toString());
        }
    }

    /** The given bus, or the detached stand-in described on {@link #forEndpoint}. */
    private static ClientEventBus busFor(ServerEndpoint endpoint, ClientEventBus eventBus) {
        if (eventBus != null) {
            return eventBus;
        }
        LOG.warn("Wiring {} with no event bus: the app was torn down while this "
                + "connection was being prepared. Events from it go nowhere.",
                endpoint.display());
        return detachedBus();
    }

    /**
     * A bus with no subscribers, for the torn-down case above. Built the same
     * way the real one is so nothing downstream has to know the difference.
     */
    private static ClientEventBus detachedBus() {
        return new ClientEventBus(ClientEventBus.newBus(), Runnable::run);
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
