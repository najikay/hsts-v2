package common.protocol;

import common.dto.ErrorPayload;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The single wire envelope exchanged between the JavaFX client and the fat
 * server over OCSF (Common tier) — protocol v2 (ARCHITECTURE §3).
 *
 * <pre>
 *   verb      : the operation (never null on a well-formed message)
 *   requestId : UUID minted by the client; responses echo it so the
 *               {@code RequestDispatcher} can complete the right future
 *   status    : REQUEST | OK | ERROR | PUSH
 *   errorCode : machine-readable reason, non-null exactly on ERROR
 *   payload   : a typed DTO from {@code common.dto} — never a raw map
 * </pre>
 *
 * <p>Instances are immutable and {@link Serializable}; whatever is placed in the
 * payload MUST itself be {@code Serializable} or the OCSF object stream will
 * fail on write. Build messages through the static factories — the all-args
 * constructor is the low-level escape hatch (and the one the deserializer
 * effectively uses), so it deliberately accepts nulls: a hostile or truncated
 * peer must produce a {@code Message} the router can reject, not an exception
 * mid-stream.
 */
public final class Message implements Serializable {

    /**
     * Bumped 1 → 2 with protocol v2 (the field set changed completely). Keep
     * stable from here so client and server JARs stay wire-compatible.
     */
    private static final long serialVersionUID = 2L;

    private final Verb verb;
    private final String requestId;
    private final Status status;
    private final ErrorCode errorCode;
    private final Object payload;

    /**
     * Low-level constructor. Prefer {@link #request}, {@link #ok},
     * {@link #error} or {@link #push}; every argument may be {@code null}.
     */
    public Message(Verb verb, String requestId, Status status, ErrorCode errorCode, Object payload) {
        this.verb = verb;
        this.requestId = requestId;
        this.status = status;
        this.errorCode = errorCode;
        this.payload = payload;
    }

    // ===================== Factories =====================================

    /** A client request with a freshly minted {@code requestId}. */
    public static Message request(Verb verb, Object payload) {
        Objects.requireNonNull(verb, "verb");
        return new Message(verb, newRequestId(), Status.REQUEST, null, payload);
    }

    /** A successful response correlated to {@code request}. */
    public static Message ok(Message request, Object payload) {
        Objects.requireNonNull(request, "request");
        return new Message(request.verb, request.requestId, Status.OK, null, payload);
    }

    /**
     * A successful response correlated to a raw request id — for handlers that
     * only kept the id (or replay a stored one).
     */
    public static Message ok(Verb verb, String requestId, Object payload) {
        return new Message(verb, requestId, Status.OK, null, payload);
    }

    /** A failed response correlated to {@code request}, carrying an {@link ErrorPayload}. */
    public static Message error(Message request, ErrorCode code, String message) {
        Objects.requireNonNull(request, "request");
        return error(request.verb, request.requestId, code, message);
    }

    /** A failed response correlated to a raw request id (may be {@code null} for unparseable input). */
    public static Message error(Verb verb, String requestId, ErrorCode code, String message) {
        Objects.requireNonNull(code, "code");
        return new Message(verb, requestId, Status.ERROR, code, new ErrorPayload(message));
    }

    /**
     * A server-initiated push. It answers no request, but still gets an id so a
     * single delivery can be followed across the server log and the client log.
     */
    public static Message push(Verb verb, Object payload) {
        Objects.requireNonNull(verb, "verb");
        return new Message(verb, newRequestId(), Status.PUSH, null, payload);
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    // ===================== Accessors =====================================

    public Verb getVerb() {
        return verb;
    }

    public String getRequestId() {
        return requestId;
    }

    public Status getStatus() {
        return status;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getPayload() {
        return payload;
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    public boolean isError() {
        return status == Status.ERROR;
    }

    public boolean isPush() {
        return status == Status.PUSH;
    }

    /**
     * @return the human-readable text of an {@link ErrorPayload} payload, or
     *         {@code null} when this is not an error carrying one.
     */
    public String errorMessage() {
        return payload instanceof ErrorPayload error ? error.message() : null;
    }

    @Override
    public String toString() {
        return "Message{verb=" + verb
                + ", status=" + status
                + ", requestId=" + requestId
                + ", errorCode=" + errorCode
                + ", payload=" + describe(payload) + '}';
    }

    /**
     * Log lines must survive a payload with a broken {@code toString()} — the
     * envelope is logged on the OCSF read thread before anything validates it.
     */
    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return String.valueOf(value);
        } catch (RuntimeException e) {
            return value.getClass().getName() + "@<toString failed: " + e.getClass().getSimpleName() + '>';
        }
    }
}
