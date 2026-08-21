package common.dto.discovery;

import java.util.Objects;

/**
 * What a server says about itself when a client broadcasts for one (Common tier,
 * E19.8, F13.3).
 *
 * <p>Four fields, and the list is exhaustive on purpose: this travels unsolicited,
 * in cleartext, to whoever sent a UDP packet to the right port. Nothing here is
 * sensitive and nothing here is worth stealing. There is no version list, no user
 * count, no database name, no build path. A discovery reply is an invitation to
 * connect and then authenticate, not a status page.
 *
 * <p>Not {@link java.io.Serializable}, deliberately. This crosses the wire as
 * JSON through {@link DiscoveryProtocol}, never as a Java object graph, because
 * Java deserialization of a datagram from an unauthenticated stranger is exactly
 * the shape of problem this product does not need to have. The protocol envelope
 * used for everything else ({@code common.protocol.Message}) travels over an
 * accepted TCP connection; this does not.
 *
 * @param name        the friendly name shown in the client's picker, for example
 *                    "Room 12 server"
 * @param ip          the address clients should connect to, which is what the
 *                    console header shows and may be a manual override rather
 *                    than the interface the datagram arrived on
 * @param port        the OCSF port to connect to (not the discovery port)
 * @param fingerprint this installation's id; see {@code ServerFingerprint} for
 *                    what it does and does not prove
 */
public record ServerAnnouncement(String name, String ip, int port, String fingerprint) {

    /** Longest name accepted, in characters. A picker row, not an essay. */
    public static final int MAX_NAME_LENGTH = 64;

    /** Longest fingerprint accepted. A UUID is 36; the slack is for a future form. */
    public static final int MAX_FINGERPRINT_LENGTH = 128;

    public ServerAnnouncement {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(fingerprint, "fingerprint");
        name = truncate(name.trim(), MAX_NAME_LENGTH);
        ip = ip.trim();
        fingerprint = truncate(fingerprint.trim(), MAX_FINGERPRINT_LENGTH);
        if (ip.isEmpty()) {
            throw new IllegalArgumentException("An announcement needs an ip");
        }
        if (fingerprint.isEmpty()) {
            throw new IllegalArgumentException("An announcement needs a fingerprint");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    /** @return {@code "192.168.1.42:5555"}, the endpoint a client would dial. */
    public String endpoint() {
        return ip + ':' + port;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
