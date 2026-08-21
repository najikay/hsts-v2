package client.core;

import common.dto.discovery.Fingerprints;

import java.util.Objects;

/**
 * A server this client has connected to before, and the id it had (Presentation
 * tier, E19.10, F13.4).
 *
 * <p>Trust on first use, in one record. The first successful connect writes the
 * pair {address, fingerprint}; every later launch compares what it finds at that
 * address against what it remembers.
 *
 * <h2>What a mismatch means, and what it does not</h2>
 *
 * <p>A mismatch means the machine answering at this address is not the machine
 * that was answering here last time. That is genuinely worth interrupting a
 * student for, and the usual causes are innocent: the server was reinstalled, the
 * demo moved to the spare laptop, or DHCP handed the address to somebody else.
 *
 * <p>It does <b>not</b> mean the client can tell a real server from an impostor.
 * The id travels in cleartext to anyone who broadcasts, so anyone who has heard
 * it can repeat it (see {@code server.discovery.ServerFingerprint}). Pinning
 * detects change; it does not resist impersonation. The dialog the mismatch
 * raises is worded to match that claim exactly, because a security promise the
 * product cannot keep is worse than none.
 *
 * @param endpoint    the address and port that was connected to
 * @param fingerprint the id that machine announced, stored in full
 */
public record ServerPin(ServerEndpoint endpoint, String fingerprint) {

    public ServerPin {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(fingerprint, "fingerprint");
        fingerprint = fingerprint.trim();
        if (fingerprint.isEmpty()) {
            throw new IllegalArgumentException("A pin needs a fingerprint");
        }
    }

    /** @return {@code true} when {@code candidate} is the same host and port. */
    public boolean isSameEndpoint(ServerEndpoint candidate) {
        return candidate != null
                && endpoint.port() == candidate.port()
                && endpoint.host().equalsIgnoreCase(candidate.host());
    }

    /**
     * @param candidate an id just announced at this address
     * @return {@code true} when it is the id this pin remembers. Compared in full,
     *         never on the short display form
     */
    public boolean matches(String candidate) {
        return Fingerprints.sameFingerprint(fingerprint, candidate);
    }

    /** @return the grouped display form, for the mismatch dialog. */
    public String shortFingerprint() {
        return Fingerprints.shortForm(fingerprint);
    }
}
