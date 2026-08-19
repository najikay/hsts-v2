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
            return Optional.of("Enter the address only — the port goes in the next field");
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
            log.warn("client.properties endpoint {}:{} is unusable — falling back to {}",
                    configured.host(), configured.port(), ServerEndpoint.LOCALHOST.display());
            return ServerEndpoint.LOCALHOST;
        }
        return new ServerEndpoint(configured.host(), configured.port());
    }
}
