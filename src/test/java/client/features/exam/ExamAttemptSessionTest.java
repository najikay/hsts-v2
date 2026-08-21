package client.features.exam;

import client.events.ClientEventBus;
import client.events.ConnectionLostEvent;
import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.exam.AttemptForm;
import common.dto.exam.AttemptOutcome;
import common.dto.exam.AttemptState;
import common.dto.exam.AttemptSummaryEntry;
import common.dto.exam.AttemptTiming;
import common.dto.exam.ExamHeader;
import common.dto.exam.ExamQuestion;
import common.dto.exam.SaveAnswerRequest;
import common.dto.exam.SaveAnswerResult;
import common.dto.exam.SavedAnswer;
import common.dto.exam.TimerExtended;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The exam form's conversation with the server (E10.11–E10.16).
 *
 * <p>No JavaFX toolkit: a {@link FakeClientConnection} answers the real
 * {@link RequestDispatcher} and the bus posts synchronously, so a push becomes state on the
 * next line. The debounce runs on a manual {@link DelayedRunner}, which is what turns "one
 * write per question, not one per click" into an assertion rather than a sleep.
 */
class ExamAttemptSessionTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant ENDS = NOW.plus(Duration.ofMinutes(45));
    private static final long EXECUTION = 5001L;
    private static final long ATTEMPT = 42L;

    /** A debounce a test fires by hand. */
    private static final class ManualDelay implements DelayedRunner {

        private final List<Runnable> pending = new ArrayList<>();

        @Override
        public void runAfter(Duration delay, Runnable task) {
            pending.add(task);
        }

        void fire() {
            List<Runnable> due = List.copyOf(pending);
            pending.clear();
            due.forEach(Runnable::run);
        }

        int pendingCount() {
            return pending.size();
        }
    }

    private FakeClientConnection connection;
    private ClientEventBus eventBus;
    private AttemptModel model;
    private ManualDelay delay;
    private ExamAttemptSession session;
    private List<TimerExtended> extensions;
    private List<AttemptOutcome> endings;
    private List<ConnectionLostEvent> drops;

    @BeforeEach
    void setUp() throws IOException {
        connection = new FakeClientConnection();
        connection.connect();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        eventBus = new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster());
        // The one wire a real connect installs: pushes off the socket become bus events.
        // Without it a push would be dropped by the dispatcher and this suite would be
        // testing nothing about the two moments that matter most.
        dispatcher.setPushListener(new client.events.PushEventBridge(eventBus));
        model = new AttemptModel();
        delay = new ManualDelay();
        session = new ExamAttemptSession(dispatcher, eventBus, model, delay);

        extensions = new ArrayList<>();
        endings = new ArrayList<>();
        drops = new ArrayList<>();
        session.onExtended(extensions::add);
        session.onFinished(endings::add);
        session.onDisconnected(drops::add);
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("starting adopts the form and subscribes to pushes")
        void startAdoptsAndSubscribes() {
            session.start(EXECUTION, liveForm(List.of()));

            assertThat(session.isStarted()).isTrue();
            assertThat(session.executionId()).isEqualTo(EXECUTION);
            assertThat(eventBus.isRegistered(session)).isTrue();
            assertThat(model.questionCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("starting twice does not subscribe twice")
        void startIsIdempotent() {
            session.start(EXECUTION, liveForm(List.of()));
            session.start(EXECUTION, liveForm(List.of()));

            assertThat(session.isStarted()).isTrue();
        }

        @Test
        @DisplayName("starting with an already finished form raises the ending at once (E10.14 ⚑)")
        void startingFinishedRaisesTheEnding() {
            session.start(EXECUTION, finishedForm(AttemptState.TIMED_OUT));

            // The resume-into-takeover path: her time ran out while her laptop was shut.
            assertThat(endings).hasSize(1);
            assertThat(endings.get(0).state()).isEqualTo(AttemptState.TIMED_OUT);
        }

        @Test
        @DisplayName("stopping unsubscribes and empties the model")
        void stopClearsEverything() {
            session.start(EXECUTION, liveForm(List.of()));

            session.stop();

            assertThat(session.isStarted()).isFalse();
            assertThat(eventBus.isRegistered(session)).isFalse();
            assertThat(model.questionCount()).isZero();
            assertThat(session.pendingQuestions()).isEmpty();
        }

        @Test
        @DisplayName("its collaborators are all required")
        void constructorGuards() {
            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            assertThatNullPointerException().isThrownBy(() ->
                    new ExamAttemptSession(null, eventBus, model, delay));
            assertThatNullPointerException().isThrownBy(() ->
                    new ExamAttemptSession(dispatcher, null, model, delay));
            assertThatNullPointerException().isThrownBy(() ->
                    new ExamAttemptSession(dispatcher, eventBus, null, delay));
            assertThatNullPointerException().isThrownBy(() ->
                    new ExamAttemptSession(dispatcher, eventBus, model, null));
            assertThatNullPointerException().isThrownBy(() -> session.onExtended(null));
            assertThatNullPointerException().isThrownBy(() -> session.onFinished(null));
            assertThatNullPointerException().isThrownBy(() -> session.onDisconnected(null));
        }
    }

    @Nested
    @DisplayName("autosave (F6.3)")
    class Autosave {

        @BeforeEach
        void start() {
            session.start(EXECUTION, liveForm(List.of()));
            connection.replyOk(Verb.ANSWER_SAVE, saveResult(1, 3));
        }

        @Test
        @DisplayName("a click shows at once and is written after the debounce, not before")
        void writesAfterTheDebounce() {
            session.select(1001, 3);

            assertThat(model.answerFor(1001)).contains(3);
            assertThat(model.saveState()).isEqualTo(SaveState.SAVING);
            assertThat(connection.sentCount()).as("nothing sent yet").isZero();

            delay.fire();

            assertThat(connection.sentCount()).isEqualTo(1);
            SaveAnswerRequest sent = (SaveAnswerRequest) connection.lastSent().getPayload();
            assertThat(sent.attemptId()).isEqualTo(ATTEMPT);
            assertThat(sent.questionVersionId()).isEqualTo(1001);
            assertThat(sent.selected()).isEqualTo(3);
        }

        @Test
        @DisplayName("four clicks on one question send one write, not four ⚑")
        void oneWritePerQuestion() {
            session.select(1001, 1);
            session.select(1001, 2);
            session.select(1001, 3);
            session.select(1001, 4);

            delay.fire();

            assertThat(connection.sentCount()).isEqualTo(1);
            assertThat(((SaveAnswerRequest) connection.lastSent().getPayload()).selected())
                    .as("and it is the last choice she made")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("only one debounce is armed however many clicks arrive")
        void oneTimerPerBurst() {
            session.select(1001, 1);
            session.select(1002, 2);
            session.select(1003, 3);

            assertThat(delay.pendingCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("answers to different questions are all written")
        void everyQuestionIsWritten() {
            session.select(1001, 1);
            session.select(1002, 2);

            delay.fire();

            assertThat(connection.sentCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("the indicator says saved only once the server has confirmed")
        void indicatorFollowsTheServer() {
            session.select(1001, 3);
            assertThat(model.saveState()).isEqualTo(SaveState.SAVING);

            delay.fire();

            assertThat(model.saveState()).isEqualTo(SaveState.SAVED);
        }

        @Test
        @DisplayName("every save re-anchors the countdown (S-18) ⚑")
        void everySaveResyncsTheClock() {
            connection.replyOk(Verb.ANSWER_SAVE, new SaveAnswerResult(1001, 3, 1, 3,
                    new AttemptTiming(NOW.plusSeconds(600), ENDS,
                            Duration.ofMinutes(35).toMillis(), Duration.ofMinutes(45).toMillis())));

            session.select(1001, 3);
            delay.fire();

            assertThat(model.remaining()).isEqualTo(Duration.ofMinutes(35));
        }

        @Test
        @DisplayName("a failed write is retried on the next flush and says so meanwhile ⚑")
        void failedWriteIsRetried() throws IOException {
            connection.failSendsWith(new IOException("socket closed"));

            session.select(1001, 3);
            delay.fire();

            assertThat(model.saveState()).isEqualTo(SaveState.FAILED);
            assertThat(session.pendingQuestions()).containsExactly(1001L);
        }

        @Test
        @DisplayName("a refused write is not retried, and the paper is refetched")
        void refusedWriteIsNotRetried() {
            connection.replyError(Verb.ANSWER_SAVE, ErrorCode.CONFLICT,
                    "Time is up. Your exam was handed in automatically.");
            connection.replyOk(Verb.ATTEMPT_RESUME, finishedForm(AttemptState.TIMED_OUT));

            session.select(1001, 3);
            delay.fire();

            // Hammering a closed attempt would turn one honest rejection into a loop; the
            // right move is to find out what the server thinks, which is the takeover.
            assertThat(session.pendingQuestions()).isEmpty();
            assertThat(model.isFinished()).isTrue();
            assertThat(endings).hasSize(1);
        }

        @Test
        @DisplayName("flushing with nothing pending sends nothing")
        void flushingNothing() {
            session.flush().join();

            assertThat(connection.sentCount()).isZero();
        }

        @Test
        @DisplayName("a click after the takeover never leaves the client")
        void clickAfterTheEndSendsNothing() {
            session.start(EXECUTION, finishedForm(AttemptState.TIMED_OUT));
            connection.clearSent();

            session.select(1001, 2);
            delay.fire();

            assertThat(connection.sentCount()).isZero();
        }
    }

    @Nested
    @DisplayName("submitting (F6.9)")
    class Submitting {

        @BeforeEach
        void start() {
            session.start(EXECUTION, liveForm(List.of()));
            connection.replyOk(Verb.ANSWER_SAVE, saveResult(1, 3));
        }

        @Test
        @DisplayName("submitting flushes first, so a last-second click is not lost ⚑")
        void submitFlushesFirst() {
            connection.replyOk(Verb.ATTEMPT_SUBMIT, outcome(AttemptState.SUBMITTED));

            session.select(1001, 3);
            AttemptOutcome result = session.submit().join();

            assertThat(connection.sentMessages()).extracting(Message::getVerb)
                    .containsExactly(Verb.ANSWER_SAVE, Verb.ATTEMPT_SUBMIT);
            assertThat(result).isNotNull();
            assertThat(result.state()).isEqualTo(AttemptState.SUBMITTED);
        }

        @Test
        @DisplayName("the ending is adopted and announced")
        void submitEndsTheAttempt() {
            connection.replyOk(Verb.ATTEMPT_SUBMIT, outcome(AttemptState.SUBMITTED));

            session.submit().join();

            assertThat(model.isFinished()).isTrue();
            assertThat(endings).hasSize(1);
        }

        @Test
        @DisplayName("a submit that lost the race comes back TIMED_OUT, not as an error ⚑")
        void lostRaceIsAnEnding() {
            connection.replyOk(Verb.ATTEMPT_SUBMIT, outcome(AttemptState.TIMED_OUT));

            AttemptOutcome result = session.submit().join();

            // From her side both are "it is handed in", which is the truth.
            assertThat(result.state()).isEqualTo(AttemptState.TIMED_OUT);
            assertThat(endings).hasSize(1);
        }

        @Test
        @DisplayName("a refused submit leaves the paper live rather than faking an ending")
        void refusedSubmitLeavesItLive() {
            connection.replyError(Verb.ATTEMPT_SUBMIT, ErrorCode.NOT_FOUND, "That exam is not open for you.");

            AttemptOutcome result = session.submit().join();

            assertThat(result).isNull();
            assertThat(model.isFinished()).isFalse();
            assertThat(endings).isEmpty();
        }

        @Test
        @DisplayName("a failed submit does not throw at the screen")
        void failedSubmitDoesNotThrow() throws IOException {
            connection.failSendsWith(new IOException("socket closed"));

            assertThat(session.submit().join()).isNull();
            assertThat(model.isFinished()).isFalse();
        }
    }

    @Nested
    @DisplayName("resuming (E10.6/E10.15)")
    class Resuming {

        @Test
        @DisplayName("a resume replaces everything from the server's answer")
        void resumeReplacesEverything() {
            session.start(EXECUTION, liveForm(List.of()));
            connection.replyOk(Verb.ATTEMPT_RESUME, liveForm(List.of(new SavedAnswer(1002, 4))));

            session.resume().join();

            assertThat(model.answerFor(1002)).contains(4);
            assertThat(model.answeredCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a resume that finds the attempt timed out raises the takeover ⚑")
        void resumeIntoTheTakeover() {
            session.start(EXECUTION, liveForm(List.of()));
            connection.replyOk(Verb.ATTEMPT_RESUME, finishedForm(AttemptState.TIMED_OUT));

            session.resume().join();

            assertThat(model.isFinished()).isTrue();
            assertThat(endings).hasSize(1);
            assertThat(endings.get(0).state()).isEqualTo(AttemptState.TIMED_OUT);
        }

        @Test
        @DisplayName("resuming an attempt that was already finished raises nothing new")
        void resumingAFinishedAttempt() {
            session.start(EXECUTION, finishedForm(AttemptState.SUBMITTED));
            endings.clear();
            connection.replyOk(Verb.ATTEMPT_RESUME, finishedForm(AttemptState.SUBMITTED));

            session.resume().join();

            assertThat(endings).isEmpty();
        }

        @Test
        @DisplayName("a failed resume leaves the model untouched")
        void failedResumeChangesNothing() throws IOException {
            session.start(EXECUTION, liveForm(List.of(new SavedAnswer(1001, 2))));
            connection.failSendsWith(new IOException("socket closed"));

            session.resume().join();

            assertThat(model.answerFor(1001)).contains(2);
        }

        @Test
        @DisplayName("a refused resume leaves the model untouched too")
        void refusedResumeChangesNothing() {
            session.start(EXECUTION, liveForm(List.of(new SavedAnswer(1001, 2))));
            connection.replyError(Verb.ATTEMPT_RESUME, ErrorCode.NOT_FOUND, "not yours");

            session.resume().join();

            assertThat(model.answerFor(1001)).contains(2);
        }
    }

    @Nested
    @DisplayName("pushes")
    class Pushes {

        @BeforeEach
        void start() {
            session.start(EXECUTION, liveForm(List.of()));
        }

        @Test
        @DisplayName("an extension re-syncs the clock and plays the moment (F7.1 ⚑)")
        void extensionIsNeverSilent() {
            connection.pushToClient(Verb.PUSH_TIMER_EXTENDED, extension(15));

            assertThat(extensions).hasSize(1);
            assertThat(extensions.get(0).teacherName()).isEqualTo("Dana Cohen");
            assertThat(model.remaining()).isEqualTo(Duration.ofMinutes(60));
            assertThat(model.endsAt()).contains(ENDS.plus(Duration.ofMinutes(15)));
        }

        @Test
        @DisplayName("an extension for another execution is ignored")
        void otherExecutionsExtensionIsIgnored() {
            connection.pushToClient(Verb.PUSH_TIMER_EXTENDED,
                    new TimerExtended(9999, "Other exam", "Someone", 15,
                            AttemptTiming.between(NOW, NOW, ENDS)));

            assertThat(extensions).isEmpty();
        }

        @Test
        @DisplayName("a force-submit locks the paper and raises the takeover (F6.4 ⚑)")
        void forceSubmitTakesOver() {
            connection.pushToClient(Verb.PUSH_FORCE_SUBMITTED, outcome(AttemptState.TIMED_OUT));

            assertThat(model.isFinished()).isTrue();
            assertThat(model.state()).isEqualTo(AttemptState.TIMED_OUT);
            assertThat(endings).hasSize(1);
        }

        @Test
        @DisplayName("a force-submit for another attempt is ignored")
        void otherAttemptsForceSubmitIsIgnored() {
            connection.pushToClient(Verb.PUSH_FORCE_SUBMITTED,
                    new AttemptOutcome(999, AttemptState.TIMED_OUT, "Other", ENDS, 45, 0, 3, List.of()));

            assertThat(model.isFinished()).isFalse();
            assertThat(endings).isEmpty();
        }

        @Test
        @DisplayName("a second force-submit does not raise the takeover twice")
        void forceSubmitIsIdempotent() {
            connection.pushToClient(Verb.PUSH_FORCE_SUBMITTED, outcome(AttemptState.TIMED_OUT));
            connection.pushToClient(Verb.PUSH_FORCE_SUBMITTED, outcome(AttemptState.TIMED_OUT));

            assertThat(endings).hasSize(1);
        }

        @Test
        @DisplayName("a push of another verb passes straight through")
        void otherPushesAreIgnored() {
            connection.pushToClient(Verb.PUSH_NOTIFICATION, "something else");

            assertThat(extensions).isEmpty();
            assertThat(endings).isEmpty();
        }

        @Test
        @DisplayName("a push with the wrong payload type is ignored rather than crashing")
        void malformedPushIsIgnored() {
            connection.pushToClient(Verb.PUSH_TIMER_EXTENDED, "fifteen minutes");
            connection.pushToClient(Verb.PUSH_FORCE_SUBMITTED, 42);

            assertThat(extensions).isEmpty();
            assertThat(model.isFinished()).isFalse();
        }
    }

    @Nested
    @DisplayName("a dropped socket (E10.15)")
    class Disconnect {

        @Test
        @DisplayName("the banner is raised and the indicator stops claiming things are saved")
        void dropIsSurfaced() {
            session.start(EXECUTION, liveForm(List.of()));
            session.select(1001, 3);

            eventBus.post(new ConnectionLostEvent("demo:5555", "socket closed"));

            assertThat(drops).hasSize(1);
            assertThat(drops.get(0).serverLabel()).isEqualTo("demo:5555");
            assertThat(model.saveState()).isEqualTo(SaveState.FAILED);
        }

        @Test
        @DisplayName("with nothing pending the indicator is left alone")
        void nothingPendingKeepsTheIndicator() {
            session.start(EXECUTION, liveForm(List.of()));

            eventBus.post(new ConnectionLostEvent("demo:5555", "socket closed"));

            assertThat(model.saveState()).isEqualTo(SaveState.SAVED);
            assertThat(drops).hasSize(1);
        }

        @Test
        @DisplayName("a drop before the exam starts is not this screen's business")
        void dropBeforeStart() {
            eventBus.post(new ConnectionLostEvent("demo:5555", "socket closed"));

            assertThat(drops).isEmpty();
        }
    }

    @Nested
    @DisplayName("attention events (E11.7 — F7.1b)")
    class Attention {

        @Test
        @DisplayName("a live paper starts the focus watcher; a finished one never does")
        void watcherFollowsTheAttempt() {
            session.start(EXECUTION, liveForm(List.of()));
            assertThat(session.attention().isTracking()).isTrue();

            session.stop();
            assertThat(session.attention().isTracking()).isFalse();

            session.start(EXECUTION, finishedForm(AttemptState.TIMED_OUT));
            assertThat(session.attention().isTracking())
                    .as("a paper that is already over cannot accrue attention events")
                    .isFalse();
        }

        @Test
        @DisplayName("an absence becomes exactly one ATTEMPT_ATTENTION carrying its duration ⚑")
        void absenceIsReportedOnTheWire() {
            connection.replyOk(Verb.ATTEMPT_ATTENTION, null);
            session.start(EXECUTION, liveForm(List.of()));

            session.reportAttention(12_000);

            List<Message> reports = connection.sentMessages().stream()
                    .filter(message -> message.getVerb() == Verb.ATTEMPT_ATTENTION)
                    .toList();
            assertThat(reports).hasSize(1);
            assertThat(reports.get(0).getPayload())
                    .isEqualTo(new common.dto.exam.AttentionReport(12_000));
        }

        @Test
        @DisplayName("a zero or negative duration is never sent")
        void nothingIsSentForANonAbsence() {
            session.start(EXECUTION, liveForm(List.of()));

            session.reportAttention(0);
            session.reportAttention(-5);

            assertThat(connection.sentMessages())
                    .noneMatch(message -> message.getVerb() == Verb.ATTEMPT_ATTENTION);
        }

        @Test
        @DisplayName("a force-submit stops the watcher, so the takeover cannot report anything")
        void forceSubmitStopsTheWatcher() {
            session.start(EXECUTION, liveForm(List.of()));

            connection.pushToClient(Verb.PUSH_FORCE_SUBMITTED, outcome(AttemptState.TIMED_OUT));

            assertThat(endings).hasSize(1);
            assertThat(session.attention().isTracking()).isFalse();
        }
    }

    // ===================== Fixture =======================================

    private static ExamHeader header() {
        return new ExamHeader(EXECUTION, "Java Midterm", "21", "Java Programming", 45,
                "Answer every question.", 3, AttemptState.IN_PROGRESS);
    }

    private static ExamQuestion question(int ordinal) {
        return new ExamQuestion(1000 + ordinal, "2100" + ordinal, ordinal, 10,
                "Question " + ordinal, "a", "b", "c", "d", null);
    }

    private static AttemptForm liveForm(List<SavedAnswer> answers) {
        return new AttemptForm(ATTEMPT, header(),
                List.of(question(1), question(2), question(3)), answers,
                AttemptTiming.between(NOW, NOW, ENDS), AttemptState.IN_PROGRESS, null);
    }

    private static AttemptForm finishedForm(AttemptState state) {
        return new AttemptForm(ATTEMPT, header(),
                List.of(question(1), question(2), question(3)), List.of(),
                AttemptTiming.finished(NOW, ENDS, Duration.ofMinutes(45).toMillis()),
                state, outcome(state));
    }

    private static AttemptOutcome outcome(AttemptState state) {
        return new AttemptOutcome(ATTEMPT, state, "Java Midterm", ENDS, 45, 1, 3,
                List.of(new AttemptSummaryEntry(1, "21001", true),
                        new AttemptSummaryEntry(2, "21002", false),
                        new AttemptSummaryEntry(3, "21003", false)));
    }

    private static SaveAnswerResult saveResult(int answered, int total) {
        return new SaveAnswerResult(1001, 3, answered, total,
                AttemptTiming.between(NOW, NOW, ENDS));
    }

    private static TimerExtended extension(int minutes) {
        Instant newEnd = ENDS.plus(Duration.ofMinutes(minutes));
        return new TimerExtended(EXECUTION, "Java Midterm", "Dana Cohen", minutes,
                new AttemptTiming(NOW, newEnd, Duration.ofMinutes(45 + minutes).toMillis(),
                        Duration.ofMinutes(45 + minutes).toMillis()));
    }
}
