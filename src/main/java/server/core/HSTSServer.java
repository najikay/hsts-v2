package server.core;

import common.dto.lock.LockTiming;
import ocsf.server.AbstractServer;
import ocsf.server.ConnectionToClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.QuestionDAO;
import server.features.auth.AuthService;
import server.features.auth.InMemoryUserDirectory;
import server.features.auth.UserDirectory;
import server.features.auth.UserRecord;
import server.features.bank.LegacyQuestionHandlers;
import server.features.locks.EditLockService;
import server.features.notify.InMemoryNotificationStore;
import server.features.notify.NotificationService;
import server.features.notify.NotificationStore;
import server.realtime.PushGateway;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    /**
     * How often expired edit locks are swept (E18.1). Half the TTL: a lock that
     * lapses immediately after a sweep is noticed within twenty seconds, which
     * keeps the wait for a crashed colleague's editor under a minute without
     * waking a thread every second for a map that is usually empty.
     */
    private static final Duration LOCK_SWEEP_INTERVAL = LockTiming.TTL.dividedBy(2);

    private final SessionManager sessions;
    private final MessageRouter router;
    private final PushGateway pushGateway;
    private final ScheduledExecutorService lockSweeper;

    /** Production wiring: session map + router + the auth, notify, lock and legacy handlers. */
    public HSTSServer(int port) {
        this(port, new SessionManager());
    }

    private HSTSServer(int port, SessionManager sessions) {
        super(port);
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.pushGateway = new PushGateway(sessions);
        this.lockSweeper = newSweeperThread();
        this.router = defaultRouter(sessions, pushGateway, lockSweeper);
    }

    /** Test/console wiring: bring your own router (and its handlers). */
    public HSTSServer(int port, SessionManager sessions, MessageRouter router) {
        super(port);
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.router = Objects.requireNonNull(router, "router");
        this.pushGateway = new PushGateway(sessions);
        this.lockSweeper = null;
    }

    /**
     * The production handler set: authentication first (it is what makes every
     * other verb reachable), then the cross-cutting realtime services, then the
     * feature handlers.
     *
     * <p>Two seams E2 replaces here and nowhere else: the
     * {@link InMemoryUserDirectory} (see {@link UserDirectory}) and the
     * {@link InMemoryNotificationStore} (see {@link NotificationStore}).
     *
     * <p>Order matters in one place: {@code NotificationService} is built before
     * {@code AuthService} because the sign-in answer carries the user's unread
     * count (E17.5).
     */
    private static MessageRouter defaultRouter(SessionManager sessions, PushGateway pushGateway,
                                               ScheduledExecutorService sweeper) {
        MessageRouter router = new MessageRouter(sessions);
        InMemoryUserDirectory directory = new InMemoryUserDirectory();

        NotificationService notifications =
                new NotificationService(new InMemoryNotificationStore(), pushGateway);
        notifications.registerOn(router);

        EditLockService locks = new EditLockService(pushGateway,
                userId -> directory.findById(userId).map(UserRecord::displayName));
        locks.registerOn(router);
        // Logout and a dropped socket both end a session, and both must free that
        // session's locks (E18.3) — one hook covers both because SessionManager
        // funnels them through one detach.
        locks.attachTo(sessions);
        sweeper.scheduleWithFixedDelay(locks::sweepExpired,
                LOCK_SWEEP_INTERVAL.toMillis(), LOCK_SWEEP_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);

        new AuthService(directory, sessions, Clock.systemUTC(), notifications::unreadCount)
                .registerOn(router);
        new LegacyQuestionHandlers(new QuestionDAO()).registerOn(router);
        return router;
    }

    /** A single daemon thread: it must never keep a shut-down JVM alive. */
    private static ScheduledExecutorService newSweeperThread() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hsts-lock-sweeper");
            thread.setDaemon(true);
            return thread;
        });
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
        if (lockSweeper != null) {
            lockSweeper.shutdownNow();
        }
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
