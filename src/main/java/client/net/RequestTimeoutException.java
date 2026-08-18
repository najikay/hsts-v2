package client.net;

import common.protocol.ErrorCode;
import common.protocol.Verb;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Thrown into a {@link RequestDispatcher} future when the server does not answer
 * within the configured window (Presentation tier).
 *
 * <p>Extends {@link TimeoutException} so ordinary {@code CompletableFuture}
 * plumbing recognises it, and carries {@link ErrorCode#TIMEOUT} so a screen can
 * render a timeout exactly like any server-sent error instead of special-casing
 * an exception type.
 */
public class RequestTimeoutException extends TimeoutException {

    private static final long serialVersionUID = 1L;

    private final transient Verb verb;
    private final String requestId;

    public RequestTimeoutException(Verb verb, String requestId, Duration waited) {
        super("No response to " + verb + " (requestId=" + requestId + ") within " + waited.toMillis() + " ms");
        this.verb = verb;
        this.requestId = requestId;
    }

    /** Always {@link ErrorCode#TIMEOUT} — the client-side mapping of this failure. */
    public ErrorCode errorCode() {
        return ErrorCode.TIMEOUT;
    }

    public Verb verb() {
        return verb;
    }

    public String requestId() {
        return requestId;
    }
}
