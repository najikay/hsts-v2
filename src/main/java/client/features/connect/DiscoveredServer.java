package client.features.connect;

import client.core.ServerEndpoint;
import common.dto.discovery.Fingerprints;
import common.dto.discovery.ServerAnnouncement;

import java.util.Objects;

/**
 * One server the client heard back from (Presentation tier, E19.10, F13.4).
 *
 * <p>An announcement plus the display strings the picker needs. The strings are
 * here rather than in the view because the picker's row is a decision about what
 * a person can tell servers apart by, and getting it wrong looks like this:
 * "192.168.1.42" beside "192.168.1.43", which nobody can choose between under
 * pressure. The row is therefore name first, address second, id third.
 *
 * @param name        the friendly name the server announced, or a fallback when
 *                    it announced none
 * @param endpoint    the address and port to connect to
 * @param fingerprint the server's full id, which the client pins
 */
public record DiscoveredServer(String name, ServerEndpoint endpoint, String fingerprint) {

    /** Shown when a server announced no name of its own. */
    public static final String UNNAMED = "HSTS server";

    public DiscoveredServer {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(fingerprint, "fingerprint");
        name = name == null || name.isBlank() ? UNNAMED : name.trim();
    }

    /**
     * @param announcement a decoded reply
     * @return the picker row for it
     */
    public static DiscoveredServer from(ServerAnnouncement announcement) {
        Objects.requireNonNull(announcement, "announcement");
        return new DiscoveredServer(announcement.name(),
                new ServerEndpoint(announcement.ip(), announcement.port()),
                announcement.fingerprint());
    }

    /** @return the short, grouped id form, matching what the server console shows. */
    public String shortFingerprint() {
        return Fingerprints.shortForm(fingerprint);
    }

    /** @return {@code "Room 12 server · 192.168.1.42:5555 · ID 7F3A-2B91"}. */
    public String display() {
        return name + " · " + endpoint.display() + " · ID " + shortFingerprint();
    }

    /** @return {@code true} when this is the same machine as {@code other} claims. */
    public boolean hasFingerprint(String candidate) {
        return Fingerprints.sameFingerprint(fingerprint, candidate);
    }

    @Override
    public String toString() {
        return display();
    }
}
