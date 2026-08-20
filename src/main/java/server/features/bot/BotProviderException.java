package server.features.bot;

import java.util.Objects;

/**
 * A provider could not answer, and why (Logic tier, E16.1 — ADR-009).
 *
 * <p>The taxonomy exists so {@link ProviderChain} can make one decision without
 * reading anybody's error strings: <b>should this provider be trusted again in a
 * moment?</b> A rate limit or a 500 says the provider is having a bad minute and
 * should be skipped for a while; a malformed response says the same, because a
 * provider answering nonsense is down in every sense that matters here. A bad key
 * says it will never work in this process, so it is marked unhealthy for the same
 * window and re-checked rather than retried in a tight loop.
 *
 * <p>None of these ever reach a student. The chain turns "every provider failed"
 * into the one S-32 sentence ({@code BotAnswer.S32_FALLBACK}); the reason lands
 * in the log, where somebody can act on it.
 */
public class BotProviderException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why a provider could not answer. */
    public enum Kind {

        /** The key is missing, wrong, or has been revoked (HTTP 401/403). */
        AUTH,

        /** The provider is throttling us (HTTP 429). */
        RATE_LIMITED,

        /** The request did not come back in time. */
        TIMEOUT,

        /** The provider failed on its own side (HTTP 5xx). */
        SERVER,

        /** A response arrived that this adapter could not read as an answer. */
        MALFORMED;

        /**
         * @return {@code true} when re-sending the same request immediately might
         *         work. Only {@link #TIMEOUT} and {@link #SERVER} qualify: a
         *         throttle needs time rather than another attempt, a bad key needs
         *         a human, and a response we cannot parse will not parse better
         *         the second time
         */
        public boolean isWorthOneRetry() {
            return this == TIMEOUT || this == SERVER;
        }
    }

    private final Kind kind;
    private final String provider;

    public BotProviderException(String provider, Kind kind, String message) {
        this(provider, kind, message, null);
    }

    public BotProviderException(String provider, Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.provider = Objects.requireNonNull(provider, "provider");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public Kind kind() {
        return kind;
    }

    /** @return the adapter that failed, for the log line. */
    public String provider() {
        return provider;
    }

    @Override
    public String toString() {
        return provider + '/' + kind + ": " + getMessage();
    }
}
