package server.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.ServerConfig;
import server.core.ServerConfig.Credentials;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Brings the database schema up to date before the server accepts clients (E2.1).
 *
 * <p>Runs the versioned migrations in {@code db/migration} through Flyway (ADR-004),
 * so a fresh machine goes from an empty MySQL to a complete schema with no manual
 * SQL — the fresh-machine demo path of NFR-17.
 *
 * <p>Credentials come from {@link ServerConfig}, the same lookup the rest of the
 * server uses (external {@code server.properties} → bundled default → {@code root}),
 * so there is exactly one place a machine's MySQL login is configured.
 *
 * <p>Two deliberate Flyway settings:
 * <ul>
 *   <li>{@code validateOnMigrate} — a migration that was edited after being applied
 *       fails loudly instead of leaving two machines on silently different schemas.</li>
 *   <li>{@code baselineOnMigrate = false} — pointing the server at a non-empty
 *       database that Flyway has never managed is an operator error (a leftover
 *       prototype schema, or the wrong database name). Baselining would quietly
 *       skip V1 and leave the app running against a schema it does not know.</li>
 * </ul>
 */
public final class DbBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DbBootstrap.class);

    /** Where the versioned migrations live on the classpath. */
    static final String MIGRATIONS_LOCATION = "classpath:db/migration";

    static final String DEFAULT_HOST = "localhost";
    static final int DEFAULT_PORT = 3306;
    static final String DEFAULT_DATABASE = "hsts_db";

    /**
     * TLS off for LAN dev, UTF-8 on the wire so Hebrew round-trips, and UTC so the
     * server-authoritative timestamps of ADR-010 are not reinterpreted per machine.
     */
    // createDatabaseIfNotExist: the first boot on a clean machine (or after the
    // PR-1 "drop and recreate hsts_db" instruction) must reach Flyway, not die in
    // the pool with "Unknown database". The demo laptop double-clicks the jar;
    // nobody is there to run CREATE DATABASE first. Tables carry their own
    // explicit utf8mb4 charset, so the created database's default does not matter.
    static final String JDBC_PARAMS =
            "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8"
                    + "&createDatabaseIfNotExist=true";

    /** Migrations need one connection; the pool exists only to satisfy Flyway's API. */
    private static final int MIGRATION_POOL_SIZE = 2;

    private DbBootstrap() {
        // utility class - no instances
    }

    /**
     * Builds a JDBC URL for the HSTS database on the given server.
     *
     * @param host     MySQL host
     * @param port     MySQL port
     * @param database schema name
     * @return a fully-formed {@code jdbc:mysql://…} URL including the shared parameters
     */
    public static String jdbcUrl(String host, int port, String database) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        return "jdbc:mysql://" + host + ":" + port + "/" + database + "?" + JDBC_PARAMS;
    }

    /** The URL the packaged server uses: {@code hsts_db} on the local MySQL. */
    public static String defaultJdbcUrl() {
        return jdbcUrl(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_DATABASE);
    }

    /**
     * Creates a small pool for one migration run. The caller owns it and must close it.
     *
     * @param url      JDBC URL
     * @param user     database user
     * @param password database password
     * @return a configured {@link HikariDataSource}
     */
    public static HikariDataSource dataSource(String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(Objects.requireNonNull(url, "url"));
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(MIGRATION_POOL_SIZE);
        config.setPoolName("hsts-migration");
        return new HikariDataSource(config);
    }

    /**
     * Migrates the configured database to the latest version. This is the call the
     * server makes at startup, before it begins listening.
     *
     * @return the Flyway result, for logging or assertions
     * @throws org.flywaydb.core.api.FlywayException if a migration fails or validation
     *                                               rejects an already-applied file
     */
    public static MigrateResult migrate() {
        Credentials creds = ServerConfig.load();
        try (HikariDataSource ds = dataSource(defaultJdbcUrl(), creds.user(), creds.password())) {
            return migrate(ds);
        }
    }

    /**
     * Migrates whatever database the given source points at - the seam tests use to
     * run against a throwaway schema instead of the real one.
     *
     * @param dataSource the database to migrate; not closed by this method
     * @return the Flyway result
     */
    public static MigrateResult migrate(DataSource dataSource) {
        MigrateResult result = flywayFor(dataSource).migrate();
        if (result.migrationsExecuted == 0) {
            // Flyway leaves targetSchemaVersion null when it had nothing to do.
            log.info("Database schema already up to date");
        } else {
            log.info("Applied {} migration(s) - schema now at version {}",
                    result.migrationsExecuted, result.targetSchemaVersion);
        }
        return result;
    }

    /**
     * Assembles the Flyway configuration. Package-private so the settings can be
     * asserted without a database.
     *
     * @param dataSource the database to configure Flyway against
     * @return a configured {@link Flyway}
     */
    static Flyway flywayFor(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATIONS_LOCATION)
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .load();
    }
}
