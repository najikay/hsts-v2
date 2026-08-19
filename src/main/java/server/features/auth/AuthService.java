package server.features.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;
import common.dto.auth.LoginRequest;
import common.dto.auth.LoginResult;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.core.SessionManager;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Sign-in and sign-out (Logic tier, E5.1/E5.2 — F1.1, F1.3, F1.4).
 *
 * <p>The whole of authentication is four rules, and all four live here:
 *
 * <ol>
 *   <li><b>BCrypt only</b> (S-38). The stored value is a hash; the submitted
 *       password is verified against it by the library, which compares in
 *       constant time. Nothing in this class compares password material with
 *       {@code equals}.</li>
 *   <li><b>One message for every credential failure</b> (F1.1, X-SEC). Unknown
 *       user, wrong password, and wrong password on a locked account all answer
 *       {@link #GENERIC_FAILURE}. An unknown username is even verified against a
 *       dummy hash so the wrong answer does not come back measurably faster than
 *       the right one.</li>
 *   <li><b>Throttle after five failures</b> (F1.1), delegated to
 *       {@link LoginThrottle}. While an account is locked, correct credentials
 *       are still refused — with {@link #THROTTLED_FAILURE}, which is a different
 *       sentence on purpose: at that point the user is the legitimate owner and
 *       "incorrect password" would be a lie that makes them keep guessing.</li>
 *   <li><b>One session per user</b> (F1.3/T-16). {@link SessionManager#attach}
 *       is the authority; a refusal becomes {@code CONFLICT} carrying the exact
 *       wording the PRD specifies.</li>
 * </ol>
 *
 * <p>Everything the service needs is constructor-injected — a
 * {@link UserDirectory} (the E2 seam), the {@link SessionManager}, and a
 * {@link Clock} — so every rule above is unit-testable without a socket, a
 * database or a thirty-second wait.
 */
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** The only thing a failed credential check is ever allowed to say (F1.1). */
    public static final String GENERIC_FAILURE = "Incorrect username or password.";

    /** Shown when the account is locked and the credentials were right (F1.1). */
    public static final String THROTTLED_FAILURE = "Too many attempts — try again shortly.";

    /** Exact F1.3 wording for a second concurrent session. */
    public static final String ALREADY_SIGNED_IN = "This account is already signed in elsewhere.";

    /** Answer to a LOGIN whose payload is not a {@link LoginRequest}. */
    public static final String MALFORMED_REQUEST = "That sign-in request could not be read.";

    /**
     * A real BCrypt hash of a value nobody knows, verified against whenever the
     * username does not exist. Its only job is to spend the same time the real
     * path would, so response timing does not become a user-enumeration oracle.
     * Generated at class load (rather than pasted as a literal) so it can never
     * drift into an unparseable string.
     */
    private static final String DUMMY_HASH = BCrypt.withDefaults()
            .hashToString(10, java.util.UUID.randomUUID().toString().toCharArray());

    private final UserDirectory directory;
    private final SessionManager sessions;
    private final LoginThrottle throttle;

    /** Production wiring: system clock. */
    public AuthService(UserDirectory directory, SessionManager sessions) {
        this(directory, sessions, Clock.systemUTC());
    }

    /** @param clock time source for the lockout window; a test clock in tests */
    public AuthService(UserDirectory directory, SessionManager sessions, Clock clock) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.throttle = new LoginThrottle(Objects.requireNonNull(clock, "clock"));
    }

    /** Registers {@code LOGIN} (open) and {@code LOGOUT} (authenticated) handlers. */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        // LOGIN is open by definition — it is how a connection stops being anonymous.
        router.registerOpen(Verb.LOGIN, this::handleLogin);
        // LOGOUT is not: there is nothing to end without a session, and the router
        // already answers UNAUTHORIZED for that case.
        router.register(Verb.LOGOUT, this::handleLogout);
    }

    /** @return the throttle, for the server console's "who is locked out" view. */
    public LoginThrottle throttle() {
        return throttle;
    }

    // ===================== Handlers ======================================

    /** {@code LOGIN} → OK with a {@link LoginResult}, or a typed ERROR. */
    Message handleLogin(CallerContext caller, Message request) {
        if (!(request.getPayload() instanceof LoginRequest credentials)) {
            log.warn("LOGIN with a {} payload", describe(request.getPayload()));
            return Message.error(request, ErrorCode.VALIDATION, MALFORMED_REQUEST);
        }
        Outcome outcome = login(credentials.username(), credentials.password(), caller.connection());
        return outcome.isSuccess()
                ? Message.ok(request, outcome.result())
                : Message.error(request, outcome.errorCode(), outcome.message());
    }

    /** {@code LOGOUT} → OK, always: ending a session cannot fail. */
    Message handleLogout(CallerContext caller, Message request) {
        logout(caller.connection());
        return Message.ok(request, null);
    }

    // ===================== Rules =========================================

    /**
     * Verifies credentials and, on success, opens the session.
     *
     * @param connection the socket the caller is speaking on; the session is bound
     *                   to it, never to anything in the payload
     * @return the outcome — success carries the {@link LoginResult}, failure
     *         carries the {@link ErrorCode} and the sentence the user will read
     */
    public Outcome login(String username, String password, ConnectionToClient connection) {
        String name = username == null ? "" : username.trim();

        // While locked, refuse BEFORE any lookup or verification, with one message
        // for every credential. Verifying during the lock and answering differently
        // for a correct password would hand an attacker a free password oracle —
        // the throttle would confirm the very guess it exists to slow down. The
        // sentence is still truthful for the account's real owner.
        if (throttle.isLocked(name)) {
            log.warn("Refused login for '{}' — locked out for another {}", name,
                    throttle.remainingLockout(name).orElse(java.time.Duration.ZERO));
            return Outcome.failure(ErrorCode.UNAUTHORIZED, THROTTLED_FAILURE);
        }

        Optional<UserRecord> found = directory.findByUsername(name);
        boolean credentialsOk = verify(password, found.map(UserRecord::passwordHash).orElse(DUMMY_HASH))
                && found.isPresent();

        if (!credentialsOk) {
            boolean nowLocked = throttle.recordFailure(name);
            log.warn("Failed login for '{}' ({} consecutive failure(s){})",
                    name, throttle.failureCount(name), nowLocked ? ", now locked" : "");
            return Outcome.failure(ErrorCode.UNAUTHORIZED, GENERIC_FAILURE);
        }

        UserRecord user = found.orElseThrow();
        if (!sessions.attach(user.id(), user.role(), connection)) {
            log.warn("Refused login for '{}' — already signed in elsewhere (T-16)", name);
            return Outcome.failure(ErrorCode.CONFLICT, ALREADY_SIGNED_IN);
        }

        throttle.recordSuccess(name);
        log.info("User {} ({}) signed in as {}", user.id(), user.username(), user.role());
        return Outcome.success(new LoginResult(user.id(), user.username(), user.displayName(),
                user.role(), user.courses()));
    }

    /**
     * Ends the session on this connection (F1.4). The disconnect hooks registered
     * on {@link SessionManager} — edit locks, attempt bookkeeping — fire from
     * there, so logout and a dropped socket clean up along exactly one path.
     *
     * @return {@code true} when a session was actually ended
     */
    public boolean logout(ConnectionToClient connection) {
        Optional<Long> ended = sessions.detach(connection);
        ended.ifPresent(userId -> log.info("User {} signed out", userId));
        return ended.isPresent();
    }

    /**
     * BCrypt verification that survives a malformed stored hash (it answers
     * "not verified" rather than throwing) and a null password.
     */
    private static boolean verify(String password, String hash) {
        char[] candidate = (password == null ? "" : password).toCharArray();
        return BCrypt.verifyer().verify(candidate, hash.toCharArray()).verified;
    }

    private static String describe(Object payload) {
        return payload == null ? "null" : payload.getClass().getName();
    }

    // ===================== Outcome =======================================

    /**
     * The result of a login attempt: either a {@link LoginResult} or a
     * {@code (code, message)} pair.
     *
     * <p>A value rather than an exception, because none of the failure paths is
     * exceptional — a mistyped password is the most ordinary thing that happens
     * to this method — and because the handler needs both halves to build the
     * response.
     */
    public record Outcome(LoginResult result, ErrorCode errorCode, String message) {

        /** @return a successful outcome carrying the payload for the client. */
        public static Outcome success(LoginResult result) {
            return new Outcome(Objects.requireNonNull(result, "result"), null, null);
        }

        /** @return a refusal carrying the code and the user-facing sentence. */
        public static Outcome failure(ErrorCode code, String message) {
            return new Outcome(null, Objects.requireNonNull(code, "code"),
                    Objects.requireNonNull(message, "message"));
        }

        public boolean isSuccess() {
            return result != null;
        }
    }
}
