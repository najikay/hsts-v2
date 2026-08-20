package client.features.exam;

import common.dto.exam.AttemptForm;
import common.dto.exam.AttemptOutcome;
import common.dto.exam.AttemptState;
import common.dto.exam.AttemptTiming;
import common.dto.exam.ExamHeader;
import common.dto.exam.ExamQuestion;
import common.dto.exam.SavedAnswer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The exam form's state, with no JavaFX in it (Presentation tier, E10.10/E10.16).
 *
 * <p>Everything a screen renders while a student sits an exam: the paper, what she has
 * chosen, where she is, how far through she is, whether her changes are stored, and how it
 * ended. It is the whole reason the take-exam screens can be tested without a toolkit,
 * which matters more here than anywhere else in the product because this is the epic that
 * failed a defence.
 *
 * <h2>The server's state, held; never the client's opinion</h2>
 *
 * <p>{@link #apply(AttemptForm)} <b>replaces</b> everything rather than merging. A resume
 * after a dropped connection is not a reconciliation between what the client remembers and
 * what the server holds; the server holds the truth and the client adopts it. Merging is
 * how a client ends up showing an answer that was never stored.
 *
 * <p>The one place a local value leads the server is a selection the student has just made
 * and the autosave has not yet confirmed. That is deliberate — a radio button that waited
 * 400 ms for a round trip before appearing selected would be unusable — and it is bounded:
 * the next server answer overwrites it, and {@link #saveState()} tells the student, in
 * words, that it is not stored yet.
 */
public final class AttemptModel {

    private final List<ExamQuestion> questions = new ArrayList<>();
    private final Map<Long, Integer> answers = new LinkedHashMap<>();

    private long attemptId;
    private ExamHeader header;
    private AttemptTiming timing;
    private AttemptState state = AttemptState.NOT_STARTED;
    private AttemptOutcome outcome;
    private SaveState saveState = SaveState.SAVED;
    private int currentIndex;
    private Runnable onChange = () -> { };

    /** Registers the "re-read me and re-render" callback the view installs. */
    public AttemptModel onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== Adopting server state =========================

    /**
     * Adopts a whole form: the answer to a start, a resume, or a reconnect (E10.6).
     *
     * <p>Replaces the paper, the answers, the clock and the state in one step, so there is
     * no window in which half of the screen is showing one attempt and half another. The
     * current question is kept when it still exists, because a student who reconnects
     * should find herself where she was rather than back at question one.
     *
     * @param form what the server sent
     */
    public void apply(AttemptForm form) {
        Objects.requireNonNull(form, "form");
        this.attemptId = form.attemptId();
        this.header = form.header();
        this.timing = form.timing();
        this.state = form.state();
        this.outcome = form.outcome();
        questions.clear();
        questions.addAll(form.questions());
        answers.clear();
        for (SavedAnswer answer : form.savedAnswers()) {
            answers.put(answer.questionVersionId(), answer.selected());
        }
        currentIndex = Math.min(currentIndex, Math.max(0, questions.size() - 1));
        // A resume proves the server has everything it was sent; anything the student
        // chose in the seconds before the socket dropped is either in this form or gone,
        // and either way the indicator is now telling the truth.
        saveState = SaveState.SAVED;
        onChange.run();
    }

    /**
     * Re-anchors the countdown to the server's word (S-18).
     *
     * <p>Called on every response and every push that carries one, which is all of them.
     * The client never advances this itself; between messages the countdown interpolates
     * from it and nothing more.
     *
     * @param fresh the server's timing
     */
    public void syncTiming(AttemptTiming fresh) {
        if (fresh == null) {
            return;
        }
        this.timing = fresh;
        onChange.run();
    }

    /**
     * Adopts a terminal outcome: a submit's answer, or a force-submit push (E10.14 ⚑).
     *
     * <p>This is the takeover's trigger. It is one-way: once an attempt is finished nothing
     * puts it back, so a late-arriving answer to a save that was in flight when the bell
     * went cannot reopen the form.
     *
     * @param finished how it ended
     */
    public void finish(AttemptOutcome finished) {
        Objects.requireNonNull(finished, "finished");
        if (state.isFinished()) {
            return;
        }
        this.outcome = finished;
        this.state = finished.state();
        this.saveState = SaveState.SAVED;
        onChange.run();
    }

    // ===================== The student's own actions =====================

    /**
     * Records a choice locally, optimistically.
     *
     * <p>The radio button has to move now, not after a round trip. What makes that honest
     * rather than a lie is {@link #saveState()}: the indicator says "Saving" until the
     * server confirms, and says so in words a student can act on if it never does.
     *
     * @param questionVersionId which question
     * @param option            1..4
     * @return {@code true} when this actually changed something
     */
    public boolean select(long questionVersionId, int option) {
        if (state.isFinished()) {
            // The takeover locks the paper. The server would refuse anyway, but a form that
            // still responds to clicks after time is up is the v1 screen this replaces.
            return false;
        }
        Integer previous = answers.put(questionVersionId, option);
        if (previous != null && previous == option) {
            return false;
        }
        saveState = SaveState.SAVING;
        onChange.run();
        return true;
    }

    /** Moves to a question by index, clamped to the paper. Drives the navigator strip. */
    public void goTo(int index) {
        if (questions.isEmpty()) {
            return;
        }
        int clamped = Math.max(0, Math.min(index, questions.size() - 1));
        if (clamped != currentIndex) {
            currentIndex = clamped;
            onChange.run();
        }
    }

    /** Sets the indicator; the session calls this as writes land or fail. */
    public void setSaveState(SaveState newState) {
        Objects.requireNonNull(newState, "newState");
        if (newState != saveState) {
            saveState = newState;
            onChange.run();
        }
    }

    // ===================== Reading it ====================================

    /** @return the attempt id, for the save and submit verbs. */
    public long attemptId() {
        return attemptId;
    }

    /** @return the exam header, or {@code null} before the first form arrives. */
    public ExamHeader header() {
        return header;
    }

    /** @return the paper, in order. */
    public List<ExamQuestion> questions() {
        return List.copyOf(questions);
    }

    /** @return the question on screen, or empty when the paper is empty. */
    public Optional<ExamQuestion> currentQuestion() {
        return questions.isEmpty() ? Optional.empty() : Optional.of(questions.get(currentIndex));
    }

    /** @return the 0-based position of the question on screen. */
    public int currentIndex() {
        return currentIndex;
    }

    /**
     * @param questionVersionId a question
     * @return the chosen option 1..4, or empty when it is blank
     */
    public Optional<Integer> answerFor(long questionVersionId) {
        return Optional.ofNullable(answers.get(questionVersionId));
    }

    /** @return how many questions carry a choice. */
    public int answeredCount() {
        return answers.size();
    }

    /** @return the paper's length. */
    public int questionCount() {
        return questions.size();
    }

    /** @return how many are blank; they score zero (§6). */
    public int unansweredCount() {
        return Math.max(0, questionCount() - answeredCount());
    }

    /** @return the progress line, "Answered 7 of 20". */
    public String progressLabel() {
        return ExamCopy.progress(answeredCount(), questionCount());
    }

    /** @return progress as a fraction 0..1, for the bar. */
    public double progress() {
        return questionCount() == 0 ? 0 : (double) answeredCount() / questionCount();
    }

    /** @return where the attempt stands. */
    public AttemptState state() {
        return state;
    }

    /** @return {@code true} while the paper is editable. */
    public boolean isLive() {
        return state.isLive();
    }

    /** @return {@code true} once the takeover or the Submitted screen should be showing. */
    public boolean isFinished() {
        return state.isFinished();
    }

    /** @return how it ended, once it has. */
    public Optional<AttemptOutcome> outcome() {
        return Optional.ofNullable(outcome);
    }

    /** @return what the autosave indicator says. */
    public SaveState saveState() {
        return saveState;
    }

    /** @return the server's last word on the clock, or {@code null} before the first form. */
    public AttemptTiming timing() {
        return timing;
    }

    /** @return the deadline to anchor the countdown to, or empty before the first form. */
    public Optional<Instant> endsAt() {
        return Optional.ofNullable(timing).map(AttemptTiming::endsAt);
    }

    /** @return the whole allotted duration, extensions included; drives the amber threshold. */
    public Duration totalDuration() {
        return timing == null ? Duration.ZERO : timing.total();
    }

    /** @return remaining time as the server last stated it. */
    public Duration remaining() {
        return timing == null ? Duration.ZERO : timing.remaining();
    }

    /**
     * The navigator strip and the submit dialog's grid (F6.9).
     *
     * @return one chip per question, in paper order
     */
    public List<QuestionChip> chips() {
        List<QuestionChip> chips = new ArrayList<>(questions.size());
        for (int index = 0; index < questions.size(); index++) {
            ExamQuestion question = questions.get(index);
            chips.add(new QuestionChip(index, Integer.toString(question.ordinal()),
                    question.displayId(),
                    answers.containsKey(question.questionVersionId()),
                    index == currentIndex));
        }
        return List.copyOf(chips);
    }

    /** Empties everything. Called when the screen is left, so nothing survives into a re-entry. */
    public void clear() {
        attemptId = 0;
        header = null;
        timing = null;
        state = AttemptState.NOT_STARTED;
        outcome = null;
        saveState = SaveState.SAVED;
        currentIndex = 0;
        questions.clear();
        answers.clear();
        onChange.run();
    }
}
