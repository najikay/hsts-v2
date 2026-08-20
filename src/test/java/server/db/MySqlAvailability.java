package server.db;

import server.core.ServerConfig;
import server.core.ServerConfig.Credentials;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The single gate for MySQL-backed tests.
 *
 * <p>Tests that need a real MySQL are annotated
 * {@code @EnabledIf("server.db.MySqlAvailability#isReachable")}: they run wherever a
 * server answers and skip cleanly everywhere else. CI needs no configuration — the
 * workflow already starts MySQL 8.4 with {@code root/root}, which is exactly what the
 * bundled {@code server.properties} falls back to.
 *
 * <p>Locally, credentials come from the gitignored {@code server.properties} at the
 * repository root via {@link ServerConfig}, so a developer configures their MySQL login
 * in one place rather than once per test. Every value can be overridden by environment
 * variable for a machine that runs MySQL somewhere unusual.
 *
 * <p>Skipping is right on a developer machine with no MySQL, and wrong in CI: there a
 * skip is a false green, because the whole point of the job is to prove the migrations
 * run. Setting {@code HSTS_REQUIRE_MYSQL=true} — which the workflow does — turns an
 * unreachable server into a loud failure instead.
 *
 * <p><b>This class is the one place to change</b> if the team later adds the Failsafe
 * plugin and moves the MySQL suite to {@code *IT} classes — the gating decision lives
 * here and nowhere else.
 */
final class MySqlAvailability {

    /**
     * Schema the migration tests create and drop. Deliberately not {@code hsts_db} and
     * not any name a developer is likely to already own — these tests DROP it.
     */
    static final String TEST_SCHEMA = env("HSTS_TEST_SCHEMA", "hsts_e2_migration_test");

    private static final String HOST = env("HSTS_DB_HOST", DbBootstrap.DEFAULT_HOST);
    private static final int PORT = Integer.parseInt(env("HSTS_DB_PORT", String.valueOf(DbBootstrap.DEFAULT_PORT)));

    /** Probing must not hang a build on an unreachable host. */
    private static final int PROBE_TIMEOUT_SECONDS = 3;

    /** Environment variable CI sets so an unreachable MySQL fails instead of skipping. */
    static final String REQUIRE_FLAG = "HSTS_REQUIRE_MYSQL";

    private static Boolean cachedReachable;

    private MySqlAvailability() {
        // static helper — no instances
    }

    /** JDBC URL with no schema selected — used to create and drop the test schema. */
    static String serverUrl() {
        return DbBootstrap.jdbcUrl(HOST, PORT, "");
    }

    /** JDBC URL pointing at the throwaway test schema. */
    static String schemaUrl() {
        return schemaUrl(TEST_SCHEMA);
    }

    /**
     * JDBC URL for any schema on the configured server.
     *
     * <p>The repository suite (E2.13) needs a schema of its own: {@link #TEST_SCHEMA} is
     * dropped and recreated by every migration test, so a repository test sharing it would
     * have its tables deleted out from under it mid-run.
     *
     * @param schema the schema name to connect to
     * @return the JDBC URL for that schema on the configured host and port
     */
    static String schemaUrl(String schema) {
        return DbBootstrap.jdbcUrl(HOST, PORT, schema);
    }

    static String user() {
        return env("HSTS_DB_USER", credentials().user());
    }

    static String password() {
        return env("HSTS_DB_PASSWORD", credentials().password());
    }

    /** Opens a connection with no schema selected. Caller closes it. */
    static Connection openServerConnection() throws SQLException {
        return DriverManager.getConnection(serverUrl(), user(), password());
    }

    /**
     * Whether a MySQL server answered on the configured host. Referenced by name from
     * {@code @EnabledIf}, and cached so a suite of tests probes at most once.
     *
     * @return {@code true} when the tests can run against a live server
     */
    static boolean isReachable() {
        if (cachedReachable == null) {
            cachedReachable = probe();
        }
        return gate(cachedReachable, isRequired());
    }

    /** Whether this environment insists the MySQL suite actually runs. */
    static boolean isRequired() {
        return Boolean.parseBoolean(env(REQUIRE_FLAG, "false"));
    }

    /**
     * The skip-or-fail decision, kept as a pure function so every branch is testable
     * without a database and without mutating the environment.
     *
     * @param reachable whether a server answered
     * @param required  whether an unreachable server must fail the build
     * @return whether the MySQL-backed tests should run
     * @throws IllegalStateException when the suite is required but MySQL is absent
     */
    static boolean gate(boolean reachable, boolean required) {
        if (!reachable && required) {
            throw new IllegalStateException(REQUIRE_FLAG + "=true, but no MySQL answered at "
                    + HOST + ":" + PORT + ". This flag exists so CI can never report green on a"
                    + " silently skipped migration suite. Start MySQL, or unset " + REQUIRE_FLAG
                    + " to allow skipping on a machine without one.");
        }
        return reachable;
    }

    /**
     * Whether the application schema {@code hsts_db} itself exists.
     *
     * <p>Separate from {@link #isReachable()}, which only asks whether a server answered.
     * The one test that exercises the production boot path needs the real schema to be
     * there, and it legitimately will not be on a machine that has just dropped it — which
     * the E2 PR 1 findings tell developers to do once, and which happens again whenever
     * someone starts from a clean MySQL. Failing that test would punish following the
     * instructions; skipping it is correct. CI provisions {@code hsts_db} in the workflow,
     * so the coverage is not lost where it matters.
     *
     * @return {@code true} when a server answered and the schema exists
     */
    static boolean defaultSchemaExists() {
        if (!isReachable()) {
            return false;
        }
        try (Connection connection = openServerConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
            statement.setString(1, DbBootstrap.DEFAULT_DATABASE);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            System.out.println("[MySqlAvailability] could not check for schema "
                    + DbBootstrap.DEFAULT_DATABASE + " — treating as absent (" + e.getMessage() + ")");
            return false;
        }
    }

    private static boolean probe() {
        DriverManager.setLoginTimeout(PROBE_TIMEOUT_SECONDS);
        try (Connection ignored = openServerConnection()) {
            return true;
        } catch (SQLException e) {
            System.out.println("[MySqlAvailability] MySQL not reachable at " + HOST + ":" + PORT
                    + " — MySQL-backed tests will be skipped (" + e.getMessage() + ")");
            return false;
        }
    }

    private static Credentials credentials() {
        return ServerConfig.load();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
