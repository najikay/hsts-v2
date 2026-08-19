package server.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import server.core.ServerConfig;
import server.core.ServerConfig.Credentials;
import server.db.entities.AttemptAnswer;
import server.db.entities.Bot;
import server.db.entities.BotMessage;
import server.db.entities.BotSession;
import server.db.entities.BotSource;
import server.db.entities.Coordinator;
import server.db.entities.Course;
import server.db.entities.CourseTeacher;
import server.db.entities.Enrollment;
import server.db.entities.Exam;
import server.db.entities.ExamAttempt;
import server.db.entities.ExamExecution;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionQuestion;
import server.db.entities.Grade;
import server.db.entities.Notification;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.entities.Subject;
import server.db.entities.User;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The one {@link SessionFactory} for the server (E2.10) — Singleton, built lazily and
 * closed once at shutdown.
 *
 * <p>A {@code SessionFactory} is expensive to create and cheap to share: it holds the
 * mapping metadata and the connection pool, and is thread-safe. {@code Session} is the
 * opposite — cheap, not thread-safe, one per unit of work — which is what
 * {@link Transactions} is for. Nothing outside this class should build a factory.
 *
 * <h2>Choices worth knowing about</h2>
 *
 * <p><b>The entity list is written out</b> in {@link #ENTITY_CLASSES} rather than
 * discovered by scanning the classpath. Scanning fails quietly — a new entity that is
 * never registered simply does not exist as far as Hibernate is concerned, and the first
 * symptom is a confusing query error far from the cause. A hard-coded list turns that
 * into a compile error, and a test asserts the list still matches the package.
 *
 * <p><b>Schema management is off</b> ({@code hbm2ddl.auto = none}). Flyway owns the
 * schema; letting Hibernate near it would give two tools authority over the same tables.
 * Tests override this setting deliberately: the MySQL suite asks for {@code validate}
 * against the real migrated schema — which is the check that actually proves these
 * mappings match V1–V7 — and the H2 suite asks for {@code create}, which proves nothing
 * about the migrations and is only a fast harness for repository logic.
 *
 * <p><b>{@code jdbc.time_zone} is pinned to UTC.</b> §5 requires every stored timestamp
 * to be UTC, and MySQL {@code DATETIME} carries no zone of its own, so without this the
 * value written would depend on the server machine's locale — a demo laptop in another
 * offset would silently shift every exam window. Pinning it here, together with mapping
 * every timestamp as {@link java.time.Instant}, makes UTC a property of the system
 * rather than a rule people have to remember.
 */
public final class HibernateUtil {

    /**
     * Every entity, listed explicitly. Adding a class to {@code server.db.entities}
     * means adding it here too — {@code HibernateUtilTest} fails otherwise.
     */
    public static final List<Class<?>> ENTITY_CLASSES = List.of(
            // V1 — core
            Subject.class, Course.class, User.class,
            CourseTeacher.class, Enrollment.class, Coordinator.class,
            // V2 — bank
            Question.class, QuestionVersion.class,
            // V3 — exams
            Exam.class, ExamVersion.class, ExamVersionQuestion.class,
            // V4 — executions
            ExamExecution.class, ExamAttempt.class, AttemptAnswer.class,
            // V5 — grading
            Grade.class,
            // V6 — bot
            Bot.class, BotSource.class, BotSession.class, BotMessage.class,
            // V7 — notifications
            Notification.class);

    /** Wide enough for the OCSF read threads; a school-sized server, not a web farm. */
    private static final int RUNTIME_POOL_SIZE = 10;

    private static volatile SessionFactory instance;

    /**
     * The pool {@link #sessionFactory()} built for itself, if it built one.
     *
     * <p>Tracked because Hibernate does not close a {@link DataSource} it was handed —
     * it only closes ones it created. Closing the factory alone would leave the pool and
     * its threads alive for the life of the JVM.
     */
    private static volatile HikariDataSource ownedPool;


    private HibernateUtil() {
        // static holder — no instances
    }

    /**
     * The server's shared factory, built on first use from the same configuration
     * {@link DbBootstrap} migrates with, so a machine's MySQL login is still configured
     * in exactly one place.
     *
     * @return the singleton factory
     */
    public static SessionFactory sessionFactory() {
        SessionFactory local = instance;
        if (local == null) {
            synchronized (HibernateUtil.class) {
                local = instance;
                if (local == null) {
                    HikariDataSource pool = runtimePool();
                    try {
                        local = build(pool, Map.of());
                    } catch (RuntimeException | Error e) {
                        pool.close();
                        throw e;
                    }
                    ownedPool = pool;
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * Builds a factory over an arbitrary {@link DataSource} without touching the
     * singleton — the seam the test suites use.
     *
     * @param dataSource where connections come from
     * @param overrides  extra Hibernate settings, e.g. {@code hbm2ddl.auto=validate};
     *                   applied last, so they win
     * @return a new factory the caller owns and must close
     */
    public static SessionFactory build(DataSource dataSource, Map<String, Object> overrides) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(overrides, "overrides");

        StandardServiceRegistryBuilder registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.DATASOURCE, dataSource)
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "none")
                // §5: every stored DATETIME is UTC, and the column has no zone of its own.
                .applySetting(AvailableSettings.JDBC_TIME_ZONE, "UTC")
                .applySetting(AvailableSettings.SHOW_SQL, false)
                .applySetting(AvailableSettings.FORMAT_SQL, false)
                // Fail loudly rather than lazily initialising outside a session: the
                // entities map no associations, so anything that would need this is a bug.
                .applySetting(AvailableSettings.ENABLE_LAZY_LOAD_NO_TRANS, false);

        overrides.forEach(registry::applySetting);

        StandardServiceRegistry built = registry.build();
        try {
            MetadataSources sources = new MetadataSources(built);
            ENTITY_CLASSES.forEach(sources::addAnnotatedClass);
            return sources.buildMetadata().buildSessionFactory();
        } catch (RuntimeException | Error e) {
            // Error too, not just RuntimeException: a LinkageError here is not
            // hypothetical — it is exactly how the JDK-24/ByteBuddy incompatibility
            // presents, and losing the registry would strand its connection provider.
            // The registry owns a connection provider; leaking it on a failed boot would
            // hold pool threads for the life of the JVM.
            StandardServiceRegistryBuilder.destroy(built);
            throw e;
        }
    }

    /**
     * Installs a factory as the shared one, replacing anything already there.
     *
     * <p>Package-private, and the only way to point {@link Transactions}' single-argument
     * forms at something other than the configured MySQL. Its real audience is the
     * service tests from E6 onwards: {@code QuestionService} and its neighbours call
     * {@code Transactions.inTx(...)} with no factory argument, and being able to run
     * those against H2 is the difference between a fast test suite and one that needs a
     * database server on every developer machine.
     *
     * <p>Refuses to replace a factory that is already installed. Silently overwriting one
     * would orphan it: still open, still holding connections, and no longer reachable to
     * be closed. Call {@link #shutdown()} first — which closes an installed factory for
     * you, so the caller does not also need to.
     *
     * @param factory the factory to share
     * @throws IllegalStateException if a factory is already in place
     */
    static synchronized void install(SessionFactory factory) {
        if (instance != null) {
            throw new IllegalStateException(
                    "a SessionFactory is already installed — call shutdown() before installing another");
        }
        instance = Objects.requireNonNull(factory, "factory");
    }

    /**
     * The long-lived pool the running server queries through.
     *
     * <p>Deliberately not {@link DbBootstrap}'s: that one is two connections wide and is
     * closed the moment migration finishes, which is right for a task that happens once
     * at boot and wrong for one that serves every request for the rest of the day.
     * Credentials still come from {@link ServerConfig}, so a machine's MySQL login is
     * configured in exactly one place.
     *
     * @return a pool sized for serving requests; closed by {@link #shutdown()}
     */
    private static HikariDataSource runtimePool() {
        Credentials credentials = ServerConfig.load();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(DbBootstrap.defaultJdbcUrl());
        config.setUsername(credentials.user());
        config.setPassword(credentials.password());
        config.setMaximumPoolSize(RUNTIME_POOL_SIZE);
        config.setPoolName("hsts-runtime");
        return new HikariDataSource(config);
    }

    /**
     * Closes the shared factory and, if this class created it, the pool underneath.
     * Safe to call more than once — server shutdown paths do.
     */
    public static synchronized void shutdown() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
        if (ownedPool != null) {
            ownedPool.close();
            ownedPool = null;
        }
    }
}
