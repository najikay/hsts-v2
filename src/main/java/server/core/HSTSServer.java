package server.core;

import common.dto.lock.LockTiming;
import ocsf.server.AbstractServer;
import ocsf.server.ConnectionToClient;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.HibernateUtil;
import server.db.QuestionDAO;
import server.db.repos.RepositoryUserDirectory;
import server.features.auth.AuthService;
import server.features.auth.UserDirectory;
import server.features.auth.UserRecord;
import server.features.bank.LegacyQuestionHandlers;
import server.features.exam.AttemptFinalizedListener;
import server.features.exam.AttemptService;
import server.features.exam.ExamStore;
import server.features.exam.ExtendService;
import server.features.exam.JpaExamStore;
import server.features.exam.MonitorService;
import server.features.exam.TimerService;
import server.features.locks.EditLockService;
import server.features.notify.JpaNotificationStore;
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
    private final ScheduledExecutorService examTimers;

    /**
     * Production wiring: session map + router + the auth, notify, lock and legacy
     * handlers, all reading through the database.
     *
     * <p><b>Requires a migrated database.</b> Constructing this opens the Hibernate
     * {@code SessionFactory} and its connection pool (see {@link #defaultRouter}), so
     * {@code DbBootstrap.migrate()} must have completed first — {@link ServerMain} is
     * where that ordering is enforced. Tests use the bring-your-own-router constructor
     * below and touch no database at all.
     */
    public HSTSServer(int port) {
        this(port, new SessionManager());
    }

    private HSTSServer(int port, SessionManager sessions) {
        super(port);
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.pushGateway = new PushGateway(sessions);
        this.lockSweeper = newDaemonThread("hsts-lock-sweeper");
        // A thread of its own, not the lock sweeper's: an expiry force-submits inside a
        // database transaction, and a slow one must not be able to delay the sweep that
        // frees a crashed teacher's edit lock (or the reverse).
        this.examTimers = newDaemonThread("hsts-exam-timers");
        this.router = defaultRouter(sessions, pushGateway, lockSweeper, examTimers);
    }

    /**
     * Test/console wiring: bring your own router (and its handlers).
     *
     * <p>This is the constructor every test uses, and deliberately: it builds no
     * directory, no store and no session factory, so a unit test of the transport or the
     * protocol never needs MySQL to be running.
     */
    public HSTSServer(int port, SessionManager sessions, MessageRouter router) {
        super(port);
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.router = Objects.requireNonNull(router, "router");
        this.pushGateway = new PushGateway(sessions);
        this.lockSweeper = null;
        this.examTimers = null;
    }

    /**
     * The production handler set: authentication first (it is what makes every
     * other verb reachable), then the cross-cutting realtime services, then the
     * feature handlers.
     *
     * <p>Both E2 seams are closed here and nowhere else: the {@link UserDirectory}
     * is a {@link RepositoryUserDirectory} over the {@code users} table, and the
     * {@link NotificationStore} is a {@link JpaNotificationStore} over
     * {@code notifications}. The in-memory implementations of both are still in
     * the tree, now as test fixtures.
     *
     * <p><b>Ordering rule — read before moving anything.</b>
     * {@link HibernateUtil#sessionFactory()} boots a HikariCP pool against
     * {@code hsts_db} on first call, which happens right here, in this method,
     * during {@code new HSTSServer(port)}. The database therefore has to exist
     * and be migrated <em>before</em> this constructor runs:
     * {@link ServerMain} calls {@code DbBootstrap.migrate()} first for exactly
     * that reason, and reordering those two lines turns a clean first boot into a
     * pool failing against a schema that is not there yet.
     *
     * <p>Order matters in one more place: {@code NotificationService} is built
     * before {@code AuthService} because the sign-in answer carries the user's
     * unread count (E17.5).
     */
    private static MessageRouter defaultRouter(SessionManager sessions, PushGateway pushGateway,
                                               ScheduledExecutorService sweeper,
                                               ScheduledExecutorService examTimers) {
        MessageRouter router = new MessageRouter(sessions);
        // One factory for both seams: the Singleton is what owns the pool, and asking
        // for it twice would still be one pool but would read as if it were two.
        SessionFactory sessionFactory = HibernateUtil.sessionFactory();
        UserDirectory directory = new RepositoryUserDirectory(sessionFactory);

        NotificationService notifications =
                new NotificationService(new JpaNotificationStore(sessionFactory), pushGateway);
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

        Clock clock = Clock.systemUTC();
        new AuthService(directory, sessions, clock, notifications::unreadCount)
                .registerOn(router);
        new LegacyQuestionHandlers(new QuestionDAO()).registerOn(router);

        registerExamFeature(router, sessions, pushGateway, notifications, sessionFactory,
                clock, examTimers);
        return router;
    }

    /**
     * Take exam, extension and live monitoring (E10/E11).
     *
     * <p>Assembled in this order because the dependencies genuinely run in it, and the one
     * place they run backwards is closed explicitly:
     *
     * <ol>
     *   <li>{@link AttemptService} owns the attempts and, with them, the
     *       {@link TimerService} — expiry <em>is</em> force-submission, so the timer has no
     *       meaning apart from the service that acts on it;</li>
     *   <li>{@link MonitorService} reads the attempt service's tracker for the C-4
     *       integrity flags;</li>
     *   <li>{@code publishTo} closes the loop: the attempt service tells the monitor when
     *       to repaint. That is the one backwards edge, and it is a setter rather than a
     *       constructor argument so neither object has to exist half-built.</li>
     * </ol>
     *
     * <p>The grading seam is {@link AttemptFinalizedListener#NO_OP} until E12 registers a
     * real one here; submissions are complete and correct without it, they are simply not
     * marked yet, and the no-op says so in the log rather than silently.
     *
     * <p>Two scheduled jobs are armed at the end, and both exist because a timer that only
     * fires while the process is up is not a timer. {@code rearmFromDatabase} re-arms every
     * live attempt from rows that outlived the previous process (ARCHITECTURE §4), and the
     * periodic sweep is the backstop for a task that was lost to a long pause or a
     * saturated executor. Expiry is a compare-and-set, so both are free to overlap.
     */
    private static void registerExamFeature(MessageRouter router, SessionManager sessions,
                                            PushGateway pushGateway, NotificationService notifications,
                                            SessionFactory sessionFactory, Clock clock,
                                            ScheduledExecutorService examTimers) {
        ExamStore examStore = new JpaExamStore(sessionFactory);

        AttemptService attempts = new AttemptService(examStore, clock,
                TimerService.Scheduler.on(examTimers), pushGateway, notifications,
                AttemptFinalizedListener.NO_OP);
        attempts.registerOn(router);

        MonitorService monitors = new MonitorService(examStore, attempts, pushGateway, clock);
        monitors.registerOn(router);
        // A teacher who closed her laptop must stop being pushed to; the same detach hook
        // that frees her edit locks drops her monitor subscriptions (E18.3's pattern).
        monitors.attachTo(sessions);
        attempts.publishTo(monitors);

        new ExtendService(examStore, attempts.timers(), monitors, pushGateway, notifications, clock)
                .registerOn(router);

        attempts.rearmFromDatabase();
        examTimers.scheduleWithFixedDelay(attempts.timers()::sweep,
                TimerService.SWEEP_INTERVAL.toMillis(), TimerService.SWEEP_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** A single daemon thread: it must never keep a shut-down JVM alive. */
    private static ScheduledExecutorService newDaemonThread(String name) {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
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
        if (examTimers != null) {
            examTimers.shutdownNow();
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
