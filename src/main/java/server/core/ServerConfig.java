package server.core;

import common.config.ExternalConfig;

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
 * <p>Lookup order (E20.4, the candidate list itself is
 * {@link common.config.ExternalConfig}):
 * <ol>
 *   <li>File beside the running JAR</li>
 *   <li>File of the same name in the working directory (the only external
 *       candidate when launched from the IDE or from exploded classes)</li>
 *   <li>Classpath resource {@code /server.properties} (bundled default)</li>
 *   <li>Hard-coded fallback ({@code root} / {@code root})</li>
 * </ol>
 *
 * <p>Place {@code server.properties} next to the server JAR and set
 * {@code db.user} / {@code db.password} to match the local MySQL instance. The
 * {@code package} build puts a copy there already: the machine's real file when it
 * has one, otherwise {@code server.properties.example}, so a fresh clone's
 * {@code target/} runs without anybody hand-copying anything.
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
        return load(resolveExternalConfigPath(), "/" + CONFIG_FILE);
    }

    /**
     * The whole of {@code server.properties}, resolved the same way as
     * {@link #load()} (E16.1).
     *
     * <p>Added for the study bot, which reads {@code bot.*} settings out of the
     * same file. It deliberately reuses this class's lookup order rather than
     * opening the file itself: two places that both "find server.properties" is
     * how a machine ends up with a database that reads one file and a bot that
     * reads another.
     *
     * @return every property found; empty when there is no file anywhere
     */
    public static Properties loadProperties() {
        return loadProperties(resolveExternalConfigPath(), "/" + CONFIG_FILE);
    }

    /** Resolution core for {@link #loadProperties()}; visible for testing. */
    static Properties loadProperties(Path external, String classpathResource) {
        Properties props = new Properties();
        if (external != null && Files.isRegularFile(external)) {
            loadFromFile(props, external);
        } else {
            loadFromClasspath(props, classpathResource);
        }
        return props;
    }

    /**
     * Resolution core, with both sources injected - visible for testing so the
     * external-file / classpath / defaults branches can each be exercised.
     *
     * @param external          candidate external file (may be {@code null} or absent)
     * @param classpathResource absolute classpath resource name (may be absent)
     */
    static Credentials load(Path external, String classpathResource) {
        Properties props = new Properties();

        if (external != null && Files.isRegularFile(external)) {
            loadFromFile(props, external);
            System.out.println("[ServerConfig] Loaded " + CONFIG_FILE + " from " + external.toAbsolutePath());
        } else if (!loadFromClasspath(props, classpathResource)) {
            System.out.println("[ServerConfig] No " + CONFIG_FILE + " found - using default credentials");
        }

        String user = props.getProperty(KEY_USER, DEFAULT_USER).trim();
        String password = props.getProperty(KEY_PASSWORD, DEFAULT_PASSWORD);
        return new Credentials(user, password);
    }

    private static Path resolveExternalConfigPath() {
        try {
            URI codeSource = ServerConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return externalPathFor(Paths.get(codeSource));
        } catch (Exception ignored) {
            // Fall through to cwd (IDE / exploded classes).
            return Paths.get(CONFIG_FILE);
        }
    }

    /**
     * Maps this class's code-source location to the external config file to read:
     * next to the JAR when one is there, otherwise the same name in the working
     * directory (E20.4). Visible for testing.
     *
     * @see common.config.ExternalConfig
     */
    static Path externalPathFor(Path codeSourceLocation) {
        return ExternalConfig.locate(codeSourceLocation, CONFIG_FILE);
    }

    /** Visible for testing (unreadable-file branch). */
    static void loadFromFile(Properties props, Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("[ServerConfig] Could not read " + path + ": " + e.getMessage());
        }
    }

    private static boolean loadFromClasspath(Properties props, String resource) {
        try (InputStream in = ServerConfig.class.getResourceAsStream(resource)) {
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
