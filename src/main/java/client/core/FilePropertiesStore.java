package client.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Properties;

/**
 * {@link PropertiesStore} backed by a file under the user's home directory
 * (Presentation tier, E4.7 / F14.1).
 *
 * <p>Files live in {@code ~/.hsts/} — one per concern ({@code ui.properties} for
 * theme, {@code connect.properties} for the last-used server), so a corrupt or
 * hand-edited theme file cannot take the connection settings down with it.
 *
 * <p>Every failure path is swallowed and logged, deliberately: preferences are a
 * convenience, and a read-only home directory or a half-written file must
 * degrade to "use the defaults", never to a stack trace on startup.
 */
public final class FilePropertiesStore implements PropertiesStore {

    private static final Logger log = LoggerFactory.getLogger(FilePropertiesStore.class);

    /** Directory holding all per-user HSTS client preferences. */
    public static final String APP_DIR = ".hsts";

    /** Theme mode + accent palette (E4.7). */
    public static final String UI_FILE = "ui.properties";

    /** Last successfully-used server endpoint (E4.5). */
    public static final String CONNECT_FILE = "connect.properties";

    private final Path file;

    public FilePropertiesStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** @return a store for {@code ~/.hsts/<fileName>}. */
    public static FilePropertiesStore inUserHome(String fileName) {
        return new FilePropertiesStore(userHomeFile(fileName));
    }

    /** Resolves {@code ~/.hsts/<fileName>}; visible for testing. */
    static Path userHomeFile(String fileName) {
        return Paths.get(System.getProperty("user.home", ".")).resolve(APP_DIR).resolve(fileName);
    }

    /** @return the backing file path (may not exist yet). */
    public Path file() {
        return file;
    }

    @Override
    public Properties load() {
        Properties props = new Properties();
        if (!Files.isRegularFile(file)) {
            return props;
        }
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException: malformed \\u escape inside a hand-edited file.
            log.warn("Ignoring unreadable preferences file {}: {}", file, e.getMessage());
            props.clear();
        }
        return props;
    }

    @Override
    public void save(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "HSTS client preferences - managed by the app, safe to delete");
            }
        } catch (IOException e) {
            log.warn("Could not write preferences file {}: {}", file, e.getMessage());
        }
    }
}
