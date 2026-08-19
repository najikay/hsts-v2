package server.core;

import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.events.PushEventBridge;
import client.events.ServerPushEvent;
import client.net.HSTSClient;
import client.net.RequestDispatcher;
import common.dto.auth.Role;
import common.dto.bank.Question;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.greenrobot.eventbus.Subscribe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import server.db.QuestionDAO;
import server.features.bank.LegacyQuestionHandlers;
import server.realtime.PushGateway;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * End-to-end protocol test over a real TCP loopback socket.
 *
 * <p>Everything else in E1 is unit-tested in isolation; this one boots the
 * actual {@link HSTSServer} on a free port, connects a real {@link HSTSClient},
 * and drives the prototype's question flow through the whole v2 stack —
 * {@code RequestDispatcher → OCSF socket → MessageRouter → handler → DAO} and
 * back — plus one server-initiated push through {@link PushGateway} to the
 * client's event bus. Only {@link QuestionDAO} is mocked, so the suite needs no
 * MySQL and runs anywhere (the DB itself is E2's problem, and no database was
 * available in this environment).
 *
 * <p>This is the automated stand-in for "we ran the demo again after the
 * rewrite": if the phase-3 list/edit flow ever breaks on the wire, it fails here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Timeout(30)
class ProtocolLoopbackTest {

    private static final long STUDENT_ID = 42L;

    @Mock
    private QuestionDAO questionDAO;

    private HSTSServer server;
    private HSTSClient client;
    private RequestDispatcher dispatcher;
    private PushCollector pushes;

    @BeforeEach
    void startServerAndConnect() throws Exception {
        int port = freePort();
        SessionManager sessions = new SessionManager();
        MessageRouter router = new MessageRouter(sessions);
        new LegacyQuestionHandlers(questionDAO).registerOn(router);
        server = new HSTSServer(port, sessions, router);
        server.listen();

        ClientEventBus eventBus = new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster());
        pushes = new PushCollector();
        eventBus.register(pushes);

        client = new HSTSClient("localhost", port);
        dispatcher = new RequestDispatcher(client);
        dispatcher.setPushListener(new PushEventBridge(eventBus));
        client.setServerMessageHandler(dispatcher::dispatchIncoming);
        client.setConnectionLostHandler(dispatcher::failAllPending);
        client.connect();

        // Since E5 the question verbs require a session (they moved from
        // registerOpen to register), so this socket gets one before the flows
        // below run. Authentication itself is covered by LoginIntegrationTest;
        // here the point is still the protocol round trip.
        sessions.attach(STUDENT_ID, Role.STUDENT, awaitServerSideConnection());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null && client.isConnectionOpen()) {
            client.disconnect();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("the prototype flow still works end to end: list, edit, list again")
    void legacyQuestionFlowOverARealSocket() throws Exception {
        List<Question> bank = new ArrayList<>(List.of(
                new Question(1, "מהי בירת צרפת?", "פריז"),
                new Question(2, "2 + 2 = ?", "4")));
        when(questionDAO.getAll()).thenReturn(bank);
        when(questionDAO.update(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        // 1. The screen asks for the bank.
        Message listed = dispatcher.send(Verb.GET_ALL_QUESTIONS, null).get(15, TimeUnit.SECONDS);

        assertThat(listed.isOk()).isTrue();
        assertThat(listed.getVerb()).isEqualTo(Verb.GET_ALL_QUESTIONS);
        @SuppressWarnings("unchecked")
        List<Question> received = (List<Question>) listed.getPayload();
        assertThat(received).hasSize(2);
        assertThat(received.get(0).getQuestionText()).isEqualTo("מהי בירת צרפת?");

        // 2. The teacher edits one and saves; the answer is the refreshed list.
        Question edited = received.get(1);
        edited.setAnswer("four");
        bank.set(1, edited);

        Message saved = dispatcher.send(Verb.UPDATE_QUESTION, edited).get(15, TimeUnit.SECONDS);

        assertThat(saved.isOk()).isTrue();
        @SuppressWarnings("unchecked")
        List<Question> refreshed = (List<Question>) saved.getPayload();
        assertThat(refreshed.get(1).getAnswer()).isEqualTo("four");
        assertThat(dispatcher.pendingCount()).isZero();
    }

    @Test
    @DisplayName("a server-side failure comes back as a correlated ERROR, not a dropped connection")
    void serverFailureIsReportedAndTheSocketSurvives() throws Exception {
        when(questionDAO.update(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(questionDAO.getAll()).thenReturn(new ArrayList<>());

        Message failed = dispatcher.send(Verb.UPDATE_QUESTION, new Question(404, "gone", "gone"))
                .get(15, TimeUnit.SECONDS);

        assertThat(failed.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(client.isConnectionOpen()).isTrue();

        // The very next request on the same socket still works.
        assertThat(dispatcher.send(Verb.GET_ALL_QUESTIONS, null).get(15, TimeUnit.SECONDS).isOk()).isTrue();
    }

    @Test
    @DisplayName("an unsupported verb answers BAD_REQUEST over the wire")
    void unsupportedVerbIsAnswered() throws Exception {
        Message response = dispatcher.send(Verb.LOGIN, null).get(15, TimeUnit.SECONDS);

        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(client.isConnectionOpen()).isTrue();
    }

    @Test
    @DisplayName("a server push travels the socket and lands on the client event bus")
    void pushReachesTheClient() throws Exception {
        ConnectionToClient serverSide = awaitServerSideConnection();
        assertThat(server.sessions().attach(STUDENT_ID, Role.STUDENT, serverSide)).isTrue();

        PushGateway gateway = server.pushGateway();
        assertThat(gateway.toUser(STUDENT_ID, Verb.PUSH_NOTIFICATION, "ציון פורסם")).isTrue();

        ServerPushEvent event = pushes.received.poll(15, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.verb()).isEqualTo(Verb.PUSH_NOTIFICATION);
        assertThat(event.payload()).isEqualTo("ציון פורסם");
    }

    @Test
    @DisplayName("a dropped socket frees the session immediately (F1.3)")
    void disconnectFreesTheSession() throws Exception {
        ConnectionToClient serverSide = awaitServerSideConnection();
        server.sessions().attach(STUDENT_ID, Role.STUDENT, serverSide);

        client.disconnect();

        long deadline = System.currentTimeMillis() + 15_000;
        while (server.sessions().isOnline(STUDENT_ID) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(server.sessions().isOnline(STUDENT_ID)).isFalse();
    }

    // ===================== Helpers =======================================

    private ConnectionToClient awaitServerSideConnection() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Thread[] connections = server.getClientConnections();
            if (connections.length > 0) {
                return (ConnectionToClient) connections[0];
            }
            Thread.sleep(20);
        }
        throw new AssertionError("The server never saw the client connect");
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    /** Stands in for a screen subscribed to the push channel. */
    public static class PushCollector {
        final LinkedBlockingQueue<ServerPushEvent> received = new LinkedBlockingQueue<>();

        @Subscribe
        public void onPush(ServerPushEvent event) {
            received.add(event);
        }
    }
}
