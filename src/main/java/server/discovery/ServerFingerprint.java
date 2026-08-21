package server.discovery;

import common.dto.discovery.Fingerprints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * This installation's identity, generated once and kept (Logic tier, E19.8 /
 * E19.9, F13.3).
 *
 * <h2>What the fingerprint is for, stated honestly</h2>
 *
 * <p><b>It is change detection, not impersonation resistance.</b> It is a random
 * id that the server hands out in cleartext to anyone who asks, so anyone who has
 * ever received one can send the same one back. It cannot prove that a server is
 * the server, and nothing in this class or in {@link DiscoveryResponder} claims
 * that it can.
 *
 * <p>What it genuinely does, and what the client's trust-on-first-use pinning is
 * built on, is narrower and still worth having:
 *
 * <ul>
 *   <li><b>Disambiguation.</b> Two servers on one classroom network are told
 *       apart by their ids rather than by an address that DHCP may have swapped
 *       between them since yesterday.</li>
 *   <li><b>Change detection.</b> A client that connected to {@code 192.168.1.42}
 *       yesterday and finds a <em>different</em> id there today has learned
 *       something real: the machine at that address is not the machine it was.
 *       That is worth stopping and asking about, and the client does
 *       (E19.10).</li>
 * </ul>
 *
 * <p>The property it does not have is the one a cryptographic binding would give:
 * a client cannot tell a genuine server from one that copied the id. Getting that
 * means the id becoming a TLS certificate fingerprint, which is E19.12's gated
 * decision (ADR-019) and would change none of the user experience.
 *
 * <h2>Persistence and regeneration</h2>
 *
 * <p>The id lives in {@code server-id.properties} beside {@code server.properties},
 * so it survives restarts (a client's pin would otherwise be wrong after every
 * boot, which would train operators to click through the very warning the pin
 * exists to raise) and does not survive a reinstall onto a clean machine. That
 * asymmetry is correct: a reinstalled server genuinely <em>is</em> a different
 * machine, and every pinned client should be told so once.
 *
 * <p>{@link #regenerate()} is the deliberate version of the same event, for an
 * operator who has cloned a disk image and now has two servers claiming one id.
 * It is not reachable from the console: it invalidates every client's pin, and a
 * button that does that does not belong next to the ones people press during a
 * demo.
 *
 * <p>A file that cannot be written is not fatal. The server runs with an id that
 * lasts until the next restart, logs one line saying exactly that, and every
 * pinned client sees a mismatch on the next boot. Refusing to start over a
 * missing write permission would be a far worse failure than the one avoided.
 */
public final class ServerFingerprint {

    private static final Logger log = LoggerFactory.getLogger(ServerFingerprint.class);

    /** The file the id is kept in, beside {@code server.properties}. */
    public static final String FILE_NAME = "server-id.properties";

    /** Property key of the id itself. */
    public static final String KEY_FINGERPRINT = "server.fingerprint";

    /** Property key of the friendly name shown in the client's picker. */
    public static final String KEY_NAME = "server.name";

    /** The name used when nobody has set one. */
    public static final String DEFAULT_NAME = "HSTS server";

    private final Path file;
    private final Supplier<String> generator;

    /**
     * @param file      where to keep the id
     * @param generator the source of new ids; {@code UUID.randomUUID} in
     *                  production, a fixed value in tests so a persisted id is
     *                  assertable rather than merely present
     */
    public ServerFingerprint(Path file, Supplier<String> generator) {
        this.file = Objects.requireNonNull(file, "file");
        this.generator = Objects.requireNonNull(generator, "generator");
    }

    /** @return a store over {@code server-id.properties} in {@code directory}. */
    public static ServerFingerprint in(Path directory) {
        return new ServerFingerprint(directory.resolve(FILE_NAME),
                () -> UUID.randomUUID().toString());
    }

    /** This server's identity, as the responder announces it. */
    public record Identity(String name, String fingerprint) {

        public Identity {
            Objects.requireNonNull(fingerprint, "fingerprint");
            name = name == null || name.isBlank() ? DEFAULT_NAME : name.trim();
        }

        /** @return the grouped eight-character form shown to people. */
        public String shortFingerprint() {
            return Fingerprints.shortForm(fingerprint);
        }
    }

    /**
     * Reads the stored identity, creating one on first boot.
     *
     * @return the identity to announce; never fails, see the class javadoc on an
     *         unwritable file
     */
    public Identity loadOrCreate() {
        Properties stored = read();
        String existing = stored.getProperty(KEY_FINGERPRINT);
        String name = stored.getProperty(KEY_NAME);
        if (existing != null && !existing.isBlank()) {
            return new Identity(name, existing.trim());
        }
        Identity created = new Identity(name, generator.get());
        write(created);
        log.info("Generated this server's discovery id {} and saved it to {}. "
                        + "It identifies this installation to clients and survives restarts.",
                created.shortFingerprint(), file);
        return created;
    }

    /**
     * Throws away the stored id and mints a new one (E19.9).
     *
     * <p>Every client that pinned this server will see a mismatch warning on its
     * next connect. That is the intended outcome and the only reason to call this.
     *
     * @return the new identity
     */
    public Identity regenerate() {
        Properties stored = read();
        Identity created = new Identity(stored.getProperty(KEY_NAME), generator.get());
        write(created);
        log.warn("Regenerated this server's discovery id to {}. Every client that "
                        + "remembered the old one will ask for confirmation before connecting.",
                created.shortFingerprint());
        return created;
    }

    /** Sets the friendly name and keeps the id. @return the updated identity */
    public Identity rename(String name) {
        Identity current = loadOrCreate();
        Identity renamed = new Identity(name, current.fingerprint());
        write(renamed);
        return renamed;
    }

    /** @return the file the id is kept in. */
    public Path file() {
        return file;
    }

    /**
     * @see Fingerprints#shortForm(String)
     * @param fingerprint the full id
     * @return the grouped display form both tiers render
     */
    public static String shortForm(String fingerprint) {
        return Fingerprints.shortForm(fingerprint);
    }

    // ------------------------------------------------------------------ io

    private Properties read() {
        Properties props = new Properties();
        if (!Files.isRegularFile(file)) {
            return props;
        }
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("Could not read {}: {}. This server will announce a new id until "
                    + "the file is readable again.", file, e.getMessage());
        }
        return props;
    }

    private void write(Identity identity) {
        Properties props = new Properties();
        props.setProperty(KEY_FINGERPRINT, identity.fingerprint());
        props.setProperty(KEY_NAME, identity.name());
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "HSTS server identity. Generated on first boot, "
                        + "kept so clients recognise this installation across restarts. "
                        + "Delete this file to make every client ask for confirmation once.");
            }
        } catch (IOException e) {
            log.warn("Could not save this server's discovery id to {}: {}. "
                            + "The server runs with a temporary id and clients will ask for "
                            + "confirmation after every restart. Fix the file permissions to stop that.",
                    file, e.getMessage());
        }
    }
}
