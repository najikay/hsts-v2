package client.features.results;

import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import client.ui.components.logic.StatChartData;
import client.ui.components.logic.StatChartLogic;
import common.dto.grading.GradeState;
import common.dto.exam.AttemptState;
import common.dto.grading.StudentGradeRow;
import common.dto.results.ExamResultRow;
import common.dto.results.ExecutionResultRow;
import common.dto.results.ExecutionResults;
import common.dto.results.ExecutionResultsRequest;
import common.dto.results.ExecutionState;
import common.dto.results.ResultStatistics;
import common.dto.results.TeacherResults;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TeacherResultsSession} — E14.2/E14.3b's screen behaviour, without a JavaFX toolkit.
 *
 * <p>The session talks to a {@link FakeClientConnection} through a real
 * {@link RequestDispatcher}, and the FX hop is a {@link DirectFxThreadPoster}, so every
 * transition settles synchronously (TEAM_SPLIT §3.2).
 *
 * <p>The fixture is the seeded world: Dana's Algebra exam with the closed, fully graded
 * sitting 4821 (mean 72.5, median 72.5, σ 17.5, 7 of 8 passed) and the live sitting 2075 that
 * nobody has marked. The two together are what the default-selection and
 * grading-unfinished rules are about.
 */
class TeacherResultsSessionTest {

    private static final Instant OPENED = Instant.parse("2026-08-07T06:00:00Z");
    private static final Instant CLOSED = Instant.parse("2026-08-07T08:00:00Z");

    private static final long GRADED_EXECUTION = 10;
    private static final long LIVE_EXECUTION = 11;

    private FakeClientConnection connection;
    private TeacherResultsSession session;
    private int renders;

    @BeforeEach
    void setUp() {
        connection = new FakeClientConnection();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        session = new TeacherResultsSession(dispatcher, new DirectFxThreadPoster())
                .onChange(() -> renders++);
    }

    // ===================== Fixture =======================================

    private static ResultStatistics seededStats() {
        return new ResultStatistics(8, 72.5, 72.5, 17.5, 45, 100, 7, 0.875,
                List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));
    }

    private static ExecutionResultRow graded() {
        return new ExecutionResultRow(GRADED_EXECUTION, "4821", OPENED, CLOSED,
                ExecutionState.CLOSED, 8, 8, true, true);
    }

    private static ExecutionResultRow live() {
        return new ExecutionResultRow(LIVE_EXECUTION, "2075", CLOSED, CLOSED.plusSeconds(7200),
                ExecutionState.LIVE, 3, 0, false, false);
    }

    /** Newest first, exactly as the server orders them: the live one leads. */
    private static ExamResultRow algebraExam() {
        return new ExamResultRow(1, "101101", "מבחן אמצע: אלגברה", "11", "אלגברה",
                List.of(live(), graded()));
    }

    private static ExamResultRow drawerExam() {
        return new ExamResultRow(2, "101102", "בוחן: אי-שוויונות", "11", "אלגברה", List.of());
    }

    private static ExecutionResults gradedResults() {
        return new ExecutionResults(graded(), "מבחן אמצע: אלגברה", "11", "אלגברה",
                // ⚑ B-16: Omer's timed-out 45 beside Yael's submitted 45. Same score, and
                // the whole point is that the table can now tell them apart.
                List.of(row(1, "Maya Levi", 60, null), row(2, "Yael Azulay", 45, 55),
                        timedOutRow(3, "Omer Katz", 45)),
                seededStats());
    }

    private static ExecutionResults liveResults() {
        return new ExecutionResults(live(), "מבחן אמצע: אלגברה", "11", "אלגברה",
                List.of(), null);
    }

    private static StudentGradeRow row(long id, String name, int auto, Integer finalScore) {
        return new StudentGradeRow(id, 2000 + id, name, auto, finalScore,
                finalScore == null ? auto : finalScore, GradeState.APPROVED,
                finalScore == null ? null : "ניתן ניקוד חלקי.", null, CLOSED,
                // A7 leaves the teacher's name empty on her own results table.
                null, null, "", AttemptState.SUBMITTED, 55);
    }

    /** A paper the server handed in at the bell, as the results wire now carries it (B-16). */
    private static StudentGradeRow timedOutRow(long id, String name, int auto) {
        return new StudentGradeRow(id, 2000 + id, name, auto, null, auto, GradeState.APPROVED,
                null, null, CLOSED, null, null, "", AttemptState.TIMED_OUT, 90);
    }

    private void serverHasEverything() {
        connection.replyOk(Verb.RESULTS_EXAMS_GET,
                new TeacherResults(List.of(algebraExam(), drawerExam())));
        connection.respondTo(Verb.RESULTS_EXECUTION_GET, request -> {
            ExecutionResultsRequest ask = (ExecutionResultsRequest) request.getPayload();
            return Message.ok(request, ask.executionId() == GRADED_EXECUTION
                    ? gradedResults() : liveResults());
        });
    }

    // ===================== Loading =======================================

    @Nested
    @DisplayName("loading")
    class Loading {

        @Test
        @DisplayName("the exams arrive and the screen becomes content")
        void loadsExams() {
            serverHasEverything();

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.exams()).hasSize(2);
            assertThat(session.error()).isEmpty();
        }

        @Test
        @DisplayName("a skeleton is shown while the request is in flight")
        void skeletonWhileLoading() {
            // No canned reply: the future stays pending.
            session.load();

            assertThat(session.isLoading()).isTrue();
            assertThat(session.state().showsSkeleton()).isTrue();
        }

        @Test
        @DisplayName("a second load while one is in flight is ignored rather than raced")
        void loadIsNotReentrant() {
            session.load();
            session.load();

            assertThat(connection.sentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a teacher who has written nothing gets the empty state, not an error")
        void emptyList() {
            connection.replyOk(Verb.RESULTS_EXAMS_GET, TeacherResults.EMPTY);

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.EMPTY);
            assertThat(session.selectedExam()).isEmpty();
            assertThat(session.error()).isEmpty();
        }

        @Test
        @DisplayName("a refused list is a sentence, not a blank screen")
        void refusedList() {
            connection.replyError(Verb.RESULTS_EXAMS_GET, ErrorCode.FORBIDDEN, "no");

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.error()).contains(ResultsCopy.LOAD_FAILED);
            assertThat(session.exams()).isEmpty();
        }

        @Test
        @DisplayName("an OK carrying the wrong payload type fails the same calm way")
        void wrongPayloadType() {
            connection.replyOk(Verb.RESULTS_EXAMS_GET, "surprise");

            session.load();

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.error()).contains(ResultsCopy.LOAD_FAILED);
        }
    }

    // ===================== Default selection =============================

    @Nested
    @DisplayName("what opens first")
    class Defaults {

        @Test
        @DisplayName("the sitting whose statistics are ready opens, not merely the newest")
        void opensOnResultsThatExist() {
            serverHasEverything();

            session.load();

            assertThat(session.selectedExam().orElseThrow().displayId()).isEqualTo("101101");
            assertThat(session.selectedExecution().orElseThrow().code4())
                    .as("2075 is newer but has nothing to show yet")
                    .isEqualTo("4821");
            assertThat(session.results()).isPresent();
        }

        @Test
        @DisplayName("with nothing graded anywhere, the newest sitting opens rather than none")
        void fallsBackToTheNewest() {
            ExamResultRow ungraded = new ExamResultRow(1, "101101", "Algebra", "11", "אלגברה",
                    List.of(live()));
            connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of(ungraded)));
            connection.replyOk(Verb.RESULTS_EXECUTION_GET, liveResults());

            session.load();

            assertThat(session.selectedExecution().orElseThrow().code4()).isEqualTo("2075");
        }

        @Test
        @DisplayName("a teacher whose only exam was never released selects it and asks nothing")
        void neverReleasedExamAsksNothing() {
            connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of(drawerExam())));

            session.load();

            assertThat(session.selectedExam().orElseThrow().neverReleased()).isTrue();
            assertThat(session.selectedExecution()).isEmpty();
            assertThat(session.detailState()).isEqualTo(AsyncViewState.EMPTY);
            assertThat(connection.sentCount())
                    .as("only the list was asked for; there is no sitting to open")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the exam with results is preferred over an earlier one with none")
        void prefersTheExamThatHasResults() {
            assertThat(TeacherResultsSession.defaultExam(List.of(drawerExam(), algebraExam())))
                    .isEqualTo(algebraExam());
            assertThat(TeacherResultsSession.defaultExam(List.of(drawerExam())))
                    .isEqualTo(drawerExam());
            assertThat(TeacherResultsSession.defaultExam(List.of())).isNull();
            assertThat(TeacherResultsSession.defaultExecution(drawerExam())).isNull();
        }
    }

    // ===================== Opening a sitting =============================

    @Nested
    @DisplayName("opening a sitting")
    class Opening {

        @Test
        @DisplayName("picking the other sitting asks for it and adopts the answer")
        void picksAnotherSitting() {
            serverHasEverything();
            session.load();

            session.openExecution(live());

            assertThat(session.selectedExecution().orElseThrow().code4()).isEqualTo("2075");
            assertThat(session.results().orElseThrow().execution().executionId())
                    .isEqualTo(LIVE_EXECUTION);
            assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.RESULTS_EXECUTION_GET);
        }

        @Test
        @DisplayName("a sitting with no frozen statistics is a state, not an error")
        void gradingUnfinishedIsAState() {
            serverHasEverything();
            session.load();

            session.openExecution(live());

            assertThat(session.isGradingUnfinished()).isTrue();
            assertThat(session.statistics()).isEmpty();
            assertThat(session.detailError()).isEmpty();
        }

        @Test
        @DisplayName("a refused sitting shows its own sentence and keeps the list intact")
        void refusedSitting() {
            connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of(algebraExam())));
            connection.replyError(Verb.RESULTS_EXECUTION_GET, ErrorCode.NOT_FOUND, "no");

            session.load();

            assertThat(session.detailState()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.detailError()).contains(ResultsCopy.EXECUTION_FAILED);
            assertThat(session.exams()).as("the list survives a failed detail").hasSize(1);
        }

        @Test
        @DisplayName("the last sitting picked is the one on screen, header and rows together")
        void theLastSelectionWins() {
            serverHasEverything();
            session.load();
            assertThat(session.selectedExecution().orElseThrow().executionId())
                    .isEqualTo(GRADED_EXECUTION);

            session.openExecution(live());
            session.openExecution(graded());

            // The answer is matched against the id that was asked for before it is adopted,
            // so a slow reply for an abandoned sitting can never repaint the current one.
            assertThat(session.selectedExecution().orElseThrow().executionId())
                    .isEqualTo(GRADED_EXECUTION);
            assertThat(session.results().orElseThrow().execution().executionId())
                    .isEqualTo(GRADED_EXECUTION);
            assertThat(session.statistics()).isPresent();
        }

        @Test
        @DisplayName("selecting an exam switches the sitting under it")
        void selectingAnExamSwitchesTheSitting() {
            serverHasEverything();
            session.load();

            session.selectExam(drawerExam());

            assertThat(session.selectedExam().orElseThrow().displayId()).isEqualTo("101102");
            assertThat(session.executions()).isEmpty();
            assertThat(session.results()).isEmpty();
        }

        @Test
        @DisplayName("a null selection is ignored rather than clearing the screen")
        void nullSelectionsAreIgnored() {
            serverHasEverything();
            session.load();
            int before = connection.sentCount();

            session.selectExam(null);
            session.openExecution(null);

            assertThat(connection.sentCount()).isEqualTo(before);
            assertThat(session.results()).isPresent();
        }
    }

    // ===================== The chart (E14.3b) ============================

    @Nested
    @DisplayName("the histogram's input")
    class Chart {

        @Test
        @DisplayName("⚑ the stored figures are mapped straight across, none of them recomputed")
        void chartDataIsTheStoredRecord() {
            serverHasEverything();
            session.load();

            StatChartData data = session.chartData();

            assertThat(data.buckets()).containsExactly(0, 0, 0, 0, 1, 1, 1, 2, 1, 2);
            assertThat(data.mean()).isEqualTo(72.5);
            assertThat(data.median()).isEqualTo(72.5);
            assertThat(data.standardDeviation())
                    .as("population sigma as frozen; the sample form would be 18.71")
                    .isEqualTo(17.5);
            assertThat(data.participantCount()).isEqualTo(8);
            assertThat(data.isConsistent())
                    .as("the distribution accounts for exactly the stored population")
                    .isTrue();
        }

        @Test
        @DisplayName("the chart agrees with the stat cards printed above it")
        void chartAndCardsAgree() {
            serverHasEverything();
            session.load();

            StatChartLogic logic = new StatChartLogic(session.chartData());

            assertThat(logic.meanLabel()).isEqualTo("Mean 72.5");
            assertThat(logic.medianLabel()).isEqualTo("Median 72.5");
            assertThat(logic.summaryCaption())
                    .isEqualTo("8 students · mean 72.5 · median 72.5 · σ 17.5");
            assertThat(ResultsCopy.statCards(session.statistics().orElseThrow()).get(0).value())
                    .isEqualTo("72.5");
        }

        @Test
        @DisplayName("a sitting with no statistics is empty, never ten bars of height zero")
        void noStatisticsIsEmptyNotZeroed() {
            serverHasEverything();
            session.load();

            session.openExecution(live());

            StatChartData data = session.chartData();
            assertThat(data).isEqualTo(StatChartData.empty());
            assertThat(new StatChartLogic(data).state())
                    .as("'no results yet', not 'not enough results to chart'")
                    .isEqualTo(StatChartLogic.State.EMPTY);
        }

        @Test
        @DisplayName("before anything is loaded the chart still has a defined input")
        void chartDataBeforeLoading() {
            assertThat(session.chartData()).isEqualTo(StatChartData.empty());
            assertThat(session.rows()).isEmpty();
            assertThat(session.isGradingUnfinished()).isFalse();
        }
    }

    // ===================== The toggles (T-10, E14.4) =====================

    @Nested
    @DisplayName("the view toggle")
    class Toggle {

        @Test
        @DisplayName("the table is the view a teacher lands on")
        void tableFirst() {
            assertThat(session.view()).isEqualTo(TeacherResultsSession.ResultsView.TABLE);
        }

        @Test
        @DisplayName("switching views repaints once, and switching to the same view does not")
        void switchingRepaintsOnce() {
            renders = 0;

            session.setView(TeacherResultsSession.ResultsView.HISTOGRAM);
            session.setView(TeacherResultsSession.ResultsView.HISTOGRAM);

            assertThat(session.view()).isEqualTo(TeacherResultsSession.ResultsView.HISTOGRAM);
            assertThat(renders).isEqualTo(1);
        }

        @Test
        @DisplayName("the print layout is off until it is asked for, and is idempotent")
        void printLayout() {
            renders = 0;

            assertThat(session.isPrintLayout()).isFalse();
            session.setPrintLayout(true);
            session.setPrintLayout(true);

            assertThat(session.isPrintLayout()).isTrue();
            assertThat(renders).isEqualTo(1);

            session.setPrintLayout(false);
            assertThat(session.isPrintLayout()).isFalse();
        }
    }

    @Nested
    @DisplayName("what the screen says when there is no table")
    class Empty {

        @Test
        @DisplayName("before anything loads, and for a teacher with no exams, it is 'no exams'")
        void noExams() {
            assertThat(session.emptyPanel()).isEqualTo(ResultsCopy.NO_EXAMS);

            connection.replyOk(Verb.RESULTS_EXAMS_GET, TeacherResults.EMPTY);
            session.load();

            assertThat(session.emptyPanel()).isEqualTo(ResultsCopy.NO_EXAMS);
        }

        @Test
        @DisplayName("an exam that has never been released says so, not 'nobody sat it'")
        void neverReleased() {
            connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of(drawerExam())));

            session.load();

            assertThat(session.emptyPanel()).isEqualTo(ResultsCopy.NEVER_RELEASED);
        }

        @Test
        @DisplayName("a sitting nobody entered says that, and one nobody marked says the other")
        void satAndMarkedAreDifferentFacts() {
            ExecutionResultRow deserted = new ExecutionResultRow(12, "5164", OPENED, CLOSED,
                    ExecutionState.CLOSED, 0, 0, false, false);
            ExamResultRow exam = new ExamResultRow(1, "101101", "Algebra", "11", "אלגברה",
                    List.of(deserted, live()));
            connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of(exam)));
            connection.replyOk(Verb.RESULTS_EXECUTION_GET, liveResults());
            session.load();

            assertThat(session.emptyPanel())
                    .as("the newest is the deserted one, and nobody entered its code")
                    .isEqualTo(ResultsCopy.NOBODY_SAT);

            session.openExecution(live());

            assertThat(session.emptyPanel())
                    .as("three students sat this one; none of their papers is marked")
                    .isEqualTo(ResultsCopy.NOTHING_MARKED);
        }

        @Test
        @DisplayName("'nothing to load' is not 'still loading', so no skeleton spins forever")
        void openingIsDistinctFromIdle() {
            connection.replyOk(Verb.RESULTS_EXAMS_GET, TeacherResults.EMPTY);
            session.load();

            assertThat(session.isOpeningExecution())
                    .as("no sitting is selected, so nothing is on its way")
                    .isFalse();
        }

        @Test
        @DisplayName("a sitting whose answer is still in flight is genuinely loading")
        void openingASittingIsLoading() {
            connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of(algebraExam())));
            // No canned reply for the detail verb, so the request stays pending.
            session.load();

            assertThat(session.isOpeningExecution()).isTrue();
            assertThat(session.detailState()).isEqualTo(AsyncViewState.LOADING);
        }
    }

    @Test
    @DisplayName("the rows the table shows are the server's, in the server's order")
    void rowsArePassedThrough() {
        serverHasEverything();

        session.load();

        assertThat(session.rows()).extracting(StudentGradeRow::studentName)
                .containsExactly("Maya Levi", "Yael Azulay", "Omer Katz");
        assertThat(session.rows().get(1).overrideReason())
                .as("the teacher path keeps the justification (S-23)")
                .isNotNull();
    }

    @Test
    @DisplayName("⚑ the session hands the table a timed-out row it can tell apart (B-16)")
    void attemptStatusAndSolvingTimeReachTheTable() {
        serverHasEverything();

        session.load();

        StudentGradeRow omer = session.rows().get(2);
        StudentGradeRow yael = session.rows().get(1);
        assertThat(omer.effectiveScore()).isEqualTo(yael.effectiveScore() - 10);
        assertThat(omer.autoScore())
                .as("the same machine score, and now a visibly different situation")
                .isEqualTo(yael.autoScore());
        assertThat(ResultsCopy.attemptStatusLabel(omer.attemptStatus()))
                .as("a word, not only a colour: the B-5 / wave rule")
                .isEqualTo("Timed out");
        assertThat(ResultsCopy.attemptStatusLabel(yael.attemptStatus())).isEqualTo("Submitted");
        assertThat(ResultsCopy.solvingTimeLabel(omer.actualMinutes())).isEqualTo("90 min");
        assertThat(ResultsCopy.solvingTimeLabel(yael.actualMinutes())).isEqualTo("55 min");
        assertThat(ResultsCopy.solvingTimeLabel(null))
                .as("absent is a different fact from zero and is said as one")
                .isEqualTo("Not recorded");
        assertThat(ResultsCopy.wasTimedOut(omer)).isTrue();
        assertThat(ResultsCopy.wasTimedOut(yael)).isFalse();
    }

    @Test
    @DisplayName("every state change tells the screen to repaint")
    void repaintsOnEveryChange() {
        serverHasEverything();
        renders = 0;

        session.load();

        assertThat(renders).isGreaterThanOrEqualTo(2);
    }
}
