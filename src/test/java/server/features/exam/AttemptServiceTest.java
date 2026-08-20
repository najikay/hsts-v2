package server.features.exam;

import common.dto.auth.Role;
import common.dto.exam.AttemptForm;
import common.dto.exam.AttemptOutcome;
import common.dto.exam.AttemptResumeRequest;
import common.dto.exam.AttemptStartRequest;
import common.dto.exam.AttemptState;
import common.dto.exam.ExamHeader;
import common.dto.exam.ExamJoinRequest;
import common.dto.exam.SaveAnswerRequest;
import common.dto.exam.SaveAnswerResult;
import common.dto.exam.SubmitAttemptRequest;
import common.dto.notify.NotificationType;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.core.SessionManager;
import server.db.entities.AttemptStatus;
import server.db.entities.ExecutionStatus;
import server.db.projections.AttemptRecord;
import server.db.projections.ExecutionContext;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every rule of taking an exam (E10.1–E10.7, E10.16).
 *
 * <p>This is the epic whose v1 version failed the team's first defence, so the tests are
 * written against the two failures rather than against the happy path: <b>the server owns
 * time</b> and <b>no correctness reaches a student</b>. Everything runs on a
 * {@link TestClock} and a {@link ManualScheduler}, which is what lets "one millisecond
 * after the deadline" and "the timer fires while she is pressing submit" be exact rather
 * than approximately timed.
 *
 * <p>The store is in memory but faithful about the two things the rules stand on: the
 * unique key that makes a double start impossible, and the compare-and-set that decides
 * the submit-versus-expiry race. The same scenarios run again against real MySQL in
 * {@code ExamConcurrencyIntegrationTest}, where the constraint and the UPDATE are real.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttemptServiceTest {

    private static final Instant T0 = Instant.parse("2026-08-20T09:00:00Z");
    private static final long EXECUTION = 5001L;
    private static final long EXAM_VERSION = 7001L;
    private static final long MAYA = 2001L;
    private static final long NOAM = 2002L;
    private static final long DANA = 1001L;
    private static final String COURSE = "21";
    private static final String CODE = "4B7Q";
    private static final String MAYAS_ID = "374301851";
    private static final int DURATION = 45;

    @Mock
    private ConnectionToClient mayaSocket;
    @Mock
    private ConnectionToClient danaSocket;

    private TestClock clock;
    private InMemoryExamStore store;
    private ManualScheduler scheduler;
    private RecordingGateway gateway;
    private RecordingNotifier notifier;
    private SessionManager sessions;
    private List<AttemptFinalizedListener.FinalizedAttempt> graded;
    private List<Long> monitorEvents;
    private AttemptService service;

    @BeforeEach
    void setUp() {
        clock = new TestClock(T0);
        store = new InMemoryExamStore();
        scheduler = new ManualScheduler();
        sessions = new SessionManager();
        gateway = new RecordingGateway(sessions);
        notifier = new RecordingNotifier();
        graded = new ArrayList<>();
        monitorEvents = new ArrayList<>();

        store.execution(EXECUTION, EXAM_VERSION, CODE, ExecutionStatus.LIVE,
                T0.minus(Duration.ofMinutes(10)), T0.plus(Duration.ofHours(3)), DURATION, DANA, COURSE);
        store.paper(EXAM_VERSION, 3);
        store.addUser(MAYA, "Maya Levi", MAYAS_ID);
        store.addUser(NOAM, "Noam Bar", "301548202");
        store.addUser(DANA, "Dana Cohen", "214703951");
        store.enrol(MAYA, COURSE);
        store.enrol(NOAM, COURSE);

        service = new AttemptService(store, clock, scheduler, gateway, notifier, graded::add);
        service.publishTo(monitorEvents::add);
    }

    // ===================== EXAM_JOIN =====================================

    @Nested
    @DisplayName("join by code")
    class Join {

        @Test
        @DisplayName("a live code answers the header, and never the questions (S-18)")
        void liveCodeAnswersHeader() {
            Message response = join(MAYA, CODE);

            assertThat(response.isOk()).isTrue();
            ExamHeader header = (ExamHeader) response.getPayload();
            assertThat(header.executionId()).isEqualTo(EXECUTION);
            assertThat(header.questionCount()).isEqualTo(3);
            assertThat(header.durationMinutes()).isEqualTo(DURATION);
            assertThat(header.attemptState()).isEqualTo(AttemptState.NOT_STARTED);
            // The whole reason join and start are separate verbs: this response is a
            // description of the paper, not the paper.
            assertThat(response.getPayload()).isNotInstanceOf(AttemptForm.class);
        }

        @Test
        @DisplayName("the code is matched case-insensitively (C-1)")
        void codeIsCaseInsensitive() {
            assertThat(join(MAYA, "4b7q").isOk()).isTrue();
        }

        @Test
        @DisplayName("a malformed code is refused before anything is read")
        void malformedCode() {
            Message response = join(MAYA, "12");

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.CODE_MALFORMED);
        }

        @Test
        @DisplayName("an unknown code says so, and says what to do (NOT_FOUND)")
        void unknownCode() {
            Message response = join(MAYA, "ZZZZ");

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.CODE_UNKNOWN);
        }

        @Test
        @DisplayName("a scheduled execution is 'not open yet', not 'no such code' (CONFLICT)")
        void notOpenYet() {
            store.addExecution(scheduledExecution());

            Message response = join(MAYA, "SOON");

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.CODE_NOT_OPEN_YET);
        }

        @Test
        @DisplayName("a closed execution is 'no longer open', a third distinct message")
        void alreadyClosed() {
            store.addExecution(new ExecutionContext(5003, EXAM_VERSION, 903, COURSE, "Java",
                    "Java Midterm", DURATION, "", "GONE", ExecutionStatus.CLOSED,
                    T0.minus(Duration.ofDays(2)), T0.minus(Duration.ofDays(1)), 0, DANA, DANA));

            Message response = join(MAYA, "GONE");

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.CODE_CLOSED);
        }

        @Test
        @DisplayName("a live execution whose window has passed is not joinable either")
        void windowHasPassed() {
            clock.advance(Duration.ofHours(4));

            Message response = join(MAYA, CODE);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.CODE_CLOSED);
        }

        @Test
        @DisplayName("a student who is not enrolled is refused with her own message (FORBIDDEN)")
        void notEnrolled() {
            Message response = join(DANA, CODE);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.NOT_ENROLLED);
        }

        @Test
        @DisplayName("a student who already handed in is told so by the header (F6.7)")
        void alreadySubmitted() {
            long attemptId = startAttempt(MAYA);
            submit(MAYA, attemptId);

            ExamHeader header = (ExamHeader) join(MAYA, CODE).getPayload();

            assertThat(header.attemptState()).isEqualTo(AttemptState.SUBMITTED);
        }

        @Test
        @DisplayName("a payload of the wrong type is a validation error, not a crash")
        void malformedPayload() {
            Message response = service.join(caller(MAYA), Message.request(Verb.EXAM_JOIN, "oops"));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.MALFORMED_REQUEST);
        }
    }

    // ===================== ATTEMPT_START =================================

    @Nested
    @DisplayName("start")
    class Start {

        @Test
        @DisplayName("the right ID starts the clock and hands over the paper (S-18)")
        void startsTheAttempt() {
            Message response = service.start(caller(MAYA),
                    Message.request(Verb.ATTEMPT_START, new AttemptStartRequest(EXECUTION, MAYAS_ID)));

            assertThat(response.isOk()).isTrue();
            AttemptForm form = (AttemptForm) response.getPayload();
            assertThat(form.questions()).hasSize(3);
            assertThat(form.savedAnswers()).isEmpty();
            assertThat(form.state()).isEqualTo(AttemptState.IN_PROGRESS);
            assertThat(form.timing().remainingMillis())
                    .as("the server states the remaining time; the client renders it")
                    .isEqualTo(Duration.ofMinutes(DURATION).toMillis());
            assertThat(form.timing().endsAt()).isEqualTo(T0.plus(Duration.ofMinutes(DURATION)));
        }

        @Test
        @DisplayName("the paper carries no correctness of any kind (F6.6)")
        void paperCarriesNoAnswerKey() {
            AttemptForm form = form(startResponse(MAYA));

            // The structural guarantee is tested by ExamWireLeakGuardTest, which scans the
            // package. This is the behavioural half: what actually travelled.
            assertThat(form.questions()).allSatisfy(question -> {
                assertThat(question.option(1)).isNotBlank();
                assertThat(question.getClass().getRecordComponents())
                        .extracting(java.lang.reflect.RecordComponent::getName)
                        .doesNotContain("correctAnswer", "correct", "solution");
            });
        }

        @Test
        @DisplayName("the expiry timer is armed for the derived deadline (E10.5)")
        void armsTheTimer() {
            long attemptId = startAttempt(MAYA);

            assertThat(service.timers().isArmed(attemptId)).isTrue();
            assertThat(service.timers().deadlineOf(attemptId))
                    .contains(T0.plus(Duration.ofMinutes(DURATION)));
        }

        @Test
        @DisplayName("a wrong ID number is refused, and the clock does not start (S-18)")
        void wrongIdentity() {
            Message response = service.start(caller(MAYA),
                    Message.request(Verb.ATTEMPT_START, new AttemptStartRequest(EXECUTION, "999999999")));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.ID_MISMATCH);
            assertThat(service.timers().armedCount()).isZero();
        }

        @Test
        @DisplayName("somebody else's ID number identifies nobody")
        void anotherStudentsIdentity() {
            Message response = service.start(caller(MAYA),
                    Message.request(Verb.ATTEMPT_START, new AttemptStartRequest(EXECUTION, "301548202")));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
        }

        @Test
        @DisplayName("an ID with spaces and dashes still matches its own number")
        void identityIsNormalised() {
            Message response = service.start(caller(MAYA),
                    Message.request(Verb.ATTEMPT_START, new AttemptStartRequest(EXECUTION, " 374-301 851 ")));

            assertThat(response.isOk()).isTrue();
        }

        @Test
        @DisplayName("an empty ID is refused before anything is read")
        void emptyIdentity() {
            Message response = service.start(caller(MAYA),
                    Message.request(Verb.ATTEMPT_START, new AttemptStartRequest(EXECUTION, "  ")));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.ID_MISSING);
        }

        @Test
        @DisplayName("starting twice answers the first attempt, never an error (F6.7) ⚑")
        void secondStartResumes() {
            long first = startAttempt(MAYA);
            clock.advance(Duration.ofMinutes(5));

            Message response = startResponse(MAYA);

            assertThat(response.isOk()).as("a double click is not a failure").isTrue();
            AttemptForm form = form(response);
            assertThat(form.attemptId()).isEqualTo(first);
            assertThat(form.timing().remainingMillis())
                    .as("and the clock is the original one, five minutes shorter")
                    .isEqualTo(Duration.ofMinutes(40).toMillis());
        }

        @Test
        @DisplayName("the window is re-checked at start, not only at join")
        void windowRecheckedAtStart() {
            clock.advance(Duration.ofHours(4));

            Message response = startResponse(MAYA);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("enrolment is re-checked at start too")
        void enrolmentRecheckedAtStart() {
            Message response = service.start(caller(DANA),
                    Message.request(Verb.ATTEMPT_START, new AttemptStartRequest(EXECUTION, "214703951")));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("an unknown execution answers NOT_FOUND")
        void unknownExecution() {
            Message response = service.start(caller(MAYA),
                    Message.request(Verb.ATTEMPT_START, new AttemptStartRequest(999, MAYAS_ID)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("a malformed payload is a validation error")
        void malformedPayload() {
            Message response = service.start(caller(MAYA), Message.request(Verb.ATTEMPT_START, 42));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
        }

        @Test
        @DisplayName("the monitor is told a student has started")
        void tellsTheMonitor() {
            startAttempt(MAYA);

            assertThat(monitorEvents).contains(EXECUTION);
        }
    }

    // ===================== ANSWER_SAVE ===================================

    @Nested
    @DisplayName("save answer")
    class SaveAnswer {

        private long attemptId;

        @BeforeEach
        void start() {
            attemptId = startAttempt(MAYA);
        }

        @Test
        @DisplayName("a choice is stored and the answered count comes back counted")
        void savesAndCounts() {
            Message response = save(MAYA, attemptId, question(1), 3);

            assertThat(response.isOk()).isTrue();
            SaveAnswerResult result = (SaveAnswerResult) response.getPayload();
            assertThat(result.selected()).isEqualTo(3);
            assertThat(result.answeredCount()).isEqualTo(1);
            assertThat(result.questionCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("every save re-syncs the clock, so a client cannot drift (S-18) ⚑")
        void everySaveResyncsTheClock() {
            clock.advance(Duration.ofMinutes(10));

            SaveAnswerResult result = (SaveAnswerResult) save(MAYA, attemptId, question(1), 1).getPayload();

            assertThat(result.timing().serverNow()).isEqualTo(clock.instant());
            assertThat(result.timing().remainingMillis()).isEqualTo(Duration.ofMinutes(35).toMillis());
        }

        @Test
        @DisplayName("changing an answer replaces it rather than adding a second row")
        void answersAreUpserted() {
            save(MAYA, attemptId, question(1), 1);
            SaveAnswerResult result = (SaveAnswerResult) save(MAYA, attemptId, question(1), 4).getPayload();

            assertThat(result.answeredCount()).isEqualTo(1);
            assertThat(store.answersOf(attemptId)).containsEntry(question(1), 4);
        }

        @Test
        @DisplayName("clearing an answer is allowed and drops the count")
        void clearingAnAnswer() {
            save(MAYA, attemptId, question(1), 1);

            Message response = service.saveAnswer(caller(MAYA), Message.request(Verb.ANSWER_SAVE,
                    new SaveAnswerRequest(attemptId, question(1), null)));

            assertThat(((SaveAnswerResult) response.getPayload()).answeredCount()).isZero();
        }

        @Test
        @DisplayName("an option outside 1..4 is refused")
        void optionOutOfRange() {
            Message response = save(MAYA, attemptId, question(1), 5);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.ANSWER_INVALID);
        }

        @Test
        @DisplayName("a question that is not on this paper is refused (a client can send anything)")
        void questionNotOnPaper() {
            Message response = save(MAYA, attemptId, 999_999L, 2);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.QUESTION_NOT_ON_PAPER);
        }

        @Test
        @DisplayName("somebody else's attempt id answers NOT_FOUND, revealing nothing")
        void anotherStudentsAttempt() {
            Message response = save(NOAM, attemptId, question(1), 1);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.ATTEMPT_UNKNOWN);
        }

        @Test
        @DisplayName("an attempt id nobody has answers the same way, indistinguishably")
        void unknownAttempt() {
            Message response = save(MAYA, 999_999L, question(1), 1);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.ATTEMPT_UNKNOWN);
        }

        @Test
        @DisplayName("an answer arriving after the deadline is rejected (E10.8 ⚑)")
        void answerAfterExpiryIsRejected() {
            clock.advance(Duration.ofMinutes(DURATION + 1));

            Message response = save(MAYA, attemptId, question(1), 2);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.TIME_IS_UP);
            assertThat(store.answersOf(attemptId)).isEmpty();
        }

        @Test
        @DisplayName("and that late answer also closes the attempt rather than leaving it open ⚑")
        void lateAnswerClosesTheAttempt() {
            clock.advance(Duration.ofMinutes(DURATION + 1));

            save(MAYA, attemptId, question(1), 2);

            // The v1 bug was an exam that stayed open. A rejection alone would leave the
            // row IN_PROGRESS until some other path noticed.
            assertThat(store.attempt(attemptId)).get()
                    .extracting(AttemptRecord::status).isEqualTo(AttemptStatus.TIMED_OUT);
            assertThat(graded).hasSize(1);
        }

        @Test
        @DisplayName("an answer exactly at the deadline is already too late")
        void exactlyAtTheDeadline() {
            clock.moveTo(T0.plus(Duration.ofMinutes(DURATION)));

            assertThat(save(MAYA, attemptId, question(1), 2).getErrorCode())
                    .isEqualTo(ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("one millisecond before it is still in time")
        void justInTime() {
            clock.moveTo(T0.plus(Duration.ofMinutes(DURATION)).minusMillis(1));

            assertThat(save(MAYA, attemptId, question(1), 2).isOk()).isTrue();
        }

        @Test
        @DisplayName("a save on an attempt already handed in says so, not 'time is up'")
        void saveAfterSubmit() {
            submit(MAYA, attemptId);

            Message response = save(MAYA, attemptId, question(1), 1);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.ALREADY_SUBMITTED);
        }

        @Test
        @DisplayName("a malformed payload is a validation error")
        void malformedPayload() {
            Message response = service.saveAnswer(caller(MAYA),
                    Message.request(Verb.ANSWER_SAVE, "not a request"));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
        }
    }

    // ===================== ATTEMPT_SUBMIT ================================

    @Nested
    @DisplayName("submit")
    class Submit {

        private long attemptId;

        @BeforeEach
        void start() {
            attemptId = startAttempt(MAYA);
        }

        @Test
        @DisplayName("handing in records the outcome and the solving minutes (S-19)")
        void submitsAndRecordsMinutes() {
            save(MAYA, attemptId, question(1), 2);
            clock.advance(Duration.ofMinutes(20));

            AttemptOutcome outcome = (AttemptOutcome) submit(MAYA, attemptId).getPayload();

            assertThat(outcome.state()).isEqualTo(AttemptState.SUBMITTED);
            assertThat(outcome.solvingMinutes()).isEqualTo(20);
            assertThat(outcome.answeredCount()).isEqualTo(1);
            assertThat(outcome.questionCount()).isEqualTo(3);
            assertThat(outcome.unansweredCount()).isEqualTo(2);
            assertThat(outcome.endedAt()).isEqualTo(clock.instant());
        }

        @Test
        @DisplayName("the summary grid names every question, answered or not (F6.9/F6.10)")
        void summaryCoversThePaper() {
            save(MAYA, attemptId, question(2), 3);

            AttemptOutcome outcome = (AttemptOutcome) submit(MAYA, attemptId).getPayload();

            assertThat(outcome.summary()).hasSize(3);
            assertThat(outcome.summary()).extracting("ordinal").containsExactly(1, 2, 3);
            assertThat(outcome.summary()).extracting("answered").containsExactly(false, true, false);
        }

        @Test
        @DisplayName("solving time rounds to the nearest minute rather than truncating (S-19)")
        void minutesRoundToNearest() {
            clock.advance(Duration.ofSeconds(44 * 60 + 50));

            AttemptOutcome outcome = (AttemptOutcome) submit(MAYA, attemptId).getPayload();

            assertThat(outcome.solvingMinutes()).isEqualTo(45);
        }

        @Test
        @DisplayName("the timer is disarmed, so nothing tries to expire a handed-in paper")
        void disarmsTheTimer() {
            submit(MAYA, attemptId);

            assertThat(service.timers().isArmed(attemptId)).isFalse();
        }

        @Test
        @DisplayName("the grading seam is called exactly once, with the pinned exam version")
        void callsTheGradingSeam() {
            submit(MAYA, attemptId);

            assertThat(graded).hasSize(1);
            assertThat(graded.get(0).examVersionId()).isEqualTo(EXAM_VERSION);
            assertThat(graded.get(0).state()).isEqualTo(AttemptState.SUBMITTED);
            assertThat(graded.get(0).studentId()).isEqualTo(MAYA);
        }

        @Test
        @DisplayName("submitting twice is idempotent and answers the same outcome")
        void submitIsIdempotent() {
            AttemptOutcome first = (AttemptOutcome) submit(MAYA, attemptId).getPayload();
            AttemptOutcome second = (AttemptOutcome) submit(MAYA, attemptId).getPayload();

            assertThat(second.state()).isEqualTo(first.state());
            assertThat(second.solvingMinutes()).isEqualTo(first.solvingMinutes());
            assertThat(graded).as("and the grader is not asked twice").hasSize(1);
        }

        @Test
        @DisplayName("a submit after the deadline is recorded as TIMED_OUT, not SUBMITTED ⚑")
        void lateSubmitIsATimeout() {
            clock.advance(Duration.ofMinutes(DURATION + 5));

            AttemptOutcome outcome = (AttemptOutcome) submit(MAYA, attemptId).getPayload();

            assertThat(outcome.state()).isEqualTo(AttemptState.TIMED_OUT);
            assertThat(outcome.endedAt())
                    .as("and it ended at the bell, not when she happened to press the button")
                    .isEqualTo(T0.plus(Duration.ofMinutes(DURATION)));
            assertThat(outcome.solvingMinutes()).isEqualTo(DURATION);
        }

        @Test
        @DisplayName("a submit racing the timer never errors at the student (E10.8 ⚑)")
        void submitLosingTheRaceStillAnswers() {
            clock.advance(Duration.ofMinutes(DURATION + 1));
            // The timer wins: it fires first, closing the attempt as TIMED_OUT.
            scheduler.runAll();

            Message response = submit(MAYA, attemptId);

            assertThat(response.isOk())
                    .as("she pressed submit and did nothing wrong; she must not see a failure")
                    .isTrue();
            AttemptOutcome outcome = (AttemptOutcome) response.getPayload();
            assertThat(outcome.state()).isEqualTo(AttemptState.TIMED_OUT);
            assertThat(graded).as("and only the winner triggered grading").hasSize(1);
        }

        @Test
        @DisplayName("the other order too: her submit wins and the timer stands down ⚑")
        void timerLosingTheRaceChangesNothing() {
            clock.advance(Duration.ofMinutes(30));
            submit(MAYA, attemptId);

            clock.advance(Duration.ofMinutes(20));
            scheduler.runAll();

            assertThat(store.attempt(attemptId)).get()
                    .extracting(AttemptRecord::status).isEqualTo(AttemptStatus.SUBMITTED);
            assertThat(store.attempt(attemptId)).get()
                    .extracting(AttemptRecord::actualMinutes).isEqualTo(30);
            assertThat(graded).hasSize(1);
        }

        @Test
        @DisplayName("somebody else's attempt id answers NOT_FOUND")
        void anotherStudentsAttempt() {
            assertThat(submit(NOAM, attemptId).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("a malformed payload is a validation error")
        void malformedPayload() {
            Message response = service.submit(caller(MAYA), Message.request(Verb.ATTEMPT_SUBMIT, "x"));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
        }
    }

    // ===================== ATTEMPT_RESUME ================================

    @Nested
    @DisplayName("resume")
    class Resume {

        @Test
        @DisplayName("coming back returns the paper, the saved answers and the real time left")
        void resumeRestoresEverything() {
            long attemptId = startAttempt(MAYA);
            save(MAYA, attemptId, question(1), 4);
            save(MAYA, attemptId, question(3), 2);
            clock.advance(Duration.ofMinutes(12));

            AttemptForm form = form(resume(MAYA));

            assertThat(form.attemptId()).isEqualTo(attemptId);
            assertThat(form.questions()).hasSize(3);
            assertThat(form.savedAnswers()).hasSize(2);
            assertThat(form.timing().remainingMillis()).isEqualTo(Duration.ofMinutes(33).toMillis());
            assertThat(form.state()).isEqualTo(AttemptState.IN_PROGRESS);
        }

        @Test
        @DisplayName("a student who never started has nothing to resume")
        void nothingToResume() {
            Message response = resume(MAYA);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.ATTEMPT_UNKNOWN);
        }

        @Test
        @DisplayName("resuming after the deadline closes the attempt and shows the takeover ⚑")
        void resumeAfterExpiry() {
            long attemptId = startAttempt(MAYA);
            save(MAYA, attemptId, question(1), 1);
            // The server was down when the bell went, so no timer ever fired. This is the
            // path that stops v1's "the exam stayed open" from coming back through a crash.
            clock.advance(Duration.ofHours(1));

            AttemptForm form = form(resume(MAYA));

            assertThat(form.state()).isEqualTo(AttemptState.TIMED_OUT);
            assertThat(form.outcome()).isNotNull();
            assertThat(form.outcome().state()).isEqualTo(AttemptState.TIMED_OUT);
            assertThat(form.outcome().answeredCount()).isEqualTo(1);
            assertThat(form.timing().hasExpired()).isTrue();
            assertThat(graded).hasSize(1);
        }

        @Test
        @DisplayName("resuming a handed-in attempt shows the Submitted ending, not the paper")
        void resumeAfterSubmit() {
            long attemptId = startAttempt(MAYA);
            submit(MAYA, attemptId);

            AttemptForm form = form(resume(MAYA));

            assertThat(form.state()).isEqualTo(AttemptState.SUBMITTED);
            assertThat(form.outcome().state()).isEqualTo(AttemptState.SUBMITTED);
            assertThat(form.isLive()).isFalse();
        }

        @Test
        @DisplayName("a live resume re-arms the timer, in case the process restarted")
        void resumeRearms() {
            long attemptId = startAttempt(MAYA);
            service.timers().disarm(attemptId);

            resume(MAYA);

            assertThat(service.timers().isArmed(attemptId)).isTrue();
        }

        @Test
        @DisplayName("an unknown execution answers NOT_FOUND")
        void unknownExecution() {
            Message response = service.resume(caller(MAYA),
                    Message.request(Verb.ATTEMPT_RESUME, new AttemptResumeRequest(999)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("a malformed payload is a validation error")
        void malformedPayload() {
            Message response = service.resume(caller(MAYA), Message.request(Verb.ATTEMPT_RESUME, 1));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
        }
    }

    // ===================== Expiry (E10.5 ⚑) ==============================

    @Nested
    @DisplayName("force-submit on expiry")
    class Expiry {

        @Test
        @DisplayName("the attempt is closed with the client gone ⚑")
        void expiresWithNoClient() {
            long attemptId = startAttempt(MAYA);
            save(MAYA, attemptId, question(2), 3);
            clock.advance(Duration.ofMinutes(DURATION));

            // Nobody is signed in: no socket, no session, no client anywhere.
            scheduler.runAll();

            assertThat(store.attempt(attemptId)).get()
                    .extracting(AttemptRecord::status).isEqualTo(AttemptStatus.TIMED_OUT);
            assertThat(store.attempt(attemptId)).get()
                    .extracting(AttemptRecord::actualMinutes).isEqualTo(DURATION);
            assertThat(store.answersOf(attemptId))
                    .as("and what she had saved is what was handed in")
                    .containsEntry(question(2), 3);
        }

        @Test
        @DisplayName("an online student is pushed the outcome (F6.4)")
        void pushesToAnOnlineStudent() {
            sessions.attach(MAYA, Role.STUDENT, mayaSocket);
            long attemptId = startAttempt(MAYA);
            clock.advance(Duration.ofMinutes(DURATION));

            scheduler.runAll();

            Optional<AttemptOutcome> pushed =
                    gateway.firstPayload(MAYA, Verb.PUSH_FORCE_SUBMITTED, AttemptOutcome.class);
            assertThat(pushed).isPresent();
            assertThat(pushed.get().state()).isEqualTo(AttemptState.TIMED_OUT);
            assertThat(pushed.get().attemptId()).isEqualTo(attemptId);
        }

        @Test
        @DisplayName("the ending is recorded at the bell, not at whenever the task ran")
        void endedAtIsTheDeadline() {
            long attemptId = startAttempt(MAYA);
            clock.advance(Duration.ofMinutes(DURATION + 7));

            scheduler.runAll();

            assertThat(store.attempt(attemptId)).get()
                    .extracting(AttemptRecord::endedAt)
                    .isEqualTo(T0.plus(Duration.ofMinutes(DURATION)));
        }

        @Test
        @DisplayName("expiring twice changes nothing (the sweep and the task can both arrive)")
        void expiryIsIdempotent() {
            long attemptId = startAttempt(MAYA);
            clock.advance(Duration.ofMinutes(DURATION));

            service.expire(attemptId);
            service.expire(attemptId);

            assertThat(graded).hasSize(1);
        }

        @Test
        @DisplayName("a task that fires early re-arms rather than ending the exam ⚑")
        void earlyFireRearms() {
            long attemptId = startAttempt(MAYA);

            // The extension case: the deadline moved after this task was scheduled.
            service.expire(attemptId);

            assertThat(store.attempt(attemptId)).get()
                    .extracting(AttemptRecord::status).isEqualTo(AttemptStatus.IN_PROGRESS);
            assertThat(service.timers().isArmed(attemptId)).isTrue();
        }

        @Test
        @DisplayName("expiring an attempt that does not exist is a no-op, not a crash")
        void unknownAttempt() {
            service.expire(999_999L);

            assertThat(graded).isEmpty();
        }

        @Test
        @DisplayName("expiring an attempt already handed in disarms and does nothing else")
        void alreadySubmitted() {
            long attemptId = startAttempt(MAYA);
            submit(MAYA, attemptId);
            graded.clear();

            service.expire(attemptId);

            assertThat(graded).isEmpty();
            assertThat(service.timers().isArmed(attemptId)).isFalse();
        }

        @Test
        @DisplayName("timers are re-armed from the database after a restart")
        void rearmFromDatabase() {
            long attemptId = startAttempt(MAYA);
            service.timers().disarmAll();

            int armed = service.rearmFromDatabase();

            assertThat(armed).isEqualTo(1);
            assertThat(service.timers().deadlineOf(attemptId))
                    .contains(T0.plus(Duration.ofMinutes(DURATION)));
        }

        @Test
        @DisplayName("and an attempt whose deadline passed while the server was down expires at once ⚑")
        void rearmExpiresOverdueAttempts() {
            long attemptId = startAttempt(MAYA);
            service.timers().disarmAll();
            scheduler.clear();
            clock.advance(Duration.ofHours(2));

            service.rearmFromDatabase();
            scheduler.runAll();

            assertThat(store.attempt(attemptId)).get()
                    .extracting(AttemptRecord::status).isEqualTo(AttemptStatus.TIMED_OUT);
        }
    }

    // ===================== Two students in parallel ======================

    @Nested
    @DisplayName("two students at once")
    class Parallel {

        @Test
        @DisplayName("their attempts and answers are completely separate (E10.8 ⚑)")
        void attemptsAreIsolated() {
            long mayas = startAttempt(MAYA);
            long noams = startAttempt(NOAM, "301548202");

            save(MAYA, mayas, question(1), 1);
            save(NOAM, noams, question(1), 4);

            assertThat(mayas).isNotEqualTo(noams);
            assertThat(store.answersOf(mayas)).containsEntry(question(1), 1);
            assertThat(store.answersOf(noams)).containsEntry(question(1), 4);
        }

        @Test
        @DisplayName("one submitting does not end the other's exam")
        void oneSubmitDoesNotAffectTheOther() {
            long mayas = startAttempt(MAYA);
            long noams = startAttempt(NOAM, "301548202");

            submit(MAYA, mayas);

            assertThat(store.attempt(noams)).get()
                    .extracting(AttemptRecord::status).isEqualTo(AttemptStatus.IN_PROGRESS);
            assertThat(save(NOAM, noams, question(2), 2).isOk()).isTrue();
        }

        @Test
        @DisplayName("their deadlines are their own, taken from when each of them started")
        void deadlinesAreIndependent() {
            long mayas = startAttempt(MAYA);
            clock.advance(Duration.ofMinutes(10));
            long noams = startAttempt(NOAM, "301548202");

            assertThat(service.timers().deadlineOf(mayas))
                    .contains(T0.plus(Duration.ofMinutes(DURATION)));
            assertThat(service.timers().deadlineOf(noams))
                    .contains(T0.plus(Duration.ofMinutes(DURATION + 10)));
        }
    }

    // ===================== AttemptTracker (E10.7 ⚑) ======================

    @Nested
    @DisplayName("bot lockout and the integrity net")
    class Tracker {

        @Test
        @DisplayName("a sitting student's course is reported to the bot (C-4)")
        void reportsTheCourse() {
            startAttempt(MAYA);

            assertThat(service.coursesInProgressFor(MAYA)).containsExactly(COURSE);
            assertThat(service.activeAttemptFor(MAYA, COURSE)).isPresent();
            assertThat(service.activeAttemptFor(MAYA, "11")).isEmpty();
        }

        @Test
        @DisplayName("the lockout names the exam, so the bot can explain itself")
        void lockoutNamesTheExam() {
            startAttempt(MAYA);

            assertThat(service.activeAttemptFor(MAYA, COURSE))
                    .get().extracting(ActiveAttempt::examName).isEqualTo("Java Midterm");
        }

        @Test
        @DisplayName("submitting unlocks the course's bot again")
        void submitUnlocks() {
            long attemptId = startAttempt(MAYA);
            submit(MAYA, attemptId);

            assertThat(service.coursesInProgressFor(MAYA)).isEmpty();
        }

        @Test
        @DisplayName("expiry unlocks it too")
        void expiryUnlocks() {
            startAttempt(MAYA);
            clock.advance(Duration.ofMinutes(DURATION));
            scheduler.runAll();

            assertThat(service.activeAttemptsFor(MAYA)).isEmpty();
        }

        @Test
        @DisplayName("using another course's bot notifies the executing teacher (C-4) ⚑")
        void crossCourseBotUseAlertsTheTeacher() {
            long attemptId = startAttempt(MAYA);

            boolean raised = service.reportCrossCourseBotUse(MAYA, "11", "Algebra 11");

            assertThat(raised).isTrue();
            assertThat(notifier.of(NotificationType.INTEGRITY_ALERT)).hasSize(1);
            assertThat(notifier.recipients()).containsExactly(DANA);
            assertThat(service.flagOf(attemptId)).isPresent();
            assertThat(service.flagOf(attemptId).get().courseName()).isEqualTo("Algebra 11");
        }

        @Test
        @DisplayName("the monitor row is flagged as well as the teacher notified")
        void flagsTheMonitorRow() {
            startAttempt(MAYA);
            monitorEvents.clear();

            service.reportCrossCourseBotUse(MAYA, "11", "Algebra 11");

            assertThat(monitorEvents).contains(EXECUTION);
        }

        @Test
        @DisplayName("forty questions raise one flag, keeping the first time")
        void repeatReportsKeepTheFirst() {
            long attemptId = startAttempt(MAYA);
            service.reportCrossCourseBotUse(MAYA, "11", "Algebra 11");
            Instant first = service.flagOf(attemptId).orElseThrow().at();
            clock.advance(Duration.ofMinutes(5));

            boolean second = service.reportCrossCourseBotUse(MAYA, "11", "Algebra 11");

            assertThat(second).isFalse();
            assertThat(service.flagOf(attemptId).orElseThrow().at()).isEqualTo(first);
            assertThat(notifier.of(NotificationType.INTEGRITY_ALERT)).hasSize(1);
        }

        @Test
        @DisplayName("a report for a student who is not sitting anything does nothing")
        void reportWithNoAttempt() {
            assertThat(service.reportCrossCourseBotUse(MAYA, "11", "Algebra 11")).isFalse();
            assertThat(notifier.all()).isEmpty();
        }

        @Test
        @DisplayName("a report naming the exam's own course is ignored (that branch is a lockout)")
        void sameCourseReportIsIgnored() {
            startAttempt(MAYA);

            assertThat(service.reportCrossCourseBotUse(MAYA, COURSE, "Java Programming")).isFalse();
            assertThat(notifier.all()).isEmpty();
        }

        @Test
        @DisplayName("lifecycle listeners hear a sitting start and end")
        void listenersHearBothEnds() {
            List<String> heard = new ArrayList<>();
            service.addListener(new AttemptTracker.Listener() {
                @Override
                public void attemptStarted(ActiveAttempt attempt) {
                    heard.add("started");
                }

                @Override
                public void attemptFinished(ActiveAttempt attempt) {
                    heard.add("finished");
                }
            });

            long attemptId = startAttempt(MAYA);
            submit(MAYA, attemptId);

            assertThat(heard).containsExactly("started", "finished");
        }
    }

    // ===================== Wiring ========================================

    @Nested
    @DisplayName("wiring")
    class Wiring {

        @Test
        @DisplayName("all five student verbs are registered, and none of them is open")
        void registersItsVerbs() {
            MessageRouter router = new MessageRouter(new SessionManager());

            service.registerOn(router);

            for (Verb verb : List.of(Verb.EXAM_JOIN, Verb.ATTEMPT_START, Verb.ATTEMPT_RESUME,
                    Verb.ANSWER_SAVE, Verb.ATTEMPT_SUBMIT)) {
                assertThat(router.isRegistered(verb)).as("%s registered", verb).isTrue();
                assertThat(router.isOpen(verb)).as("%s must need a session", verb).isFalse();
            }
        }

        @Test
        @DisplayName("an anonymous caller cannot reach any of them")
        void anonymousIsRefused() {
            MessageRouter router = new MessageRouter(new SessionManager());
            service.registerOn(router);

            Message response = router.route(
                    Message.request(Verb.EXAM_JOIN, new ExamJoinRequest(CODE)),
                    CallerContext.anonymous(mayaSocket));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("a null monitor publisher restores the no-op rather than blowing up")
        void nullPublisherIsSafe() {
            service.publishTo(null);

            assertThat(startResponse(MAYA).isOk()).isTrue();
        }

        @Test
        @DisplayName("the live-attempt index is the tracker E16 will be handed")
        void registryIsTheTracker() {
            startAttempt(MAYA);

            assertThat(service.registry().activeCount()).isEqualTo(1);
            assertThat(service.registry().coursesInProgressFor(MAYA)).containsExactly(COURSE);
        }

        @Test
        @DisplayName("a grader that throws does not turn a successful submit into a failure ⚑")
        void aBrokenGraderCannotFailASubmission() {
            AttemptService fragile = new AttemptService(store, clock, scheduler, gateway, notifier,
                    attempt -> {
                        throw new IllegalStateException("grading service is down");
                    });
            Message start = fragile.start(caller(MAYA),
                    Message.request(Verb.ATTEMPT_START, new AttemptStartRequest(EXECUTION, MAYAS_ID)));
            long attemptId = ((AttemptForm) start.getPayload()).attemptId();

            Message response = fragile.submit(caller(MAYA),
                    Message.request(Verb.ATTEMPT_SUBMIT, new SubmitAttemptRequest(attemptId)));

            // Her paper is in. A grade that has not been computed yet is recoverable; a
            // submission she was told failed is not.
            assertThat(response.isOk()).isTrue();
            assertThat(store.attempt(attemptId)).get()
                    .extracting(AttemptRecord::status).isEqualTo(AttemptStatus.SUBMITTED);
        }

        @Test
        @DisplayName("starting before the window opens says 'not open yet', not 'closed'")
        void startBeforeTheWindow() {
            store.addExecution(scheduledExecution());

            Message response = service.start(caller(MAYA), Message.request(Verb.ATTEMPT_START,
                    new AttemptStartRequest(5002, MAYAS_ID)));

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.CODE_NOT_OPEN_YET);
        }

        @Test
        @DisplayName("a save on a timed-out attempt says time is up, not 'already handed in'")
        void saveOnATimedOutAttempt() {
            long attemptId = startAttempt(MAYA);
            clock.advance(Duration.ofMinutes(DURATION));
            scheduler.runAll();

            Message response = save(MAYA, attemptId, question(1), 1);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(response.errorMessage()).isEqualTo(ExamMessages.TIME_IS_UP);
        }

        @Test
        @DisplayName("an integrity report with no course name falls back to the code (C-4)")
        void integrityAlertWithoutACourseName() {
            startAttempt(MAYA);

            assertThat(service.reportCrossCourseBotUse(MAYA, "11", "  ")).isTrue();

            assertThat(notifier.of(NotificationType.INTEGRITY_ALERT))
                    .singleElement()
                    .extracting(RecordingNotifier.Sent::title)
                    .asString()
                    .as("a notification that says 'Check an attempt in null' is worse than none")
                    .contains("11");
        }
    }

    // ===================== Fixture =======================================

    private CallerContext caller(long userId) {
        return CallerContext.authenticated(userId == DANA ? danaSocket : mayaSocket,
                userId, userId == DANA ? Role.TEACHER : Role.STUDENT);
    }

    private Message join(long userId, String code) {
        return service.join(caller(userId), Message.request(Verb.EXAM_JOIN, new ExamJoinRequest(code)));
    }

    private Message startResponse(long userId) {
        return service.start(caller(userId),
                Message.request(Verb.ATTEMPT_START, new AttemptStartRequest(EXECUTION, MAYAS_ID)));
    }

    private long startAttempt(long userId) {
        return startAttempt(userId, MAYAS_ID);
    }

    private long startAttempt(long userId, String nationalId) {
        Message response = service.start(caller(userId),
                Message.request(Verb.ATTEMPT_START, new AttemptStartRequest(EXECUTION, nationalId)));
        assertThat(response.isOk()).as("fixture start must succeed: %s", response.errorMessage()).isTrue();
        return ((AttemptForm) response.getPayload()).attemptId();
    }

    private Message save(long userId, long attemptId, long questionVersionId, Integer option) {
        return service.saveAnswer(caller(userId), Message.request(Verb.ANSWER_SAVE,
                new SaveAnswerRequest(attemptId, questionVersionId, option)));
    }

    private Message submit(long userId, long attemptId) {
        return service.submit(caller(userId),
                Message.request(Verb.ATTEMPT_SUBMIT, new SubmitAttemptRequest(attemptId)));
    }

    private Message resume(long userId) {
        return service.resume(caller(userId),
                Message.request(Verb.ATTEMPT_RESUME, new AttemptResumeRequest(EXECUTION)));
    }

    private static AttemptForm form(Message response) {
        assertThat(response.isOk()).as("expected a form: %s", response.errorMessage()).isTrue();
        return (AttemptForm) response.getPayload();
    }

    private long question(int ordinal) {
        return store.questionId(EXAM_VERSION, ordinal);
    }

    private ExecutionContext scheduledExecution() {
        return new ExecutionContext(5002, EXAM_VERSION, 902, COURSE, "Java", "Java Final",
                DURATION, "", "SOON", ExecutionStatus.SCHEDULED,
                T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)).plusSeconds(7200),
                0, DANA, DANA);
    }
}
