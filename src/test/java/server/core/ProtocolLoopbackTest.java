package server.core;

import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.events.PushEventBridge;
import client.events.ServerPushEvent;
import client.net.HSTSClient;
import client.net.RequestDispatcher;
import common.dto.auth.Role;
import common.dto.bank.QuestionRequest;
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
import server.realtime.PushGateway;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end protocol test over a real TCP loopback socket.
 *
 * <p>Everything else in E1 is unit-tested in isolation; this one boots the
 * actual {@link HSTSServer} on a free port, connects a real {@link HSTSClient},
 * and drives a request/response pair through the whole v2 stack —
 * {@code RequestDispatcher → OCSF socket → MessageRouter → handler} and back —
 * plus one server-initiated push through {@link PushGateway} to the client's
 * event bus.
 *
 * <h2>Why the handler is a stub written here</h2>
 *
 * <p>It used to be {@code LegacyQuestionHandlers} over a mocked {@code QuestionDAO}, because the
 * prototype's list/edit flow was the one flow that worked end to end and this suite was the
 * automated stand-in for "we ran the demo again after the rewrite". Both of those retired with
 * the legacy screen.
 *
 * <p>Nothing real replaced them, deliberately. <b>What is under test here is the transport, not
 * any feature</b>: that a verb reaches its handler over a socket, that the answer is correlated
 * back to the right future, that a handler's error crosses as an ERROR rather than as a dropped
 * connection, and that a push arrives unsolicited. A stub makes those four the only things that
 * can fail. Wiring in the real bank would drag in Hibernate and a live MySQL to prove a property
 * neither of them is involved in, and a red here would no longer mean the socket is broken —
 * which is the one thing this file exists to tell us. The bank's own behaviour is covered by
 * {@code BankHandlersTest}, {@code QuestionServiceTest} and the MySQL leaves.
 */
@Timeout(30)
class ProtocolLoopbackTest {

    private static final long STUDENT_ID = 42L;

    /** The id the echo handler answers about, so a response can be told from an echo of nothing. */
    private static final String DISPLAY_ID = "21014";

    private HSTSServer server;
    private HSTSClient client;
    private RequestDispatcher dispatcher;
    private PushCollector pushes;

    @BeforeEach
    void startServerAndConnect() throws Exception {
        int port = freePort();
        SessionManager sessions = new SessionManager();
        MessageRouter router = new MessageRouter(sessions);
        registerStubHandlers(router);
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

        // The stub verbs are registered guarded rather than open, because that is how every
        // real feature verb is registered and the session lookup is part of the path under
        // test. Authentication itself is covered by LoginIntegrationTest.
        sessions.attach(STUDENT_ID, Role.TEACHER, awaitServerSideConnection());
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
    @DisplayName("a request reaches its handler over the socket and the answer is correlated back")
    void requestAndResponseTravelTheSocket() throws Exception {
        // A non-ASCII payload, because serialization across the socket is the half of this that
        // a same-JVM router test cannot exercise (the UI is bilingual).
        Message listed = dispatcher.send(Verb.BANK_LIST, "מהי בירת צרפת?").get(15, TimeUnit.SECONDS);

        assertThat(listed.isOk()).isTrue();
        assertThat(listed.getVerb()).isEqualTo(Verb.BANK_LIST);
        assertThat(listed.getPayload())
                .as("the handler saw the payload it was sent, not a mangled copy")
                .isEqualTo(List.of("מהי בירת צרפת?"));
        assertThat(dispatcher.pendingCount()).isZero();

        // A second verb on the same socket, carrying a DTO rather than a string.
        Message saved = dispatcher.send(Verb.QUESTION_UPDATE, new QuestionRequest(DISPLAY_ID))
                .get(15, TimeUnit.SECONDS);

        assertThat(saved.isOk()).isTrue();
        assertThat(saved.getPayload()).isEqualTo(DISPLAY_ID);
        assertThat(dispatcher.pendingCount()).isZero();
    }

    @Test
    @DisplayName("a server-side failure comes back as a correlated ERROR, not a dropped connection")
    void serverFailureIsReportedAndTheSocketSurvives() throws Exception {
        // A payload the stub refuses, so this drives the handler's error path rather than the
        // router's unregistered-verb path (which the case below covers).
        Message failed = dispatcher.send(Verb.QUESTION_UPDATE, null).get(15, TimeUnit.SECONDS);

        assertThat(failed.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(client.isConnectionOpen()).isTrue();

        // The very next request on the same socket still works.
        assertThat(dispatcher.send(Verb.BANK_LIST, null).get(15, TimeUnit.SECONDS).isOk()).isTrue();
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
        assertThat(server.sessions().attach(STUDENT_ID, Role.TEACHER, serverSide)).isTrue();

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
        server.sessions().attach(STUDENT_ID, Role.TEACHER, serverSide);

        client.disconnect();

        long deadline = System.currentTimeMillis() + 15_000;
        while (server.sessions().isOnline(STUDENT_ID) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(server.sessions().isOnline(STUDENT_ID)).isFalse();
    }

    // ===================== Helpers =======================================

    /**
     * Two verbs with no feature behind them: one echoes its payload back inside a list, one
     * answers about a {@link QuestionRequest} or refuses. Between them they cover an OK, an
     * ERROR and both a string and a DTO payload, which is the whole of what the transport can
     * get wrong.
     */
    private static void registerStubHandlers(MessageRouter router) {
        router.register(Verb.BANK_LIST, (caller, request) ->
                Message.ok(request, request.getPayload() == null
                        ? List.of() : List.of(request.getPayload())));
        router.register(Verb.QUESTION_UPDATE, (caller, request) -> {
            if (request.getPayload() instanceof QuestionRequest asked) {
                return Message.ok(request, asked.displayId5());
            }
            return Message.error(request, ErrorCode.NOT_FOUND,
                    "No question was named in that request.");
        });
    }

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
