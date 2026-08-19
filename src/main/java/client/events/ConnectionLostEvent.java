package client.events;

/**
 * The socket to the server went away (E4.6 / E5.7).
 *
 * <p>Posted from the one place that learns about it — the connection-lost hook
 * {@code ConnectWiring} installs on the client adapter — and consumed by whoever
 * is on screen. Two consumers exist today: {@link ConnectionWatcher}, which
 * raises the app shell's reconnect banner, and {@code RequestDispatcher}, which
 * is called directly on the same hook so in-flight requests fail immediately
 * rather than waiting out their timeouts.
 *
 * <p>Delivered on the FX thread like every other bus event, so a subscriber may
 * touch the scene graph directly.
 *
 * @param serverLabel the endpoint the client was talking to ({@code host:port}),
 *                    for a banner that names the server rather than saying
 *                    "connection lost" into the void
 * @param detail      the technical reason, for the log and the banner's detail
 *                    line; never shown as the primary message
 */
public record ConnectionLostEvent(String serverLabel, String detail) {

    public ConnectionLostEvent {
        serverLabel = serverLabel == null ? "the server" : serverLabel;
        detail = detail == null ? "" : detail;
    }
}
