package server.db;

import server.core.ServerConfig;
import server.core.ServerConfig.Credentials;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Central JDBC configuration for the Data tier.
 *
 * <p>Host, port, and database name are fixed for the prototype. Credentials are
 * loaded from {@code server.properties} via {@link ServerConfig}.
 */
public final class DatabaseConfig {

    public static final String HOST     = "localhost";
    public static final int    PORT     = 3306;
    public static final String DATABASE = "hsts_db";

    /** Extra JDBC params: TLS off for local dev, sane timezone handling. */
    private static final String PARAMS =
            "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    /** Full JDBC URL assembled from the constants above. */
    public static final String URL =
            "jdbc:mysql://" + HOST + ":" + PORT + "/" + DATABASE + "?" + PARAMS;

    private DatabaseConfig() {
        // utility class — no instances
    }

    /**
     * Opens a new JDBC connection to the HSTS database.
     *
     * @return a live {@link Connection}; caller is responsible for closing it.
     */
    public static Connection getConnection() throws SQLException {
        Credentials creds = ServerConfig.load();
        return DriverManager.getConnection(URL, creds.user(), creds.password());
    }
}
