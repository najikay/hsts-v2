package server.core;

import common.dto.auth.Role;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Status;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link MessageRouter} (E1.6, E1.10).
 *
 * <p>The router is the server's front door, so these tests are written from an
 * attacker's point of view as much as a caller's: identity must come from the
 * session and not the payload, an unauthenticated caller must not reach a
 * guarded handler, and no handler failure — expected or not — may leak server
 * internals or leave the client without an answer.
 */
@ExtendWith(MockitoExtension.class)
class MessageRouterTest {

    private static final long ALICE = 1L;

    private SessionManager sessions;
    private MessageRouter router;

    @Mock
    private ConnectionToClient connection;

    @BeforeEach
    void setUp() {
        sessions = new SessionManager();
        router = new MessageRouter(sessions);
    }

    private static Message reply(Message request, String text) {
        return Message.ok(request, text);
    }

    @Nested
    @DisplayName("registry")
    class Registry {

        @Test
        @DisplayName("registers authenticated and open handlers separately")
        void registrationKinds() {
            router.register(Verb.LOGOUT, (caller, request) -> reply(request, "bye"));
            router.registerOpen(Verb.LOGIN, (caller, request) -> reply(request, "hi"));

            assertThat(router.isRegistered(Verb.LOGOUT)).isTrue();
            assertThat(router.isOpen(Verb.LOGOUT)).isFalse();
            assertThat(router.isOpen(Verb.LOGIN)).isTrue();
            assertThat(router.isRegistered(Verb.BANK_LIST)).isFalse();
            assertThat(router.registeredVerbs()).containsExactlyInAnyOrder(Verb.LOGOUT, Verb.LOGIN);
        }

        @Test
        @DisplayName("registering a verb twice is a boot-time failure, not a silent overwrite")
        void doubleRegistrationFails() {
            router.register(Verb.LOGOUT, (caller, request) -> reply(request, "one"));

            assertThatIllegalStateException()
                    .isThrownBy(() -> router.register(Verb.LOGOUT, (caller, request) -> reply(request, "two")))
                    .withMessageContaining("LOGOUT");
        }

        @Test
        @DisplayName("null verbs, handlers and session managers are refused")
        void nullsAreRefused() {
            assertThatNullPointerException().isThrownBy(() -> new MessageRouter(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> router.register(null, (caller, request) -> null));
            assertThatNullPointerException().isThrownBy(() -> router.register(Verb.LOGIN, null));
        }
    }

    @Nested
    @DisplayName("routing")
    class Routing {

        @Test
        @DisplayName("dispatches to the registered handler and echoes the correlation")
        void dispatchesToHandler() {
            router.registerOpen(Verb.BANK_LIST, (caller, request) -> reply(request, "questions"));
            Message request = Message.request(Verb.BANK_LIST, null);

            Message response = router.route(request, CallerContext.anonymous(connection));

            assertThat(response.getStatus()).isEqualTo(Status.OK);
            assertThat(response.getVerb()).isEqualTo(Verb.BANK_LIST);
            assertThat(response.getRequestId()).isEqualTo(request.getRequestId());
            assertThat(response.getPayload()).isEqualTo("questions");
        }

        @Test
        @DisplayName("an unknown verb gets BAD_REQUEST, not silence")
        void unknownVerbIsRejected() {
            Message response = router.route(Message.request(Verb.LOGIN, null),
                    CallerContext.anonymous(connection));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
            assertThat(response.errorMessage()).isEqualTo(MessageRouter.UNSUPPORTED_VERB_MESSAGE);
        }

        /**
         * The sentence for an unhandled verb names no verb (2026-08-30, wave 6, B-35).
         *
         * <p>This used to assert the opposite - that the answer {@code contains("LOGIN")} -
         * because the router built the sentence as {@code "Unsupported operation: " + verb}.
         * Several client sessions render {@code errorMessage()} straight into a label, so the
         * enum constant was reachable copy; the verb belongs in the server log, which is why
         * the {@code WARN} line above the return still carries it.
         */
        @Test
        @DisplayName("⚑ B-35: the unknown-verb sentence carries no protocol constant")
        void unknownVerbSentenceNamesNoVerb() {
            for (Verb verb : List.of(Verb.LOGIN, Verb.PUSH_NOTIFICATION, Verb.BANK_LIST)) {
                Message response = router.route(Message.request(verb, null),
                        CallerContext.anonymous(connection));

                assertThat(response.errorMessage())
                        .as("the answer to an unhandled %s", verb)
                        .isEqualTo(MessageRouter.UNSUPPORTED_VERB_MESSAGE)
                        .doesNotContain(verb.name())
                        .doesNotContain("_")
                        .startsWith("That action")
                        .endsWith(".");
            }
        }

        @Test
        @DisplayName("a message with no verb gets BAD_REQUEST")
        void verblessMessageIsRejected() {
            Message response = router.route(new Message(null, "id-1", Status.REQUEST, null, null),
                    CallerContext.anonymous(connection));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
            assertThat(response.getRequestId()).isEqualTo("id-1");
        }

        @Test
        @DisplayName("a null request gets BAD_REQUEST")
        void nullRequestIsRejected() {
            Message response = router.route(null, CallerContext.anonymous(connection));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("a handler returning nothing is an INTERNAL error, never a dropped request")
        void nullHandlerResultIsInternal() {
            router.registerOpen(Verb.LOGIN, (caller, request) -> null);

            Message response = router.route(Message.request(Verb.LOGIN, null),
                    CallerContext.anonymous(connection));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(response.errorMessage()).isEqualTo(MessageRouter.GENERIC_INTERNAL_MESSAGE);
        }
    }

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("a guarded verb is refused with UNAUTHORIZED when there is no session")
        void guardedVerbNeedsASession() {
            AtomicReference<Boolean> handlerRan = new AtomicReference<>(false);
            router.register(Verb.LOGOUT, (caller, request) -> {
                handlerRan.set(true);
                return reply(request, "bye");
            });

            Message response = router.route(Message.request(Verb.LOGOUT, null),
                    CallerContext.anonymous(connection));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
            assertThat(handlerRan).hasValue(false);
        }

        @Test
        @DisplayName("an open verb is served without a session (the pre-login prototype flow)")
        void openVerbNeedsNoSession() {
            router.registerOpen(Verb.BANK_LIST, (caller, request) -> reply(request, "ok"));

            Message response = router.route(Message.request(Verb.BANK_LIST, null),
                    CallerContext.anonymous(connection));

            assertThat(response.isOk()).isTrue();
        }

        @Test
        @DisplayName("a guarded verb runs for an authenticated caller")
        void guardedVerbRunsWhenSignedIn() {
            router.register(Verb.LOGOUT, (caller, request) -> reply(request, "user " + caller.userId()));

            Message response = router.route(Message.request(Verb.LOGOUT, null),
                    CallerContext.authenticated(connection, ALICE, Role.STUDENT));

            assertThat(response.getPayload()).isEqualTo("user 1");
        }

        @Test
        @DisplayName("identity comes from the session on the socket, never from the payload")
        void identityComesFromTheSession() {
            sessions.attach(ALICE, Role.STUDENT, connection);
            AtomicReference<CallerContext> seen = new AtomicReference<>();
            router.register(Verb.LOGOUT, (caller, request) -> {
                seen.set(caller);
                return reply(request, "ok");
            });

            // The payload claims to be user 999; the session says otherwise.
            router.handle(Message.request(Verb.LOGOUT, 999L), connection);

            assertThat(seen.get().userId()).isEqualTo(ALICE);
            assertThat(seen.get().role()).contains(Role.STUDENT);
            assertThat(seen.get().connection()).isSameAs(connection);
        }

        @Test
        @DisplayName("a connection with no session yields an anonymous caller")
        void unattachedConnectionIsAnonymous() {
            AtomicReference<CallerContext> seen = new AtomicReference<>();
            router.registerOpen(Verb.LOGIN, (caller, request) -> {
                seen.set(caller);
                return reply(request, "ok");
            });

            router.handle(Message.request(Verb.LOGIN, null), connection);

            assertThat(seen.get().isAuthenticated()).isFalse();
        }
    }

    @Nested
    @DisplayName("failure handling")
    class Failures {

        @Test
        @DisplayName("an AuthorizationException becomes an ERROR carrying the guard's own code")
        void authorizationExceptionKeepsItsCode() {
            router.register(Verb.LOGOUT, (caller, request) -> {
                throw AuthorizationException.forbidden("Only teachers may do that.");
            });

            Message response = router.route(Message.request(Verb.LOGOUT, null),
                    CallerContext.authenticated(connection, ALICE, Role.STUDENT));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(response.errorMessage()).isEqualTo("Only teachers may do that.");
        }

        @Test
        @DisplayName("an UNAUTHORIZED guard failure keeps its code too")
        void unauthorizedGuardFailure() {
            router.registerOpen(Verb.LOGIN, (caller, request) -> {
                throw AuthorizationException.unauthorized("Session expired.");
            });

            Message response = router.route(Message.request(Verb.LOGIN, null),
                    CallerContext.anonymous(connection));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("an unexpected RuntimeException becomes INTERNAL and leaks nothing")
        void runtimeExceptionIsGeneric() {
            router.registerOpen(Verb.LOGIN, (caller, request) -> {
                throw new IllegalStateException(
                        "jdbc:mysql://10.0.0.5/hsts?user=root — NullPointerException at QuestionRepository:42");
            });

            Message response = router.route(Message.request(Verb.LOGIN, null),
                    CallerContext.anonymous(connection));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(response.errorMessage()).isEqualTo(MessageRouter.GENERIC_INTERNAL_MESSAGE);
            assertThat(response.toString())
                    .doesNotContain("jdbc")
                    .doesNotContain("QuestionRepository")
                    .doesNotContain("IllegalStateException");
        }

        @Test
        @DisplayName("a checked exception from a handler is contained just as well")
        void checkedExceptionIsGeneric() {
            router.registerOpen(Verb.LOGIN, (caller, request) -> {
                throw new SQLException("Access denied for user 'root'@'localhost'");
            });

            Message response = router.route(Message.request(Verb.LOGIN, null),
                    CallerContext.anonymous(connection));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(response.errorMessage()).doesNotContain("root");
        }
    }

    @Nested
    @DisplayName("handle() — the OCSF entry point")
    class Handle {

        @Test
        @DisplayName("writes exactly one response back to the connection")
        void sendsTheResponse() throws IOException {
            router.registerOpen(Verb.BANK_LIST, (caller, request) -> reply(request, "list"));

            router.handle(Message.request(Verb.BANK_LIST, null), connection);

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(connection).sendToClient(captor.capture());
            Message response = (Message) captor.getValue();
            assertThat(response.isOk()).isTrue();
            assertThat(response.getPayload()).isEqualTo("list");
        }

        @Test
        @DisplayName("a non-Message object is answered with BAD_REQUEST, connection intact")
        void nonMessageObjectIsRejected() throws IOException {
            router.handle("a bare string", connection);

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(connection).sendToClient(captor.capture());
            Message response = (Message) captor.getValue();
            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
            assertThat(response.getRequestId()).isNull();
        }

        @Test
        @DisplayName("a null object is answered with BAD_REQUEST")
        void nullObjectIsRejected() throws IOException {
            router.handle(null, connection);

            verify(connection).sendToClient(any());
        }

        @Test
        @DisplayName("a message with no connection is dropped rather than NPE'd")
        void nullConnectionIsDropped() {
            assertThatCode(() -> router.handle(Message.request(Verb.LOGIN, null), null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a socket that dies mid-answer is logged, not rethrown")
        void sendFailureIsContained() throws IOException {
            router.registerOpen(Verb.LOGIN, (caller, request) -> reply(request, "ok"));
            doThrow(new IOException("broken pipe")).when(connection).sendToClient(any());

            assertThatCode(() -> router.handle(Message.request(Verb.LOGIN, null), connection))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a handler failure still produces exactly one response")
        void handlerFailureStillAnswers() throws IOException {
            router.registerOpen(Verb.LOGIN, (caller, request) -> {
                throw new RuntimeException("boom");
            });

            router.handle(Message.request(Verb.LOGIN, null), connection);

            verify(connection).sendToClient(any());
            verify(connection, never()).close();
        }
    }
}
