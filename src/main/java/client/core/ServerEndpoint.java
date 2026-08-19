package client.core;

import java.util.Objects;

/**
 * A server address the client can connect to (Presentation tier, E4.5).
 *
 * @param host hostname or IP, never blank
 * @param port TCP port, 1–65535
 */
public record ServerEndpoint(String host, int port) {

    /** Fallback used when neither preferences nor {@code client.properties} say otherwise. */
    public static final ServerEndpoint LOCALHOST = new ServerEndpoint("localhost", 5555);

    public ServerEndpoint {
        Objects.requireNonNull(host, "host");
        host = host.trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < ConnectPrefs.MIN_PORT || port > ConnectPrefs.MAX_PORT) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    /** @return {@code host:port}, the form shown in the UI and in logs. */
    public String display() {
        return host + ":" + port;
    }

    @Override
    public String toString() {
        return display();
    }
}
