package server.features.auth;

import client.net.HSTSClient;
import client.net.RequestDispatcher;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginRequest;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import server.core.HSTSServer;
import server.core.MessageRouter;
import server.core.SessionManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole authentication path over a real TCP loopback socket (E5.8).
 *
 * <p>Sibling of {@code ProtocolLoopbackTest}: a real {@link HSTSServer} on a free
 * port, real {@link HSTSClient}s, no mocks below the {@link UserDirectory} seam.
 * It is the automated version of the two-machine demo — sign in, sign in again
 * from a second client, pull a plug — and it is the only place where the
 * client's dispatcher, the socket, the router's authentication check, the
 * service and the session map are all exercised together.
 */
@Timeout(60)
class LoginIntegrationTest {

    private static final String PASSWORD = "demo123";
    private static final String TEACHER = "dana.cohen";

    private HSTSServer server;
    private SessionManager sessions;
    private final List<HSTSClient> clients = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        sessions = new SessionManager();
        MessageRouter router = new MessageRouter(sessions);
        new AuthService(new InMemoryUserDirectory(PASSWORD), sessions).registerOn(router);
        server = new HSTSServer(freePort(), sessions, router);
        server.listen();
    }

    @AfterEach
    void stopServer() throws Exception {
        for (HSTSClient client : clients) {
            if (client.isConnectionOpen()) {
                client.disconnect();
            }
        }
        server.close();
    }

    @Test
    @DisplayName("a correct password answers with the login result the shell boots from")
    void successCarriesTheShellPayload() throws Exception {
        RequestDispatcher dispatcher = connectClient();

        Message response = login(dispatcher, TEACHER, PASSWORD);

        assertThat(response.isOk()).isTrue();
        LoginResult result = (LoginResult) response.getPayload();
        assertThat(result.username()).isEqualTo(TEACHER);
        assertThat(result.displayName()).isEqualTo("Dana Cohen");
        assertThat(result.role()).isEqualTo(Role.TEACHER);
        assertThat(result.courses()).extracting(CourseRef::code).containsExactly("11", "12");
        assertThat(sessions.isOnline(result.userId())).isTrue();
    }

    @Test
    @DisplayName("a wrong password answers UNAUTHORIZED with the generic sentence")
    void wrongPasswordIsGeneric() throws Exception {
        RequestDispatcher dispatcher = connectClient();

        Message response = login(dispatcher, TEACHER, "nope");

        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(response.errorMessage()).isEqualTo(AuthService.GENERIC_FAILURE);
        assertThat(sessions.onlineCount()).isZero();
    }

    @Test
    @DisplayName("the sixth attempt is throttled even with the right password (F1.1)")
    void throttleAfterFiveFailures() throws Exception {
        RequestDispatcher dispatcher = connectClient();

        for (int attempt = 0; attempt < LoginThrottle.MAX_FAILURES; attempt++) {
            assertThat(login(dispatcher, "maya.levi", "wrong").errorMessage())
                    .isEqualTo(AuthService.GENERIC_FAILURE);
        }

        Message throttled = login(dispatcher, "maya.levi", PASSWORD);

        assertThat(throttled.isError()).isTrue();
        assertThat(throttled.errorMessage()).isEqualTo(AuthService.THROTTLED_FAILURE);
    }

    @Test
    @DisplayName("a second client signing in as the same user is refused (F1.3)")
    void duplicateLoginFromASecondClient() throws Exception {
        RequestDispatcher first = connectClient();
        RequestDispatcher second = connectClient();

        assertThat(login(first, TEACHER, PASSWORD).isOk()).isTrue();
        Message refused = login(second, TEACHER, PASSWORD);

        assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(refused.errorMessage()).isEqualTo("This account is already signed in elsewhere.");
        assertThat(sessions.onlineCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a dropped socket frees the session — the second client can then sign in")
    void disconnectFreesTheSession() throws Exception {
        RequestDispatcher first = connectClient();
        RequestDispatcher second = connectClient();
        long userId = ((LoginResult) login(first, TEACHER, PASSWORD).getPayload()).userId();

        clients.get(0).disconnect();
        awaitOffline(userId);

        assertThat(login(second, TEACHER, PASSWORD).isOk()).isTrue();
    }

    @Test
    @DisplayName("LOGOUT frees the session — the same client can sign back in")
    void logoutFreesTheSession() throws Exception {
        RequestDispatcher dispatcher = connectClient();
        long userId = ((LoginResult) login(dispatcher, TEACHER, PASSWORD).getPayload()).userId();

        assertThat(dispatcher.send(Verb.LOGOUT, null).get(15, TimeUnit.SECONDS).isOk()).isTrue();
        assertThat(sessions.isOnline(userId)).isFalse();

        assertThat(login(dispatcher, TEACHER, PASSWORD).isOk()).isTrue();
    }

    @Test
    @DisplayName("LOGOUT before signing in is refused by the router, and the socket survives")
    void logoutNeedsASession() throws Exception {
        RequestDispatcher dispatcher = connectClient();

        Message response = dispatcher.send(Verb.LOGOUT, null).get(15, TimeUnit.SECONDS);

        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(login(dispatcher, TEACHER, PASSWORD).isOk()).isTrue();
    }

    // ===================== Helpers =======================================

    private Message login(RequestDispatcher dispatcher, String username, String password)
            throws Exception {
        return dispatcher.send(Verb.LOGIN, new LoginRequest(username, password))
                .get(15, TimeUnit.SECONDS);
    }

    private RequestDispatcher connectClient() throws IOException {
        HSTSClient client = new HSTSClient("localhost", server.getPort());
        RequestDispatcher dispatcher = new RequestDispatcher(client);
        client.setServerMessageHandler(dispatcher::dispatchIncoming);
        client.setConnectionLostHandler(dispatcher::failAllPending);
        client.connect();
        clients.add(client);
        return dispatcher;
    }

    private void awaitOffline(long userId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (sessions.isOnline(userId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(sessions.isOnline(userId)).as("the dropped socket freed the session").isFalse();
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
