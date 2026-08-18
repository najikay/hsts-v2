package server.core;

import common.dto.auth.Role;
import ocsf.server.ConnectionToClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /** Notified after a session is removed, whatever removed it. */
    @FunctionalInterface
    public interface DisconnectHook {
        void onSessionEnded(long userId, ConnectionToClient connection);
    }

    private final Object lock = new Object();
    private final Map<Long, ConnectionToClient> connectionByUser = new ConcurrentHashMap<>();
    private final Map<ConnectionToClient, Session> sessionByConnection = new ConcurrentHashMap<>();
    private final List<DisconnectHook> hooks = new CopyOnWriteArrayList<>();

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
                log.warn("Rejected duplicate login for user {} — already attached to {}", userId, existing);
                return false;
            }
            Session onThisConnection = sessionByConnection.get(connection);
            if (onThisConnection != null) {
                log.warn("Rejected attach of user {} — connection already bound to user {}",
                        userId, onThisConnection.userId());
                return false;
            }
            connectionByUser.put(userId, connection);
            sessionByConnection.put(connection, new Session(userId, role));
        }
        log.info("Session opened for user {} ({})", userId, role);
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
        }
        log.info("Session closed for user {}", removed.userId());
        fireHooks(removed.userId(), connection);
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
        }
        log.info("Session closed for user {} (forced)", userId);
        fireHooks(userId, connection);
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

    // ===================== Hooks =========================================

    /** Registers a cleanup callback fired once per ended session. */
    public void addDisconnectHook(DisconnectHook hook) {
        hooks.add(Objects.requireNonNull(hook, "hook"));
    }

    /** @return true when the hook was registered and is now removed. */
    public boolean removeDisconnectHook(DisconnectHook hook) {
        return hooks.remove(hook);
    }
}
