package client.core;

import common.config.ExternalConfig;

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
 * <p>Lookup order (E20.4, the candidate list itself is
 * {@link common.config.ExternalConfig}):
 * <ol>
 *   <li>File beside the running JAR</li>
 *   <li>File of the same name in the working directory (the only external
 *       candidate when launched from the IDE or from exploded classes)</li>
 *   <li>Classpath resource {@code /client.properties} (bundled default)</li>
 *   <li>Hard-coded fallback ({@code localhost:5555})</li>
 * </ol>
 *
 * <p>This is the fallback path, not the usual one: since E19.10 the client
 * discovers the server over UDP and pins it, and these settings are what it starts
 * from when discovery finds nothing. For a two-machine demo on a network that
 * blocks broadcast, place {@code client.properties} next to the client JAR and set
 * {@code server.host} to the server machine's IP.
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
        return load(resolveExternalConfigPath(), "/" + CONFIG_FILE);
    }

    /** Optional key: how long a first HELLO may take before the connect gives up. */
    private static final String KEY_HELLO_TIMEOUT = "connect.hello.timeout.ms";
    private static final long DEFAULT_HELLO_TIMEOUT_MS = 15_000;

    /**
     * The HELLO budget, from {@code client.properties} when the demo-day escape hatch is
     * needed without a rebuild (2026-08-31, B-49 field addendum: a Wi-Fi power-save network
     * was measured delaying the FIRST data packet of every new TCP connection by ~5 s, so
     * the proof packet needs the same patience the dial already gets). Floor of one second;
     * an unparseable value falls back to the default.
     */
    public static long helloTimeoutMs() {
        Properties props = new Properties();
        Path external = resolveExternalConfigPath();
        if (external != null && Files.isRegularFile(external)) {
            loadFromFile(props, external);
        } else {
            loadFromClasspath(props, "/" + CONFIG_FILE);
        }
        try {
            long value = Long.parseLong(
                    props.getProperty(KEY_HELLO_TIMEOUT,
                            Long.toString(DEFAULT_HELLO_TIMEOUT_MS)).trim());
            return Math.max(1_000, value);
        } catch (NumberFormatException e) {
            return DEFAULT_HELLO_TIMEOUT_MS;
        }
    }

    /**
     * Resolution core, with both sources injected - visible for testing so the
     * external-file / classpath / defaults branches can each be exercised.
     *
     * @param external          candidate external file (may be {@code null} or absent)
     * @param classpathResource absolute classpath resource name (may be absent)
     */
    static Settings load(Path external, String classpathResource) {
        Properties props = new Properties();

        if (external != null && Files.isRegularFile(external)) {
            loadFromFile(props, external);
            System.out.println("[ClientConfig] Loaded " + CONFIG_FILE + " from " + external.toAbsolutePath());
        } else if (!loadFromClasspath(props, classpathResource)) {
            System.out.println("[ClientConfig] No " + CONFIG_FILE + " found - using defaults ("
                    + DEFAULT_HOST + ":" + DEFAULT_PORT + ")");
        }

        String host = props.getProperty(KEY_HOST, DEFAULT_HOST).trim();
        int port = parsePort(props.getProperty(KEY_PORT, Integer.toString(DEFAULT_PORT)));
        return new Settings(host, port);
    }

    private static Path resolveExternalConfigPath() {
        try {
            URI codeSource = ClientConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI();
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
            System.err.println("[ClientConfig] Could not read " + path + ": " + e.getMessage());
        }
    }

    private static boolean loadFromClasspath(Properties props, String resource) {
        try (InputStream in = ClientConfig.class.getResourceAsStream(resource)) {
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
