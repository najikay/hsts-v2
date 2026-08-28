package client.features.exam;

import client.events.ClientEventBus;
import client.events.ConnectionLostEvent;
import client.events.FxThreadPoster;
import client.events.ServerPushEvent;
import client.net.RequestDispatcher;
import common.dto.exam.AttemptForm;
import common.dto.exam.AttemptOutcome;
import common.dto.exam.AttemptResumeRequest;
import common.dto.exam.AttentionReport;
import common.dto.exam.SaveAnswerRequest;
import common.dto.exam.SaveAnswerResult;
import common.dto.exam.SubmitAttemptRequest;
import common.dto.exam.TimerExtended;
import common.protocol.Message;
import common.protocol.Verb;
import org.greenrobot.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The exam form's conversation with the server (Presentation tier, E10.11–E10.16 — F6.3,
 * F6.4, F6.9, F7.1).
 *
 * <p>Everything that is not a node: the debounced autosave, the submit, the resume after a
 * dropped socket, and the two pushes that can arrive at any moment. It speaks only to
 * {@link RequestDispatcher} and {@link ClientEventBus}, so all of it is unit-tested against
 * {@code FakeClientConnection} with no JavaFX toolkit — the pattern every screen in this
 * project follows, and the reason this epic's state machine can be tested at all.
 *
 * <h2>The client decides nothing about time</h2>
 *
 * <p>Every response this class receives carries an {@code AttemptTiming}, and every one of
 * them is handed to {@link AttemptModel#syncTiming}. The countdown re-anchors; it never
 * accumulates. That means a laptop that slept, a clock two minutes out, and a client that
 * missed the extension push entirely all correct themselves on the next autosave, which
 * happens every few seconds while a student works.
 *
 * <h2>Autosave, and what "saved" is allowed to mean</h2>
 *
 * <p>A click updates the model immediately and marks a dirty question; {@value
 * #DEBOUNCE_MS} ms after the <em>last</em> click the dirty set is flushed, so a student
 * changing her mind four times sends one write per question rather than four. The indicator
 * says {@code Saving} until the server confirms and {@code Not saved yet, retrying} if it
 * does not, because a student must never believe an answer is stored when it is not.
 * Submitting flushes first, so nothing chosen in the last half second is lost to the button.
 *
 * <h2>The two pushes</h2>
 *
 * <ul>
 *   <li>{@code PUSH_TIMER_EXTENDED} re-syncs the clock and raises the <i>Time Extended</i>
 *       moment. It is never swallowed: F7.1 makes "the student always knows" a rule.</li>
 *   <li>{@code PUSH_FORCE_SUBMITTED} finishes the model, which locks the paper and triggers
 *       the Time Up takeover. There is no confirmation because it has already happened
 *       (F6.4), and no way back because {@link AttemptModel#finish} is one-way.</li>
 * </ul>
 *
 * <h2>Attention events (E11.7 — F7.1b)</h2>
 *
 * <p>This class owns the <em>reporting</em> half: it holds an {@link AttentionTracker}, starts
 * it with a live paper, stops it the moment the attempt finishes however it finished, and
 * sends one {@code ATTEMPT_ATTENTION} per absence that survives the flicker threshold. The
 * view owns only the other half — forwarding the window's focus property in. Nothing about
 * this is visible to the student, by rule.
 */
public final class ExamAttemptSession {

    private static final Logger log = LoggerFactory.getLogger(ExamAttemptSession.class);

    /**
     * How long the autosave waits after the last change.
     *
     * <p>400 ms: past the pause between a student reading an option and clicking the next
     * one, short enough that a dropped socket half a second later loses nothing a person
     * would notice. Lower would send a write per keystroke-equivalent; higher would start
     * to feel like a form that has not registered the click.
     */
    public static final int DEBOUNCE_MS = 400;

    private final RequestDispatcher dispatcher;
    private final ClientEventBus eventBus;
    /**
     * The FX-thread seam (M-4, 2026-08-28). Pushes arrive through the bus already posted,
     * but responses complete on OCSF's read thread, and everything they touch here ends in
     * a render. Taken from the bus rather than a fifth parameter so every constructor site
     * gets it for free and the two can never disagree about which thread "the" FX thread is.
     */
    private final FxThreadPoster poster;
    private final AttemptModel model;
    private final DelayedRunner delayed;
    private final AttentionTracker attention;

    private final Map<Long, Integer> dirty = new LinkedHashMap<>();
    private final List<Consumer<TimerExtended>> extensionListeners = new ArrayList<>();
    private final List<Consumer<AttemptOutcome>> finishListeners = new ArrayList<>();
    private final List<Consumer<ConnectionLostEvent>> connectionListeners = new ArrayList<>();

    private long executionId;
    private boolean started;
    private boolean flushScheduled;

    /**
     * @param dispatcher the shared request correlator
     * @param eventBus   the app bus; pushes arrive on it already on the FX thread
     * @param model      the state every view of the form reads
     * @param delayed    the debounce timer; {@link DelayedRunner#shared()} in production
     */
    public ExamAttemptSession(RequestDispatcher dispatcher, ClientEventBus eventBus,
                              AttemptModel model, DelayedRunner delayed) {
        this(dispatcher, eventBus, model, delayed, AttentionTracker.systemClock());
    }

    /**
     * @param attention the E11.7 focus watcher; inject a fake-clock one in tests. It is a
     *                  constructor argument rather than something the view owns because the
     *                  <em>reporting</em> half belongs here — the view knows about a
     *                  {@code Stage}, this class knows about the wire
     */
    public ExamAttemptSession(RequestDispatcher dispatcher, ClientEventBus eventBus,
                              AttemptModel model, DelayedRunner delayed,
                              AttentionTracker attention) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.poster = eventBus.poster();
        this.model = Objects.requireNonNull(model, "model");
        this.delayed = Objects.requireNonNull(delayed, "delayed");
        this.attention = Objects.requireNonNull(attention, "attention");
        this.attention.onAbsence(this::reportAttention);
    }

    /** @return the state this session maintains. */
    public AttemptModel model() {
        return model;
    }

    /**
     * @return the focus watcher (E11.7). {@code TakeExamView} forwards the exam window's
     *         {@code focusedProperty} into it; nothing else in the client touches it
     */
    public AttentionTracker attention() {
        return attention;
    }

    // ===================== Lifecycle =====================================

    /**
     * Adopts a form and subscribes to pushes.
     *
     * <p>Called with the answer to {@code ATTEMPT_START} or {@code ATTEMPT_RESUME}. A form
     * that is already finished is adopted too, and that is the resume-into-the-takeover
     * path (E10.14 ⚑): a student whose time ran out while her laptop was shut finds the
     * takeover waiting, not a live paper.
     *
     * @param executionId which execution this is, for the resume verb
     * @param form        the server's form
     */
    public void start(long executionId, AttemptForm form) {
        Objects.requireNonNull(form, "form");
        this.executionId = executionId;
        if (!started) {
            eventBus.register(this);
            started = true;
        }
        model.apply(form);
        // Watching begins with the live paper and never with a finished one: an attempt that
        // is already over cannot accrue attention events (E11.7).
        if (model.isFinished()) {
            attention.stop();
            model.outcome().ifPresent(this::notifyFinished);
        } else {
            attention.start();
        }
    }

    /** Unsubscribes and empties the model. Called when the screen is left. */
    public void stop() {
        if (started) {
            eventBus.unregister(this);
            started = false;
        }
        dirty.clear();
        flushScheduled = false;
        attention.stop();
        model.clear();
    }

    /** @return {@code true} while this session is subscribed to the bus. */
    public boolean isStarted() {
        return started;
    }

    /** @return the execution being sat, for the route guard and for resume. */
    public long executionId() {
        return executionId;
    }

    // ===================== Listeners =====================================

    /** Subscribes to the <i>Time Extended</i> designed moment (F7.1 ⚑). */
    public void onExtended(Consumer<TimerExtended> listener) {
        extensionListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Subscribes to the attempt ending, however it ended (F6.4/F6.10). */
    public void onFinished(Consumer<AttemptOutcome> listener) {
        finishListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Subscribes to the socket dropping mid-exam, for the reconnect banner (E10.15).
     *
     * <p>Named for what a subscriber wants rather than for the event, so it does not
     * collide with this class's own {@code @Subscribe} handler below. The two would
     * otherwise overload each other, and an overload set where one member is a bus callback
     * and the other is a registration is a trap for whoever reads it next.
     */
    public void onDisconnected(Consumer<ConnectionLostEvent> listener) {
        connectionListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    // ===================== The student's actions =========================

    /**
     * Records a choice and schedules the write (F6.3).
     *
     * <p>Returns immediately; the round trip happens on its own. A choice made while the
     * paper is finished is refused by the model and never leaves the client, so the
     * takeover cannot be typed through.
     *
     * @param questionVersionId which question
     * @param option            1..4
     */
    public void select(long questionVersionId, int option) {
        if (!model.select(questionVersionId, option)) {
            return;
        }
        dirty.put(questionVersionId, option);
        scheduleFlush();
    }

    /**
     * Sends every pending change now.
     *
     * <p>Called by the debounce, and directly by {@link #submit()} so a choice made half a
     * second before the button cannot be lost to it.
     *
     * @return a future completing when every pending write has been answered; never
     *         completes exceptionally, because a failed autosave is a state the indicator
     *         shows rather than an exception a screen must catch
     */
    public CompletableFuture<Void> flush() {
        if (dirty.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Map<Long, Integer> sending = new LinkedHashMap<>(dirty);
        dirty.clear();
        List<CompletableFuture<Void>> writes = new ArrayList<>(sending.size());
        for (Map.Entry<Long, Integer> entry : sending.entrySet()) {
            writes.add(send(entry.getKey(), entry.getValue()));
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    /**
     * Hands the paper in (F6.9).
     *
     * <p>Flushes first, then submits. The answer is an {@link AttemptOutcome} whichever way
     * the attempt actually ended: a submit that lost the race with the expiry timer comes
     * back {@code TIMED_OUT} rather than as an error, and the screen shows the takeover.
     * From the student's side both are "it is handed in", which is the truth.
     *
     * @return the outcome, or a future completed with {@code null} when the request failed
     *         and the paper is still live
     */
    public CompletableFuture<AttemptOutcome> submit() {
        long attemptId = model.attemptId();
        CompletableFuture<AttemptOutcome> settled = new CompletableFuture<>();
        flush().thenCompose(ignored -> dispatcher.send(Verb.ATTEMPT_SUBMIT,
                        new SubmitAttemptRequest(attemptId)))
                .whenComplete((response, failure) -> poster.run(() -> {
                    if (failure != null) {
                        log.warn("Submit failed: {}", failure.toString());
                        settled.complete(null);
                        return;
                    }
                    if (response.isError() || !(response.getPayload() instanceof AttemptOutcome outcome)) {
                        log.warn("Submit refused: {} {}", response.getErrorCode(), response.errorMessage());
                        settled.complete(null);
                        return;
                    }
                    model.finish(outcome);
                    notifyFinished(outcome);
                    settled.complete(outcome);
                }));
        return settled;
    }

    /**
     * Refetches the whole attempt (E10.6, E10.15).
     *
     * <p>The reconnect path, and the "screen reopened" path. Everything is replaced from
     * the answer, so nothing the client believed while it was disconnected survives.
     *
     * @return a future completing when the model has been updated, or when the attempt
     *         failed (the model is then unchanged)
     */
    public CompletableFuture<Void> resume() {
        CompletableFuture<Void> settled = new CompletableFuture<>();
        dispatcher.send(Verb.ATTEMPT_RESUME, new AttemptResumeRequest(executionId))
                .whenComplete((response, failure) -> poster.run(() -> {
                    if (failure != null) {
                        log.warn("Resume failed: {}", failure.toString());
                        settled.complete(null);
                        return;
                    }
                    if (response.isError() || !(response.getPayload() instanceof AttemptForm form)) {
                        log.warn("Resume refused: {} {}", response.getErrorCode(), response.errorMessage());
                        settled.complete(null);
                        return;
                    }
                    boolean wasLive = !model.isFinished();
                    model.apply(form);
                    if (wasLive && model.isFinished()) {
                        // She was away when her time ran out. This is E10.14's other door.
                        model.outcome().ifPresent(this::notifyFinished);
                    } else if (!model.isFinished() && !attention.isTracking()) {
                        // Back on a live paper after a dropped socket: watch again (E11.7).
                        // Only when it had stopped, so a resume mid-absence does not silently
                        // discard the absence it is in the middle of.
                        attention.start();
                    }
                    settled.complete(null);
                }));
        return settled;
    }

    // ===================== Pushes ========================================

    /**
     * Applies {@code PUSH_TIMER_EXTENDED} and {@code PUSH_FORCE_SUBMITTED}.
     *
     * <p>One generic event carries every push (E1.8), so anything else passes through
     * untouched.
     */
    @Subscribe
    public void onServerPush(ServerPushEvent event) {
        if (event == null) {
            return;
        }
        if (event.verb() == Verb.PUSH_TIMER_EXTENDED
                && event.payload() instanceof TimerExtended extension) {
            if (extension.executionId() != executionId) {
                return;
            }
            model.syncTiming(extension.timing());
            log.info("{} added {} minutes", extension.teacherName(), extension.extraMinutes());
            for (Consumer<TimerExtended> listener : List.copyOf(extensionListeners)) {
                listener.accept(extension);
            }
            return;
        }
        if (event.verb() == Verb.PUSH_FORCE_SUBMITTED
                && event.payload() instanceof AttemptOutcome outcome) {
            if (outcome.attemptId() != model.attemptId() || model.isFinished()) {
                return;
            }
            log.info("Server force-submitted attempt {}", outcome.attemptId());
            model.finish(outcome);
            notifyFinished(outcome);
        }
    }

    /** Raises the reconnect banner and stops pretending the autosave is working (E10.15). */
    @Subscribe
    public void onConnectionLost(ConnectionLostEvent event) {
        if (event == null || !started) {
            return;
        }
        if (!dirty.isEmpty() || model.saveState() == SaveState.SAVING) {
            model.setSaveState(SaveState.FAILED);
        }
        for (Consumer<ConnectionLostEvent> listener : List.copyOf(connectionListeners)) {
            listener.accept(event);
        }
    }

    // ===================== Internals =====================================

    /**
     * Arms the debounce, once.
     *
     * <p>The flag rather than cancelling and rescheduling: a single pending flush that
     * always sends whatever is dirty when it fires has the same effect as a resettable
     * timer, with no handle to lose and nothing to cancel on a background thread. The cost
     * is that a burst of clicks can flush up to {@value #DEBOUNCE_MS} ms after the first
     * rather than the last, which is not a cost anybody can perceive.
     */
    private void scheduleFlush() {
        if (flushScheduled) {
            return;
        }
        flushScheduled = true;
        // Posted: the debounce fires on DelayedRunner's timer thread, and the dirty map
        // it flushes belongs to the FX thread that fills it (M-4's sibling race).
        delayed.runAfter(Duration.ofMillis(DEBOUNCE_MS), () -> poster.run(() -> {
            flushScheduled = false;
            flush();
        }));
    }

    /** One write, and what its answer means for the indicator and the clock. */
    private CompletableFuture<Void> send(long questionVersionId, int option) {
        CompletableFuture<Void> settled = new CompletableFuture<>();
        dispatcher.send(Verb.ANSWER_SAVE,
                        new SaveAnswerRequest(model.attemptId(), questionVersionId, option))
                .whenComplete((response, failure) -> poster.run(() -> {
                    if (failure != null) {
                        log.warn("Autosave of question {} failed: {}", questionVersionId, failure.toString());
                        retry(questionVersionId, option);
                        settled.complete(null);
                        return;
                    }
                    if (response.isError()) {
                        // A refusal is the server's decision and is not retried: the usual
                        // reason is that time is up, and hammering a closed attempt would
                        // turn one honest rejection into a loop.
                        log.info("Autosave refused: {} {}", response.getErrorCode(), response.errorMessage());
                        model.setSaveState(SaveState.FAILED);
                        resume();
                        settled.complete(null);
                        return;
                    }
                    if (response.getPayload() instanceof SaveAnswerResult result) {
                        model.syncTiming(result.timing());
                    }
                    if (dirty.isEmpty()) {
                        model.setSaveState(SaveState.SAVED);
                    }
                    settled.complete(null);
                }));
        return settled;
    }

    /** Puts a failed write back in the queue, unless the student has since changed it. */
    private void retry(long questionVersionId, int option) {
        dirty.putIfAbsent(questionVersionId, option);
        model.setSaveState(SaveState.FAILED);
    }

    /**
     * Sends one {@code ATTEMPT_ATTENTION} report (E11.7 — F7.1b).
     *
     * <p>Fire and forget, and deliberately unretried. The server answers OK whether or not
     * she still has a live attempt, so there is nothing to branch on; and an absence that was
     * lost to a dropped socket is one signal missing from a row that already carries the
     * others, which is a far smaller problem than a client that queues focus events and
     * replays them minutes later against whatever attempt is live by then.
     *
     * @param awayMillis how long the window was unfocused
     */
    void reportAttention(long awayMillis) {
        if (awayMillis <= 0) {
            return;
        }
        dispatcher.send(Verb.ATTEMPT_ATTENTION, new AttentionReport(awayMillis))
                .handle((response, failure) -> {
                    if (failure != null) {
                        log.debug("Attention report not delivered: {}", failure.toString());
                    } else if (response.isError()) {
                        log.debug("Attention report refused: {}", response.getErrorCode());
                    }
                    return null;
                });
    }

    private void notifyFinished(AttemptOutcome outcome) {
        // Whatever ended it — her submit, the timer, a resume that found it overdue — the
        // paper is closed and the focus watcher goes quiet.
        attention.stop();
        for (Consumer<AttemptOutcome> listener : List.copyOf(finishListeners)) {
            listener.accept(outcome);
        }
    }

    /** @return the questions still waiting to be written (diagnostics and tests). */
    Set<Long> pendingQuestions() {
        return new LinkedHashSet<>(dirty.keySet());
    }
}
