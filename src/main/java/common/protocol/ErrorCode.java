package common.protocol;

/**
 * Machine-readable failure reasons carried by every {@link Status#ERROR}
 * message (Common tier).
 *
 * <p>The client switches on the code (retry? re-login? show a conflict dialog?)
 * and shows the human text from the {@code ErrorPayload}. Server internals —
 * stack traces, SQL, class names — are logged, never wired.
 */
public enum ErrorCode {

    /** Unparseable or structurally invalid request (unknown verb, missing verb, wrong payload type). */
    BAD_REQUEST,

    /** The request was understood but its data breaks a business rule. */
    VALIDATION,

    /** No authenticated session on this connection — the caller must log in. */
    UNAUTHORIZED,

    /** Authenticated, but this user may not perform this operation on this entity. */
    FORBIDDEN,

    /** The addressed entity does not exist (or is not visible to the caller). */
    NOT_FOUND,

    /** A concurrency/state clash: duplicate login, stale optimistic version, held edit lock. */
    CONFLICT,

    /** Unexpected server-side failure. The client only ever sees a generic message. */
    INTERNAL,

    /** Client-side only: no response arrived within the dispatcher's timeout window. */
    TIMEOUT
}
