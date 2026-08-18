package common.dto.auth;

import java.io.Serializable;

/**
 * The {@link common.protocol.Verb#LOGIN} request payload (Common tier).
 *
 * <p>The password crosses the socket in the clear — phase 1 is a LAN-only
 * deployment (S-42) and the transport is plain OCSF object streams; only the
 * BCrypt hash is ever stored (ADR-005). {@code toString()} is overridden so a
 * logged envelope can never print it.
 */
public record LoginRequest(String username, String password) implements Serializable {

    private static final long serialVersionUID = 1L;

    @Override
    public String toString() {
        return "LoginRequest{username=" + username + ", password=***}";
    }
}
