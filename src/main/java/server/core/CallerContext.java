package server.core;

import common.dto.auth.Role;
import ocsf.server.ConnectionToClient;

import java.util.Objects;
import java.util.Optional;

/**
 * Who is on the other end of this connection (Logic tier).
 *
 * <p>Built by {@link MessageRouter} for every inbound message from the
 * {@link SessionManager} binding — <b>never</b> from anything in the payload.
 * That is the whole point: a client can put any user id it likes in a DTO and it
 * will change nothing, because handlers and {@link Authorization} guards read
 * identity only from here (ARCHITECTURE §3, security).
 */
public final class CallerContext {

    private final ConnectionToClient connection;
    private final Long userId;
    private final Role role;

    private CallerContext(ConnectionToClient connection, Long userId, Role role) {
        this.connection = connection;
        this.userId = userId;
        this.role = role;
    }

    /** A connection with no authenticated session yet (pre-login). */
    public static CallerContext anonymous(ConnectionToClient connection) {
        return new CallerContext(connection, null, null);
    }

    /** A connection bound to a logged-in user. */
    public static CallerContext authenticated(ConnectionToClient connection, long userId, Role role) {
        return new CallerContext(connection, userId, role);
    }

    public boolean isAuthenticated() {
        return userId != null;
    }

    /**
     * @return the authenticated user id
     * @throws IllegalStateException if the caller is anonymous — call
     *         {@link Authorization#requireAuthenticated(CallerContext)} first
     */
    public long userId() {
        if (userId == null) {
            throw new IllegalStateException("Caller is not authenticated");
        }
        return userId;
    }

    /** @return the caller's role, empty when anonymous (or not yet known). */
    public Optional<Role> role() {
        return Optional.ofNullable(role);
    }

    /** @return the OCSF connection this caller is speaking on. */
    public ConnectionToClient connection() {
        return connection;
    }

    /** @return true when this caller holds exactly one of {@code roles}. */
    public boolean hasAnyRole(Role... roles) {
        if (role == null || roles == null) {
            return false;
        }
        for (Role candidate : roles) {
            if (role == candidate) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return isAuthenticated()
                ? "Caller{userId=" + userId + ", role=" + role + '}'
                : "Caller{anonymous, connection=" + Objects.toString(connection, "none") + '}';
    }
}
