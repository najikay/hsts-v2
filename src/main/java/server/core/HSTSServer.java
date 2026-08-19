package server.core;

import ocsf.server.AbstractServer;
import ocsf.server.ConnectionToClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.QuestionDAO;
import server.features.auth.AuthService;
import server.features.auth.InMemoryUserDirectory;
import server.features.auth.UserDirectory;
import server.features.bank.LegacyQuestionHandlers;
import server.realtime.PushGateway;

import java.util.Objects;

/**
 * The OCSF listener (Logic tier, E3.2) — and nothing else.
 *
 * <p>Every decision moved out in E1: inbound objects go straight to
 * {@link MessageRouter}, connection lifecycle events go to {@link SessionManager}
 * (a dropped socket frees the session immediately, F1.3). What is left is
 * transport glue with no branches worth testing, which is why this class is the
 * one server class excluded from the coverage gate.
 */
public class HSTSServer extends AbstractServer {

    private static final Logger log = LoggerFactory.getLogger(HSTSServer.class);

    private final SessionManager sessions;
    private final MessageRouter router;
    private final PushGateway pushGateway;

    /** Production wiring: session map + router + the auth and legacy bank handlers. */
    public HSTSServer(int port) {
        this(port, new SessionManager());
    }

    private HSTSServer(int port, SessionManager sessions) {
        this(port, sessions, defaultRouter(sessions));
    }

    /** Test/console wiring: bring your own router (and its handlers). */
    public HSTSServer(int port, SessionManager sessions, MessageRouter router) {
        super(port);
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.router = Objects.requireNonNull(router, "router");
        this.pushGateway = new PushGateway(sessions);
    }

    /**
     * The production handler set: authentication first (it is what makes every
     * other verb reachable), then the feature handlers.
     *
     * <p>The {@link InMemoryUserDirectory} here is the one line E2 PR3 replaces
     * with the repository-backed adapter — see {@link UserDirectory}.
     */
    private static MessageRouter defaultRouter(SessionManager sessions) {
        MessageRouter router = new MessageRouter(sessions);
        new AuthService(new InMemoryUserDirectory(), sessions).registerOn(router);
        new LegacyQuestionHandlers(new QuestionDAO()).registerOn(router);
        return router;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public MessageRouter router() {
        return router;
    }

    public PushGateway pushGateway() {
        return pushGateway;
    }

    // ===== OCSF callbacks =================================================

    @Override
    protected void handleMessageFromClient(Object msg, ConnectionToClient client) {
        router.handle(msg, client);
    }

    @Override
    protected void serverStarted() {
        log.info("Server started, listening on port {}", getPort());
    }

    @Override
    protected void serverStopped() {
        log.info("Server has stopped listening for connections.");
    }

    @Override
    protected void clientConnected(ConnectionToClient client) {
        log.info("Client connected: {}", client);
    }

    @Override
    protected synchronized void clientDisconnected(ConnectionToClient client) {
        sessions.detach(client).ifPresent(userId -> log.info("Freed session of user {}", userId));
        log.info("Client disconnected: {}", client);
    }

    @Override
    protected synchronized void clientException(ConnectionToClient client, Throwable exception) {
        sessions.detach(client);
        log.warn("Client connection exception ({}): {}", client, exception.toString());
    }
}
