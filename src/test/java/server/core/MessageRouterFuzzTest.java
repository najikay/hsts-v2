package server.core;

import common.dto.ErrorPayload;
import common.dto.auth.LoginRequest;
import common.dto.auth.Role;
import common.dto.bank.Question;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Status;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Hostile-input fuzz suite for {@link MessageRouter} (E1.11 ⚑).
 *
 * <p>A client is an untrusted peer: it can send a truncated envelope, a
 * deliberately deep structure, a payload of the wrong type, or an object that is
 * not a {@code Message} at all. The invariant this suite defends is narrow and
 * absolute — <b>the router never throws out, always answers with an ERROR (or
 * safely ignores), and never touches the connection beyond writing that one
 * response</b>. If this breaks, one malformed packet takes a client's socket
 * down mid-exam.
 *
 * <p>The seeded {@link Random} keeps failures reproducible: the seed is printed
 * in the assertion message.
 */
@ExtendWith(MockitoExtension.class)
class MessageRouterFuzzTest {

    private MessageRouter router;
    private AtomicInteger handlerInvocations;

    @Mock
    private ConnectionToClient connection;

    @BeforeEach
    void setUp() {
        router = new MessageRouter(new SessionManager());
        handlerInvocations = new AtomicInteger();
        // One open and one guarded handler, both hostile to bad input themselves.
        router.registerOpen(Verb.GET_ALL_QUESTIONS, (caller, request) -> {
            handlerInvocations.incrementAndGet();
            return Message.ok(request, List.of());
        });
        router.registerOpen(Verb.UPDATE_QUESTION, (caller, request) -> {
            handlerInvocations.incrementAndGet();
            // Deliberately careless: a cast that fails on anything but a Question.
            Question question = (Question) request.getPayload();
            return Message.ok(request, question.getId());
        });
        router.register(Verb.LOGOUT, (caller, request) -> {
            handlerInvocations.incrementAndGet();
            return Message.ok(request, caller.userId());
        });
    }

    @RepeatedTest(value = 50, name = "random hostile object {currentRepetition}/{totalRepetitions}")
    @DisplayName("random objects and malformed envelopes always get an answer, never an exception")
    void randomInputIsAlwaysAnswered() {
        long seed = System.nanoTime();
        Random random = new Random(seed);
        ConnectionToClient victim = mock(ConnectionToClient.class);

        for (int i = 0; i < 40; i++) {
            Object hostile = randomHostileInput(random);
            assertThatCode(() -> router.handle(hostile, victim))
                    .as("router threw on %s (seed=%d)", describe(hostile), seed)
                    .doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @EnumSource(Verb.class)
    @DisplayName("every verb — registered or not — answers with a well-formed Message")
    void everyVerbIsAnswered(Verb verb) throws Exception {
        ConnectionToClient victim = mock(ConnectionToClient.class);

        router.handle(new Message(verb, "id", Status.REQUEST, null, "surprise payload"), victim);

        Message response = captureSingleResponse(victim);
        assertThat(response.getStatus()).isIn(Status.OK, Status.ERROR);
        if (response.isError()) {
            assertThat(response.getErrorCode()).isNotNull();
            assertThat(response.getPayload()).isInstanceOf(ErrorPayload.class);
        }
    }

    @Test
    @DisplayName("a Message with every field null is answered with BAD_REQUEST")
    void allNullMessage() throws Exception {
        router.handle(new Message(null, null, null, null, null), connection);

        Message response = captureSingleResponse(connection);
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("a response-shaped message arriving as a request is still handled safely")
    void inboundResponseShapeIsSafe() throws Exception {
        router.handle(Message.error(Verb.GET_ALL_QUESTIONS, "id", ErrorCode.INTERNAL, "spoofed"), connection);

        Message response = captureSingleResponse(connection);
        assertThat(response.getStatus()).isEqualTo(Status.OK); // the verb is open and the handler ignores payloads
    }

    @Test
    @DisplayName("a payload of the wrong type crashes the handler and comes back as INTERNAL")
    void wrongPayloadTypeIsContained() throws Exception {
        router.handle(Message.request(Verb.UPDATE_QUESTION, "not a question"), connection);

        Message response = captureSingleResponse(connection);
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(response.errorMessage()).isEqualTo(MessageRouter.GENERIC_INTERNAL_MESSAGE);
        assertThat(response.errorMessage()).doesNotContain("ClassCastException");
    }

    @Test
    @DisplayName("a payload whose toString() explodes cannot stop the answer")
    void hostileToStringIsContained() throws Exception {
        router.handle(Message.request(Verb.UPDATE_QUESTION, new ExplodingPayload()), connection);

        Message response = captureSingleResponse(connection);
        assertThat(response.isError()).isTrue();
    }

    @Test
    @DisplayName("a deeply self-referential payload is answered, not recursed into")
    void selfReferentialPayloadIsSafe() throws Exception {
        List<Object> loop = new ArrayList<>();
        loop.add(loop);

        router.handle(Message.request(Verb.GET_ALL_QUESTIONS, loop), connection);

        assertThat(captureSingleResponse(connection).isOk()).isTrue();
    }

    @Test
    @DisplayName("garbage never reaches a handler, and never closes the connection")
    void connectionObjectIsUntouched() throws Exception {
        router.handle("not a message", connection);
        router.handle(null, connection);
        router.handle(new Message(null, null, Status.REQUEST, null, null), connection);
        router.handle(Message.request(Verb.LOGOUT, null), connection); // guarded, no session

        assertThat(handlerInvocations).hasValue(0);
        verify(connection, atLeastOnce()).sendToClient(any());
        verify(connection, never()).close();
        verify(connection, never()).setInfo(any(), any());
        verifyNoMoreInteractions(connection);
    }

    // ===================== Hostile input generator ========================

    private static Object randomHostileInput(Random random) {
        return switch (random.nextInt(12)) {
            case 0 -> null;
            case 1 -> "a bare string";
            case 2 -> random.nextInt();
            case 3 -> new byte[]{1, 2, 3};
            case 4 -> new HashMap<>();
            case 5 -> new Message(null, null, null, null, null);
            case 6 -> new Message(randomVerb(random), null, null, null, null);
            case 7 -> new Message(randomVerb(random), randomString(random), randomStatus(random),
                    randomErrorCode(random), randomPayload(random));
            case 8 -> Message.request(anyVerb(random), randomPayload(random));
            case 9 -> new Message(randomVerb(random), randomString(random), Status.PUSH, null,
                    new ExplodingPayload());
            case 10 -> new Object();
            default -> new Message(randomVerb(random), "", Status.REQUEST, ErrorCode.INTERNAL,
                    new LoginRequest(randomString(random), randomString(random)));
        };
    }

    /** Sometimes {@code null} — a truncated envelope is a realistic hostile input. */
    private static Verb randomVerb(Random random) {
        return random.nextInt(6) == 0 ? null : anyVerb(random);
    }

    private static Verb anyVerb(Random random) {
        return Verb.values()[random.nextInt(Verb.values().length)];
    }

    private static Status randomStatus(Random random) {
        return random.nextInt(6) == 0 ? null : Status.values()[random.nextInt(Status.values().length)];
    }

    private static ErrorCode randomErrorCode(Random random) {
        return random.nextInt(2) == 0 ? null : ErrorCode.values()[random.nextInt(ErrorCode.values().length)];
    }

    private static Object randomPayload(Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> null;
            case 1 -> randomString(random);
            case 2 -> new Question(random.nextInt(), randomString(random), null);
            case 3 -> new ExplodingPayload();
            case 4 -> Arrays.asList(null, randomString(random), new Object());
            default -> new HashMap<>();
        };
    }

    private static String randomString(Random random) {
        int length = random.nextInt(20);
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append((char) (random.nextInt(0x5D0 + 26) + 1));
        }
        return text.toString();
    }

    private static String describe(Object hostile) {
        if (hostile == null) {
            return "null";
        }
        try {
            return hostile.getClass().getName() + " = " + hostile;
        } catch (RuntimeException e) {
            return hostile.getClass().getName() + " = <toString failed>";
        }
    }

    private static Message captureSingleResponse(ConnectionToClient target) throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(target).sendToClient(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(Message.class);
        return (Message) captor.getValue();
    }

    /** A payload that misbehaves exactly where logging code is tempted to trust it. */
    private static final class ExplodingPayload implements Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public String toString() {
            throw new IllegalStateException("toString exploded");
        }

        @Override
        public int hashCode() {
            throw new IllegalStateException("hashCode exploded");
        }

        @Override
        public boolean equals(Object other) {
            throw new IllegalStateException("equals exploded");
        }
    }
}
