package server.core;

import common.protocol.Message;
import common.protocol.Status;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code HELLO} answers anyone, and says nothing (B-49).
 *
 * <p>Two properties, and they pull against each other, which is why both are
 * pinned here. It has to be reachable by a connection that has not signed in,
 * because it is asked before anyone has had the chance to; and being reachable
 * by an anonymous caller is exactly why it must not carry a payload for an
 * unauthenticated stranger to read.
 */
class HelloResponderTest {

    private MessageRouter router;

    @BeforeEach
    void registerHello() {
        router = HelloResponder.registerOn(new MessageRouter(new SessionManager()));
    }

    @Test
    @DisplayName("HELLO is open: an anonymous connection gets OK, not UNAUTHORIZED")
    void answersAnAnonymousCaller() {
        Message response = router.route(Message.request(Verb.HELLO, null),
                CallerContext.anonymous(null));

        assertThat(response.isOk()).isTrue();
        assertThat(response.getStatus()).isEqualTo(Status.OK);
        assertThat(router.isOpen(Verb.HELLO))
                .as("requiring a session would make the verb useless")
                .isTrue();
    }

    @Test
    @DisplayName("the answer carries nothing: an anonymous caller learns only that a server is up")
    void tellsAnAnonymousCallerNothing() {
        Message response = router.route(Message.request(Verb.HELLO, null),
                CallerContext.anonymous(null));

        assertThat(response.getPayload()).isNull();
    }

    @Test
    @DisplayName("the answer is correlated, so the dispatcher can complete the right future")
    void correlatesToTheRequest() {
        Message request = Message.request(Verb.HELLO, null);

        Message response = router.route(request, CallerContext.anonymous(null));

        assertThat(response.getRequestId()).isEqualTo(request.getRequestId());
        assertThat(response.getVerb()).isEqualTo(Verb.HELLO);
    }
}
