package server.console;

import server.core.SessionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The connected-clients table's rows, already rendered (Logic tier, E19.3 /
 * F13.1).
 *
 * <p>{@link SessionManager} answers user ids and instants; an operator glancing
 * at a projector needs names and "4 min". Turning one into the other is where the
 * decisions are (what an unnamed user is called, how a duration is phrased, how a
 * just-connected client reads), so it happens here rather than inside a cell
 * factory where it would never be tested.
 */
public final class ConsoleClients {

    /** What a user id with no known name is called. */
    public static final String UNKNOWN_USER = "Unknown user";

    /** How the console resolves a user id to a name. */
    @FunctionalInterface
    public interface UserNames {

        /** A lookup that knows nobody; every row shows the id only. */
        UserNames NONE = userId -> Optional.empty();

        /** @return the user's display name, empty when it cannot be resolved */
        Optional<String> displayName(long userId);
    }

    /**
     * One table row.
     *
     * @param userId  kept alongside the name because two people can share a name
     *                and an operator asked to "kick user 1042" needs the number
     * @param user    display name, or {@link #UNKNOWN_USER}
     * @param role    the role's name, or {@code "signing in"} before it is known
     * @param address the client's IP
     * @param since   how long they have been connected, in words
     */
    public record Row(long userId, String user, String role, String address, String since) {
    }

    private final UserNames names;

    public ConsoleClients(UserNames names) {
        this.names = Objects.requireNonNull(names, "names");
    }

    /**
     * @param clients the session map's snapshot
     * @param now     the console's clock reading
     * @return one row per client, in the snapshot's order (oldest connection
     *         first), so a table refreshed every second does not reshuffle
     */
    public List<Row> rows(List<SessionManager.ConnectedClient> clients, Instant now) {
        Objects.requireNonNull(clients, "clients");
        Objects.requireNonNull(now, "now");
        return clients.stream()
                .map(client -> new Row(
                        client.userId(),
                        names.displayName(client.userId()).orElse(UNKNOWN_USER),
                        roleText(client),
                        client.remoteAddress(),
                        since(client.connectedSince(), now)))
                .toList();
    }

    /**
     * @return the role column. A session exists for a moment before the role is
     *         known, and "signing in" is a truer label for that moment than a
     *         blank cell
     */
    static String roleText(SessionManager.ConnectedClient client) {
        return client.role() == null ? "signing in" : client.role().name();
    }

    /**
     * How long ago, in words.
     *
     * <p>Coarse on purpose: a table that ticks every second is a table nobody can
     * read. Seconds under a minute, minutes under an hour, hours after that, and
     * a clock skew that would produce a negative reads as "just now" rather than
     * as a bug on screen.
     *
     * @param connectedSince when the session opened
     * @param now            the console's clock reading
     * @return {@code "just now"}, {@code "42 sec"}, {@code "4 min"} or {@code "2 h 5 min"}
     */
    public static String since(Instant connectedSince, Instant now) {
        if (connectedSince == null || now == null) {
            return "unknown";
        }
        Duration elapsed = Duration.between(connectedSince, now);
        if (elapsed.isNegative() || elapsed.toSeconds() < 5) {
            return "just now";
        }
        if (elapsed.toMinutes() < 1) {
            return elapsed.toSeconds() + " sec";
        }
        if (elapsed.toHours() < 1) {
            return elapsed.toMinutes() + " min";
        }
        return elapsed.toHours() + " h " + (elapsed.toMinutes() % 60) + " min";
    }

    /**
     * @param rowCount how many rows the table has
     * @return the sentence shown in the table's empty state, which is a normal
     *         state before a demo starts rather than an error
     */
    public static String emptyStateText(int rowCount) {
        return rowCount > 0 ? ""
                : "Nobody is connected yet. Give students the address above, "
                        + "or let their clients find this server by themselves.";
    }
}
