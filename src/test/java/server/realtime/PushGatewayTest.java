package server.realtime;

import common.dto.auth.Role;
import common.protocol.Message;
import common.protocol.Status;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.core.SessionManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link PushGateway} (E1.7).
 *
 * <p>Push delivery sits inside business transactions (a grade is approved, a
 * timer expires), so the contract that matters most is what happens when it
 * <i>cannot</i> deliver: an offline recipient and a dead socket must both be
 * ordinary, silent, non-throwing outcomes — otherwise one closed laptop could
 * roll back somebody else's grade.
 */
@ExtendWith(MockitoExtension.class)
class PushGatewayTest {

    private static final long ALICE = 1L;
    private static final long BOB = 2L;
    private static final long OFFLINE = 99L;

    private SessionManager sessions;
    private PushGateway gateway;

    @Mock
    private ConnectionToClient aliceConnection;

    @Mock
    private ConnectionToClient bobConnection;

    @BeforeEach
    void setUp() {
        sessions = new SessionManager();
        gateway = new PushGateway(sessions);
    }

    @Test
    @DisplayName("toUser delivers a PUSH envelope carrying the verb and payload")
    void deliversToAnOnlineUser() throws IOException {
        sessions.attach(ALICE, Role.STUDENT, aliceConnection);

        assertThat(gateway.toUser(ALICE, Verb.PUSH_GRADE_PUBLISHED, "88")).isTrue();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(aliceConnection).sendToClient(captor.capture());
        Message push = (Message) captor.getValue();
        assertThat(push.getStatus()).isEqualTo(Status.PUSH);
        assertThat(push.getVerb()).isEqualTo(Verb.PUSH_GRADE_PUBLISHED);
        assertThat(push.getPayload()).isEqualTo("88");
        assertThat(push.getRequestId()).isNotBlank();
    }

    @Test
    @DisplayName("an offline user is skipped silently")
    void skipsOfflineUsers() {
        assertThat(gateway.toUser(OFFLINE, Verb.PUSH_NOTIFICATION, "hello")).isFalse();
    }

    @Test
    @DisplayName("a dead socket is logged and counted as undelivered, never rethrown")
    void survivesASendFailure() throws IOException {
        sessions.attach(ALICE, Role.STUDENT, aliceConnection);
        doThrow(new IOException("broken pipe")).when(aliceConnection).sendToClient(any());

        assertThatCode(() -> assertThat(gateway.toUser(ALICE, Verb.PUSH_NOTIFICATION, null)).isFalse())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a RuntimeException from the socket layer is contained too")
    void survivesARuntimeFailure() throws IOException {
        sessions.attach(ALICE, Role.STUDENT, aliceConnection);
        doThrow(new IllegalStateException("socket in a bad state")).when(aliceConnection).sendToClient(any());

        assertThat(gateway.toUser(ALICE, Verb.PUSH_NOTIFICATION, null)).isFalse();
    }

    @Test
    @DisplayName("toUsers delivers to everyone online and reports the count")
    void deliversToManyUsers() throws IOException {
        sessions.attach(ALICE, Role.STUDENT, aliceConnection);
        sessions.attach(BOB, Role.STUDENT, bobConnection);

        int delivered = gateway.toUsers(List.of(ALICE, BOB, OFFLINE), Verb.PUSH_EXECUTION_STATUS, "LIVE");

        assertThat(delivered).isEqualTo(2);
        verify(aliceConnection).sendToClient(any());
        verify(bobConnection).sendToClient(any());
    }

    @Test
    @DisplayName("toUsers collapses duplicates so nobody is notified twice")
    void collapsesDuplicateRecipients() throws IOException {
        sessions.attach(ALICE, Role.STUDENT, aliceConnection);

        int delivered = gateway.toUsers(List.of(ALICE, ALICE, ALICE), Verb.PUSH_NOTIFICATION, null);

        assertThat(delivered).isEqualTo(1);
        verify(aliceConnection).sendToClient(any());
    }

    @Test
    @DisplayName("toUsers tolerates an empty, null or null-containing recipient list")
    void toleratesEmptyRecipientLists() {
        sessions.attach(ALICE, Role.STUDENT, aliceConnection);

        assertThat(gateway.toUsers(List.of(), Verb.PUSH_NOTIFICATION, null)).isZero();
        assertThat(gateway.toUsers(null, Verb.PUSH_NOTIFICATION, null)).isZero();
        assertThat(gateway.toUsers(Arrays.asList((Long) null), Verb.PUSH_NOTIFICATION, null)).isZero();
    }

    @Test
    @DisplayName("one failing recipient does not stop the rest of the batch")
    void oneFailureDoesNotStopTheBatch() throws IOException {
        sessions.attach(ALICE, Role.STUDENT, aliceConnection);
        sessions.attach(BOB, Role.STUDENT, bobConnection);
        doThrow(new IOException("gone")).when(aliceConnection).sendToClient(any());

        int delivered = gateway.toUsers(Set.of(ALICE, BOB), Verb.PUSH_FORCE_SUBMITTED, null);

        assertThat(delivered).isEqualTo(1);
        verify(bobConnection).sendToClient(any());
    }

    @Test
    @DisplayName("broadcast reaches every signed-in user")
    void broadcastsToEveryone() throws IOException {
        sessions.attach(ALICE, Role.STUDENT, aliceConnection);
        sessions.attach(BOB, Role.TEACHER, bobConnection);

        assertThat(gateway.broadcast(Verb.PUSH_NOTIFICATION, "server restarting")).isEqualTo(2);
        verify(aliceConnection).sendToClient(any());
        verify(bobConnection).sendToClient(any());
    }

    @Test
    @DisplayName("broadcasting with nobody online delivers nothing")
    void broadcastWithNobodyOnline() {
        assertThat(gateway.broadcast(Verb.PUSH_NOTIFICATION, null)).isZero();
    }

    @Test
    @DisplayName("a detached user stops receiving pushes immediately")
    void detachedUsersStopReceiving() throws IOException {
        sessions.attach(ALICE, Role.STUDENT, aliceConnection);
        sessions.detach(aliceConnection);

        assertThat(gateway.toUser(ALICE, Verb.PUSH_NOTIFICATION, null)).isFalse();
        verify(aliceConnection, never()).sendToClient(any());
    }

    @Test
    @DisplayName("a non-push verb still goes out (logged as suspicious) — forward compatibility over strictness")
    void nonPushVerbIsStillDelivered() throws IOException {
        sessions.attach(ALICE, Role.STUDENT, aliceConnection);

        assertThat(gateway.toUser(ALICE, Verb.BANK_LIST, null)).isTrue();
        verify(aliceConnection).sendToClient(any());
    }

    @Test
    @DisplayName("it refuses to be built without a session manager, or to push a null verb")
    void validatesArguments() {
        assertThatNullPointerException().isThrownBy(() -> new PushGateway(null));
        assertThatNullPointerException().isThrownBy(() -> gateway.toUser(ALICE, null, null));
    }
}
