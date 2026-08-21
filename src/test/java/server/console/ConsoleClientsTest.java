package server.console;

import common.dto.auth.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.core.SessionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The connected-clients table's rows (E19.3, F13.1).
 *
 * <p>The session map answers ids and instants; this is where they become the
 * names and the "4 min" an operator reads off a projector, so this is where those
 * choices are asserted.
 */
class ConsoleClientsTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");

    private static final ConsoleClients.UserNames NAMES = userId -> switch ((int) userId) {
        case 1001 -> Optional.of("Dana Cohen");
        case 1002 -> Optional.of("Rina Barak");
        default -> Optional.empty();
    };

    private static SessionManager.ConnectedClient client(long id, Role role, String ip, Duration ago) {
        return new SessionManager.ConnectedClient(id, role, ip, NOW.minus(ago));
    }

    @Test
    @DisplayName("a row carries the name, the role, the address and how long")
    void rows() {
        ConsoleClients clients = new ConsoleClients(NAMES);

        List<ConsoleClients.Row> rows = clients.rows(List.of(
                client(1001, Role.TEACHER, "192.168.1.51", Duration.ofMinutes(4)),
                client(1002, Role.COORDINATOR, "192.168.1.52", Duration.ofHours(2).plusMinutes(5))),
                NOW);

        assertThat(rows).containsExactly(
                new ConsoleClients.Row(1001, "Dana Cohen", "TEACHER", "192.168.1.51", "4 min"),
                new ConsoleClients.Row(1002, "Rina Barak", "COORDINATOR", "192.168.1.52", "2 h 5 min"));
    }

    @Test
    @DisplayName("an unresolvable id still gets a row, with its number kept")
    void unknownUser() {
        ConsoleClients clients = new ConsoleClients(NAMES);

        List<ConsoleClients.Row> rows = clients.rows(
                List.of(client(9999, Role.STUDENT, "10.0.0.9", Duration.ofSeconds(30))), NOW);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.user()).isEqualTo(ConsoleClients.UNKNOWN_USER);
            assertThat(row.userId())
                    .as("two people can share a name; an operator asked to kick one needs the id")
                    .isEqualTo(9999);
        });
    }

    @Test
    @DisplayName("a session whose role is not yet known says so rather than showing blank")
    void roleNotYetKnown() {
        assertThat(ConsoleClients.roleText(client(1001, null, "10.0.0.1", Duration.ZERO)))
                .isEqualTo("signing in");
        assertThat(ConsoleClients.roleText(client(1001, Role.STUDENT, "10.0.0.1", Duration.ZERO)))
                .isEqualTo("STUDENT");
    }

    @Test
    @DisplayName("durations are coarse, so a table refreshed every second is readable")
    void since() {
        assertThat(ConsoleClients.since(NOW, NOW)).isEqualTo("just now");
        assertThat(ConsoleClients.since(NOW.minusSeconds(4), NOW)).isEqualTo("just now");
        assertThat(ConsoleClients.since(NOW.minusSeconds(42), NOW)).isEqualTo("42 sec");
        assertThat(ConsoleClients.since(NOW.minusSeconds(59), NOW)).isEqualTo("59 sec");
        assertThat(ConsoleClients.since(NOW.minusSeconds(60), NOW)).isEqualTo("1 min");
        assertThat(ConsoleClients.since(NOW.minusSeconds(3599), NOW)).isEqualTo("59 min");
        assertThat(ConsoleClients.since(NOW.minusSeconds(3600), NOW)).isEqualTo("1 h 0 min");
    }

    @Test
    @DisplayName("a clock skew reads as just now rather than as a negative on screen")
    void clockSkew() {
        assertThat(ConsoleClients.since(NOW.plusSeconds(30), NOW)).isEqualTo("just now");
        assertThat(ConsoleClients.since(null, NOW)).isEqualTo("unknown");
        assertThat(ConsoleClients.since(NOW, null)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("an empty table says what to do, because empty is normal before a demo")
    void emptyState() {
        assertThat(ConsoleClients.emptyStateText(0))
                .contains("Give students the address above")
                .contains("find this server by themselves");
        assertThat(ConsoleClients.emptyStateText(3)).isEmpty();
    }

    @Test
    @DisplayName("a lookup that knows nobody is a supported wiring")
    void noNames() {
        List<ConsoleClients.Row> rows = new ConsoleClients(ConsoleClients.UserNames.NONE)
                .rows(List.of(client(1001, Role.TEACHER, "10.0.0.1", Duration.ZERO)), NOW);

        assertThat(rows).singleElement()
                .extracting(ConsoleClients.Row::user).isEqualTo(ConsoleClients.UNKNOWN_USER);
    }

    @Test
    @DisplayName("arguments are required")
    void required() {
        ConsoleClients clients = new ConsoleClients(NAMES);

        assertThatNullPointerException().isThrownBy(() -> new ConsoleClients(null));
        assertThatNullPointerException().isThrownBy(() -> clients.rows(null, NOW));
        assertThatNullPointerException().isThrownBy(() -> clients.rows(List.of(), null));
    }
}
