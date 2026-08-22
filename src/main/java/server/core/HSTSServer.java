package server.core;

import common.dto.lock.EntityRef;
import common.dto.lock.LockTiming;
import ocsf.server.AbstractServer;
import ocsf.server.ConnectionToClient;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.HibernateUtil;
import server.db.QuestionDAO;
import server.db.Transactions;
import server.db.ids.QuestionIdAllocator;
import server.db.repos.AttemptRepository;
import server.db.repos.CourseRepository;
import server.db.repos.ExamRepository;
import server.db.repos.ExecutionRepository;
import server.db.repos.GradeRepository;
import server.db.repos.QuestionRepository;
import server.db.repos.RepositoryUserDirectory;
import server.db.repos.UserRepository;
import server.features.approval.ApprovalService;
import server.features.approval.JpaApprovalStore;
import server.features.auth.AuthService;
import server.features.auth.UserDirectory;
import server.features.auth.UserRecord;
import server.features.bank.BankBrowseService;
import server.features.bank.BankHandlers;
import server.features.bank.BankReadHandlers;
import server.features.bank.LegacyQuestionHandlers;
import server.features.bank.QuestionService;
import server.features.bot.AskRateLimiter;
import server.features.bot.BotAdminService;
import server.features.bot.BotConfig;
import server.features.bot.BotService;
import server.features.bot.BotStore;
import server.features.bot.ContextBuilder;
import server.features.bot.JpaBotStore;
import server.features.bot.ProviderChain;
import server.features.bot.SourceExtractor;
import server.features.exam.AttemptFinalizedListener;
import server.features.exam.AttemptService;
import server.features.exam.ExamStore;
import server.features.exam.ExtendService;
import server.features.exam.JpaExamStore;
import server.features.exam.MonitorService;
import server.features.exam.TimerService;
import server.features.grading.GradeApprovalService;
import server.features.grading.GradeReviewService;
import server.features.grading.GradingHandlers;
import server.features.grading.GradingOnSubmit;
import server.features.grading.GradingQueueService;
import server.features.grading.GradingReads;
import server.features.grading.GradingService;
import server.features.grading.OverrideService;
import server.features.grading.RepositoryGradingReads;
import server.features.locks.EditLockService;
import server.features.notify.JpaNotificationStore;
import server.features.notify.NotificationService;
import server.features.notify.NotificationStore;
import server.features.results.CheckedFormService;
import server.features.results.JpaTeacherResultsStore;
import server.features.results.ResultsHandlers;
import server.features.results.ResultsService;
import server.features.results.TeacherResultsService;
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
     * The two collaborators the server console reads for its health cards
     * (E19.2), captured here because {@link #defaultRouter} is where they are
     * built and nothing else keeps a reference. Both are {@code null} on the
     * bring-your-own-router constructor, and the console's probes report that
     * honestly rather than by failing.
     */
    private SessionFactory sessionFactory;
    private ProviderChain providerChain;
    private UserDirectory userDirectory;

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
    private MessageRouter defaultRouter(SessionManager sessions, PushGateway pushGateway,
                                        ScheduledExecutorService sweeper,
                                        ScheduledExecutorService examTimers) {
        MessageRouter router = new MessageRouter(sessions);
        // One factory for both seams: the Singleton is what owns the pool, and asking
        // for it twice would still be one pool but would read as if it were two.
        SessionFactory sessionFactory = HibernateUtil.sessionFactory();
        UserDirectory directory = new RepositoryUserDirectory(sessionFactory);
        this.sessionFactory = sessionFactory;
        this.userDirectory = directory;

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

        // Grading first, because take-exam is assembled with the grader rather than the other
        // way round: the seam points from submission to marking and never back.
        AttemptFinalizedListener grader =
                registerGradingFeature(router, notifications, sessionFactory, clock);
        AttemptService attempts = registerExamFeature(router, sessions, pushGateway, notifications,
                sessionFactory, clock, examTimers, grader);
        registerBotFeature(router, notifications, locks, sessionFactory, clock, attempts);
        // Teacher results and statistics (E14). Reads only, so it depends on nothing above
        // it and nothing above it depends on it: the figures it serves were frozen when the
        // grades were approved (F8.5) and it recomputes none of them.
        new TeacherResultsService(new JpaTeacherResultsStore(sessionFactory)).registerOn(router);
        registerApprovalFeature(router, notifications, sessionFactory);
        // The question bank's write verbs (E6.1, E6.3, E6.4). Assembled last, and the only
        // things above it that it uses are the sessionFactory and clock locals: its guards ask
        // the open transaction's own data through a repository lambda rather than the
        // process-wide COURSE_TEACHERS seam, so it holds no snapshot that could go stale
        // against an ordering change.
        //
        // This does NOT replace LegacyQuestionHandlers above. The two answer disjoint verbs
        // (GET_ALL_QUESTIONS / UPDATE_QUESTION against QUESTION_CREATE / UPDATE / DELETE), and
        // the legacy pair is retired in its own PR (contract §7.4). Keep them disjoint:
        // MessageRouter.register throws on a duplicate verb rather than overwriting, and it
        // throws here, inside the HSTSServer(port) constructor, which no test calls and which
        // JaCoCo excludes. A collision is therefore not a red build, it is a server that will
        // not boot, found by a human. The read line below is the second bank registration this
        // comment anticipated; its verbs are disjoint from both of the others.
        //
        // BankWiringGuardTest reads these statements out of the source and fails if either stops
        // constructing-and-registering in one expression. It is literal on purpose: extracting
        // this into a registerBankFeature helper will fail it even if the helper is called.
        new BankHandlers(sessionFactory,
                new QuestionService(new QuestionRepository(), new CourseRepository(),
                        new UserRepository(), new QuestionIdAllocator(), clock))
                .registerOn(router);
        // The bank's read verbs (E6.3, E6.5, E6.6), separate from the writes above because they
        // carry a different guard over a wider role set: reachesCourse, and the principal reads
        // where she may not write (F9.3). The split is the contract's section 3 and the reason
        // the two handler classes exist rather than one.
        new BankReadHandlers(sessionFactory,
                new BankBrowseService(new QuestionRepository(), new CourseRepository(),
                        new UserRepository()))
                .registerOn(router);
        return router;
    }

    /**
     * Grading and student results (E12/E13), and the wire that makes the pipeline real.
     *
     * <p>Everything here is assembled from repositories rather than from a store interface,
     * which is the pattern E12 was written to: the services take a session and hold no
     * transaction of their own, so their rules are provable against mocks and the transaction
     * boundary is drawn once, at the handlers.
     *
     * <p>Three things are registered and one is returned.
     *
     * <ul>
     *   <li>{@link GradingHandlers} puts the three teacher verbs on the router behind one
     *       shared authorization shape;</li>
     *   <li>{@link ResultsHandlers} puts the student verb on, behind a deliberately different
     *       one — the caller's id is the query, not a check;</li>
     *   <li>{@link GradingOnSubmit} is <b>returned</b> rather than registered, because it is not
     *       a verb. It is the {@link AttemptFinalizedListener} that take-exam calls when an
     *       attempt closes, and handing it back is what replaces the no-op that has been
     *       standing in for E12 since E10 shipped.</li>
     * </ul>
     *
     * <p>The order matters exactly once: the returned listener has to exist before
     * {@link #registerExamFeature} builds the {@link AttemptService} that will call it. Every
     * other dependency here runs the same direction, so there is no backwards edge to close.
     *
     * @return the listener take-exam should notify when an attempt is finalised
     */
    private static AttemptFinalizedListener registerGradingFeature(MessageRouter router,
                                                                   NotificationService notifications,
                                                                   SessionFactory sessionFactory,
                                                                   Clock clock) {
        GradeRepository grades = new GradeRepository();
        AttemptRepository attempts = new AttemptRepository();
        ExecutionRepository executions = new ExecutionRepository();
        ExamRepository exams = new ExamRepository();
        QuestionRepository questions = new QuestionRepository();
        UserRepository users = new UserRepository();

        GradingReads reads = new RepositoryGradingReads(executions, exams, questions, attempts);
        GradeReviewService reviews =
                new GradeReviewService(grades, attempts, executions, reads, questions, users);

        new GradingHandlers(sessionFactory,
                new GradeApprovalService(grades, attempts, executions, notifications, clock),
                new OverrideService(reviews),
                reviews,
                new GradingQueueService(executions, grades, attempts)).registerOn(router);

        ResultsService studentResults = new ResultsService(grades, users);
        new ResultsHandlers(sessionFactory, studentResults,
                new CheckedFormService(studentResults, reviews, attempts, executions, users))
                .registerOn(router);

        return new GradingOnSubmit(sessionFactory, new GradingService(reads, grades), attempts);
    }

    /**
     * Exam approval (E8) and the guard it finally implements.
     *
     * <p>Two lines, and the second one is the interesting half.
     * {@code Authorization.requireCoordinatorOf} was declared in E3.5 and left failing closed
     * until the repositories arrived; this is where it is given the {@code coordinators} table
     * it was waiting for. Installed once, here, because that guard is static by design and
     * every other call site in the product should be able to use it without threading a
     * repository through five constructors.
     *
     * <p>{@link ApprovalService} itself does <b>not</b> use the installed directory: it holds
     * a transaction of its own on every verb and reads the binding through that, so its rules
     * stay testable with a two-line lambda and never depend on process-wide state. The
     * installation is for everybody else.
     */
    private void registerApprovalFeature(MessageRouter router, NotificationService notifications,
                                         SessionFactory sessionFactory) {
        CourseRepository courses = new CourseRepository();
        Authorization.useSubjectCoordinators((teacherId, subjectCode) ->
                Transactions.inTx(sessionFactory,
                        session -> courses.coordinates(session, teacherId, subjectCode)));
        // E6's write gate (requireTeachesCourse) resolves through the same repository,
        // wired beside its sibling so the two course-scoped guards read as a pair.
        Authorization.useCourseTeachers((teacherId, courseCode) ->
                Transactions.inTx(sessionFactory,
                        session -> courses.teaches(session, teacherId, courseCode)));

        new ApprovalService(new JpaApprovalStore(sessionFactory), notifications)
                .registerOn(router);
    }

    /**
     * The study bot (E16 ⚑).
     *
     * <p>Assembled last, and the order is not arbitrary: it takes the attempt
     * service as its {@code AttemptTracker} (C-4) and the lock service for its
     * source editor (E18.5), so both have to exist first. It takes neither as an
     * implementation — the bot codes against the two seams, which is what lets its
     * whole rule set be unit-tested without a database, a socket or a live exam.
     *
     * <p>Two things happen here that are worth finding again later. The provider
     * configuration is read once and its summary logged once (F12.8: a missing key
     * is one clear line at boot, not a surprise at demo time), and the advisory
     * lock is bridged into the admin service as a predicate rather than as a
     * dependency, so a teacher cannot delete a source a colleague is holding.
     *
     * <p>If no provider is configured the feature still registers and still works:
     * every question answers with the S-32 sentence, history and analytics behave
     * normally, and the log says why. A server that refused to start over a missing
     * bot key would be a worse failure than the one it was trying to prevent.
     */
    private void registerBotFeature(MessageRouter router, NotificationService notifications,
                                    EditLockService locks, SessionFactory sessionFactory,
                                    Clock clock, AttemptService attempts) {
        BotConfig config = BotConfig.load();
        config.logSummary();

        BotStore botStore = new JpaBotStore(sessionFactory);
        ProviderChain chain = ProviderChain.of(config, clock);
        this.providerChain = chain;

        new BotService(botStore, chain, new ContextBuilder(), attempts,
                new AskRateLimiter(config.asksPerMinute(), clock), clock)
                .registerOn(router);

        BotAdminService.SourceLocks sourceLocks = (sourceId, userId) ->
                locks.holderOf(new EntityRef(EntityRef.BOT_SOURCE, sourceId))
                        .map(holder -> holder.is(userId))
                        .orElse(true);
        new BotAdminService(botStore, new SourceExtractor(), notifications, sourceLocks, clock)
                .registerOn(router);
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
    private static AttemptService registerExamFeature(MessageRouter router, SessionManager sessions,
                                            PushGateway pushGateway, NotificationService notifications,
                                            SessionFactory sessionFactory, Clock clock,
                                            ScheduledExecutorService examTimers,
                                            AttemptFinalizedListener grader) {
        ExamStore examStore = new JpaExamStore(sessionFactory);

        AttemptService attempts = new AttemptService(examStore, clock,
                TimerService.Scheduler.on(examTimers), pushGateway, notifications, grader);
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
        // Returned rather than kept: the study bot's C-4 seam IS this service
        // (AttemptTracker), and handing it over here is the one wire between the
        // two features.
        return attempts;
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

    /**
     * The live Hibernate factory, for the console's database health card (E19.2).
     *
     * @return the factory this server's handlers read through, or {@code null} on
     *         a test-wired server that opened no pool
     */
    public SessionFactory sessionFactory() {
        return sessionFactory;
    }

    /**
     * The bot provider chain, for the console's provider health card (E19.2).
     *
     * @return the chain, or {@code null} on a test-wired server with no bot
     */
    public ProviderChain providerChain() {
        return providerChain;
    }

    /**
     * The user directory, for the console's client table (E19.3): the session map
     * holds ids, and the table shows names.
     *
     * @return the directory, or {@code null} on a test-wired server
     */
    public UserDirectory userDirectory() {
        return userDirectory;
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
