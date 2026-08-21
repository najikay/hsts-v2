package server.core;

import common.dto.auth.Role;
import ocsf.server.ConnectionToClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The authoritative user ↔ connection map (Logic tier, E3.3).
 *
 * <p>Three jobs:
 * <ol>
 *   <li><b>Identity:</b> it is the only way to learn who a connection belongs to.
 *       {@link MessageRouter} asks it once per message and hands the answer to
 *       handlers as a {@link CallerContext} — payload-supplied identities are
 *       never consulted.</li>
 *   <li><b>Single login (T-16, F1.3):</b> {@link #attach} refuses a second
 *       concurrent session for the same user. The refusal is a plain
 *       {@code false} so the auth service can turn it into a friendly
 *       {@code CONFLICT} instead of an exception.</li>
 *   <li><b>Cleanup:</b> a dropped socket frees the session immediately and fires
 *       the registered {@link DisconnectHook}s (edit locks, attempt bookkeeping —
 *       E3.4), each exactly once per detach.</li>
 * </ol>
 *
 * <p>Thread safety: mutations are serialised on one monitor so the two
 * directions of the map can never disagree; lookups are lock-free reads of
 * concurrent maps. Two threads racing to attach the same user therefore produce
 * exactly one winner.
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /** What the server knows about a logged-in connection. */
    public record Session(long userId, Role role) {
    }

    /**
     * One row of the server console's connected-clients table (E19.3).
     *
     * <p>A separate record from {@link Session} because it is a different thing:
     * {@code Session} is what the router needs to answer "who is this", and this
     * is what an operator needs to answer "who is on my server right now". Adding
     * an address and a timestamp to {@code Session} would put two fields on the
     * hot path of every single message so that one window could draw a table.
     *
     * @param userId         the signed-in user
     * @param role           their wire role, {@code null} only in the pre-auth
     *                       shape {@link #attach(long, ConnectionToClient)} uses
     * @param remoteAddress  the client's IP as the socket reports it, or
     *                       {@code "unknown"} when it cannot be read
     * @param connectedSince when the session was opened, UTC
     */
    public record ConnectedClient(long userId, Role role, String remoteAddress,
                                  Instant connectedSince) {

        /** Shown when a socket cannot say where it came from. */
        public static final String UNKNOWN_ADDRESS = "unknown";

        public ConnectedClient {
            Objects.requireNonNull(connectedSince, "connectedSince");
            remoteAddress = remoteAddress == null || remoteAddress.isBlank()
                    ? UNKNOWN_ADDRESS : remoteAddress.trim();
        }
    }

    /** Notified after a session is removed, whatever removed it. */
    @FunctionalInterface
    public interface DisconnectHook {
        void onSessionEnded(long userId, ConnectionToClient connection);
    }

    /**
     * Notified whenever the set of open sessions changes (E19.3).
     *
     * <p>Distinct from {@link DisconnectHook}, which is cleanup and fires only on
     * the way out. This one fires on attach as well and carries nothing: it is a
     * repaint signal for a view that will then call {@link #connectedClients()}
     * for itself. A listener that was handed the changed session would have to be
     * careful about which of the two events it received and would still have to
     * re-read for the rest of the table.
     *
     * <p>Listeners are called on the thread that caused the change, which is an
     * OCSF read thread. A JavaFX listener therefore hops to the FX thread itself;
     * that hop is the view's business, not this map's.
     */
    @FunctionalInterface
    public interface SessionListener {
        void onSessionsChanged();
    }

    private final Object lock = new Object();
    private final Map<Long, ConnectionToClient> connectionByUser = new ConcurrentHashMap<>();
    private final Map<ConnectionToClient, Session> sessionByConnection = new ConcurrentHashMap<>();
    private final Map<ConnectionToClient, Instant> attachedAt = new ConcurrentHashMap<>();
    private final List<DisconnectHook> hooks = new CopyOnWriteArrayList<>();
    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final Clock clock;

    /** Production wiring: the system UTC clock. */
    public SessionManager() {
        this(Clock.systemUTC());
    }

    /**
     * @param clock the source of {@code connectedSince}; a test clock in tests, so
     *              the console's "connected 4 minutes ago" column is assertable
     *              rather than approximately right
     */
    public SessionManager(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ===================== Attach / detach ===============================

    /**
     * Binds {@code userId} to {@code connection} with an unknown role — the
     * shape E1 needs before the auth service exists.
     */
    public boolean attach(long userId, ConnectionToClient connection) {
        return attach(userId, null, connection);
    }

    /**
     * Binds {@code userId} (with its {@code role}) to {@code connection}.
     *
     * @return {@code true} when the session was created — or already existed on
     *         this very connection (re-attaching is idempotent);
     *         {@code false} when the user is already signed in elsewhere (T-16)
     *         or this connection already carries a different user
     */
    public boolean attach(long userId, Role role, ConnectionToClient connection) {
        Objects.requireNonNull(connection, "connection");
        synchronized (lock) {
            ConnectionToClient existing = connectionByUser.get(userId);
            if (existing != null) {
                if (existing == connection) {
                    return true;
                }
                log.warn("Rejected duplicate login for user {} - already attached to {}", userId, existing);
                return false;
            }
            Session onThisConnection = sessionByConnection.get(connection);
            if (onThisConnection != null) {
                log.warn("Rejected attach of user {} - connection already bound to user {}",
                        userId, onThisConnection.userId());
                return false;
            }
            connectionByUser.put(userId, connection);
            sessionByConnection.put(connection, new Session(userId, role));
            attachedAt.put(connection, clock.instant());
        }
        log.info("Session opened for user {} ({})", userId, role);
        fireSessionsChanged();
        return true;
    }

    /**
     * Frees the session on {@code connection} (socket drop, or logout) and fires
     * the cleanup hooks.
     *
     * @return the user that was attached, empty when the connection had no session
     */
    public Optional<Long> detach(ConnectionToClient connection) {
        if (connection == null) {
            return Optional.empty();
        }
        Session removed;
        synchronized (lock) {
            removed = sessionByConnection.remove(connection);
            if (removed == null) {
                return Optional.empty();
            }
            // Only drop the forward mapping if it still points at this connection.
            connectionByUser.remove(removed.userId(), connection);
            attachedAt.remove(connection);
        }
        log.info("Session closed for user {}", removed.userId());
        fireHooks(removed.userId(), connection);
        fireSessionsChanged();
        return Optional.of(removed.userId());
    }

    /**
     * Frees {@code userId}'s session wherever it lives (forced logout).
     *
     * @return true when a session was actually removed
     */
    public boolean detachUser(long userId) {
        ConnectionToClient connection;
        synchronized (lock) {
            connection = connectionByUser.remove(userId);
            if (connection == null) {
                return false;
            }
            sessionByConnection.remove(connection);
            attachedAt.remove(connection);
        }
        log.info("Session closed for user {} (forced)", userId);
        fireHooks(userId, connection);
        fireSessionsChanged();
        return true;
    }

    private void fireHooks(long userId, ConnectionToClient connection) {
        for (DisconnectHook hook : hooks) {
            try {
                hook.onSessionEnded(userId, connection);
            } catch (RuntimeException e) {
                // One misbehaving cleanup must not strand the others (or the socket).
                log.error("Disconnect hook failed for user {}", userId, e);
            }
        }
    }

    // ===================== Lookups =======================================

    /** @return the session bound to this connection, if any. */
    public Optional<Session> sessionOf(ConnectionToClient connection) {
        return connection == null ? Optional.empty() : Optional.ofNullable(sessionByConnection.get(connection));
    }

    /** @return the user id bound to this connection, if any. */
    public Optional<Long> userIdOf(ConnectionToClient connection) {
        return sessionOf(connection).map(Session::userId);
    }

    /** @return the live connection of a signed-in user, if any. */
    public Optional<ConnectionToClient> connectionOf(long userId) {
        return Optional.ofNullable(connectionByUser.get(userId));
    }

    public boolean isOnline(long userId) {
        return connectionByUser.containsKey(userId);
    }

    /** @return a snapshot of the signed-in user ids. */
    public Set<Long> onlineUserIds() {
        return Set.copyOf(connectionByUser.keySet());
    }

    public int onlineCount() {
        return connectionByUser.size();
    }

    /**
     * A read-only snapshot of who is signed in, for the server console's client
     * table (E19.3, F13.1).
     *
     * <p>A copy, not a view. The console reads this from the FX thread while OCSF
     * threads attach and detach on it, and handing out anything live would make
     * the table's own iteration a race. It is also the only accessor that exposes
     * the connection's address, and it exposes it as text: the console shows an IP
     * and has no business holding a socket.
     *
     * <p>Ordered oldest connection first, so a table refreshed every second does
     * not reshuffle its rows under the operator's cursor.
     *
     * @return one row per signed-in user, oldest session first
     */
    public List<ConnectedClient> connectedClients() {
        List<ConnectedClient> rows = new ArrayList<>(sessionByConnection.size());
        sessionByConnection.forEach((connection, session) -> rows.add(new ConnectedClient(
                session.userId(), session.role(), addressOf(connection),
                attachedAt.getOrDefault(connection, Instant.EPOCH))));
        rows.sort(Comparator.comparing(ConnectedClient::connectedSince)
                .thenComparingLong(ConnectedClient::userId));
        return List.copyOf(rows);
    }

    /**
     * @return the client's IP as the socket reports it, or
     *         {@code ConnectedClient.UNKNOWN_ADDRESS}. A socket that has already
     *         closed answers null here, and a console row reading "unknown" is a
     *         better outcome than a table that throws while painting.
     */
    private static String addressOf(ConnectionToClient connection) {
        try {
            java.net.InetAddress address = connection.getInetAddress();
            return address == null ? ConnectedClient.UNKNOWN_ADDRESS : address.getHostAddress();
        } catch (RuntimeException e) {
            return ConnectedClient.UNKNOWN_ADDRESS;
        }
    }

    // ===================== Hooks =========================================

    /**
     * Registers a repaint callback fired whenever a session opens or closes
     * (E19.3).
     *
     * <p>The server console's client table is the only caller. Registering is
     * unconditional and unregistering is the caller's job: a console window that
     * is closed and never removes its listener would keep a dead view alive for
     * the life of the process.
     */
    public void addSessionListener(SessionListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** @return true when the listener was registered and is now removed. */
    public boolean removeSessionListener(SessionListener listener) {
        return listeners.remove(listener);
    }

    private void fireSessionsChanged() {
        for (SessionListener listener : listeners) {
            try {
                listener.onSessionsChanged();
            } catch (RuntimeException e) {
                // A console that throws while repainting must not break the login
                // that triggered it. The server outranks the window watching it.
                log.error("Session listener failed", e);
            }
        }
    }

    /** Registers a cleanup callback fired once per ended session. */
    public void addDisconnectHook(DisconnectHook hook) {
        hooks.add(Objects.requireNonNull(hook, "hook"));
    }

    /** @return true when the hook was registered and is now removed. */
    public boolean removeDisconnectHook(DisconnectHook hook) {
        return hooks.remove(hook);
    }
}
