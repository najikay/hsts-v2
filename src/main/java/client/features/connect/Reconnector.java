package client.features.connect;

import client.core.ConnectPrefs;
import client.core.ScreenManager;
import client.core.ServerEndpoint;
import client.core.ServerPin;
import client.events.ClientEventBus;
import client.events.FxThreadPoster;
import client.net.IClientConnection;
import client.net.RequestDispatcher;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Re-dialling the server the client already knows about (Presentation tier,
 * E4.6 ⚑ U-52).
 *
 * <p>2026-08-30, Findings.txt, U-52. The reconnect banner's Retry did nothing,
 * because nothing was wired to it: {@code TakeExamView} pointed its own banner at
 * {@code ExamAttemptSession.resume()}, and the shell's banner — the one a user
 * sees on every other screen — had no action at all. U-17 had already fixed the
 * <i>server</i>-restart path, where the user reaches the connect screen and dials
 * again by hand. This is the other path: the <b>client</b> lost the network, the
 * server never went anywhere, and there is nothing for the user to type because
 * the address has not changed.
 *
 * <p>So Retry is a re-dial of a known endpoint, which is a small sequence with
 * three rules in it, and the rules are what this class exists to hold:
 *
 * <ul>
 *   <li><b>where to dial.</b> The pinned server first (F13.4 — it is the one this
 *       computer trusts), then the remembered endpoint (F1.5), and only then the
 *       host and port of the client that just died. Empty means the client has
 *       never completed a connect, and the caller sends the user to the connect
 *       screen rather than guessing.</li>
 *   <li><b>what to build.</b> {@link ConnectWiring#forEndpoint(ServerEndpoint,
 *       ClientEventBus, RequestDispatcher)} with the dispatcher the app already
 *       holds, so the rebind is the one U-17 established and every cached screen
 *       keeps working. A new client, never a re-pointed one.</li>
 *   <li><b>where it runs.</b> Opening a socket blocks; it happens off the FX
 *       thread and the outcome comes back through the one documented hop
 *       (ARCHITECTURE §6), exactly as {@code ConnectView} does it.</li>
 * </ul>
 *
 * <p>Nothing here decides what a successful re-dial <i>means</i>. The server freed
 * the session when the socket dropped (F1.4), so the shell signs out and lands on
 * Login, and the exam screen resumes its attempt; those are the callers' rules and
 * they are different from each other.
 */
public final class Reconnector {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(Reconnector.class);

    /**
     * How a re-dial builds its network stack.
     *
     * <p>A seam, and only a seam: production passes
     * {@code ConnectWiring::forEndpoint}, which creates a real {@code HSTSClient}
     * bound to a real host and port. A test passes a wirer that attaches a
     * {@link client.net.FakeClientConnection} instead, so the sequence under test
     * is this one rather than a rehearsal of it.
     */
    @FunctionalInterface
    public interface Wirer {

        /** @param existing the dispatcher to rebind; never {@code null} after the first connect */
        ConnectWiring.Wiring wire(ServerEndpoint endpoint, ClientEventBus bus,
                                  RequestDispatcher existing);
    }

    private final ScreenManager manager;
    private final ClientEventBus bus;
    private final ConnectPrefs prefs;
    private final Wirer wirer;
    private final Executor worker;

    /**
     * @param manager where the client and dispatcher live
     * @param bus     the app's bus, passed rather than read off the manager so the
     *                sequence can be driven without a Stage
     * @param prefs   the pinned and remembered endpoints
     */
    public Reconnector(ScreenManager manager, ClientEventBus bus, ConnectPrefs prefs) {
        this(manager, bus, prefs, ConnectWiring::forEndpoint, Reconnector::onADaemonThread);
    }

    /**
     * The injected form, for tests: the wiring and the thread the connect runs on.
     *
     * <p>Public for the reason {@code ConnectView}'s two-argument constructor is:
     * the seam is the point of the class being testable at all, and a test in
     * another package is still a caller.
     *
     * @param wirer  how the stack is built; production is {@code ConnectWiring::forEndpoint}
     * @param worker where the blocking connect runs; production is a daemon thread
     */
    public Reconnector(ScreenManager manager, ClientEventBus bus, ConnectPrefs prefs,
                       Wirer wirer, Executor worker) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.prefs = Objects.requireNonNull(prefs, "prefs");
        this.wirer = Objects.requireNonNull(wirer, "wirer");
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    /**
     * Where a re-dial would go.
     *
     * @return the pinned endpoint, else the remembered one, else the address the
     *         dead client was bound to; empty when this client has never connected,
     *         which is the caller's cue to show the connect screen
     */
    public Optional<ServerEndpoint> endpoint() {
        Optional<ServerEndpoint> pinned = prefs.pinned().map(ServerPin::endpoint);
        if (pinned.isPresent()) {
            return pinned;
        }
        Optional<ServerEndpoint> remembered = prefs.lastUsed();
        if (remembered.isPresent()) {
            return remembered;
        }
        return fromDeadClient();
    }

    /**
     * Dials {@link #endpoint()} again, off the FX thread.
     *
     * <p>The client and dispatcher are installed on the manager <b>before</b> the
     * socket is opened, which is what {@code ConnectView} does and for the same
     * reason: the dispatcher rebind must be visible to every cached screen the
     * moment the attempt starts, and a client that then fails to open is a closed
     * client, which {@code ScreenManager.isConnectionAlive()} already reads as
     * dead.
     *
     * @param onOpen   run on the FX thread when the socket opened
     * @param onFailed run on the FX thread with the failure when it did not
     * @return the endpoint being dialled, or empty when there is none to dial and
     *         nothing was started
     */
    public Optional<ServerEndpoint> redial(Runnable onOpen, Consumer<Throwable> onFailed) {
        Objects.requireNonNull(onOpen, "onOpen");
        Objects.requireNonNull(onFailed, "onFailed");

        Optional<ServerEndpoint> target = endpoint();
        if (target.isEmpty()) {
            LOG.warn("Nothing to re-dial: no pinned or remembered server, and no client");
            return Optional.empty();
        }
        ServerEndpoint endpoint = target.get();

        // The same instance goes in and comes back out, rebound ⚑ U-17.
        ConnectWiring.Wiring wiring = wirer.wire(endpoint, bus, manager.getDispatcher());
        manager.setClient(wiring.client());
        manager.setDispatcher(wiring.dispatcher());

        // Captured here, while the bus certainly has one, for the reason
        // ConnectView.startDiscovery documents: the screen can be gone by the time
        // the socket answers.
        FxThreadPoster poster = bus.poster();
        worker.execute(() -> {
            try {
                wiring.client().connect();
                LOG.info("Re-dialled {}", endpoint.display());
                poster.run(onOpen);
            } catch (Exception e) {
                LOG.warn("Re-dial of {} failed", endpoint.display(), e);
                poster.run(() -> onFailed.accept(e));
            }
        });
        return target;
    }

    /** The address the client that just died was bound to, when there is one. */
    private Optional<ServerEndpoint> fromDeadClient() {
        IClientConnection dead = manager.getClient();
        if (dead == null || dead.getHost() == null || dead.getHost().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ServerEndpoint(dead.getHost(), dead.getPort()));
        } catch (RuntimeException e) {
            // A client bound to something an endpoint cannot represent is not a
            // reason to fail a Retry; it is a reason to send the user to Connect.
            LOG.debug("The dead client's address is not a usable endpoint: {}", e.toString());
            return Optional.empty();
        }
    }

    private static void onADaemonThread(Runnable work) {
        Thread thread = new Thread(work, "hsts-reconnect");
        thread.setDaemon(true);
        thread.start();
    }
}
