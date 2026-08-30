package client.features.data;

import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.approval.ApprovalRow;
import common.dto.approval.ApprovalState;
import common.dto.approval.ExamPreview;
import common.dto.approval.ExamPreviewRequest;
import common.dto.approval.PreviewAnswerRow;
import common.dto.approval.TeacherOnlyBlock;
import common.dto.bank.Difficulty;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionImage;
import common.dto.bank.QuestionImageRequest;
import common.dto.bank.QuestionRequest;
import common.dto.bank.QuestionVersionDetail;
import common.dto.bank.VersionHistory;
import common.dto.exam.ExamQuestion;
import common.dto.report.DataResults;
import common.dto.report.ReportRow;
import common.dto.results.ResultStatistics;
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
 * The three screens a Data row opens, without a JavaFX toolkit (E15.2 — F9.3, S-7, U-44, the
 * lead's ruling of 2026-08-30).
 *
 * <p>One file for three sessions, because the three make one claim together and it is the claim
 * the ruling was about: every row of the principal's browser opens something, each of the four
 * verbs behind them is a read, and none of the three sessions has a method that writes.
 *
 * <p>Each session talks to a {@link FakeClientConnection} through a real {@link RequestDispatcher}
 * with a {@link DirectFxThreadPoster}, so every transition settles synchronously (TEAM_SPLIT
 * section 3.2). The fixture is the seeded school seen from the principal's chair.
 */
class DataDetailSessionTest {

    private static final Instant SPRING = Instant.parse("2026-03-10T07:00:00Z");
    private static final Instant SUMMER = Instant.parse("2026-08-07T06:00:00Z");

    private FakeClientConnection connection;
    private RequestDispatcher dispatcher;
    private int renders;

    @BeforeEach
    void setUp() {
        connection = new FakeClientConnection();
        dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        renders = 0;
    }

    // ===================== The question (T-11.1) ==========================

    @Nested
    @DisplayName("DataQuestionSession — QUESTION_GET and QUESTION_VERSIONS, both reads")
    class Question {

        private DataQuestionSession session;

        @BeforeEach
        void openSession() {
            session = new DataQuestionSession(dispatcher, new DirectFxThreadPoster())
                    .onChange(() -> renders++);
        }

        @Test
        @DisplayName("⚑ a row opens the question, its key and its history in one visit")
        void opensTheQuestionAndItsHistory() {
            connection.replyOk(Verb.QUESTION_GET, LINEAR);
            connection.replyOk(Verb.QUESTION_VERSIONS, LINEAR_HISTORY);

            session.open("11001");

            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.detail()).contains(LINEAR);
            assertThat(session.detail().orElseThrow().correctAnswer())
                    .as("she may see the key: F9.3 has her browse the bank, and QuestionDetail "
                            + "has carried it to every staff reader since 2026-08-21")
                    .isEqualTo(2);
            assertThat(session.historyEntries()).hasSize(2);
            assertThat(session.historyEntries().get(0).isCurrent()).isTrue();
            assertThat(session.historyEntries().get(1).changes())
                    .as("the timeline is BankSession's, so the principal's history and the "
                            + "teacher's cannot describe the same two versions differently")
                    .isEqualTo("The first version.");
            assertThat(session.error()).isEmpty();
        }

        @Test
        @DisplayName("both verbs travel, and both name the question the row carried")
        void sendsTheTwoReadsAndNothingElse() {
            connection.replyOk(Verb.QUESTION_GET, LINEAR);
            connection.replyOk(Verb.QUESTION_VERSIONS, LINEAR_HISTORY);

            session.open("11001");

            assertThat(connection.sentMessages()).extracting(Message::getVerb)
                    .containsExactly(Verb.QUESTION_GET, Verb.QUESTION_VERSIONS);
            assertThat(connection.sentMessages()).allSatisfy(sent ->
                    assertThat(((QuestionRequest) sent.getPayload()).displayId5())
                            .isEqualTo("11001"));
        }

        @Test
        @DisplayName("a refused question says so and says nothing about why (F1.1)")
        void aRefusedQuestionIsExplained() {
            connection.replyError(Verb.QUESTION_GET, ErrorCode.NOT_FOUND, "no such question");
            connection.replyOk(Verb.QUESTION_VERSIONS, LINEAR_HISTORY);

            session.open("11001");

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.detail()).isEmpty();
            assertThat(session.error()).contains(DataDetailCopy.QUESTION_FAILED_HINT);
        }

        @Test
        @DisplayName("⚑ a failed timeline does not fail the question that is already on screen")
        void aFailedHistoryLeavesTheQuestionAlone() {
            connection.replyOk(Verb.QUESTION_GET, LINEAR);
            connection.replyError(Verb.QUESTION_VERSIONS, ErrorCode.INTERNAL, "boom");

            session.open("11001");

            assertThat(session.detail()).contains(LINEAR);
            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.historyError()).contains(DataDetailCopy.HISTORY_FAILED);
            assertThat(session.historyEntries()).isEmpty();
        }

        @Test
        @DisplayName("an illustrated question fetches its picture; a plain one asks for nothing")
        void theImageIsFetchedOnlyWhenThereIsOne() {
            connection.replyOk(Verb.QUESTION_GET, ILLUSTRATED);
            connection.replyOk(Verb.QUESTION_VERSIONS, LINEAR_HISTORY);
            connection.replyOk(Verb.QUESTION_IMAGE_GET, new QuestionImage("11002", 1,
                    QuestionImage.PNG, new byte[]{1, 2, 3}));

            session.open("11002");

            assertThat(session.imageState()).isEqualTo(AsyncViewState.READY);
            assertThat(session.image()).containsExactly(1, 2, 3);
            assertThat(connection.sentMessages()).extracting(Message::getVerb)
                    .contains(Verb.QUESTION_IMAGE_GET);
            assertThat(connection.sentMessages()).filteredOn(sent ->
                            sent.getVerb() == Verb.QUESTION_IMAGE_GET)
                    .allSatisfy(sent -> assertThat(
                            ((QuestionImageRequest) sent.getPayload()).versionNo()).isEqualTo(1));

            connection.clearSent();
            connection.replyOk(Verb.QUESTION_GET, LINEAR);
            session.open("11001");

            assertThat(connection.sentMessages()).extracting(Message::getVerb)
                    .as("a question with no picture costs no request at all")
                    .doesNotContain(Verb.QUESTION_IMAGE_GET);
        }

        @Test
        @DisplayName("⚑ a late answer for the question she left is dropped, not applied")
        void aLateAnswerIsDropped() {
            // Nothing answers until this test says so, which is the only way to put two visits
            // in flight at once against a connection that otherwise replies inside send().
            defer(Verb.QUESTION_GET, Verb.QUESTION_VERSIONS);
            session.open("11001");
            session.open("11002");

            List<Message> asked = connection.sentMessages();
            connection.deliver(Message.ok(asked.get(2), ILLUSTRATED));
            connection.deliver(Message.ok(asked.get(3),
                    new VersionHistory("11002", List.of(version(1, "Factor the quadratic")))));

            assertThat(session.detail().orElseThrow().displayId5()).isEqualTo("11002");

            // The first visit's answer arriving now would put one question's answers under the
            // other one's heading. It is discarded because it is not about the id on screen.
            connection.deliver(Message.ok(asked.get(0), LINEAR));
            connection.deliver(Message.ok(asked.get(1), LINEAR_HISTORY));

            assertThat(session.detail().orElseThrow().displayId5()).isEqualTo("11002");
            assertThat(session.historyEntries()).hasSize(1);
        }

        @Test
        @DisplayName("a picture that will not load says so without failing the question")
        void aFailedImageLeavesTheQuestionAlone() {
            connection.replyOk(Verb.QUESTION_GET, ILLUSTRATED);
            connection.replyOk(Verb.QUESTION_VERSIONS, LINEAR_HISTORY);
            connection.replyError(Verb.QUESTION_IMAGE_GET, ErrorCode.NOT_FOUND, "gone");

            session.open("11002");

            assertThat(session.imageState()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.image()).isNull();
            assertThat(session.state())
                    .as("three sentences for three absences: the screen must not tell a reader "
                            + "whose diagram failed that the teacher never attached one")
                    .isEqualTo(AsyncViewState.READY);
        }

        @Test
        @DisplayName("a second visit to the same question while it loads asks only once")
        void asksOnceWhileLoading() {
            defer(Verb.QUESTION_GET, Verb.QUESTION_VERSIONS);
            session.open("11001");

            assertThat(session.isLoading()).isTrue();
            assertThat(session.historyState()).isEqualTo(AsyncViewState.LOADING);

            session.open("11001");

            assertThat(connection.sentMessages()).hasSize(2);
        }

        @Test
        @DisplayName("a screen opened with no parameter asks nothing")
        void aBlankIdAsksNothing() {
            session.open("");
            session.open(null);

            assertThat(connection.sentMessages()).isEmpty();
            assertThat(session.state()).isEqualTo(AsyncViewState.IDLE);
        }
    }

    // ===================== The exam (T-11.2) ==============================

    @Nested
    @DisplayName("DataExamSession — EXAM_PREVIEW_GET, and nothing that decides")
    class Exam {

        private DataExamSession session;

        @BeforeEach
        void openSession() {
            session = new DataExamSession(dispatcher, new DirectFxThreadPoster())
                    .onChange(() -> renders++);
        }

        @Test
        @DisplayName("⚑ a row opens the student's own paper, with the key beside it")
        void opensThePreview() {
            connection.replyOk(Verb.EXAM_PREVIEW_GET, PREVIEW);

            session.open(1102L);

            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.preview()).contains(PREVIEW);
            assertThat(session.correctOptionFor(PREVIEW.questions().get(0))).isEqualTo(2);
            assertThat(connection.sentMessages()).extracting(Message::getVerb)
                    .as("one read, and no second verb behind this screen")
                    .containsExactly(Verb.EXAM_PREVIEW_GET);
            assertThat(((ExamPreviewRequest) connection.lastSent().getPayload()).examVersionId())
                    .as("addressed by the version id the catalogue row carries (REPORTS A2)")
                    .isEqualTo(1102L);
        }

        @Test
        @DisplayName("a refusal is a sentence and a way back, never a stack trace")
        void aRefusalIsExplained() {
            connection.replyError(Verb.EXAM_PREVIEW_GET, ErrorCode.NOT_FOUND, "gone");

            session.open(1102L);

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.preview()).isEmpty();
            assertThat(session.error()).contains(DataDetailCopy.EXAM_FAILED_HINT);
        }

        @Test
        @DisplayName("a row carrying no version says so rather than asking for version 0")
        void anUnopenableRowAsksNothing() {
            session.open(0);

            assertThat(connection.sentMessages()).isEmpty();
            assertThat(session.error()).contains(DataDetailCopy.EXAM_NOT_OPENABLE);
        }

        @Test
        @DisplayName("a second click on the same exam while it loads asks only once")
        void asksOnceWhileLoading() {
            defer(Verb.EXAM_PREVIEW_GET);
            session.open(1102L);

            assertThat(session.isLoading()).isTrue();

            session.open(1102L);

            assertThat(connection.sentMessages()).hasSize(1);
        }

        @Test
        @DisplayName("⚑ a late answer for the exam she left is dropped, not applied")
        void aLateAnswerIsDropped() {
            defer(Verb.EXAM_PREVIEW_GET);
            session.open(1102L);
            session.open(1201L);

            List<Message> asked = connection.sentMessages();
            connection.deliver(Message.ok(asked.get(1), preview(1201L)));

            assertThat(session.preview().orElseThrow().summary().examVersionId())
                    .isEqualTo(1201L);

            connection.deliver(Message.ok(asked.get(0), preview(1102L)));

            assertThat(session.preview().orElseThrow().summary().examVersionId())
                    .as("whatever landed, it is the version on screen or it is discarded")
                    .isEqualTo(1201L);
        }
    }

    // ===================== The sitting (T-11.2) ===========================

    @Nested
    @DisplayName("DataSittingSession — DATA_RESULTS_GET, the list's own verb re-read")
    class Sitting {

        private DataSittingSession session;

        @BeforeEach
        void openSession() {
            session = new DataSittingSession(dispatcher, new DirectFxThreadPoster())
                    .onChange(() -> renders++);
        }

        @Test
        @DisplayName("⚑ a row opens the sitting's frozen figures, and no new verb was needed")
        void opensTheSitting() {
            connection.replyOk(Verb.DATA_RESULTS_GET, TWO_SITTINGS);

            session.open(1L);

            assertThat(session.state()).isEqualTo(AsyncViewState.READY);
            assertThat(session.sitting()).contains(OLDER);
            assertThat(connection.sentMessages()).extracting(Message::getVerb)
                    .as("the browser's own verb, and no DATA_SITTING_GET beside it")
                    .containsExactly(Verb.DATA_RESULTS_GET);
        }

        @Test
        @DisplayName("the distribution is the ten stored buckets, lowest band first")
        void theDistributionIsTheStoredDeciles() {
            connection.replyOk(Verb.DATA_RESULTS_GET, TWO_SITTINGS);

            session.open(1L);

            assertThat(session.distribution()).hasSize(ResultStatistics.BUCKET_COUNT);
            assertThat(session.distribution().get(0).range()).isEqualTo("0 to 9");
            assertThat(session.distribution().get(9).range())
                    .as("the top bucket is eleven wide, because that is how it was frozen")
                    .isEqualTo("90 to 100");
            assertThat(session.distribution().get(9).count()).isEqualTo(2);
            assertThat(session.distribution().get(9).share()).isEqualTo("2 (25%)");
            assertThat(session.distribution().get(0).share())
                    .as("an empty band prints a bare zero rather than a column of 0%")
                    .isEqualTo("0");
        }

        @Test
        @DisplayName("the chart is fed the stored figures and recomputes none of them (H14.4)")
        void theChartIsFedTheStoredFigures() {
            connection.replyOk(Verb.DATA_RESULTS_GET, TWO_SITTINGS);

            session.open(1L);

            assertThat(session.chartData().buckets()).isEqualTo(SEEDED.deciles());
            assertThat(session.chartData().mean()).isEqualTo(72.5);
            assertThat(session.chartData().standardDeviation()).isEqualTo(17.5);
            assertThat(session.chartData().participantCount()).isEqualTo(8);
        }

        @Test
        @DisplayName("a sitting no longer in the closed population is explained, not left blank")
        void aSittingThatHasLeftThePopulation() {
            connection.replyOk(Verb.DATA_RESULTS_GET, DataResults.EMPTY);

            session.open(1L);

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.sitting()).isEmpty();
            assertThat(session.error()).contains(DataDetailCopy.SITTING_FAILED_HINT);
            assertThat(session.distribution()).isEmpty();
        }

        @Test
        @DisplayName("a dropped connection is the same sentence, because the fix is the same")
        void aDroppedConnection() {
            connection.replyError(Verb.DATA_RESULTS_GET, ErrorCode.INTERNAL, "boom");

            session.open(1L);

            assertThat(session.state()).isEqualTo(AsyncViewState.ERROR);
            assertThat(session.error()).contains(DataDetailCopy.SITTING_FAILED_HINT);
        }

        @Test
        @DisplayName("⚑ a late answer for the sitting she left is dropped, not applied")
        void aLateAnswerIsDropped() {
            defer(Verb.DATA_RESULTS_GET);
            session.open(1L);
            session.open(2L);

            List<Message> asked = connection.sentMessages();
            connection.deliver(Message.ok(asked.get(1), TWO_SITTINGS));

            assertThat(session.sitting()).contains(NEWER);

            // Both requests carry the same payload - none at all - so only the session's own
            // guard can tell the two visits apart. This is what it is for.
            connection.deliver(Message.ok(asked.get(0), TWO_SITTINGS));

            assertThat(session.sitting()).contains(NEWER);
        }

        @Test
        @DisplayName("before anything arrives the chart is empty, never a record of zeroes")
        void theChartIsEmptyBeforeItLoads() {
            defer(Verb.DATA_RESULTS_GET);
            session.open(1L);

            assertThat(session.isLoading()).isTrue();
            assertThat(session.chartData().participantCount())
                    .as("StatChartData.empty(), so the chart draws its 'no results yet' state "
                            + "rather than a flat histogram of nothing")
                    .isZero();
            assertThat(session.distribution()).isEmpty();

            session.open(1L);

            assertThat(connection.sentMessages()).hasSize(1);
        }

        @Test
        @DisplayName("a screen opened with no parameter asks nothing")
        void aBlankIdAsksNothing() {
            session.open(0);

            assertThat(connection.sentMessages()).isEmpty();
            assertThat(session.state()).isEqualTo(AsyncViewState.IDLE);
        }
    }

    // ===================== Fixture =======================================

    /**
     * Makes these verbs go unanswered, so a test can deliver the answers itself.
     *
     * <p>{@code FakeClientConnection} answers inside {@code send}, which is what makes every
     * other test here settle synchronously and is exactly wrong for a late-answer test: with it,
     * two visits can never be in flight at once. A responder that builds no message leaves the
     * request pending, and {@code deliver} then plays the answers back in whatever order the
     * test is about.
     */
    private void defer(Verb... verbs) {
        for (Verb verb : verbs) {
            connection.respondTo(verb, request -> null);
        }
    }

    private static final QuestionDetail LINEAR = new QuestionDetail("11001", "11", "Algebra",
            2, 2, "Solve the linear equation", List.of("x = 1", "x = 2", "x = 3", "x = 4"), 2,
            "Equations", Difficulty.EASY, false, "Dana Cohen", SUMMER);

    private static final QuestionDetail ILLUSTRATED = new QuestionDetail("11002", "11", "Algebra",
            1, 1, "Factor the quadratic", List.of("One", "Two", "Three", "Four"), 3,
            "Equations", Difficulty.MEDIUM, true, "Dana Cohen", SPRING);

    private static final VersionHistory LINEAR_HISTORY = new VersionHistory("11001",
            List.of(version(2, "Solve the linear equation"), version(1, "Solve the equation")));

    private static QuestionVersionDetail version(int no, String text) {
        return new QuestionVersionDetail(no, text, List.of("x = 1", "x = 2", "x = 3", "x = 4"),
                2, "Equations", Difficulty.EASY, false, "Dana Cohen",
                no == 1 ? SPRING : SUMMER);
    }

    private static ExamQuestion previewQuestion(int ordinal) {
        return new ExamQuestion(900L + ordinal, "1200" + ordinal, ordinal, 50,
                "Question " + ordinal, "One", "Two", "Three", "Four", null);
    }

    private static ExamPreview preview(long versionId) {
        return new ExamPreview(
                new ApprovalRow(versionId, "101101", "Algebra midterm", "11", "Algebra", 1,
                        "Dana Cohen", SUMMER, 2, 45, ApprovalState.APPROVED, null, false, 0),
                "Answer every question.",
                List.of(previewQuestion(1), previewQuestion(2)),
                new TeacherOnlyBlock("Mark question 2 generously.", "Dana Cohen",
                        List.of(new PreviewAnswerRow(901, 1, (byte) 2),
                                new PreviewAnswerRow(902, 2, (byte) 3))));
    }

    private static final ExamPreview PREVIEW = preview(1102L);

    /** SEED_CONTENT section 9.1's frozen record. */
    private static final ResultStatistics SEEDED = new ResultStatistics(8, 72.5, 72.5, 17.5,
            45, 100, 7, 0.875, List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));

    private static final ResultStatistics QUIET = new ResultStatistics(4, 65, 65,
            Math.sqrt(125), 50, 80, 3, 0.75, List.of(0, 0, 0, 0, 0, 1, 1, 1, 1, 0));

    private static final ReportRow NEWER = new ReportRow(2, "5150", "Algebra quiz", "11",
            "Algebra", SUMMER, SUMMER.plusSeconds(7200), 4, QUIET);

    private static final ReportRow OLDER = new ReportRow(1, "4821", "Algebra midterm", "11",
            "Algebra", SPRING, SPRING.plusSeconds(7200), 8, SEEDED);

    private static final DataResults TWO_SITTINGS = new DataResults(List.of(NEWER, OLDER));
}
