package server.core;

import common.dto.auth.Role;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.when;

/**
 * The two accessors E19.3 added to the session map: a read-only client snapshot
 * and a repaint listener.
 *
 * <p>Deliberately minimal additions, and this is where "minimal" is checked: the
 * snapshot is a copy so a console iterating it cannot race an OCSF thread, and the
 * listener carries nothing so a view cannot come to depend on which of attach or
 * detach fired.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionManagerConsoleTest {

    private static final Instant T0 = Instant.parse("2026-08-20T09:00:00Z");

    @Mock
    private ConnectionToClient danaSocket;
    @Mock
    private ConnectionToClient rinaSocket;

    private MutableClock clock;
    private SessionManager sessions;

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(T0);
        sessions = new SessionManager(clock);
        when(danaSocket.getInetAddress()).thenReturn(InetAddress.getByName("192.168.1.51"));
        when(rinaSocket.getInetAddress()).thenReturn(InetAddress.getByName("192.168.1.52"));
    }

    @Test
    @DisplayName("a snapshot carries the user, the role, the address and when they connected")
    void snapshot() {
        sessions.attach(1001L, Role.TEACHER, danaSocket);
        clock.advance(Duration.ofMinutes(4));
        sessions.attach(1002L, Role.STUDENT, rinaSocket);

        List<SessionManager.ConnectedClient> clients = sessions.connectedClients();

        assertThat(clients).containsExactly(
                new SessionManager.ConnectedClient(1001L, Role.TEACHER, "192.168.1.51", T0),
                new SessionManager.ConnectedClient(1002L, Role.STUDENT, "192.168.1.52",
                        T0.plus(Duration.ofMinutes(4))));
    }

    @Test
    @DisplayName("rows are ordered oldest first, so a one-second refresh does not reshuffle")
    void stableOrder() {
        clock.advance(Duration.ofMinutes(1));
        sessions.attach(1002L, Role.STUDENT, rinaSocket);
        clock.advance(Duration.ofMinutes(1));
        sessions.attach(1001L, Role.TEACHER, danaSocket);

        assertThat(sessions.connectedClients())
                .extracting(SessionManager.ConnectedClient::userId)
                .containsExactly(1002L, 1001L);
    }

    @Test
    @DisplayName("the snapshot is a copy, so a console iterating it cannot race a login")
    void snapshotIsACopy() {
        sessions.attach(1001L, Role.TEACHER, danaSocket);

        List<SessionManager.ConnectedClient> before = sessions.connectedClients();
        sessions.attach(1002L, Role.STUDENT, rinaSocket);

        assertThat(before).hasSize(1);
        assertThat(sessions.connectedClients()).hasSize(2);
    }

    @Test
    @DisplayName("a detached session leaves the table")
    void detachRemovesTheRow() {
        sessions.attach(1001L, Role.TEACHER, danaSocket);
        sessions.attach(1002L, Role.STUDENT, rinaSocket);

        sessions.detach(danaSocket);

        assertThat(sessions.connectedClients())
                .extracting(SessionManager.ConnectedClient::userId).containsExactly(1002L);

        sessions.detachUser(1002L);
        assertThat(sessions.connectedClients()).isEmpty();
    }

    @Test
    @DisplayName("a socket that cannot say where it came from renders as unknown")
    void unknownAddress() {
        when(danaSocket.getInetAddress()).thenReturn(null);
        sessions.attach(1001L, Role.TEACHER, danaSocket);

        assertThat(sessions.connectedClients()).singleElement()
                .extracting(SessionManager.ConnectedClient::remoteAddress)
                .as("a row reading unknown beats a table that throws while painting")
                .isEqualTo(SessionManager.ConnectedClient.UNKNOWN_ADDRESS);
    }

    @Test
    @DisplayName("a socket that throws while being asked renders as unknown too")
    void addressThrows() {
        when(danaSocket.getInetAddress()).thenThrow(new IllegalStateException("closed"));
        sessions.attach(1001L, Role.TEACHER, danaSocket);

        assertThat(sessions.connectedClients()).singleElement()
                .extracting(SessionManager.ConnectedClient::remoteAddress)
                .isEqualTo(SessionManager.ConnectedClient.UNKNOWN_ADDRESS);
    }

    @Test
    @DisplayName("a session before its role is known still gets a row")
    void roleNotYetKnown() {
        sessions.attach(1001L, danaSocket);

        assertThat(sessions.connectedClients()).singleElement()
                .extracting(SessionManager.ConnectedClient::role).isNull();
    }

    @Test
    @DisplayName("the listener fires on login and on logout")
    void listenerFires() {
        AtomicInteger changes = new AtomicInteger();
        sessions.addSessionListener(changes::incrementAndGet);

        sessions.attach(1001L, Role.TEACHER, danaSocket);
        sessions.detach(danaSocket);

        assertThat(changes.get())
                .as("both directions repaint the table")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a refused duplicate login raises nothing, because nothing changed")
    void refusedAttachIsQuiet() {
        sessions.attach(1001L, Role.TEACHER, danaSocket);
        AtomicInteger changes = new AtomicInteger();
        sessions.addSessionListener(changes::incrementAndGet);

        assertThat(sessions.attach(1001L, Role.TEACHER, rinaSocket)).isFalse();
        assertThat(sessions.detach(rinaSocket)).isEmpty();

        assertThat(changes.get()).isZero();
    }

    @Test
    @DisplayName("a forced logout repaints too")
    void detachUserFires() {
        sessions.attach(1001L, Role.TEACHER, danaSocket);
        AtomicInteger changes = new AtomicInteger();
        sessions.addSessionListener(changes::incrementAndGet);

        assertThat(sessions.detachUser(1001L)).isTrue();
        assertThat(sessions.detachUser(4242L)).isFalse();

        assertThat(changes.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a listener that throws must not break the login that triggered it")
    void listenerFailureIsContained() {
        AtomicInteger good = new AtomicInteger();
        sessions.addSessionListener(() -> {
            throw new IllegalStateException("console is repainting badly");
        });
        sessions.addSessionListener(good::incrementAndGet);

        assertThat(sessions.attach(1001L, Role.TEACHER, danaSocket))
                .as("the server outranks the window watching it")
                .isTrue();
        assertThat(good.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a listener can be removed, and a closed console must remove its own")
    void removingAListener() {
        AtomicInteger changes = new AtomicInteger();
        SessionManager.SessionListener listener = changes::incrementAndGet;
        sessions.addSessionListener(listener);

        assertThat(sessions.removeSessionListener(listener)).isTrue();
        assertThat(sessions.removeSessionListener(listener)).isFalse();

        sessions.attach(1001L, Role.TEACHER, danaSocket);
        assertThat(changes.get()).isZero();
    }

    @Test
    @DisplayName("collaborators are required")
    void required() {
        assertThatNullPointerException().isThrownBy(() -> new SessionManager((Clock) null));
        assertThatNullPointerException().isThrownBy(() -> sessions.addSessionListener(null));
        assertThatNullPointerException().isThrownBy(() ->
                new SessionManager.ConnectedClient(1L, Role.TEACHER, "1.2.3.4", null));
    }

    @Test
    @DisplayName("the default constructor still works, on the system clock")
    void defaultConstructor() {
        SessionManager live = new SessionManager();
        live.attach(1001L, Role.TEACHER, danaSocket);

        assertThat(live.connectedClients()).singleElement()
                .extracting(SessionManager.ConnectedClient::connectedSince)
                .isNotNull();
    }

    /** A clock a test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
