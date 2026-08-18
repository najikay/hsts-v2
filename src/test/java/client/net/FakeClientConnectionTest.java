package client.net;

import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Status;
import common.protocol.Verb;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIOException;

/**
 * Unit tests for the test double itself (E1.9).
 *
 * <p>A fake that lies is worse than no fake: every session test in E4–E14 will
 * trust that this class records what was sent, answers correlated to the actual
 * request, and fails exactly where a real socket would. So it gets the same
 * treatment as production code.
 */
class FakeClientConnectionTest {

    private final FakeClientConnection connection = new FakeClientConnection();

    @Test
    @DisplayName("starts closed and opens on connect()")
    void lifecycle() throws IOException {
        assertThat(connection.isConnectionOpen()).isFalse();

        connection.connect();
        assertThat(connection.isConnectionOpen()).isTrue();

        connection.disconnect();
        assertThat(connection.isConnectionOpen()).isFalse();
    }

    @Test
    @DisplayName("reports the host and port it was built with")
    void exposesEndpoint() {
        FakeClientConnection custom = new FakeClientConnection("10.0.0.7", 6000);

        assertThat(custom.getHost()).isEqualTo("10.0.0.7");
        assertThat(custom.getPort()).isEqualTo(6000);
        assertThat(connection.getHost()).isEqualTo("fake-host");
        assertThat(connection.getPort()).isEqualTo(5555);
    }

    @Test
    @DisplayName("records every message in order")
    void recordsTraffic() throws IOException {
        connection.send(Message.request(Verb.LOGIN, "a"));
        connection.send(Message.request(Verb.GET_ALL_QUESTIONS, null));

        assertThat(connection.sentCount()).isEqualTo(2);
        assertThat(connection.sentMessages())
                .extracting(Message::getVerb)
                .containsExactly(Verb.LOGIN, Verb.GET_ALL_QUESTIONS);
        assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.GET_ALL_QUESTIONS);
    }

    @Test
    @DisplayName("lastSent() is null before anything is sent, and clearSent() forgets history")
    void clearingHistory() throws IOException {
        assertThat(connection.lastSent()).isNull();

        connection.send(Message.request(Verb.LOGIN, null));
        connection.clearSent();

        assertThat(connection.sentCount()).isZero();
        assertThat(connection.lastSent()).isNull();
    }

    @Test
    @DisplayName("replyOk answers the actual request, so the requestId correlates")
    void scriptedOkIsCorrelated() throws IOException {
        List<Message> received = new ArrayList<>();
        connection.setServerMessageHandler(received::add);
        connection.replyOk(Verb.GET_ALL_QUESTIONS, List.of("one", "two"));

        Message request = Message.request(Verb.GET_ALL_QUESTIONS, null);
        connection.send(request);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).getRequestId()).isEqualTo(request.getRequestId());
        assertThat(received.get(0).getStatus()).isEqualTo(Status.OK);
        assertThat(received.get(0).getPayload()).isEqualTo(List.of("one", "two"));
    }

    @Test
    @DisplayName("replyError answers with the scripted code and message")
    void scriptedError() throws IOException {
        List<Message> received = new ArrayList<>();
        connection.setServerMessageHandler(received::add);
        connection.replyError(Verb.LOGIN, ErrorCode.UNAUTHORIZED, "Wrong password.");

        connection.send(Message.request(Verb.LOGIN, null));

        assertThat(received.get(0).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(received.get(0).errorMessage()).isEqualTo("Wrong password.");
    }

    @Test
    @DisplayName("respondTo can build any answer from the request")
    void customResponder() throws IOException {
        List<Message> received = new ArrayList<>();
        connection.setServerMessageHandler(received::add);
        connection.respondTo(Verb.UPDATE_QUESTION, request -> Message.ok(request, "saved:" + request.getPayload()));

        connection.send(Message.request(Verb.UPDATE_QUESTION, 42));

        assertThat(received.get(0).getPayload()).isEqualTo("saved:42");
    }

    @Test
    @DisplayName("an unscripted verb is recorded but left unanswered")
    void unscriptedVerbGetsNoAnswer() throws IOException {
        List<Message> received = new ArrayList<>();
        connection.setServerMessageHandler(received::add);

        connection.send(Message.request(Verb.LOGOUT, null));

        assertThat(connection.sentCount()).isEqualTo(1);
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("a message with no verb is recorded and never matches a responder")
    void verblessMessageIsSafe() throws IOException {
        connection.replyOk(Verb.LOGIN, "x");

        assertThatCode(() -> connection.send(new Message(null, "id", Status.REQUEST, null, null)))
                .doesNotThrowAnyException();
        assertThat(connection.sentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("scripted send failures throw — after recording the attempt")
    void sendFailure() {
        connection.failSendsWith(new IOException("socket closed"));

        assertThatIOException()
                .isThrownBy(() -> connection.send(Message.request(Verb.LOGIN, null)))
                .withMessage("socket closed");
        assertThat(connection.sentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("scripted connect failures throw and leave the connection closed")
    void connectFailure() {
        connection.failConnectWith(new IOException("no route to host"));

        assertThatIOException().isThrownBy(connection::connect).withMessage("no route to host");
        assertThat(connection.isConnectionOpen()).isFalse();
    }

    @Test
    @DisplayName("failures can be cleared again")
    void failuresAreClearable() throws IOException {
        connection.failConnectWith(new IOException("down")).failSendsWith(new IOException("down"));

        connection.failConnectWith(null).failSendsWith(null);
        connection.connect();
        connection.send(Message.request(Verb.LOGIN, null));

        assertThat(connection.isConnectionOpen()).isTrue();
    }

    @Test
    @DisplayName("pushToClient injects a server-initiated message")
    void injectsPushes() {
        List<Message> received = new ArrayList<>();
        connection.setServerMessageHandler(received::add);

        connection.pushToClient(Verb.PUSH_NOTIFICATION, "new grade");

        assertThat(received).hasSize(1);
        assertThat(received.get(0).getStatus()).isEqualTo(Status.PUSH);
        assertThat(received.get(0).getVerb()).isEqualTo(Verb.PUSH_NOTIFICATION);
        assertThat(received.get(0).getPayload()).isEqualTo("new grade");
    }

    @Test
    @DisplayName("delivering with no handler registered is a no-op, not a crash")
    void deliverWithoutHandler() {
        assertThatCode(() -> connection.pushToClient(Verb.PUSH_LOCK_CHANGED, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("it is a drop-in IClientConnection")
    void satisfiesTheAdapterContract() {
        assertThat(connection).isInstanceOf(IClientConnection.class);
    }
}
