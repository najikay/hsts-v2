package common.protocol;

/**
 * The four kinds of {@link Message} on the wire (Common tier).
 *
 * <p>Direction is implied: {@link #REQUEST} is client → server, {@link #OK} and
 * {@link #ERROR} are the correlated answers (same {@code requestId}), and
 * {@link #PUSH} is a server-initiated message that answers nothing.
 */
public enum Status {

    /** A client request awaiting a correlated response. */
    REQUEST,

    /** A successful response; the payload is the operation's result DTO. */
    OK,

    /**
     * A failed response; {@link Message#getErrorCode()} carries the machine-readable
     * reason and the payload carries a {@code common.dto.ErrorPayload} for humans.
     */
    ERROR,

    /** An unsolicited server → client message on the push channel. */
    PUSH
}
