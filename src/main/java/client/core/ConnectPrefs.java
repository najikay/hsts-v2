package client.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Resolves and remembers the server endpoint the connect screen offers
 * (Presentation tier, E4.5 / F1.5).
 *
 * <p>Precedence, highest first — this is the contract F1.5 states and the reason
 * the logic is a class of its own rather than four lines inside the view:
 * <ol>
 *   <li>the <b>last endpoint that actually connected</b>, remembered in
 *       {@code ~/.hsts/connect.properties} — on a demo machine the second launch
 *       must not re-ask for the server's IP;</li>
 *   <li>{@code client.properties} beside the JAR (the deployment default);</li>
 *   <li>{@code localhost:5555}.</li>
 * </ol>
 *
 * <p>A remembered endpoint is only written <em>after</em> a successful connect
 * ({@link #remember}), so a typo the user tried once never becomes the
 * pre-filled value next time.
 *
 * <p>Also owns the two field validators, which the view binds to directly:
 * validation belongs to the same rule set as resolution, and keeping it here
 * means the messages are unit-tested rather than eyeballed.
 */
public final class ConnectPrefs {

    private static final Logger log = LoggerFactory.getLogger(ConnectPrefs.class);

    /** Property key for the remembered host. */
    public static final String KEY_LAST_HOST = "connect.last.host";

    /** Property key for the remembered port. */
    public static final String KEY_LAST_PORT = "connect.last.port";

    /**
     * Property key for the pinned server's host (E19.10).
     *
     * <p>Additive, like the two below: an older {@code connect.properties} has
     * neither key and reads as "nothing pinned yet", which is the correct state
     * for a client that has never seen a fingerprint. Nothing in this file is ever
     * renamed or repurposed, so a client upgraded mid-term keeps its remembered
     * endpoint.
     */
    public static final String KEY_PIN_HOST = "connect.pin.host";

    /** Property key for the pinned server's port (E19.10). */
    public static final String KEY_PIN_PORT = "connect.pin.port";

    /** Property key for the pinned server's fingerprint, stored in full (E19.10). */
    public static final String KEY_PIN_FINGERPRINT = "connect.pin.fingerprint";

    /** Property key for the pinned server's friendly name, for the Login status line. */
    public static final String KEY_PIN_NAME = "connect.pin.name";

    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;

    private final PropertiesStore store;

    public ConnectPrefs(PropertiesStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** @return a {@code ConnectPrefs} persisting to {@code ~/.hsts/connect.properties}. */
    public static ConnectPrefs userHome() {
        return new ConnectPrefs(FilePropertiesStore.inUserHome(FilePropertiesStore.CONNECT_FILE));
    }

    /**
     * The endpoint the connect screen pre-fills.
     *
     * @param configured what {@code client.properties} resolved to; {@code null}
     *                   falls back to {@link ServerEndpoint#LOCALHOST}
     */
    public ServerEndpoint resolve(ClientConfig.Settings configured) {
        return lastUsed().orElseGet(() -> fromSettings(configured));
    }

    /** @return the remembered endpoint, empty when absent or unusable. */
    public Optional<ServerEndpoint> lastUsed() {
        Properties props = store.load();
        String host = props.getProperty(KEY_LAST_HOST);
        String port = props.getProperty(KEY_LAST_PORT);
        if (host == null || port == null) {
            return Optional.empty();
        }
        if (validateHost(host).isPresent() || validatePort(port).isPresent()) {
            log.warn("Ignoring invalid remembered endpoint {}:{}", host, port);
            return Optional.empty();
        }
        return Optional.of(new ServerEndpoint(host.trim(), Integer.parseInt(port.trim())));
    }

    /**
     * Records an endpoint as the one to pre-fill next time. Call only after the
     * socket actually opened.
     *
     * @throws IllegalArgumentException when the endpoint is not valid
     */
    public void remember(String host, int port) {
        remember(new ServerEndpoint(host, port));
    }

    /** @see #remember(String, int) */
    public void remember(ServerEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        Properties props = store.load();
        props.setProperty(KEY_LAST_HOST, endpoint.host());
        props.setProperty(KEY_LAST_PORT, Integer.toString(endpoint.port()));
        store.save(props);
        log.debug("remembered endpoint {}", endpoint.display());
    }

    /** Forgets the remembered endpoint (used by "reset connection settings"). */
    public void forget() {
        Properties props = store.load();
        props.remove(KEY_LAST_HOST);
        props.remove(KEY_LAST_PORT);
        store.save(props);
    }

    // ------------------------------------------------------- pinning (E19.10)

    /**
     * The server this client trusts on sight (F13.4's trust on first use).
     *
     * @return the pin, empty when this client has never completed a connect or
     *         when the stored pin is unusable. An unreadable pin is dropped
     *         silently rather than repaired: the cost of forgetting it is one
     *         confirmation dialog, and the cost of half-trusting it is worse
     */
    public Optional<ServerPin> pinned() {
        Properties props = store.load();
        String host = props.getProperty(KEY_PIN_HOST);
        String port = props.getProperty(KEY_PIN_PORT);
        String fingerprint = props.getProperty(KEY_PIN_FINGERPRINT);
        if (host == null || port == null || fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }
        if (!isValid(host, port)) {
            log.warn("Ignoring an unusable pinned server {}:{}", host, port);
            return Optional.empty();
        }
        return Optional.of(new ServerPin(
                new ServerEndpoint(host.trim(), Integer.parseInt(port.trim())), fingerprint));
    }

    /** @return the pinned server's friendly name, for the Login status line. */
    public Optional<String> pinnedName() {
        String name = store.load().getProperty(KEY_PIN_NAME);
        return name == null || name.isBlank() ? Optional.empty() : Optional.of(name.trim());
    }

    /**
     * Records a server as trusted, replacing any previous pin.
     *
     * <p>Call only after a socket actually opened, exactly like
     * {@link #remember(ServerEndpoint)}: pinning a server that could not be
     * reached would teach the client to trust an address that never worked, and
     * would then raise a mismatch against it later.
     *
     * <p>Re-pinning on an accepted mismatch is the same call. That is the point of
     * confirming: the user has said this machine is the right one now.
     *
     * @param endpoint    where it was reached
     * @param fingerprint the id it announced
     * @param name        its friendly name, may be {@code null}
     */
    public void pin(ServerEndpoint endpoint, String fingerprint, String name) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Properties props = store.load();
        props.setProperty(KEY_PIN_HOST, endpoint.host());
        props.setProperty(KEY_PIN_PORT, Integer.toString(endpoint.port()));
        props.setProperty(KEY_PIN_FINGERPRINT, fingerprint.trim());
        if (name != null && !name.isBlank()) {
            props.setProperty(KEY_PIN_NAME, name.trim());
        } else {
            props.remove(KEY_PIN_NAME);
        }
        store.save(props);
        log.info("Pinned {} with id {}", endpoint.display(),
                common.dto.discovery.Fingerprints.shortForm(fingerprint));
    }

    /**
     * Forgets the pinned server ("change server", and the reset path).
     *
     * <p>Leaves the remembered endpoint alone: those are two different facts. The
     * endpoint is what to pre-fill, the pin is what to trust, and a user changing
     * servers still wants the old address offered as a starting point.
     */
    public void unpin() {
        Properties props = store.load();
        props.remove(KEY_PIN_HOST);
        props.remove(KEY_PIN_PORT);
        props.remove(KEY_PIN_FINGERPRINT);
        props.remove(KEY_PIN_NAME);
        store.save(props);
    }

    // ------------------------------------------------------------- validation

    /**
     * @return a human error message when the host field is not usable, empty when it is
     */
    public static Optional<String> validateHost(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Optional.of("Enter the server's address");
        }
        String host = raw.trim();
        if (host.contains(" ")) {
            return Optional.of("Address cannot contain spaces");
        }
        if (host.contains(":")) {
            return Optional.of("Enter the address only. The port goes in the next field");
        }
        return Optional.empty();
    }

    /**
     * @return a human error message when the port field is not usable, empty when it is
     */
    public static Optional<String> validatePort(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Optional.of("Enter the server's port");
        }
        int port;
        try {
            port = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return Optional.of("Port must be a number");
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            return Optional.of("Port must be between " + MIN_PORT + " and " + MAX_PORT);
        }
        return Optional.empty();
    }

    /** @return {@code true} when both fields would produce a connectable endpoint. */
    public static boolean isValid(String host, String port) {
        return validateHost(host).isEmpty() && validatePort(port).isEmpty();
    }

    /**
     * Builds an endpoint from raw field text.
     *
     * @throws IllegalArgumentException with the first validation message, so a
     *         caller that skipped {@link #isValid} still fails with a readable reason
     */
    public static ServerEndpoint parse(String host, String port) {
        validateHost(host).ifPresent(msg -> {
            throw new IllegalArgumentException(msg);
        });
        validatePort(port).ifPresent(msg -> {
            throw new IllegalArgumentException(msg);
        });
        return new ServerEndpoint(host.trim(), Integer.parseInt(port.trim()));
    }

    private static ServerEndpoint fromSettings(ClientConfig.Settings configured) {
        if (configured == null) {
            return ServerEndpoint.LOCALHOST;
        }
        if (!isValid(configured.host(), Integer.toString(configured.port()))) {
            log.warn("client.properties endpoint {}:{} is unusable, falling back to {}",
                    configured.host(), configured.port(), ServerEndpoint.LOCALHOST.display());
            return ServerEndpoint.LOCALHOST;
        }
        return new ServerEndpoint(configured.host(), configured.port());
    }
}
