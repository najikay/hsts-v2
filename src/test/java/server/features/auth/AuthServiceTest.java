package server.features.auth;

import common.dto.auth.CourseRef;
import common.dto.auth.LoginRequest;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.core.SessionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuthService} — every rule of F1.1, F1.3 and F1.4 (E5.1).
 *
 * <p>The tests that matter here are the ones about what the server <i>says</i>:
 * a wrong password, an unknown user and a locked account with a wrong password
 * must be indistinguishable, and the two sentences that are allowed to differ —
 * the throttle message and the duplicate-session message — must match the PRD
 * word for word, because the UI shows them verbatim.
 *
 * <p>Time is a {@link MutableClock}, so "unlocks after 30 seconds" is proven in
 * microseconds.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String USER = "dana.cohen";
    private static final String PASSWORD = "demo123";

    /** Hashing five users costs real BCrypt time; do it once for the whole class. */
    private static final InMemoryUserDirectory DIRECTORY = new InMemoryUserDirectory(PASSWORD);

    @Mock
    private ConnectionToClient connection;

    @Mock
    private ConnectionToClient otherConnection;

    private MutableClock clock;
    private SessionManager sessions;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-08-19T09:00:00Z"));
        sessions = new SessionManager();
        auth = new AuthService(DIRECTORY, sessions, clock);
    }

    @Nested
    @DisplayName("credentials")
    class Credentials {

        @Test
        @DisplayName("the right password signs the user in and describes them to the shell")
        void successCarriesEverythingTheShellNeeds() {
            AuthService.Outcome outcome = auth.login(USER, PASSWORD, connection);

            assertThat(outcome.isSuccess()).isTrue();
            LoginResult result = outcome.result();
            assertThat(result.username()).isEqualTo(USER);
            assertThat(result.displayName()).isEqualTo("Dana Cohen");
            assertThat(result.role()).isEqualTo(Role.TEACHER);
            assertThat(result.courses()).extracting(CourseRef::name)
                    .containsExactly("Algebra 11", "Calculus 12");
            assertThat(sessions.isOnline(result.userId())).isTrue();
        }

        @Test
        @DisplayName("a wrong password is refused with the generic message")
        void wrongPasswordIsGeneric() {
            AuthService.Outcome outcome = auth.login(USER, "not-it", connection);

            assertThat(outcome.isSuccess()).isFalse();
            assertThat(outcome.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
            assertThat(outcome.message()).isEqualTo(AuthService.GENERIC_FAILURE);
            assertThat(sessions.onlineCount()).isZero();
        }

        @Test
        @DisplayName("an unknown user answers exactly what a wrong password answers (no enumeration)")
        void unknownUserIsIndistinguishable() {
            AuthService.Outcome unknown = auth.login("nobody.here", PASSWORD, connection);
            AuthService.Outcome wrongPassword = auth.login(USER, "not-it", connection);

            assertThat(unknown.message()).isEqualTo(wrongPassword.message());
            assertThat(unknown.errorCode()).isEqualTo(wrongPassword.errorCode());
        }

        @Test
        @DisplayName("blank and null credentials are refused, not crashed on")
        void blankCredentialsAreRefused() {
            assertThat(auth.login(null, null, connection).message())
                    .isEqualTo(AuthService.GENERIC_FAILURE);
            assertThat(auth.login("", "", connection).message())
                    .isEqualTo(AuthService.GENERIC_FAILURE);
            assertThat(auth.login(USER, null, connection).message())
                    .isEqualTo(AuthService.GENERIC_FAILURE);
        }

        @Test
        @DisplayName("the username is matched case- and whitespace-insensitively")
        void usernameIsNormalised() {
            assertThat(auth.login("  DANA.Cohen ", PASSWORD, connection).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("every fixture user can sign in with the documented password")
        void allFixtureUsersWork() {
            for (UserRecord user : DIRECTORY.all()) {
                SessionManager freshSessions = new SessionManager();
                AuthService service = new AuthService(DIRECTORY, freshSessions, clock);

                assertThat(service.login(user.username(), PASSWORD, connection).isSuccess())
                        .as("%s can sign in", user.username())
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("throttle (F1.1)")
    class Throttle {

        @Test
        @DisplayName("five failures lock the account; while locked EVERY attempt gets the throttle message")
        void fiveFailuresLock() {
            for (int attempt = 1; attempt <= LoginThrottle.MAX_FAILURES; attempt++) {
                assertThat(auth.login(USER, "wrong", connection).message())
                        .isEqualTo(AuthService.GENERIC_FAILURE);
            }
            assertThat(auth.throttle().isLocked(USER)).isTrue();

            // While locked the answer is the SAME for wrong and right credentials —
            // answering differently would be a password oracle: the throttle would
            // confirm the very guess it exists to slow down (security review, E5).
            assertThat(auth.login(USER, "wrong", connection).message())
                    .isEqualTo(AuthService.THROTTLED_FAILURE);
            assertThat(auth.login(USER, PASSWORD, connection).message())
                    .isEqualTo(AuthService.THROTTLED_FAILURE);
        }

        @Test
        @DisplayName("an unknown username throttles identically — no user enumeration via lockout")
        void unknownUsernameThrottlesTheSameWay() {
            for (int attempt = 1; attempt <= LoginThrottle.MAX_FAILURES; attempt++) {
                assertThat(auth.login("no.such.user", "wrong", connection).message())
                        .isEqualTo(AuthService.GENERIC_FAILURE);
            }
            assertThat(auth.login("no.such.user", "wrong", connection).message())
                    .isEqualTo(AuthService.THROTTLED_FAILURE);
        }

        @Test
        @DisplayName("the right password on a locked account is refused with the throttle message")
        void lockedWithCorrectCredentialsIsThrottled() {
            failFiveTimes();

            AuthService.Outcome outcome = auth.login(USER, PASSWORD, connection);

            assertThat(outcome.isSuccess()).isFalse();
            assertThat(outcome.message()).isEqualTo(AuthService.THROTTLED_FAILURE);
            assertThat(sessions.onlineCount()).isZero();
        }

        @Test
        @DisplayName("fewer than five failures never locks")
        void fourFailuresStillLetYouIn() {
            for (int attempt = 0; attempt < LoginThrottle.MAX_FAILURES - 1; attempt++) {
                auth.login(USER, "wrong", connection);
            }
            assertThat(auth.login(USER, PASSWORD, connection).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("the lock lifts after 30 seconds")
        void lockExpires() {
            failFiveTimes();
            assertThat(auth.login(USER, PASSWORD, connection).message())
                    .isEqualTo(AuthService.THROTTLED_FAILURE);

            clock.advance(LoginThrottle.LOCKOUT.plusSeconds(1));

            assertThat(auth.login(USER, PASSWORD, connection).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("a successful login clears the failure count")
        void successResetsTheCounter() {
            auth.login(USER, "wrong", connection);
            auth.login(USER, "wrong", connection);

            auth.login(USER, PASSWORD, connection);

            assertThat(auth.throttle().failureCount(USER)).isZero();
        }

        @Test
        @DisplayName("attempts during the lock neither verify credentials nor extend the window")
        void attemptsDuringLockAreInert() {
            failFiveTimes();
            clock.advance(Duration.ofSeconds(25));

            // Short-circuited before lookup/verify: throttle message, no new failure.
            assertThat(auth.login(USER, "wrong", connection).message())
                    .isEqualTo(AuthService.THROTTLED_FAILURE);
            clock.advance(Duration.ofSeconds(6));   // 31s past the lock start

            assertThat(auth.login(USER, PASSWORD, connection).isSuccess()).isTrue();
        }

        private void failFiveTimes() {
            for (int attempt = 0; attempt < LoginThrottle.MAX_FAILURES; attempt++) {
                auth.login(USER, "wrong", connection);
            }
        }

        @Test
        @DisplayName("spraying random usernames cannot grow the throttle map without bound")
        void randomUsernameSprayIsBounded() {
            LoginThrottle throttle = new LoginThrottle(clock);
            for (int i = 0; i < LoginThrottle.PURGE_THRESHOLD; i++) {
                throttle.recordFailure("spray-" + i);        // one stale failure each
            }
            // The next failure crosses the threshold and purges everything stale;
            // a live lock must survive the purge.
            for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
                throttle.recordFailure("locked-user");
            }
            throttle.recordFailure("one-more");

            assertThat(throttle.trackedUsernames()).isLessThan(100);
            assertThat(throttle.isLocked("locked-user")).isTrue();
        }
    }

    @Nested
    @DisplayName("single session (F1.3 / T-16)")
    class SingleSession {

        @Test
        @DisplayName("a second concurrent login is refused with the exact PRD wording")
        void secondLoginConflicts() {
            assertThat(auth.login(USER, PASSWORD, connection).isSuccess()).isTrue();

            AuthService.Outcome second = auth.login(USER, PASSWORD, otherConnection);

            assertThat(second.isSuccess()).isFalse();
            assertThat(second.errorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(second.message()).isEqualTo("This account is already signed in elsewhere.");
        }

        @Test
        @DisplayName("logout frees the session and the next login succeeds")
        void logoutFreesTheSession() {
            AuthService.Outcome first = auth.login(USER, PASSWORD, connection);
            assertThat(auth.logout(connection)).isTrue();
            assertThat(sessions.isOnline(first.result().userId())).isFalse();

            assertThat(auth.login(USER, PASSWORD, otherConnection).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("logging out a connection with no session is a no-op, not an error")
        void logoutWithoutASessionIsHarmless() {
            assertThat(auth.logout(connection)).isFalse();
            assertThat(auth.logout(null)).isFalse();
        }

        @Test
        @DisplayName("a refused duplicate does not disturb the live session")
        void conflictLeavesTheFirstSessionAlone() {
            long userId = auth.login(USER, PASSWORD, connection).result().userId();

            auth.login(USER, PASSWORD, otherConnection);

            assertThat(sessions.connectionOf(userId)).contains(connection);
        }

        @Test
        @DisplayName("two different users may be signed in at the same time")
        void differentUsersCoexist() {
            assertThat(auth.login(USER, PASSWORD, connection).isSuccess()).isTrue();
            assertThat(auth.login("maya.levi", PASSWORD, otherConnection).isSuccess()).isTrue();
            assertThat(sessions.onlineCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("router handlers")
    class Handlers {

        private MessageRouter router;

        @BeforeEach
        void register() {
            router = new MessageRouter(sessions);
            auth.registerOn(router);
        }

        @Test
        @DisplayName("LOGIN is open and LOGOUT is not")
        void verbRegistration() {
            assertThat(router.isRegistered(Verb.LOGIN)).isTrue();
            assertThat(router.isOpen(Verb.LOGIN)).isTrue();
            assertThat(router.isRegistered(Verb.LOGOUT)).isTrue();
            assertThat(router.isOpen(Verb.LOGOUT)).isFalse();
        }

        @Test
        @DisplayName("LOGIN from an anonymous connection answers OK with the login result")
        void loginOverTheRouter() {
            Message response = router.route(
                    Message.request(Verb.LOGIN, new LoginRequest(USER, PASSWORD)),
                    CallerContext.anonymous(connection));

            assertThat(response.isOk()).isTrue();
            assertThat(response.getPayload()).isInstanceOf(LoginResult.class);
        }

        @Test
        @DisplayName("a bad LOGIN payload is a validation error, not a crash")
        void malformedLoginPayload() {
            Message response = router.route(Message.request(Verb.LOGIN, "just a string"),
                    CallerContext.anonymous(connection));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage()).isEqualTo(AuthService.MALFORMED_REQUEST);
        }

        @Test
        @DisplayName("a failed LOGIN comes back as ERROR carrying the user-facing sentence")
        void failedLoginOverTheRouter() {
            Message response = router.route(
                    Message.request(Verb.LOGIN, new LoginRequest(USER, "nope")),
                    CallerContext.anonymous(connection));

            assertThat(response.isError()).isTrue();
            assertThat(response.errorMessage()).isEqualTo(AuthService.GENERIC_FAILURE);
        }

        @Test
        @DisplayName("LOGOUT without a session is refused by the router itself")
        void logoutNeedsASession() {
            Message response = router.route(Message.request(Verb.LOGOUT, null),
                    CallerContext.anonymous(connection));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("LOGOUT ends the session on the calling connection")
        void logoutOverTheRouter() {
            LoginResult login = auth.login(USER, PASSWORD, connection).result();

            Message response = router.route(Message.request(Verb.LOGOUT, null),
                    CallerContext.authenticated(connection, login.userId(), login.role()));

            assertThat(response.isOk()).isTrue();
            assertThat(sessions.isOnline(login.userId())).isFalse();
        }
    }

    @Nested
    @DisplayName("directory seam")
    class DirectorySeam {

        @Test
        @DisplayName("any UserDirectory works — the service knows nothing about storage")
        void aLambdaDirectoryIsEnough() {
            String hash = InMemoryUserDirectory.hash("s3cret");
            UserDirectory oneUser = username -> "ext.user".equals(username)
                    ? Optional.of(new UserRecord(77, "ext.user", hash, "External User",
                            Role.PRINCIPAL, List.of()))
                    : Optional.empty();
            AuthService service = new AuthService(oneUser, sessions, clock);

            AuthService.Outcome outcome = service.login("ext.user", "s3cret", connection);

            assertThat(outcome.isSuccess()).isTrue();
            assertThat(outcome.result().role()).isEqualTo(Role.PRINCIPAL);
        }

        @Test
        @DisplayName("a stored hash that is not BCrypt refuses rather than throwing")
        void malformedStoredHashIsRefused() {
            UserDirectory broken = username -> Optional.of(
                    new UserRecord(9, "broken", "not-a-hash", "Broken", Role.STUDENT, List.of()));
            AuthService service = new AuthService(broken, sessions, clock);

            assertThat(service.login("broken", "anything", connection).message())
                    .isEqualTo(AuthService.GENERIC_FAILURE);
        }
    }

    /** A {@link Clock} the test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
