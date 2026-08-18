package client.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Loads client connection settings from {@code client.properties}.
 *
 * <p>Lookup order:
 * <ol>
 *   <li>File beside the running JAR (or project root when launched from the IDE)</li>
 *   <li>Classpath resource {@code /client.properties} (bundled default)</li>
 *   <li>Hard-coded fallback ({@code localhost:5555})</li>
 * </ol>
 *
 * <p>For a two-machine demo, place {@code client.properties} next to
 * {@code hsts-client.jar} and set {@code server.host} to the server machine's IP.
 */
public final class ClientConfig {

    private static final String CONFIG_FILE = "client.properties";
    private static final String KEY_HOST = "server.host";
    private static final String KEY_PORT = "server.port";
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 5555;

    private ClientConfig() {}

    /** Resolved host/port pair for the OCSF client connection. */
    public record Settings(String host, int port) {}

    public static Settings load() {
        Properties props = new Properties();
        Path external = resolveExternalConfigPath();

        if (external != null && Files.isRegularFile(external)) {
            loadFromFile(props, external);
            System.out.println("[ClientConfig] Loaded " + CONFIG_FILE + " from " + external.toAbsolutePath());
        } else if (!loadFromClasspath(props)) {
            System.out.println("[ClientConfig] No " + CONFIG_FILE + " found — using defaults ("
                    + DEFAULT_HOST + ":" + DEFAULT_PORT + ")");
        }

        String host = props.getProperty(KEY_HOST, DEFAULT_HOST).trim();
        int port = parsePort(props.getProperty(KEY_PORT, Integer.toString(DEFAULT_PORT)));
        return new Settings(host, port);
    }

    private static Path resolveExternalConfigPath() {
        try {
            URI codeSource = ClientConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI();
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
            System.err.println("[ClientConfig] Could not read " + path + ": " + e.getMessage());
        }
    }

    private static boolean loadFromClasspath(Properties props) {
        try (InputStream in = ClientConfig.class.getResourceAsStream("/" + CONFIG_FILE)) {
            if (in == null) {
                return false;
            }
            props.load(in);
            System.out.println("[ClientConfig] Loaded bundled " + CONFIG_FILE);
            return true;
        } catch (IOException e) {
            System.err.println("[ClientConfig] Could not read bundled " + CONFIG_FILE + ": " + e.getMessage());
            return false;
        }
    }

    private static int parsePort(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            System.err.println("[ClientConfig] Invalid port '" + raw + "', using default " + DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }
}
