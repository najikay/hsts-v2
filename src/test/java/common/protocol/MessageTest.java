package common.protocol;

import common.dto.ErrorPayload;
import common.dto.bank.Question;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for the protocol v2 envelope {@link Message}.
 *
 * <p>The envelope is the one type both JARs must agree on, so the contract under
 * test is: the four factories produce the exact status/errorCode/requestId shape
 * the router and dispatcher rely on, correlation ids survive the round-trip, and
 * a hostile or empty envelope can still be logged without blowing up.
 */
class MessageTest {

    @Nested
    @DisplayName("factories")
    class Factories {

        @Test
        @DisplayName("request() mints a UUID requestId and REQUEST status")
        void requestMintsAnId() {
            Question payload = new Question(7, "2 + 2 = ?", "4");

            Message request = Message.request(Verb.UPDATE_QUESTION, payload);

            assertThat(request.getVerb()).isEqualTo(Verb.UPDATE_QUESTION);
            assertThat(request.getStatus()).isEqualTo(Status.REQUEST);
            assertThat(request.getErrorCode()).isNull();
            assertThat(request.getPayload()).isSameAs(payload);
            assertThat(UUID.fromString(request.getRequestId())).isNotNull();
        }

        @Test
        @DisplayName("two requests never share a requestId")
        void requestIdsAreUnique() {
            Message first = Message.request(Verb.GET_ALL_QUESTIONS, null);
            Message second = Message.request(Verb.GET_ALL_QUESTIONS, null);

            assertThat(first.getRequestId()).isNotEqualTo(second.getRequestId());
        }

        @Test
        @DisplayName("request() refuses a null verb — an unroutable message must not exist")
        void requestRejectsNullVerb() {
            assertThatNullPointerException().isThrownBy(() -> Message.request(null, "x"));
        }

        @Test
        @DisplayName("ok(request, payload) echoes verb and requestId")
        void okEchoesCorrelation() {
            Message request = Message.request(Verb.GET_ALL_QUESTIONS, null);

            Message response = Message.ok(request, List.of());

            assertThat(response.getVerb()).isEqualTo(Verb.GET_ALL_QUESTIONS);
            assertThat(response.getRequestId()).isEqualTo(request.getRequestId());
            assertThat(response.getStatus()).isEqualTo(Status.OK);
            assertThat(response.getErrorCode()).isNull();
            assertThat(response.isOk()).isTrue();
            assertThat(response.isError()).isFalse();
            assertThat(response.isPush()).isFalse();
        }

        @Test
        @DisplayName("ok(verb, id, payload) correlates to a bare request id")
        void okFromRawId() {
            Message response = Message.ok(Verb.LOGIN, "abc-123", "hello");

            assertThat(response.getRequestId()).isEqualTo("abc-123");
            assertThat(response.getStatus()).isEqualTo(Status.OK);
            assertThat(response.getPayload()).isEqualTo("hello");
        }

        @Test
        @DisplayName("ok() refuses a null request")
        void okRejectsNullRequest() {
            assertThatNullPointerException().isThrownBy(() -> Message.ok(null, "x"));
        }

        @Test
        @DisplayName("error() echoes correlation and wraps the text in an ErrorPayload")
        void errorCarriesCodeAndPayload() {
            Message request = Message.request(Verb.UPDATE_QUESTION, null);

            Message response = Message.error(request, ErrorCode.VALIDATION, "Answer is required.");

            assertThat(response.getVerb()).isEqualTo(Verb.UPDATE_QUESTION);
            assertThat(response.getRequestId()).isEqualTo(request.getRequestId());
            assertThat(response.getStatus()).isEqualTo(Status.ERROR);
            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.getPayload()).isEqualTo(new ErrorPayload("Answer is required."));
            assertThat(response.errorMessage()).isEqualTo("Answer is required.");
            assertThat(response.isError()).isTrue();
            assertThat(response.isOk()).isFalse();
            assertThat(response.isPush()).isFalse();
        }

        @Test
        @DisplayName("error() tolerates an unknown verb and id — unparseable input still gets an answer")
        void errorWithoutCorrelation() {
            Message response = Message.error(null, null, ErrorCode.BAD_REQUEST, "Unrecognised message type.");

            assertThat(response.getVerb()).isNull();
            assertThat(response.getRequestId()).isNull();
            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
            assertThat(response.errorMessage()).isEqualTo("Unrecognised message type.");
        }

        @Test
        @DisplayName("error() refuses a null code and a null request")
        void errorRejectsNulls() {
            Message request = Message.request(Verb.LOGIN, null);

            assertThatNullPointerException().isThrownBy(() -> Message.error(request, null, "x"));
            assertThatNullPointerException()
                    .isThrownBy(() -> Message.error(null, ErrorCode.INTERNAL, "x"));
        }

        @Test
        @DisplayName("push() gets PUSH status and its own traceable id")
        void pushIsSelfContained() {
            Message push = Message.push(Verb.PUSH_NOTIFICATION, "You have a new grade");

            assertThat(push.getStatus()).isEqualTo(Status.PUSH);
            assertThat(push.isPush()).isTrue();
            assertThat(push.getErrorCode()).isNull();
            assertThat(UUID.fromString(push.getRequestId())).isNotNull();
            assertThat(push.getPayload()).isEqualTo("You have a new grade");
        }

        @Test
        @DisplayName("push() refuses a null verb")
        void pushRejectsNullVerb() {
            assertThatNullPointerException().isThrownBy(() -> Message.push(null, "x"));
        }

        @Test
        @DisplayName("errorMessage() is null when the payload is not an ErrorPayload")
        void errorMessageOnlyForErrorPayloads() {
            assertThat(Message.request(Verb.LOGIN, "not an error").errorMessage()).isNull();
            assertThat(Message.request(Verb.LOGIN, null).errorMessage()).isNull();
        }
    }

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        @DisplayName("exposes every envelope field for logs")
        void showsAllFields() {
            Message response = Message.error(Verb.LOGIN, "req-9", ErrorCode.UNAUTHORIZED, "Bad credentials.");

            assertThat(response.toString())
                    .startsWith("Message{")
                    .contains("verb=LOGIN")
                    .contains("status=ERROR")
                    .contains("requestId=req-9")
                    .contains("errorCode=UNAUTHORIZED")
                    .contains("Bad credentials.")
                    .endsWith("}");
        }

        @Test
        @DisplayName("is null-safe on a completely empty envelope")
        void handlesNulls() {
            assertThat(new Message(null, null, null, null, null).toString())
                    .isEqualTo("Message{verb=null, status=null, requestId=null, errorCode=null, payload=null}");
        }

        @Test
        @DisplayName("survives a payload whose toString() throws — logging must never be the failure")
        void survivesHostilePayload() {
            Message message = new Message(Verb.LOGIN, "id", Status.REQUEST, null, new HostilePayload());

            assertThat(message.toString())
                    .contains(HostilePayload.class.getName())
                    .contains("toString failed: IllegalStateException");
        }
    }

    @Nested
    @DisplayName("serialization (what OCSF does on the socket)")
    class Serialization {

        @ParameterizedTest
        @EnumSource(Verb.class)
        @DisplayName("every verb survives a round-trip")
        void everyVerbRoundTrips(Verb verb) throws Exception {
            Message restored = roundTrip(Message.request(verb, null));

            assertThat(restored.getVerb()).isEqualTo(verb);
            assertThat(restored.getStatus()).isEqualTo(Status.REQUEST);
            assertThat(restored.getPayload()).isNull();
        }

        @ParameterizedTest
        @EnumSource(ErrorCode.class)
        @DisplayName("every error code survives a round-trip with its message")
        void everyErrorCodeRoundTrips(ErrorCode code) throws Exception {
            Message restored = roundTrip(Message.error(Verb.LOGIN, "r-1", code, "nope"));

            assertThat(restored.getErrorCode()).isEqualTo(code);
            assertThat(restored.getStatus()).isEqualTo(Status.ERROR);
            assertThat(restored.errorMessage()).isEqualTo("nope");
        }

        @Test
        @DisplayName("the requestId survives, so correlation works across the socket")
        void requestIdSurvives() throws Exception {
            Message request = Message.request(Verb.GET_ALL_QUESTIONS, null);

            assertThat(roundTrip(request).getRequestId()).isEqualTo(request.getRequestId());
        }

        @Test
        @DisplayName("a list payload survives intact")
        void listPayloadRoundTrips() throws Exception {
            List<Question> questions = List.of(
                    new Question(1, "Capital of France?", "Paris"),
                    new Question(2, "√81 = ?", "9"));

            Message restored = roundTrip(Message.ok(Message.request(Verb.GET_ALL_QUESTIONS, null), questions));

            assertThat(restored.isOk()).isTrue();
            @SuppressWarnings("unchecked")
            List<Question> payload = (List<Question>) restored.getPayload();
            assertThat(payload).hasSize(2);
            assertThat(payload.get(0).getQuestionText()).isEqualTo("Capital of France?");
            assertThat(payload.get(1).getAnswer()).isEqualTo("9");
        }

        @Test
        @DisplayName("Hebrew text survives (the UI is bilingual)")
        void unicodePayloadRoundTrips() throws Exception {
            Message restored = roundTrip(
                    Message.error(Verb.LOGIN, "r", ErrorCode.INTERNAL, "שגיאת שרת — נסו שוב"));

            assertThat(restored.errorMessage()).isEqualTo("שגיאת שרת — נסו שוב");
        }

        @Test
        @DisplayName("a deserialized envelope is a copy, not a shared reference")
        void roundTripProducesACopy() throws Exception {
            Message original = Message.push(Verb.PUSH_LOCK_CHANGED, "locked");

            Message restored = roundTrip(original);

            assertThat(restored).isNotSameAs(original);
            assertThat(restored.toString()).isEqualTo(original.toString());
        }
    }

    @Test
    @DisplayName("Status and ErrorCode names are stable (both JARs deserialize by name)")
    void enumNamesAreStable() {
        assertThat(Status.values())
                .containsExactly(Status.REQUEST, Status.OK, Status.ERROR, Status.PUSH);
        assertThat(ErrorCode.valueOf("TIMEOUT")).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(Status.valueOf("PUSH")).isEqualTo(Status.PUSH);
    }

    /** A payload that breaks the usual "toString is harmless" assumption. */
    private static final class HostilePayload implements Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public String toString() {
            throw new IllegalStateException("boom");
        }
    }

    /** Serializes and deserializes through real object streams, exactly as OCSF does. */
    static <T extends Serializable> T roundTrip(T original) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            @SuppressWarnings("unchecked")
            T restored = (T) in.readObject();
            return restored;
        }
    }
}
