package client.features.login;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import common.dto.auth.LoginRequest;
import common.dto.auth.LoginResult;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * All of the login screen's logic (Presentation tier, E5.3 — the session-class
 * pattern of ARCHITECTURE §6).
 *
 * <p>{@code LoginView} builds nodes and nothing else; every rule the screen has
 * lives here and is unit-tested with {@code FakeClientConnection} and a
 * {@code DirectFxThreadPoster}, with no JavaFX toolkit involved:
 *
 * <ul>
 *   <li>the state machine {@code IDLE → SUBMITTING → SUCCESS | ERROR} — including
 *       the rule that a second submit while one is in flight is ignored, so a
 *       double-click cannot open two sessions;</li>
 *   <li>the mapping from a server answer to the one sentence the user reads. The
 *       server's own wording wins ("Incorrect username or password.", the F1.3
 *       duplicate-session message, the throttle message): those are written for
 *       the person in front of the screen, and re-phrasing them here would only
 *       let the two tiers drift;</li>
 *   <li>the fallbacks for the two things the server cannot say — an unreachable
 *       server, and a response that is not a {@link LoginResult}.</li>
 * </ul>
 *
 * <p>Caps Lock is a supplier seam rather than a direct {@code Platform} call:
 * {@code Platform.isKeyLocked} answers {@code Optional.empty()} on platforms that
 * cannot tell (and needs a toolkit), so the view injects the probe and the "do we
 * warn?" rule stays testable in all three states — on, off, unknown.
 */
public final class LoginSession {

    private static final Logger log = LoggerFactory.getLogger(LoginSession.class);

    /** Where the screen is in the sign-in flow. */
    public enum State {
        /** Waiting for input. */
        IDLE,
        /** A {@code LOGIN} is in flight; the form is locked and the button spins. */
        SUBMITTING,
        /** The last attempt failed; {@link #errorMessage()} says why. */
        ERROR,
        /** Signed in; {@link #result()} carries the payload the shell boots from. */
        SUCCESS
    }

    /** Shown when the request never reached the server (socket dropped, timeout). */
    public static final String OFFLINE_ERROR =
            "Could not reach the server — check your connection and try again.";

    /** Shown when the server answered something this client cannot use. */
    public static final String UNEXPECTED_ERROR = "Sign-in failed. Please try again.";

    /** Local guard, before anything is sent. */
    public static final String MISSING_FIELDS = "Enter your username and password.";

    /** The caps-lock hint text (F1.1 usability: the commonest cause of a wrong password). */
    public static final String CAPS_LOCK_WARNING = "Caps Lock is on.";

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Supplier<Optional<Boolean>> capsLockProbe = Optional::empty;
    private Runnable onChange = () -> { };

    private State state = State.IDLE;
    private String errorMessage = "";
    private ErrorCode errorCode;
    private LoginResult result;

    /**
     * @param dispatcher the request correlator (the screen never touches a socket)
     * @param poster     the single FX-thread hop; {@code DirectFxThreadPoster} in tests
     */
    public LoginSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    // ===================== Wiring ========================================

    /** Registers the "re-read me and re-render" callback. */
    public LoginSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * Injects the caps-lock probe.
     *
     * @param probe answers {@code true}/{@code false} where the platform knows,
     *              and empty where it does not
     */
    public LoginSession capsLockProbe(Supplier<Optional<Boolean>> probe) {
        this.capsLockProbe = Objects.requireNonNull(probe, "probe");
        return this;
    }

    // ===================== Rules =========================================

    /** @return {@code true} when both fields carry something and nothing is in flight. */
    public boolean canSubmit(String username, String password) {
        return state != State.SUBMITTING && !isBlank(username) && !isBlank(password);
    }

    /**
     * Sends {@code LOGIN}, unless the form is incomplete or a request is already
     * in flight.
     *
     * @return {@code true} when a request actually went out
     */
    public boolean submit(String username, String password) {
        if (state == State.SUBMITTING) {
            log.debug("Ignoring a second submit while one is in flight");
            return false;
        }
        if (isBlank(username) || isBlank(password)) {
            fail(null, MISSING_FIELDS);
            return false;
        }

        state = State.SUBMITTING;
        errorMessage = "";
        errorCode = null;
        changed();

        dispatcher.send(Verb.LOGIN, new LoginRequest(username.trim(), password))
                .whenComplete((response, failure) -> poster.run(() -> settle(response, failure)));
        return true;
    }

    /** Turns the server's answer — or the absence of one — into the next state. */
    private void settle(Message response, Throwable failure) {
        if (failure != null) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            log.warn("LOGIN failed before an answer arrived: {}", cause.toString());
            fail(null, OFFLINE_ERROR);
            return;
        }
        if (response == null) {
            fail(null, UNEXPECTED_ERROR);
            return;
        }
        if (response.isError()) {
            // The server's sentence is the user-facing one, CONFLICT (F1.3) included:
            // "already signed in elsewhere" is actionable and must be shown verbatim.
            String message = response.errorMessage();
            fail(response.getErrorCode(), isBlank(message) ? UNEXPECTED_ERROR : message);
            return;
        }
        if (!(response.getPayload() instanceof LoginResult loginResult)) {
            log.warn("LOGIN answered OK with a {} payload",
                    response.getPayload() == null ? "null" : response.getPayload().getClass().getName());
            fail(null, UNEXPECTED_ERROR);
            return;
        }

        result = loginResult;
        state = State.SUCCESS;
        errorMessage = "";
        errorCode = null;
        log.info("Signed in as {} ({})", loginResult.username(), loginResult.role());
        changed();
    }

    private void fail(ErrorCode code, String message) {
        state = State.ERROR;
        errorCode = code;
        errorMessage = message;
        result = null;
        changed();
    }

    /** Clears an error back to a clean form (the user started typing again). */
    public void clearError() {
        if (state != State.ERROR) {
            return;
        }
        state = State.IDLE;
        errorMessage = "";
        errorCode = null;
        changed();
    }

    /**
     * Returns the screen to its initial state — used when the login screen is
     * revisited after a logout, so the previous session's outcome is not still on
     * display.
     */
    public void reset() {
        state = State.IDLE;
        errorMessage = "";
        errorCode = null;
        result = null;
        changed();
    }

    // ===================== Caps Lock =====================================

    /** @return {@code true} when the platform says Caps Lock is on. */
    public boolean isCapsLockOn() {
        return capsLockProbe.get().orElse(false);
    }

    /** @return {@code true} when the platform can answer the question at all. */
    public boolean isCapsLockKnown() {
        return capsLockProbe.get().isPresent();
    }

    /** @return the hint to show, or empty when there is nothing to warn about. */
    public Optional<String> capsLockHint() {
        return isCapsLockOn() ? Optional.of(CAPS_LOCK_WARNING) : Optional.empty();
    }

    // ===================== State =========================================

    public State state() {
        return state;
    }

    public boolean isSubmitting() {
        return state == State.SUBMITTING;
    }

    public boolean hasError() {
        return state == State.ERROR;
    }

    /** @return the sentence to show under the password field; {@code ""} when none. */
    public String errorMessage() {
        return errorMessage;
    }

    /** @return the machine-readable reason, when the failure came from the server. */
    public Optional<ErrorCode> errorCode() {
        return Optional.ofNullable(errorCode);
    }

    /** @return the signed-in user, present only in {@link State#SUCCESS}. */
    public Optional<LoginResult> result() {
        return Optional.ofNullable(result);
    }

    private void changed() {
        onChange.run();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
