package server.core;

import common.protocol.ErrorCode;

import java.util.Objects;

/**
 * Thrown by an {@link Authorization} guard when a caller may not do what they
 * asked (Logic tier, E3.5).
 *
 * <p>Unchecked on purpose: guards are called at the top of service methods and
 * should not pollute every signature between there and the router. The router's
 * central catch turns it into an {@code ERROR} carrying {@link #errorCode()} —
 * {@link ErrorCode#UNAUTHORIZED} when there is no session,
 * {@link ErrorCode#FORBIDDEN} when the session simply is not allowed. The
 * message is written for the user, because it is the one exception message the
 * router does put on the wire.
 */
public class AuthorizationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public AuthorizationException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    /** Convenience for the "no session" case. */
    public static AuthorizationException unauthorized(String message) {
        return new AuthorizationException(ErrorCode.UNAUTHORIZED, message);
    }

    /** Convenience for the "signed in, but not allowed" case. */
    public static AuthorizationException forbidden(String message) {
        return new AuthorizationException(ErrorCode.FORBIDDEN, message);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
