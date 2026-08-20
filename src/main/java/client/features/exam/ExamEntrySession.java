package client.features.exam;

import client.net.RequestDispatcher;
import client.ui.components.logic.ValidationState;
import common.dto.exam.AttemptForm;
import common.dto.exam.AttemptResumeRequest;
import common.dto.exam.AttemptStartRequest;
import common.dto.exam.ExamHeader;
import common.dto.exam.ExamJoinRequest;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Code screen, then identity screen (Presentation tier, E10.9 — F6.1, C-1, S-18).
 *
 * <p>The whole entry flow as a state machine with no JavaFX in it, so every branch of it —
 * including the four different ways it can be refused — is unit-tested against
 * {@code FakeClientConnection}.
 *
 * <h2>Why two steps and not one</h2>
 *
 * <p>Because S-18 makes the identity entry the moment the timer starts. A single screen
 * asking for both would either start the clock on a mistyped code or hand a student the
 * paper before she had committed to sitting it. So {@code EXAM_JOIN} answers a header with
 * <b>no questions</b>, she reads what she is about to sit, and {@code ATTEMPT_START} is the
 * deliberate act that begins.
 *
 * <h2>The four errors, and why they are the server's words</h2>
 *
 * <p>Wrong code, not open yet, not enrolled, wrong ID: PRD §6 wants each one distinct, and
 * a student in an exam hall reading the wrong one loses minutes. The server decides which
 * it is and sends the sentence; this class only decides <em>which field</em> the sentence
 * hangs off, using the error code. Keeping a second copy of those four sentences on the
 * client is how one of them ends up out of date.
 *
 * <h2>Coming back to something already in progress</h2>
 *
 * <p>If the join says her attempt is already {@code IN_PROGRESS} the identity screen is
 * skipped entirely and this resumes straight into the form. Re-asking for her ID after a
 * dropped socket would be punishing her for the network, and the clock is already running
 * either way (E10.6).
 */
public final class ExamEntrySession {

    private static final Logger log = LoggerFactory.getLogger(ExamEntrySession.class);

    /** Codes are 4 alphanumeric characters; entry is case-insensitive (C-1). */
    public static final Pattern CODE_PATTERN = Pattern.compile("[A-Za-z0-9]{4}");

    private final RequestDispatcher dispatcher;

    private Runnable onChange = () -> { };
    private Consumer<AttemptForm> onStarted = form -> { };

    private EntryPhase phase = EntryPhase.CODE;
    private String code = "";
    private String nationalId = "";
    private boolean codeTouched;
    private boolean idTouched;
    private boolean busy;
    private ExamHeader header;
    private ValidationState codeState = ValidationState.pristine();
    private ValidationState idState = ValidationState.pristine();
    private String blockedMessage = "";

    /** @param dispatcher the shared request correlator */
    public ExamEntrySession(RequestDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    /** Registers the "re-read me and re-render" callback. */
    public ExamEntrySession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** Registers what happens when an attempt is open: the form takes over. */
    public ExamEntrySession onStarted(Consumer<AttemptForm> listener) {
        this.onStarted = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== Step one: the code ============================

    /**
     * Records what she typed.
     *
     * <p>Anything longer than four characters is kept rather than truncated: a student who
     * pasted five should see that her code is wrong, not watch a character vanish.
     */
    public void setCode(String raw) {
        this.code = raw == null ? "" : raw.trim();
        this.codeTouched = true;
        this.codeState = localCodeState();
        onChange.run();
    }

    /** @return what was typed, trimmed. */
    public String code() {
        return code;
    }

    /** @return the validation state to render on the code field. */
    public ValidationState codeState() {
        return codeState;
    }

    /** @return {@code true} when the Continue button should be enabled. */
    public boolean canContinue() {
        return !busy && CODE_PATTERN.matcher(code).matches();
    }

    /**
     * Asks the server what this code is (E10.9).
     *
     * <p>Three outcomes: the identity screen (she may sit it), the form (she is already
     * sitting it), or a refusal hung off the code field with the server's own sentence.
     *
     * @return a future completing when the answer has been applied; never completes
     *         exceptionally, because a screen must not have to catch a network failure to
     *         stay usable
     */
    public CompletableFuture<Void> submitCode() {
        if (!canContinue()) {
            codeState = ValidationState.invalid(ExamCopy.CODE_INVALID);
            onChange.run();
            return CompletableFuture.completedFuture(null);
        }
        setBusy(true);
        return dispatcher.send(Verb.EXAM_JOIN, new ExamJoinRequest(code))
                .handle((response, failure) -> {
                    setBusy(false);
                    applyJoin(response, failure);
                    return null;
                }).thenCompose(ignored -> resumeIfAlreadySitting());
    }

    private void applyJoin(Message response, Throwable failure) {
        if (failure != null) {
            codeState = ValidationState.invalid(ExamCopy.OFFLINE);
            onChange.run();
            return;
        }
        if (response.isError()) {
            // The server's sentence, hung off the field the student can act on: the code
            // field for anything about the exam, because that is what she would retype.
            codeState = ValidationState.invalid(ExamCopy.serverMessage(
                    response.getErrorCode(), response.errorMessage(), ExamCopy.OFFLINE));
            log.info("Join refused: {}", response.getErrorCode());
            onChange.run();
            return;
        }
        if (!(response.getPayload() instanceof ExamHeader found)) {
            codeState = ValidationState.invalid(ExamCopy.OFFLINE);
            onChange.run();
            return;
        }
        header = found;
        codeState = ValidationState.valid();
        if (found.attemptState().isFinished()) {
            // F6.7: she has already sat this. A dead end, but an explained one.
            phase = EntryPhase.BLOCKED;
            blockedMessage = ExamCopy.EXAM_CLOSED_FOR_YOU;
        } else {
            phase = EntryPhase.IDENTITY;
        }
        onChange.run();
    }

    /**
     * Skips the identity screen when an attempt is already running (E10.6).
     *
     * <p>The clock started the first time; asking again would be theatre. Resuming here
     * also means a student whose client died two seconds after starting never sees a second
     * ID prompt she would rightly find alarming.
     */
    private CompletableFuture<Void> resumeIfAlreadySitting() {
        if (header == null || !header.attemptState().isLive()) {
            return CompletableFuture.completedFuture(null);
        }
        setBusy(true);
        return dispatcher.send(Verb.ATTEMPT_RESUME, new AttemptResumeRequest(header.executionId()))
                .handle((response, failure) -> {
                    setBusy(false);
                    applyForm(response, failure, true);
                    return null;
                });
    }

    // ===================== Step two: the identity ========================

    /** Records the ID number she typed. */
    public void setNationalId(String raw) {
        this.nationalId = raw == null ? "" : raw.trim();
        this.idTouched = true;
        this.idState = localIdState();
        onChange.run();
    }

    /** @return what was typed, trimmed. */
    public String nationalId() {
        return nationalId;
    }

    /** @return the validation state to render on the ID field. */
    public ValidationState idState() {
        return idState;
    }

    /** @return {@code true} when the Start button should be enabled. */
    public boolean canStart() {
        return !busy && phase == EntryPhase.IDENTITY && !nationalId.isEmpty();
    }

    /**
     * Confirms identity and starts the clock (S-18).
     *
     * @return a future completing when the answer has been applied; never completes
     *         exceptionally
     */
    public CompletableFuture<Void> start() {
        if (!canStart()) {
            idState = ValidationState.invalid(ExamCopy.ID_REQUIRED);
            onChange.run();
            return CompletableFuture.completedFuture(null);
        }
        setBusy(true);
        return dispatcher.send(Verb.ATTEMPT_START,
                        new AttemptStartRequest(header.executionId(), nationalId))
                .handle((response, failure) -> {
                    setBusy(false);
                    applyForm(response, failure, false);
                    return null;
                });
    }

    /** Applies a form answer from either {@code ATTEMPT_START} or {@code ATTEMPT_RESUME}. */
    private void applyForm(Message response, Throwable failure, boolean resuming) {
        if (failure != null) {
            target(resuming).accept(ValidationState.invalid(ExamCopy.OFFLINE));
            onChange.run();
            return;
        }
        if (response.isError()) {
            String sentence = ExamCopy.serverMessage(
                    response.getErrorCode(), response.errorMessage(), ExamCopy.OFFLINE);
            // A wrong ID belongs on the ID field; anything about the exam itself belongs on
            // the code field, and sends her back a step to fix what is actually wrong.
            if (response.getErrorCode() == ErrorCode.VALIDATION && !resuming) {
                idState = ValidationState.invalid(sentence);
            } else {
                phase = EntryPhase.CODE;
                codeState = ValidationState.invalid(sentence);
            }
            log.info("Start refused: {}", response.getErrorCode());
            onChange.run();
            return;
        }
        if (!(response.getPayload() instanceof AttemptForm form)) {
            target(resuming).accept(ValidationState.invalid(ExamCopy.OFFLINE));
            onChange.run();
            return;
        }
        phase = EntryPhase.STARTED;
        onChange.run();
        onStarted.accept(form);
    }

    // ===================== Reading it ====================================

    /** @return which of the two screens is showing, or how the flow ended. */
    public EntryPhase phase() {
        return phase;
    }

    /** @return the header once a code has been accepted. */
    public Optional<ExamHeader> header() {
        return Optional.ofNullable(header);
    }

    /** @return why the flow is a dead end, when it is (F6.7). */
    public String blockedMessage() {
        return blockedMessage;
    }

    /** @return {@code true} while a request is in flight; the screen shows progress. */
    public boolean isBusy() {
        return busy;
    }

    /** Returns to the code screen with everything cleared. Called on entering the screen. */
    public void reset() {
        phase = EntryPhase.CODE;
        code = "";
        nationalId = "";
        codeTouched = false;
        idTouched = false;
        busy = false;
        header = null;
        codeState = ValidationState.pristine();
        idState = ValidationState.pristine();
        blockedMessage = "";
        onChange.run();
    }

    // ===================== Internals =====================================

    private void setBusy(boolean value) {
        busy = value;
        onChange.run();
    }

    /** An empty field the student has not touched is not an error yet; it is just empty. */
    private ValidationState localCodeState() {
        if (!codeTouched || code.isEmpty() || CODE_PATTERN.matcher(code).matches()) {
            return ValidationState.pristine();
        }
        return ValidationState.invalid(ExamCopy.CODE_INVALID);
    }

    private ValidationState localIdState() {
        return !idTouched || !nationalId.isEmpty()
                ? ValidationState.pristine()
                : ValidationState.invalid(ExamCopy.ID_REQUIRED);
    }

    private Consumer<ValidationState> target(boolean resuming) {
        return resuming ? state -> codeState = state : state -> idState = state;
    }
}
