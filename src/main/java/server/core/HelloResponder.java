package server.core;

import common.protocol.Message;
import common.protocol.Verb;

import java.util.Objects;

/**
 * Answers {@code HELLO} so a client can tell a live server from an open socket
 * (Logic tier, B-49).
 *
 * <h2>Why a verb exists at all for this</h2>
 *
 * <p>A TCP connect is not evidence that anybody is home. The operating system
 * completes the handshake into the listening socket's accept backlog, so a
 * server that has stopped accepting — the console's Stop listening, a process
 * wedged mid-shutdown, a port forwarded to nothing — hands a connecting client a
 * socket that will never be read from. The client believed that socket and sat
 * on {@code Connecting...} until somebody closed a window.
 *
 * <p>{@code HELLO} is the smallest thing that cannot be faked by the kernel: a
 * round trip. Something on the far end has to have read a request and written a
 * response, which means an accept loop, a router and a thread are all alive.
 * {@code client.features.connect.ConnectHandshake} asks it once, immediately
 * after the socket opens, and refuses the connection if no answer arrives.
 *
 * <h2>Open, and empty, on purpose</h2>
 *
 * <p>Registered with {@link MessageRouter#registerOpen}: it is asked before
 * anyone has signed in, so requiring a session would make it useless. That makes
 * it the second verb reachable by an anonymous connection after {@code LOGIN},
 * so it answers with a {@code null} payload and reveals nothing — no version, no
 * server name, no user counts. An unauthenticated caller learns exactly one
 * fact, and it is the one fact the socket already told them: this port belongs
 * to an HSTS server that is running.
 */
public final class HelloResponder {

    private HelloResponder() {
    }

    /**
     * Registers the {@code HELLO} handler.
     *
     * @param router the router to register on
     * @return {@code router}, so this can be chained into an assembly sequence
     */
    public static MessageRouter registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.registerOpen(Verb.HELLO, HelloResponder::answer);
        return router;
    }

    /**
     * The whole handler: an {@code OK} with nothing in it.
     *
     * <p>Package-visible so the response shape is asserted directly rather than
     * inferred from a round trip through a socket.
     *
     * @param caller  ignored; this verb is the same for everyone, signed in or not
     * @param request the {@code HELLO} being answered
     * @return {@code OK} carrying the request's id and a {@code null} payload
     */
    static Message answer(CallerContext caller, Message request) {
        return Message.ok(request, null);
    }
}
