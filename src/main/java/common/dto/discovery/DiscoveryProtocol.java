package common.dto.discovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The discovery wire format, both directions (Common tier, E19.8, F13.3).
 *
 * <p>Two messages and no state machine. A client broadcasts a fixed magic string;
 * every server that hears it and has discovery on replies with one compact JSON
 * object. There is no handshake, no session and no retry protocol, because the
 * client already has one: it broadcasts, waits two seconds and uses whatever
 * arrived.
 *
 * <h2>Why JSON and not the Message envelope</h2>
 *
 * <p>Everything else in this product travels as a serialized
 * {@code common.protocol.Message} over an accepted TCP connection. This does not,
 * and the difference is the sender: a discovery packet arrives by UDP from an
 * unauthenticated stranger who may have written it by hand. Java deserialization
 * of such a payload is a well-known way to hand an attacker the classpath, so the
 * one place in this system where bytes from nobody in particular are parsed uses
 * a data format that cannot instantiate anything.
 *
 * <h2>Hostile input is the normal case</h2>
 *
 * <p>{@link #decodeAnnouncement} answers {@link Optional#empty()} for every input
 * that is not a well-formed announcement, and never throws. Truncated packets,
 * random bytes, a JSON array where an object belongs, a port of nine million, a
 * name of a megabyte, another protocol's traffic that happens to share the port:
 * all of it is one code path, and that code path returns nothing. The caller logs
 * at debug and carries on, which is the only sane behaviour for a socket the
 * whole subnet can write to.
 *
 * <p>Keys are one character each because the whole reply must comfortably fit one
 * datagram with room to spare; {@link #MAX_PACKET_BYTES} is the cap, and anything
 * larger is refused before it is parsed.
 */
public final class DiscoveryProtocol {

    /** The UDP port servers listen for discovery requests on. Configurable. */
    public static final int DEFAULT_DISCOVERY_PORT = 5556;

    /**
     * The request. A fixed, versioned string rather than an empty packet, so a
     * stray datagram from something else on the network does not draw a reply.
     */
    public static final String REQUEST_MAGIC = "HSTS-DISCOVER-1";

    /**
     * The most bytes either direction may be.
     *
     * <p>512 is comfortably under the smallest datagram size every network is
     * required to carry without fragmenting, and roughly four times the size of a
     * real announcement. Anything bigger is not one of ours.
     */
    public static final int MAX_PACKET_BYTES = 512;

    private static final String KEY_NAME = "n";
    private static final String KEY_IP = "i";
    private static final String KEY_PORT = "p";
    private static final String KEY_FINGERPRINT = "f";

    private static final ObjectMapper JSON = new ObjectMapper()
            // A future field must not break an older client: the whole point of a
            // discovery reply is that mismatched builds can still find each other.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private DiscoveryProtocol() {
    }

    /** @return the bytes a client broadcasts. */
    public static byte[] requestBytes() {
        return REQUEST_MAGIC.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param data   a received datagram's buffer
     * @param length how many bytes of it are real
     * @return {@code true} when this is a discovery request and nothing else
     */
    public static boolean isRequest(byte[] data, int length) {
        if (data == null || length <= 0 || length > MAX_PACKET_BYTES) {
            return false;
        }
        return REQUEST_MAGIC.equals(text(data, length).trim());
    }

    /**
     * @param announcement what to say
     * @return the reply datagram's bytes
     */
    public static byte[] encodeAnnouncement(ServerAnnouncement announcement) {
        ObjectNode node = JSON.createObjectNode();
        node.put(KEY_NAME, announcement.name());
        node.put(KEY_IP, announcement.ip());
        node.put(KEY_PORT, announcement.port());
        node.put(KEY_FINGERPRINT, announcement.fingerprint());
        try {
            return JSON.writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
            // Four strings and an int cannot fail to serialize. If they ever do it
            // is a programming error here, not a network condition.
            throw new IllegalStateException("Could not encode a discovery announcement", e);
        }
    }

    /**
     * Parses a reply. Never throws, whatever the bytes are.
     *
     * @param data   the received buffer
     * @param length how many bytes of it are real
     * @return the announcement, or empty when this was not a valid one
     */
    public static Optional<ServerAnnouncement> decodeAnnouncement(byte[] data, int length) {
        if (data == null || length <= 0 || length > MAX_PACKET_BYTES) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(text(data, length));
            if (node == null || !node.isObject()) {
                return Optional.empty();
            }
            String ip = textField(node, KEY_IP);
            String fingerprint = textField(node, KEY_FINGERPRINT);
            String name = textField(node, KEY_NAME);
            JsonNode port = node.get(KEY_PORT);
            if (ip == null || fingerprint == null || port == null || !port.isInt()) {
                return Optional.empty();
            }
            return Optional.of(new ServerAnnouncement(
                    name == null ? "" : name, ip, port.intValue(), fingerprint));
        } catch (RuntimeException | java.io.IOException e) {
            // Includes Jackson's parse failures, our own record validation and the
            // decoder's own refusals. One path, one answer: this was not ours.
            return Optional.empty();
        }
    }

    private static String textField(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String text(byte[] data, int length) {
        return new String(data, 0, length, StandardCharsets.UTF_8);
    }
}
