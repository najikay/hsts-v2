package server.realtime;

import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import server.core.SessionManager;

/**
 * The server → client push channel (Logic tier, E1.7 — the server half of the
 * <b>Observer / Pub-Sub Pattern</b> ⚑ B-34).
 *
 * <p><b>{@code PLAN.md} §2's "Observer/Pub-Sub (EventBus + server push)" names two classes and
 * this is the second one</b>; {@code client.events.ClientEventBus} is the first. A service here
 * publishes by naming recipients and a verb and nothing else — it does not know whether anyone
 * is watching, which screen is open, or whether the message will be delivered at all. That is
 * what makes the subject/observer coupling one-directional across a network rather than merely
 * across a package.
 *
 * <p>The subscription itself lives in two places for two kinds of recipient: a session is an
 * implicit subscriber to everything addressed to its user, and
 * {@code EXECUTION_MONITOR_GET}-style verbs register explicit per-entity watchers whose
 * lifetime is the connection's ({@code MonitorService}, {@code EditLockService}).
 *
 * <p>Services never touch a socket: they name recipients by user id and this
 * gateway looks each one up in {@link SessionManager} and writes a
 * {@link common.protocol.Status#PUSH} message. Offline users are skipped
 * silently — being logged out is normal, not an error — and the persistent copy
 * (notifications table, E12) is what they see on their next login.
 *
 * <p>Delivery is best-effort by design: a socket that fails mid-write is logged
 * and counted as undelivered, never rethrown, so one dead client cannot abort a
 * broadcast or fail the transaction that triggered it.
 */
public class PushGateway {

    private static final Logger log = LoggerFactory.getLogger(PushGateway.class);

    private final SessionManager sessions;

    public PushGateway(SessionManager sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    /**
     * Pushes to one user.
     *
     * @return true when the message reached the socket; false when the user is
     *         offline or the write failed
     */
    public boolean toUser(long userId, Verb verb, Object payload) {
        Objects.requireNonNull(verb, "verb");
        warnIfNotAPushVerb(verb);
        Optional<ConnectionToClient> connection = sessions.connectionOf(userId);
        if (connection.isEmpty()) {
            log.debug("Skipping {} for user {} - offline", verb, userId);
            return false;
        }
        return write(connection.get(), userId, Message.push(verb, payload));
    }

    /**
     * Pushes to a set of users (duplicates collapse, {@code null} ids ignored).
     *
     * @return how many users actually received it
     */
    public int toUsers(Collection<Long> userIds, Verb verb, Object payload) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        Set<Long> distinct = new HashSet<>(userIds);
        distinct.remove(null);
        int delivered = 0;
        for (Long userId : distinct) {
            if (toUser(userId, verb, payload)) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * Pushes to every signed-in user.
     *
     * @return how many users received it
     */
    public int broadcast(Verb verb, Object payload) {
        return toUsers(sessions.onlineUserIds(), verb, payload);
    }

    private boolean write(ConnectionToClient connection, long userId, Message push) {
        try {
            connection.sendToClient(push);
            log.debug("push {} → user {}", push.getVerb(), userId);
            return true;
        } catch (Exception e) {
            // Dead or half-open socket: the OCSF listener will detach the session.
            log.warn("Push {} to user {} failed: {}", push.getVerb(), userId, e.toString());
            return false;
        }
    }

    private static void warnIfNotAPushVerb(Verb verb) {
        if (!verb.isPush()) {
            log.warn("Pushing non-push verb {} - check the caller", verb);
        }
    }
}
