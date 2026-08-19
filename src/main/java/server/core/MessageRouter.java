package server.core;

import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verb → handler registry and the single entry point for everything a client
 * says (Logic tier, E1.6).
 *
 * <p>Per message it: resolves the caller from {@link SessionManager} (never from
 * the payload), refuses verbs that need a session when there is none, invokes
 * the handler, and guarantees exactly one response goes back. Nothing thrown by
 * a handler escapes:
 *
 * <ul>
 *   <li>{@link AuthorizationException} → {@code ERROR} with the guard's code;</li>
 *   <li>anything else → {@code ERROR(INTERNAL)} with a fixed generic sentence —
 *       the real exception is logged with its stack trace and never wired, so a
 *       stack trace or SQL fragment cannot reach a student's screen;</li>
 *   <li>a non-{@link Message} object, {@code null}, or a {@code Message} with no
 *       verb → {@code ERROR(BAD_REQUEST)}. The connection always survives
 *       (E1.11).</li>
 * </ul>
 */
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    /** The only sentence an unexpected server failure is ever allowed to say. */
    public static final String GENERIC_INTERNAL_MESSAGE =
            "Something went wrong on the server. Please try again.";

    /** A unit of server work: pure in, pure out - no sockets, no OCSF. */
    @FunctionalInterface
    public interface Handler {
        Message handle(CallerContext caller, Message request) throws Exception;
    }

    private final SessionManager sessions;
    private final Map<Verb, Handler> handlers = new ConcurrentHashMap<>();
    private final Set<Verb> openVerbs = ConcurrentHashMap.newKeySet();

    public MessageRouter(SessionManager sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    // ===================== Registry ======================================

    /** Registers a handler that requires an authenticated session. */
    public MessageRouter register(Verb verb, Handler handler) {
        return register(verb, handler, false);
    }

    /**
     * Registers a handler reachable without a session.
     *
     * <p>Since E5 the only such verb is {@code LOGIN} - it is by definition how a
     * connection stops being anonymous. Every other verb, the legacy question-bank
     * pair included, goes through {@link #register} and is refused with
     * {@code UNAUTHORIZED} until a session exists.
     */
    public MessageRouter registerOpen(Verb verb, Handler handler) {
        return register(verb, handler, true);
    }

    private MessageRouter register(Verb verb, Handler handler, boolean open) {
        Objects.requireNonNull(verb, "verb");
        Objects.requireNonNull(handler, "handler");
        if (handlers.putIfAbsent(verb, handler) != null) {
            throw new IllegalStateException("A handler is already registered for " + verb);
        }
        if (open) {
            openVerbs.add(verb);
        }
        log.debug("Registered {} handler for {}", open ? "open" : "authenticated", verb);
        return this;
    }

    public boolean isRegistered(Verb verb) {
        return handlers.containsKey(verb);
    }

    /** @return true when this verb may be served without an authenticated session. */
    public boolean isOpen(Verb verb) {
        return openVerbs.contains(verb);
    }

    public Set<Verb> registeredVerbs() {
        return Set.copyOf(handlers.keySet());
    }

    // ===================== Dispatch ======================================

    /**
     * Handles one raw object straight off the OCSF read thread and writes the
     * response back. Never throws, whatever the client sent.
     */
    public void handle(Object raw, ConnectionToClient connection) {
        if (connection == null) {
            log.error("Dropping message with no connection: {}", raw);
            return;
        }
        Message response;
        if (raw instanceof Message request) {
            response = route(request, callerFor(connection));
        } else {
            log.warn("Rejected non-Message object from {}: {}", connection,
                    raw == null ? "null" : raw.getClass().getName());
            response = Message.error(null, null, ErrorCode.BAD_REQUEST,
                    "Unrecognised message type.");
        }
        send(connection, response);
    }

    /**
     * Routes a parsed request for an already-resolved caller and returns the
     * response. This is the whole decision surface, socket-free and directly
     * unit-testable.
     */
    public Message route(Message request, CallerContext caller) {
        if (request == null) {
            return Message.error(null, null, ErrorCode.BAD_REQUEST, "Empty request.");
        }
        Verb verb = request.getVerb();
        if (verb == null) {
            log.warn("Rejected message with no verb from {}", caller);
            return Message.error(request, ErrorCode.BAD_REQUEST, "Request is missing its verb.");
        }

        Handler handler = handlers.get(verb);
        if (handler == null) {
            log.warn("Unsupported verb {} from {}", verb, caller);
            return Message.error(request, ErrorCode.BAD_REQUEST, "Unsupported operation: " + verb);
        }

        if (!openVerbs.contains(verb) && !caller.isAuthenticated()) {
            log.warn("Rejected {} - no authenticated session on the connection", verb);
            return Message.error(request, ErrorCode.UNAUTHORIZED, "You must be signed in to do that.");
        }

        long startedAt = System.nanoTime();
        try {
            Message response = handler.handle(caller, request);
            if (response == null) {
                log.error("Handler for {} returned no response", verb);
                return Message.error(request, ErrorCode.INTERNAL, GENERIC_INTERNAL_MESSAGE);
            }
            log.debug("{} handled for {} in {} ms", verb, caller, elapsedMillis(startedAt));
            return response;
        } catch (AuthorizationException e) {
            log.warn("Refused {} for {}: {}", verb, caller, e.getMessage());
            return Message.error(request, e.errorCode(), e.getMessage());
        } catch (Exception e) {
            // Details to the log, never to the wire.
            log.error("Handler for {} failed after {} ms", verb, elapsedMillis(startedAt), e);
            return Message.error(request, ErrorCode.INTERNAL, GENERIC_INTERNAL_MESSAGE);
        }
    }

    /** Identity comes from the session bound to the socket - nothing else. */
    private CallerContext callerFor(ConnectionToClient connection) {
        return sessions.sessionOf(connection)
                .map(session -> CallerContext.authenticated(connection, session.userId(), session.role()))
                .orElseGet(() -> CallerContext.anonymous(connection));
    }

    private void send(ConnectionToClient connection, Message response) {
        try {
            connection.sendToClient(response);
        } catch (Exception e) {
            // The client vanished mid-answer; the socket layer will clean up.
            log.warn("Could not send {} response to {}: {}",
                    response.getVerb(), connection, e.toString());
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
