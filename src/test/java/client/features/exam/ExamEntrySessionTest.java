package client.features.exam;

import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.exam.AttemptForm;
import common.dto.exam.AttemptStartRequest;
import common.dto.exam.AttemptState;
import common.dto.exam.AttemptTiming;
import common.dto.exam.ExamHeader;
import common.dto.exam.ExamJoinRequest;
import common.dto.exam.ExamQuestion;
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
 * The two-step entry flow, with no JavaFX toolkit (E10.9 — F6.1).
 *
 * <p>A {@link FakeClientConnection} answers the real {@link RequestDispatcher}, so a
 * scripted server response becomes session state synchronously and the assertion follows on
 * the next line. The interesting half is the four refusals: each has to land on the field
 * the student can actually act on, and each has to carry the server's own sentence rather
 * than a second copy the client invented.
 */
class ExamEntrySessionTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final long EXECUTION = 5001L;

    private static final ExamHeader HEADER = new ExamHeader(EXECUTION, "Java Midterm", "21",
            "Java Programming", 45, "Answer every question.", 3, AttemptState.NOT_STARTED);

    private FakeClientConnection connection;
    private ExamEntrySession session;
    private List<AttemptForm> started;

    @BeforeEach
    void setUp() throws IOException {
        connection = new FakeClientConnection();
        connection.connect();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        started = new ArrayList<>();
        session = new ExamEntrySession(dispatcher).onStarted(started::add);
    }

    @Nested
    @DisplayName("the code step")
    class CodeStep {

        @Test
        @DisplayName("a valid code fetches the header and moves to the ID screen")
        void validCodeMovesOn() {
            connection.replyOk(Verb.EXAM_JOIN, HEADER);

            session.setCode("4b7q");
            session.submitCode().join();

            assertThat(session.phase()).isEqualTo(EntryPhase.IDENTITY);
            assertThat(session.header()).contains(HEADER);
            ExamJoinRequest sent = (ExamJoinRequest) connection.lastSent().getPayload();
            assertThat(sent.code())
                    .as("normalised on the way out, as the server compares it (C-1)")
                    .isEqualTo("4B7Q");
        }

        @Test
        @DisplayName("the button is disabled until the code is well formed")
        void buttonFollowsTheRule() {
            assertThat(session.canContinue()).isFalse();

            session.setCode("4B7");
            assertThat(session.canContinue()).isFalse();

            session.setCode("4B7Q");
            assertThat(session.canContinue()).isTrue();
        }

        @Test
        @DisplayName("a half-typed code is not an error yet; a wrong one is")
        void localValidationIsPatient() {
            session.setCode("4B7");
            assertThat(session.codeState().isInvalid()).isTrue();
            assertThat(session.codeState().message()).isEqualTo(ExamCopy.CODE_INVALID);

            session.setCode("");
            assertThat(session.codeState().isPristine())
                    .as("an empty field she cleared is not an error, it is empty")
                    .isTrue();
        }

        @Test
        @DisplayName("submitting an invalid code sends nothing and shows the rule")
        void invalidCodeSendsNothing() {
            session.setCode("nope!");

            session.submitCode().join();

            assertThat(connection.sentCount()).isZero();
            assertThat(session.codeState().message()).isEqualTo(ExamCopy.CODE_INVALID);
            assertThat(session.phase()).isEqualTo(EntryPhase.CODE);
        }

        @Test
        @DisplayName("an unknown code shows the server's own sentence on the code field")
        void unknownCode() {
            connection.replyError(Verb.EXAM_JOIN, ErrorCode.NOT_FOUND,
                    "No exam is using that code. Check the code with your teacher and try again.");

            session.setCode("ZZZZ");
            session.submitCode().join();

            assertThat(session.phase()).isEqualTo(EntryPhase.CODE);
            assertThat(session.codeState().message())
                    .as("the server knows why; the client does not restate it")
                    .startsWith("No exam is using that code");
        }

        @Test
        @DisplayName("a not-yet-open exam is a different sentence, still on the code field")
        void notOpenYet() {
            connection.replyError(Verb.EXAM_JOIN, ErrorCode.CONFLICT,
                    "That exam has not started yet. Wait for your teacher.");

            session.setCode("4B7Q");
            session.submitCode().join();

            assertThat(session.codeState().message()).startsWith("That exam has not started yet");
        }

        @Test
        @DisplayName("a not-enrolled refusal is a third sentence")
        void notEnrolled() {
            connection.replyError(Verb.EXAM_JOIN, ErrorCode.FORBIDDEN,
                    "You are not enrolled in this course, so you cannot sit this exam.");

            session.setCode("4B7Q");
            session.submitCode().join();

            assertThat(session.codeState().message()).startsWith("You are not enrolled");
        }

        @Test
        @DisplayName("a dead connection says so rather than showing nothing")
        void connectionFailure() throws IOException {
            connection.failSendsWith(new IOException("socket closed"));

            session.setCode("4B7Q");
            session.submitCode().join();

            assertThat(session.codeState().message()).isEqualTo(ExamCopy.OFFLINE);
        }

        @Test
        @DisplayName("a nonsense payload is treated as a failure, not as a header")
        void unexpectedPayload() {
            connection.replyOk(Verb.EXAM_JOIN, "surprise");

            session.setCode("4B7Q");
            session.submitCode().join();

            assertThat(session.phase()).isEqualTo(EntryPhase.CODE);
            assertThat(session.codeState().message()).isEqualTo(ExamCopy.OFFLINE);
        }

        @Test
        @DisplayName("a code for an exam she already handed in is a dead end with a reason (F6.7)")
        void alreadySubmitted() {
            connection.replyOk(Verb.EXAM_JOIN, new ExamHeader(EXECUTION, "Java Midterm", "21",
                    "Java Programming", 45, "", 3, AttemptState.SUBMITTED));

            session.setCode("4B7Q");
            session.submitCode().join();

            assertThat(session.phase()).isEqualTo(EntryPhase.BLOCKED);
            assertThat(session.blockedMessage()).isEqualTo(ExamCopy.EXAM_CLOSED_FOR_YOU);
        }

        @Test
        @DisplayName("an attempt already in progress skips the ID screen and resumes (E10.6)")
        void alreadyInProgressResumes() {
            connection.replyOk(Verb.EXAM_JOIN, new ExamHeader(EXECUTION, "Java Midterm", "21",
                    "Java Programming", 45, "", 3, AttemptState.IN_PROGRESS));
            connection.replyOk(Verb.ATTEMPT_RESUME, liveForm());

            session.setCode("4B7Q");
            session.submitCode().join();

            // Re-asking for her ID after a dropped socket would be punishing her for the
            // network; the clock is already running either way.
            assertThat(session.phase()).isEqualTo(EntryPhase.STARTED);
            assertThat(started).hasSize(1);
            assertThat(started.get(0).attemptId()).isEqualTo(42);
        }

        @Test
        @DisplayName("a resume that fails sends her back to the code screen with the reason")
        void resumeFailureFallsBack() {
            connection.replyOk(Verb.EXAM_JOIN, new ExamHeader(EXECUTION, "Java Midterm", "21",
                    "Java Programming", 45, "", 3, AttemptState.IN_PROGRESS));
            connection.replyError(Verb.ATTEMPT_RESUME, ErrorCode.NOT_FOUND,
                    "That exam is not open for you. Go back to your dashboard.");

            session.setCode("4B7Q");
            session.submitCode().join();

            assertThat(session.phase()).isEqualTo(EntryPhase.CODE);
            assertThat(session.codeState().message()).startsWith("That exam is not open for you");
            assertThat(started).isEmpty();
        }
    }

    @Nested
    @DisplayName("the identity step (S-18)")
    class IdentityStep {

        @BeforeEach
        void reachTheIdScreen() {
            connection.replyOk(Verb.EXAM_JOIN, HEADER);
            session.setCode("4B7Q");
            session.submitCode().join();
            connection.clearSent();
        }

        @Test
        @DisplayName("the right ID starts the attempt and hands the form on")
        void startsTheAttempt() {
            connection.replyOk(Verb.ATTEMPT_START, liveForm());

            session.setNationalId("374301851");
            session.start().join();

            assertThat(session.phase()).isEqualTo(EntryPhase.STARTED);
            assertThat(started).hasSize(1);
            AttemptStartRequest sent = (AttemptStartRequest) connection.lastSent().getPayload();
            assertThat(sent.executionId()).isEqualTo(EXECUTION);
            assertThat(sent.nationalId()).isEqualTo("374301851");
        }

        @Test
        @DisplayName("the button is disabled until something is typed")
        void buttonNeedsAnId() {
            assertThat(session.canStart()).isFalse();

            session.setNationalId("374301851");

            assertThat(session.canStart()).isTrue();
        }

        @Test
        @DisplayName("pressing start with nothing typed sends nothing and says what to do")
        void emptyIdSendsNothing() {
            session.start().join();

            assertThat(connection.sentCount()).isZero();
            assertThat(session.idState().message()).isEqualTo(ExamCopy.ID_REQUIRED);
        }

        @Test
        @DisplayName("clearing the field after typing shows the rule inline")
        void clearingTheField() {
            session.setNationalId("374301851");
            session.setNationalId("");

            assertThat(session.idState().message()).isEqualTo(ExamCopy.ID_REQUIRED);
        }

        @Test
        @DisplayName("a wrong ID lands on the ID field, where she can fix it ⚑")
        void wrongIdLandsOnTheIdField() {
            connection.replyError(Verb.ATTEMPT_START, ErrorCode.VALIDATION,
                    "That ID number is not yours. Enter your own ID number and try again.");

            session.setNationalId("999999999");
            session.start().join();

            assertThat(session.phase())
                    .as("she stays on the identity screen; going back would lose her place")
                    .isEqualTo(EntryPhase.IDENTITY);
            assertThat(session.idState().message()).startsWith("That ID number is not yours");
            assertThat(session.codeState().isInvalid()).isFalse();
        }

        @Test
        @DisplayName("an exam that closed between the two screens sends her back a step")
        void examClosedInBetween() {
            connection.replyError(Verb.ATTEMPT_START, ErrorCode.CONFLICT,
                    "That exam is no longer open. Speak to your teacher.");

            session.setNationalId("374301851");
            session.start().join();

            // Nothing she can do on the identity screen fixes this, so she is sent to the
            // one where the problem is.
            assertThat(session.phase()).isEqualTo(EntryPhase.CODE);
            assertThat(session.codeState().message()).startsWith("That exam is no longer open");
        }

        @Test
        @DisplayName("a dead connection says so on the ID field")
        void connectionFailure() throws IOException {
            connection.failSendsWith(new IOException("socket closed"));

            session.setNationalId("374301851");
            session.start().join();

            assertThat(session.idState().message()).isEqualTo(ExamCopy.OFFLINE);
        }

        @Test
        @DisplayName("a nonsense payload is treated as a failure, not as a form")
        void unexpectedPayload() {
            connection.replyOk(Verb.ATTEMPT_START, 42);

            session.setNationalId("374301851");
            session.start().join();

            assertThat(started).isEmpty();
            assertThat(session.idState().message()).isEqualTo(ExamCopy.OFFLINE);
        }
    }

    @Nested
    @DisplayName("housekeeping")
    class Housekeeping {

        @Test
        @DisplayName("reset returns to the code screen with everything cleared")
        void resetClearsEverything() {
            connection.replyOk(Verb.EXAM_JOIN, HEADER);
            session.setCode("4B7Q");
            session.submitCode().join();
            session.setNationalId("374301851");

            session.reset();

            assertThat(session.phase()).isEqualTo(EntryPhase.CODE);
            assertThat(session.code()).isEmpty();
            assertThat(session.nationalId()).isEmpty();
            assertThat(session.header()).isEmpty();
            assertThat(session.codeState().isPristine()).isTrue();
            assertThat(session.idState().isPristine()).isTrue();
            assertThat(session.blockedMessage()).isEmpty();
        }

        @Test
        @DisplayName("the change callback fires as the student types")
        void changeCallbackFires() {
            List<String> changes = new ArrayList<>();
            session.onChange(() -> changes.add("changed"));

            session.setCode("4B7Q");

            assertThat(changes).isNotEmpty();
        }

        @Test
        @DisplayName("null input is normalised rather than travelling")
        void nullInputNormalises() {
            session.setCode(null);
            session.setNationalId(null);

            assertThat(session.code()).isEmpty();
            assertThat(session.nationalId()).isEmpty();
        }

        @Test
        @DisplayName("its collaborators are all required")
        void constructorGuards() {
            assertThatNullPointerException().isThrownBy(() -> new ExamEntrySession(null));
            assertThatNullPointerException().isThrownBy(() -> session.onChange(null));
            assertThatNullPointerException().isThrownBy(() -> session.onStarted(null));
        }
    }

    // ===================== Fixture =======================================

    private static AttemptForm liveForm() {
        return new AttemptForm(42, HEADER,
                List.of(new ExamQuestion(1001, "21001", 1, 10, "q", "a", "b", "c", "d", null)),
                List.of(), AttemptTiming.between(NOW, NOW, NOW.plus(Duration.ofMinutes(45))),
                AttemptState.IN_PROGRESS, null);
    }
}
