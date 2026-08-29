package client.features.exam;

import client.events.FxThreadPoster;
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
    private final FxThreadPoster poster;

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
    private boolean confirming;

    /**
     * @param dispatcher the shared request correlator
     * @param poster     the FX-thread seam (M-4). Responses arrive on OCSF's read thread;
     *                   everything downstream of them renders, so every answer is applied
     *                   through the poster. This session went without one until 2026-08-28,
     *                   which put the whole paper render on the network thread in production
     *                   while every test delivered on the FX thread: P-8's shape, on the
     *                   screen that failed the first defence.
     */
    public ExamEntrySession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
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

    /**
     * Takes a code the dashboard already validated and turns the step into a confirmation ⚑.
     *
     * <p>2026-08-28, manual round 1, lead's ruling. Two things were wrong with pre-filling the
     * text control instead. The first is a bug: {@code setText} with the value the control
     * already holds fires no listener, so a second visit to the same exam left the session
     * empty behind a filled-in field and Continue stayed disabled forever. The second is the
     * design: she had just pressed a card carrying this code, and the screen asked her for it.
     *
     * <p>So the code goes in through the session, which is the only thing {@link #canContinue()}
     * reads, and {@link #isConfirming()} tells the view to render the step as the confirmation
     * it is. Nothing else about the flow moves: the same {@code EXAM_JOIN} is sent, by the same
     * button, and the same four refusals land on the same field.
     *
     * @param raw the code to confirm; blank, or anything that is not a well-formed code, is
     *            ignored and leaves the ordinary editable step
     */
    public void prefill(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        setCode(raw);
        confirming = CODE_PATTERN.matcher(code).matches();
        onChange.run();
    }

    /**
     * Leaves the confirmation for the ordinary editable step, cleared (lead's ruling).
     *
     * <p>Cleared rather than left filled: the student pressing this is saying the code is not
     * the one she wants, and a field she has to empty first is a field arguing with her.
     */
    public void useDifferentCode() {
        confirming = false;
        code = "";
        codeTouched = false;
        codeState = ValidationState.pristine();
        onChange.run();
    }

    /** @return {@code true} when the code step is confirming a code rather than asking for one. */
    public boolean isConfirming() {
        return confirming;
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
        CompletableFuture<Void> applied = new CompletableFuture<>();
        dispatcher.send(Verb.EXAM_JOIN, new ExamJoinRequest(code))
                .whenComplete((response, failure) -> poster.run(() -> {
                    setBusy(false);
                    applyJoin(response, failure);
                    resumeIfAlreadySitting()
                            .whenComplete((ignored, ignoredFailure) -> applied.complete(null));
                }));
        return applied;
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
        CompletableFuture<Void> applied = new CompletableFuture<>();
        dispatcher.send(Verb.ATTEMPT_RESUME, new AttemptResumeRequest(header.executionId()))
                .whenComplete((response, failure) -> poster.run(() -> {
                    setBusy(false);
                    applyForm(response, failure, true);
                    applied.complete(null);
                }));
        return applied;
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
        CompletableFuture<Void> applied = new CompletableFuture<>();
        dispatcher.send(Verb.ATTEMPT_START,
                        new AttemptStartRequest(header.executionId(), nationalId))
                .whenComplete((response, failure) -> poster.run(() -> {
                    setBusy(false);
                    applyForm(response, failure, false);
                    applied.complete(null);
                }));
        return applied;
    }

    /**
     * Hands the code step back from the identity step, unstarted ⚑.
     *
     * <p>2026-08-29, manual round 2, lead's ruling: "Confirm it is you" had no way back. Take
     * Exam is a rail route, so the shell draws no back control over it, and the only exits from
     * the identity step were forward into the exam or out of the application. A student who
     * joined the wrong sitting had to start her own clock to escape it.
     *
     * <p>Nothing is sent. {@code EXAM_JOIN} answered a header and nothing else; there is no
     * attempt yet, and this is exactly why the flow is split in two (S-18). So going back is
     * local state and the server never learns it happened.
     *
     * <p>The header and the ID go, because both belong to the join she is undoing. The code and
     * its mood stay, because she is going back to the step she came from and not to a blank one:
     * an arrival from the dashboard card lands on its confirmation again, and a typed code is
     * still in the field ready to be corrected rather than retyped.
     *
     * <p>Ignored while a request is in flight. The press would otherwise land on the identity
     * step and the answer would land on the code step behind it, which is how a student ends up
     * on the paper she just backed out of.
     */
    public void backToCode() {
        if (busy) {
            // The guard is also why nothing here sets busy: it is already false.
            return;
        }
        phase = EntryPhase.CODE;
        header = null;
        nationalId = "";
        idTouched = false;
        idState = ValidationState.pristine();
        onChange.run();
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
        confirming = false;
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
