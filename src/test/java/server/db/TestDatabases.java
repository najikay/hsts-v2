package server.db;

import com.zaxxer.hikari.HikariDataSource;
import org.hibernate.SessionFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * The two databases the repository suite runs against (E2.13).
 *
 * <h2>Why two</h2>
 *
 * <p>ARCHITECTURE §5 asks for an H2 fast suite <em>and</em> a real MySQL suite for every
 * query, because each one is blind to something the other sees:
 *
 * <ul>
 *   <li><b>H2</b> is fast and needs no server, but its schema is generated from the
 *       entities, so it reproduces no foreign keys and no CHECK constraints, and not the
 *       {@code utf8mb4_unicode_ci} collation. A test that depends on any of those can pass
 *       here and fail in production. {@link H2Support} lists the full blind spot.</li>
 *   <li><b>MySQL</b> runs the real Flyway schema with every constraint live, and is the
 *       only place a concurrency test means anything — H2's in-memory engine will not
 *       reproduce the row locking the ID allocators (E2.14) depend on.</li>
 * </ul>
 *
 * <h2>Cost control</h2>
 *
 * <p>Migrating MySQL takes about thirty seconds. Doing that per test class would add
 * minutes to every build, so the MySQL database is <b>migrated once per JVM</b> and shared;
 * isolation comes from {@link RepositoryTestBase} wiping and reseeding before each test
 * rather than from a fresh schema. {@link TestDatabase#close()} is therefore a no-op on the
 * shared instance, which is released by a shutdown hook instead.
 *
 * <p><b>This assumes Surefire does not run test classes in parallel</b>, which is the
 * default and what this build uses. If parallelism is ever switched on, the shared
 * database has to become one schema per fork, or the wipes will race.
 */
public final class TestDatabases {

    /**
     * Schema the repository suite owns. Deliberately not {@link MySqlAvailability#TEST_SCHEMA}:
     * the migration tests drop and recreate that one on every test method.
     */
    static final String REPO_SCHEMA = "hsts_e2_repo_test";

    private static TestDatabase sharedMySql;

    private TestDatabases() {
        // static factory - no instances
    }

    /**
     * A fresh in-memory database, private to the caller.
     *
     * @return a database to close when the test class finishes
     */
    public static TestDatabase h2() {
        return h2(Map.of());
    }

    /**
     * A fresh in-memory database with extra Hibernate settings — a {@code StatementInspector},
     * typically, so a test can assert on the SQL that was really emitted.
     *
     * @param extraSettings settings merged over the defaults
     * @return a database to close when the test class finishes
     */
    public static TestDatabase h2(Map<String, Object> extraSettings) {
        H2Support.H2Db db = H2Support.fresh(extraSettings);
        return new TestDatabase() {
            @Override
            public SessionFactory factory() {
                return db.factory();
            }

            @Override
            public void close() {
                db.close();
            }
        };
    }

    /**
     * The shared, Flyway-migrated MySQL database.
     *
     * <p>Created on first use and kept for the life of the JVM. Callers must still call
     * {@link TestDatabase#close()} for symmetry with {@link #h2()}; on this instance it
     * does nothing.
     *
     * @return the shared MySQL database
     */
    public static synchronized TestDatabase mySql() {
        if (sharedMySql == null) {
            sharedMySql = createMySql();
        }
        return sharedMySql;
    }

    private static TestDatabase createMySql() {
        recreateSchema();

        HikariDataSource pool = DbBootstrap.dataSource(
                MySqlAvailability.schemaUrl(REPO_SCHEMA), MySqlAvailability.user(), MySqlAvailability.password());
        try {
            DbBootstrap.migrate(pool);
            // validate rather than none: if an entity has drifted from the migrations, the
            // repository suite should say so at startup instead of failing one query later
            // with something that reads like a repository bug.
            SessionFactory factory = HibernateUtil.build(pool, Map.of("hibernate.hbm2ddl.auto", "validate"));

            TestDatabase shared = new SharedMySql(factory);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                factory.close();
                pool.close();
            }, "hsts-repo-test-cleanup"));
            return shared;
        } catch (RuntimeException | Error e) {
            pool.close();
            throw e;
        }
    }

    private static void recreateSchema() {
        try (Connection connection = MySqlAvailability.openServerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + REPO_SCHEMA + "`");
            statement.execute("CREATE DATABASE `" + REPO_SCHEMA + "`"
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException e) {
            throw new IllegalStateException("could not create the repository test schema " + REPO_SCHEMA, e);
        }
    }

    /** Shared for the life of the JVM, so closing it per test class would break the next one. */
    private record SharedMySql(SessionFactory factory) implements TestDatabase {

        @Override
        public void close() {
            // intentionally empty; the shutdown hook owns this one
        }
    }
}
