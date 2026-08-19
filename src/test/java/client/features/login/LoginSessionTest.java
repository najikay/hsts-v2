package client.features.login;

import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginRequest;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LoginSession} — the login screen's whole state machine, with no JavaFX
 * in sight (E5.3).
 *
 * <p>This is the session-class pattern paying off: a {@code FakeClientConnection}
 * scripts what the server answers, a {@code DirectFxThreadPoster} makes the
 * FX hop synchronous, and every branch of the screen — including the two that are
 * awkward to reach by hand, a duplicate session and an unreachable server — is a
 * three-line test.
 */
class LoginSessionTest {

    private static final LoginResult DANA = new LoginResult(1001, "dana.cohen", "Dana Cohen",
            Role.TEACHER, List.of(new CourseRef("11", "Algebra 11")));

    private FakeClientConnection connection;
    private LoginSession session;
    private AtomicInteger changes;

    @BeforeEach
    void setUp() {
        connection = new FakeClientConnection();
        RequestDispatcher dispatcher = new RequestDispatcher(connection, Duration.ofSeconds(5));
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);

        changes = new AtomicInteger();
        session = new LoginSession(dispatcher, new DirectFxThreadPoster())
                .onChange(changes::incrementAndGet);
    }

    @Nested
    @DisplayName("submitting")
    class Submitting {

        @Test
        @DisplayName("starts idle with nothing to show")
        void startsIdle() {
            assertThat(session.state()).isEqualTo(LoginSession.State.IDLE);
            assertThat(session.hasError()).isFalse();
            assertThat(session.errorMessage()).isEmpty();
            assertThat(session.result()).isEmpty();
            assertThat(session.errorCode()).isEmpty();
        }

        @Test
        @DisplayName("sends LOGIN carrying the typed credentials, trimmed")
        void sendsTheCredentials() {
            connection.replyOk(Verb.LOGIN, DANA);

            session.submit("  dana.cohen  ", "demo123");

            Message sent = connection.lastSent();
            assertThat(sent.getVerb()).isEqualTo(Verb.LOGIN);
            assertThat(sent.getPayload()).isEqualTo(new LoginRequest("dana.cohen", "demo123"));
        }

        @Test
        @DisplayName("an empty field is refused locally — nothing goes on the wire")
        void emptyFieldsNeverReachTheServer() {
            assertThat(session.submit("dana.cohen", "  ")).isFalse();

            assertThat(session.state()).isEqualTo(LoginSession.State.ERROR);
            assertThat(session.errorMessage()).isEqualTo(LoginSession.MISSING_FIELDS);
            assertThat(connection.sentCount()).isZero();
        }

        @Test
        @DisplayName("canSubmit needs both fields and no request in flight")
        void canSubmitRules() {
            assertThat(session.canSubmit("dana.cohen", "demo123")).isTrue();
            assertThat(session.canSubmit("", "demo123")).isFalse();
            assertThat(session.canSubmit("dana.cohen", null)).isFalse();
        }

        @Test
        @DisplayName("a second submit while one is in flight is ignored (no double session)")
        void doubleSubmitIsIgnored() {
            // No responder: the first request stays in flight.
            assertThat(session.submit("dana.cohen", "demo123")).isTrue();
            assertThat(session.isSubmitting()).isTrue();

            assertThat(session.submit("dana.cohen", "demo123")).isFalse();
            assertThat(connection.sentCount()).isEqualTo(1);
            assertThat(session.canSubmit("dana.cohen", "demo123")).isFalse();
        }
    }

    @Nested
    @DisplayName("answers")
    class Answers {

        @Test
        @DisplayName("a login result lands in SUCCESS and is exposed to the shell")
        void successExposesTheResult() {
            connection.replyOk(Verb.LOGIN, DANA);

            session.submit("dana.cohen", "demo123");

            assertThat(session.state()).isEqualTo(LoginSession.State.SUCCESS);
            assertThat(session.result()).contains(DANA);
            assertThat(session.hasError()).isFalse();
            assertThat(changes.get()).isEqualTo(2);   // submitting, then success
        }

        @Test
        @DisplayName("a credential failure shows the server's sentence verbatim")
        void serverWordingWins() {
            connection.replyError(Verb.LOGIN, ErrorCode.UNAUTHORIZED,
                    "Incorrect username or password.");

            session.submit("dana.cohen", "wrong");

            assertThat(session.state()).isEqualTo(LoginSession.State.ERROR);
            assertThat(session.errorMessage()).isEqualTo("Incorrect username or password.");
            assertThat(session.errorCode()).contains(ErrorCode.UNAUTHORIZED);
            assertThat(session.result()).isEmpty();
        }

        @Test
        @DisplayName("CONFLICT is shown like any other error — it is the user's next action (F1.3)")
        void duplicateSessionIsShownInline() {
            connection.replyError(Verb.LOGIN, ErrorCode.CONFLICT,
                    "This account is already signed in elsewhere.");

            session.submit("dana.cohen", "demo123");

            assertThat(session.errorMessage())
                    .isEqualTo("This account is already signed in elsewhere.");
            assertThat(session.errorCode()).contains(ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("an error with no sentence falls back to a usable one")
        void blankServerMessageFallsBack() {
            connection.replyError(Verb.LOGIN, ErrorCode.INTERNAL, "");

            session.submit("dana.cohen", "demo123");

            assertThat(session.errorMessage()).isEqualTo(LoginSession.UNEXPECTED_ERROR);
        }

        @Test
        @DisplayName("an OK carrying the wrong payload is a failure, not a half-login")
        void unexpectedPayloadIsAFailure() {
            connection.replyOk(Verb.LOGIN, "surprise");

            session.submit("dana.cohen", "demo123");

            assertThat(session.state()).isEqualTo(LoginSession.State.ERROR);
            assertThat(session.errorMessage()).isEqualTo(LoginSession.UNEXPECTED_ERROR);
            assertThat(session.result()).isEmpty();
        }

        @Test
        @DisplayName("a dead socket reads as a connection problem, not a wrong password")
        void sendFailureIsOffline() {
            connection.failSendsWith(new IOException("socket closed"));

            session.submit("dana.cohen", "demo123");

            assertThat(session.state()).isEqualTo(LoginSession.State.ERROR);
            assertThat(session.errorMessage()).isEqualTo(LoginSession.OFFLINE_ERROR);
            assertThat(session.errorCode()).isEmpty();
        }

        @Test
        @DisplayName("a failed attempt can be retried")
        void retryAfterFailure() {
            connection.replyError(Verb.LOGIN, ErrorCode.UNAUTHORIZED, "Incorrect username or password.");
            session.submit("dana.cohen", "wrong");

            connection.replyOk(Verb.LOGIN, DANA);
            assertThat(session.submit("dana.cohen", "demo123")).isTrue();

            assertThat(session.state()).isEqualTo(LoginSession.State.SUCCESS);
        }
    }

    @Nested
    @DisplayName("clearing")
    class Clearing {

        @Test
        @DisplayName("typing clears a stale error")
        void clearErrorReturnsToIdle() {
            session.submit("dana.cohen", "");
            assertThat(session.hasError()).isTrue();

            session.clearError();

            assertThat(session.state()).isEqualTo(LoginSession.State.IDLE);
            assertThat(session.errorMessage()).isEmpty();
        }

        @Test
        @DisplayName("clearing when there is no error changes nothing")
        void clearErrorIsANoOpOtherwise() {
            int before = changes.get();

            session.clearError();

            assertThat(session.state()).isEqualTo(LoginSession.State.IDLE);
            assertThat(changes.get()).isEqualTo(before);
        }

        @Test
        @DisplayName("reset wipes the previous session's outcome (revisit after logout)")
        void resetWipesEverything() {
            connection.replyOk(Verb.LOGIN, DANA);
            session.submit("dana.cohen", "demo123");

            session.reset();

            assertThat(session.state()).isEqualTo(LoginSession.State.IDLE);
            assertThat(session.result()).isEmpty();
            assertThat(session.errorMessage()).isEmpty();
        }
    }

    @Nested
    @DisplayName("caps lock")
    class CapsLock {

        @Test
        @DisplayName("warns only when the platform says the key is locked")
        void warnsWhenOn() {
            session.capsLockProbe(() -> Optional.of(true));

            assertThat(session.isCapsLockOn()).isTrue();
            assertThat(session.isCapsLockKnown()).isTrue();
            assertThat(session.capsLockHint()).contains(LoginSession.CAPS_LOCK_WARNING);
        }

        @Test
        @DisplayName("stays quiet when the key is off")
        void quietWhenOff() {
            session.capsLockProbe(() -> Optional.of(false));

            assertThat(session.isCapsLockOn()).isFalse();
            assertThat(session.isCapsLockKnown()).isTrue();
            assertThat(session.capsLockHint()).isEmpty();
        }

        @Test
        @DisplayName("stays quiet where the platform cannot tell — no guessing")
        void quietWhenUnknown() {
            // The default probe, and what Platform.isKeyLocked answers on Linux.
            assertThat(session.isCapsLockOn()).isFalse();
            assertThat(session.isCapsLockKnown()).isFalse();
            assertThat(session.capsLockHint()).isEmpty();
        }
    }
}
