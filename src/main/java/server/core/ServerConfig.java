package server.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Loads server-side settings from {@code server.properties}.
 *
 * <p>Lookup order:
 * <ol>
 *   <li>File beside the running JAR (or project root when launched from the IDE)</li>
 *   <li>Classpath resource {@code /server.properties} (bundled default)</li>
 *   <li>Hard-coded fallback ({@code root} / {@code root})</li>
 * </ol>
 *
 * <p>Place {@code server.properties} next to {@code hsts-server.jar} and set
 * {@code db.user} / {@code db.password} to match the local MySQL instance.
 */
public final class ServerConfig {

    private static final String CONFIG_FILE = "server.properties";
    private static final String KEY_USER = "db.user";
    private static final String KEY_PASSWORD = "db.password";
    private static final String DEFAULT_USER = "root";
    private static final String DEFAULT_PASSWORD = "root";

    private ServerConfig() {}

    /** Resolved JDBC credentials for the Data tier. */
    public record Credentials(String user, String password) {}

    public static Credentials load() {
        Properties props = new Properties();
        Path external = resolveExternalConfigPath();

        if (external != null && Files.isRegularFile(external)) {
            loadFromFile(props, external);
            System.out.println("[ServerConfig] Loaded " + CONFIG_FILE + " from " + external.toAbsolutePath());
        } else if (!loadFromClasspath(props)) {
            System.out.println("[ServerConfig] No " + CONFIG_FILE + " found — using default credentials");
        }

        String user = props.getProperty(KEY_USER, DEFAULT_USER).trim();
        String password = props.getProperty(KEY_PASSWORD, DEFAULT_PASSWORD);
        return new Credentials(user, password);
    }

    private static Path resolveExternalConfigPath() {
        try {
            URI codeSource = ServerConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path location = Paths.get(codeSource);
            if (Files.isRegularFile(location)) {
                return location.getParent().resolve(CONFIG_FILE);
            }
        } catch (Exception ignored) {
            // Fall through to cwd (IDE / exploded classes).
        }
        return Paths.get(CONFIG_FILE);
    }

    private static void loadFromFile(Properties props, Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("[ServerConfig] Could not read " + path + ": " + e.getMessage());
        }
    }

    private static boolean loadFromClasspath(Properties props) {
        try (InputStream in = ServerConfig.class.getResourceAsStream("/" + CONFIG_FILE)) {
            if (in == null) {
                return false;
            }
            props.load(in);
            System.out.println("[ServerConfig] Loaded bundled " + CONFIG_FILE);
            return true;
        } catch (IOException e) {
            System.err.println("[ServerConfig] Could not read bundled " + CONFIG_FILE + ": " + e.getMessage());
            return false;
        }
    }
}
