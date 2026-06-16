package server.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Central JDBC configuration for the Data tier.
 *
 * <p>Connection parameters are exposed as easily editable constants. For the
 * prototype these are standard local MySQL credentials; in a real deployment
 * they would be externalized (env vars / properties file) rather than compiled in.
 */
public final class DatabaseConfig {

    // ===== Easily editable connection constants ===========================
    public static final String HOST     = "localhost";
    public static final int    PORT     = 3306;
    public static final String DATABASE = "hsts_db";
    public static final String USER     = "root";
    public static final String PASSWORD = "root";

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
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}
