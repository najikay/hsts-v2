package client.features.home;

import client.core.Routes;
import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.approval.ApprovalQueue;
import common.dto.approval.ApprovalRow;
import common.dto.approval.ApprovalState;
import client.ui.components.logic.ChipTone;
import common.dto.exam.AttemptState;
import common.dto.exam.ExecutionMonitor;
import common.dto.exam.MonitorCounts;
import common.dto.exam.MonitorRow;
import common.dto.grading.ExecutionGradingSummary;
import common.dto.grading.GradeState;
import common.dto.grading.GradingQueue;
import common.dto.grading.MyGrades;
import common.dto.grading.StudentGradeRow;
import common.dto.release.ReleaseList;
import common.dto.release.ReleaseRow;
import common.dto.release.ReleaseState;
import common.dto.report.DataExams;
import common.dto.report.DataResults;
import common.dto.results.ExamResultRow;
import common.dto.results.ExecutionResultRow;
import common.dto.results.ExecutionResults;
import common.dto.results.ExecutionState;
import common.dto.results.ResultStatistics;
import common.dto.results.TeacherResults;
import common.protocol.ErrorCode;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four dashboard sessions, with no toolkit booted (UI wave 1 — F-10).
 *
 * <p>These classes exist so the dashboards have logic that can be proved, and the
 * two things worth proving are the ones a screenshot would never catch.
 *
 * <p><b>A failed read is not a zero.</b> Every session has a test for it, because
 * the whole failure mode of a summary screen is that it renders "0 waiting" when
 * it means "I could not ask". A coordinator who trusts that number stops checking.
 *
 * <p><b>Cards settle independently.</b> The teacher's reads are separate verbs and
 * one of them failing must not blank the others. The test drives exactly that:
 * answer two, refuse one, assert the survivors still carry their numbers.
 *
 * <p>UI wave 2 added two cards that need a second, conditional read — the live
 * sitting's monitor and the last closed sitting's frozen statistics — and the
 * nested classes for those hold the rules that only exist because the detail is
 * separate from the count: a failed detail read must leave a true number alone,
 * and a card that is still loading its detail must not look broken.
 */
class DashboardSessionTest {

    private static final Instant NOW = Instant.parse("2026-08-23T09:00:00Z");

    private FakeClientConnection connection;
    private RequestDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        connection = new FakeClientConnection();
        dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
    }

    // ===================== Fixtures ======================================

    private static ReleaseRow release(long id, ReleaseState state) {
        return new ReleaseRow(id, 900 + id, "Algebra midterm", "11", "Algebra", "4B7Q",
                NOW, NOW.plusSeconds(3600), 0, 60, state, null);
    }

    private static ExecutionGradingSummary sitting(long id) {
        return new ExecutionGradingSummary(id, "Algebra midterm", "11", "4B7Q",
                NOW, 8, 8, 0);
    }

    private static ExamResultRow exam(long id) {
        return new ExamResultRow(id, "10110" + id, "Algebra midterm", "11", "Algebra", List.of());
    }

    private static ApprovalRow submission(long id, String author) {
        return new ApprovalRow(id, "10110" + id, "Algebra midterm", "11", "Algebra", 1,
                author, NOW, 10, 60, ApprovalState.PENDING, null, false, 1);
    }

    private static StudentGradeRow grade(long id, int score, Instant approvedAt) {
        return new StudentGradeRow(id, 11, "Noa Friedman", score, score, score,
                GradeState.APPROVED, null, null, approvedAt, "Algebra midterm", "11",
                "Dana Cohen");
    }

    // ===================== Teacher =======================================

    @Nested
    @DisplayName("Teacher")
    class Teacher {

        /**
         * <b>Updated deliberately in UI wave 2, not weakened.</b> Wave 1's teacher
         * dashboard had one card counting live and scheduled sittings together,
         * which answered "is anything of mine running or about to" in a single
         * number and therefore answered neither: a teacher reading "2" could not
         * tell whether to walk to a classroom. The canvas splits it into LIVE NOW
         * and NEXT RELEASE, so the assertion below splits with it and now pins
         * both numbers where it used to pin their sum.
         */
        @Test
        @DisplayName("separates what is live from what is merely scheduled")
        void countsLiveAndScheduledApart() {
            connection.replyOk(Verb.RELEASE_LIST_GET, new ReleaseList(NOW, List.of(
                    release(1, ReleaseState.LIVE),
                    release(2, ReleaseState.SCHEDULED),
                    release(3, ReleaseState.CLOSED),
                    release(4, ReleaseState.CANCELLED))));
            connection.replyOk(Verb.GRADING_QUEUE_GET, new GradingQueue(List.of(sitting(1))));
            connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of(exam(1))));

            TeacherDashboardSession session =
                    new TeacherDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.cards().get(0).value()).as("one is live").isEqualTo("1");
            assertThat(session.cards().get(0).state()).isEqualTo(DashboardCard.State.READY);
            assertThat(session.cards().get(2).value()).as("one is scheduled").isEqualTo("1");
            // Closed and cancelled are on neither card: a teacher who released four
            // exams last term does not want that number on her home screen.
        }

        @Test
        @DisplayName("a live sitting puts a live chip on the card")
        void aLiveSittingIsChipped() {
            connection.replyOk(Verb.RELEASE_LIST_GET, new ReleaseList(NOW,
                    List.of(release(1, ReleaseState.LIVE))));

            TeacherDashboardSession session =
                    new TeacherDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.cards().get(0).statusChip()).isPresent();
            assertThat(session.cards().get(0).statusChip().orElseThrow().tone())
                    .isEqualTo(ChipTone.LIVE);
        }

        @Test
        @DisplayName("nothing live means no chip, rather than a chip saying nothing")
        void nothingLiveMeansNoChip() {
            connection.replyOk(Verb.RELEASE_LIST_GET, new ReleaseList(NOW, List.of()));

            TeacherDashboardSession session =
                    new TeacherDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.cards().get(0).statusChip()).isEmpty();
        }

        @Test
        @DisplayName("each card opens the screen it counted")
        void cardsNavigateToWhatTheyCounted() {
            connection.replyOk(Verb.RELEASE_LIST_GET, new ReleaseList(NOW, List.of()));
            connection.replyOk(Verb.GRADING_QUEUE_GET, new GradingQueue(List.of()));
            connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of()));

            TeacherDashboardSession session =
                    new TeacherDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.cards()).extracting(DashboardCard::routeId)
                    .containsExactly(Routes.RELEASES.id(), Routes.GRADING.id(),
                            Routes.RELEASES.id(), Routes.RESULTS.id());
        }

        @Test
        @DisplayName("every card names the screen its link opens")
        void everyCardNamesItsDestination() {
            TeacherDashboardSession session =
                    new TeacherDashboardSession(dispatcher, new DirectFxThreadPoster());

            assertThat(session.cards()).allSatisfy(card ->
                    assertThat(card.linkText()).isNotBlank());
        }

        @Test
        @DisplayName("⚑ one failing read leaves the other cards intact")
        void oneFailureDoesNotBlankThePage() {
            connection.replyOk(Verb.RELEASE_LIST_GET, new ReleaseList(NOW, List.of(
                    release(1, ReleaseState.LIVE), release(2, ReleaseState.SCHEDULED))));
            connection.replyError(Verb.GRADING_QUEUE_GET, ErrorCode.INTERNAL, "no");
            connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of(exam(1))));

            TeacherDashboardSession session =
                    new TeacherDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.cards().get(0).value()).isEqualTo("1");
            assertThat(session.cards().get(1).state()).isEqualTo(DashboardCard.State.FAILED);
            assertThat(session.cards().get(2).value()).isEqualTo("1");
        }

        @Test
        @DisplayName("⚑ a failed read says so; it never renders as a zero")
        void failureIsNotZero() {
            connection.replyError(Verb.RELEASE_LIST_GET, ErrorCode.INTERNAL, "no");
            connection.replyError(Verb.GRADING_QUEUE_GET, ErrorCode.INTERNAL, "no");
            connection.replyError(Verb.RESULTS_EXAMS_GET, ErrorCode.INTERNAL, "no");

            TeacherDashboardSession session =
                    new TeacherDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.cards()).allSatisfy(card -> {
                assertThat(card.state()).isEqualTo(DashboardCard.State.FAILED);
                assertThat(card.value())
                        .as("'0 waiting' would be a lie a teacher acts on")
                        .isNotEqualTo("0")
                        .isEqualTo(DashboardCopy.UNAVAILABLE);
            });
        }

        @Test
        @DisplayName("an empty answer is EMPTY, and says what fills it")
        void emptyNamesTheNextThing() {
            connection.replyOk(Verb.RELEASE_LIST_GET, new ReleaseList(NOW, List.of()));
            connection.replyOk(Verb.GRADING_QUEUE_GET, new GradingQueue(List.of()));
            connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of()));

            TeacherDashboardSession session =
                    new TeacherDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.cards().get(0).state()).isEqualTo(DashboardCard.State.EMPTY);
            assertThat(session.cards().get(0).hint()).isEqualTo(DashboardCopy.LIVE_EMPTY);
            assertThat(session.cards().get(1).hint()).isEqualTo(DashboardCopy.GRADING_EMPTY);
            assertThat(session.cards().get(2).hint())
                    .isEqualTo(DashboardCopy.NEXT_RELEASE_EMPTY);
        }

        @Test
        @DisplayName("cards are LOADING before anything answers")
        void loadingUntilAnswered() {
            // No responders registered: the futures never complete.
            TeacherDashboardSession session =
                    new TeacherDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.cards()).allSatisfy(card ->
                    assertThat(card.state()).isEqualTo(DashboardCard.State.LOADING));
        }
    }

    // ===================== Teacher, the wave-2 detail cards ==============

    @Nested
    @DisplayName("The live card's detail")
    class LiveCard {

        private static ExecutionMonitor monitor(List<MonitorRow> rows, long finished) {
            return new ExecutionMonitor(1, "Algebra midterm", "11", "4B7Q", true,
                    NOW, NOW.plusSeconds(1800), 0, 60,
                    new MonitorCounts(rows.size(), finished, 0), rows);
        }

        private static MonitorRow attempt(String name, AttemptState state) {
            return new MonitorRow(1, name, state, NOW, null, 0, 3, 10, null, null);
        }

        @Test
        @DisplayName("⚑ the three rows shown are the students still sitting")
        void stillSittingStudentsComeFirst() {
            // A card with three slots spent on students who handed in twenty
            // minutes ago has told the teacher nothing she can act on.
            var detail = TeacherDashboardSession.detailOf(monitor(List.of(
                    attempt("Amit", AttemptState.SUBMITTED),
                    attempt("Noa", AttemptState.IN_PROGRESS),
                    attempt("Yael", AttemptState.SUBMITTED),
                    attempt("Dana", AttemptState.IN_PROGRESS)), 2));

            assertThat(detail.students()).extracting(TeacherDashboardSession.StudentLine::name)
                    .containsExactly("Dana", "Noa", "Amit");
        }

        @Test
        @DisplayName("more students than slots is said, not silently dropped")
        void theRestAreAnnounced() {
            var detail = TeacherDashboardSession.detailOf(monitor(List.of(
                    attempt("A", AttemptState.IN_PROGRESS),
                    attempt("B", AttemptState.IN_PROGRESS),
                    attempt("C", AttemptState.IN_PROGRESS),
                    attempt("D", AttemptState.IN_PROGRESS)), 0));

            assertThat(detail.students()).hasSize(TeacherDashboardSession.LIVE_STUDENT_ROWS);
            assertThat(detail.more()).isTrue();
        }

        @Test
        @DisplayName("exactly as many students as slots is not 'and more'")
        void noPhantomRemainder() {
            var detail = TeacherDashboardSession.detailOf(monitor(List.of(
                    attempt("A", AttemptState.IN_PROGRESS),
                    attempt("B", AttemptState.IN_PROGRESS),
                    attempt("C", AttemptState.IN_PROGRESS)), 0));

            assertThat(detail.more()).isFalse();
        }

        @Test
        @DisplayName("the progress bar is submitted over sitting, and never divides by zero")
        void progressIsSafe() {
            var nobody = TeacherDashboardSession.detailOf(monitor(List.of(), 0));
            assertThat(nobody.progress()).isEqualTo(0);

            var half = TeacherDashboardSession.detailOf(monitor(List.of(
                    attempt("A", AttemptState.SUBMITTED),
                    attempt("B", AttemptState.IN_PROGRESS)), 1));
            assertThat(half.progress()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("⚑ time left is measured against the server's clock, not the workstation's")
        void timeLeftUsesTheServerClock() {
            // The card is handed serverNow with the answer, precisely so a
            // teacher whose laptop clock is ten minutes fast is not told the
            // sitting closes ten minutes sooner than it does.
            var detail = TeacherDashboardSession.detailOf(monitor(List.of(), 0));
            assertThat(detail.minutesLeft()).isEqualTo(30);
        }

        @Test
        @DisplayName("a sitting past its closing time reads as closing, not as negative")
        void pastClosingIsNotNegative() {
            var detail = new TeacherDashboardSession.LiveDetail("Algebra", "4B7Q",
                    NOW.minusSeconds(60), NOW, 0, 0, List.of(), false);
            assertThat(detail.minutesLeft()).isZero();
        }

        @Test
        @DisplayName("a failed monitor read leaves the count card alone")
        void aFailedDetailDoesNotBlankTheCount() {
            connection.replyOk(Verb.RELEASE_LIST_GET, new ReleaseList(NOW,
                    List.of(release(1, ReleaseState.LIVE))));
            connection.replyError(Verb.EXECUTION_MONITOR_GET, ErrorCode.INTERNAL, "no");

            TeacherDashboardSession session =
                    new TeacherDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.liveDetail()).isEmpty();
            assertThat(session.cards().get(0).value())
                    .as("the number was true before the detail read and still is")
                    .isEqualTo("1");
        }
    }

    @Nested
    @DisplayName("The last closed card")
    class LastClosedCard {

        private static ExecutionResultRow execution(long id, ExecutionState state,
                                                    Instant closeAt, boolean stats) {
            return new ExecutionResultRow(id, "4B7Q", NOW, closeAt, state, 8, 8, stats, false);
        }

        private static ExamResultRow examWith(ExecutionResultRow... executions) {
            return new ExamResultRow(1, "101101", "Algebra midterm", "11", "Algebra",
                    List.of(executions));
        }

        @Test
        @DisplayName("⚑ the newest CLOSED sitting wins, and cancelled ones are not candidates")
        void picksTheNewestClosed() {
            TeacherResults results = new TeacherResults(List.of(examWith(
                    execution(1, ExecutionState.CLOSED, NOW.minusSeconds(7200), true),
                    execution(2, ExecutionState.CANCELLED, NOW, true),
                    execution(3, ExecutionState.CLOSED, NOW.minusSeconds(60), true))));

            assertThat(TeacherDashboardSession.newestClosed(results))
                    .get().extracting(ExecutionResultRow::executionId).isEqualTo(3L);
        }

        @Test
        @DisplayName("a closed sitting with no frozen statistics is not a candidate")
        void unmarkedIsNotACandidate() {
            TeacherResults results = new TeacherResults(List.of(examWith(
                    execution(1, ExecutionState.CLOSED, NOW, false))));

            assertThat(TeacherDashboardSession.newestClosed(results)).isEmpty();
        }

        @Test
        @DisplayName("no closed sitting is EMPTY and says what will fill it")
        void nothingClosedYet() {
            connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of(exam(1))));

            TeacherDashboardSession session =
                    new TeacherDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.cards().get(3).state()).isEqualTo(DashboardCard.State.EMPTY);
            assertThat(session.cards().get(3).hint())
                    .isEqualTo(DashboardCopy.LAST_CLOSED_EMPTY);
        }

        @Test
        @DisplayName("the mean is rounded onto the card and the deciles reach the sparkline")
        void statisticsReachTheCard() {
            ResultStatistics stats = new ResultStatistics(8, 72.4, 71, 9.1, 55, 91, 6, 0.75,
                    List.of(0, 0, 0, 0, 0, 1, 2, 4, 1, 0));
            connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of(examWith(
                    execution(9, ExecutionState.CLOSED, NOW, true)))));
            connection.replyOk(Verb.RESULTS_EXECUTION_GET, new ExecutionResults(
                    execution(9, ExecutionState.CLOSED, NOW, true), "Algebra midterm", "11",
                    "Algebra", List.of(), stats));

            TeacherDashboardSession session =
                    new TeacherDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.cards().get(3).value()).isEqualTo("72");
            assertThat(session.closedDetail()).isPresent();
            assertThat(session.closedDetail().orElseThrow().passed()).isEqualTo(6);
            assertThat(session.closedDetail().orElseThrow().deciles()).hasSize(10);
        }

        @Test
        @DisplayName("⚑ a closed sitting whose marking is unfinished is a state, not an error")
        void unfinishedMarkingIsCalm() {
            connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of(examWith(
                    execution(9, ExecutionState.CLOSED, NOW, true)))));
            connection.replyOk(Verb.RESULTS_EXECUTION_GET, new ExecutionResults(
                    execution(9, ExecutionState.CLOSED, NOW, true), "Algebra midterm", "11",
                    "Algebra", List.of(), null));

            TeacherDashboardSession session =
                    new TeacherDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.cards().get(3).state()).isEqualTo(DashboardCard.State.EMPTY);
            assertThat(session.cards().get(3).hint())
                    .isEqualTo(DashboardCopy.LAST_CLOSED_UNMARKED);
            assertThat(session.closedDetail()).isEmpty();
        }
    }

    // ===================== Coordinator ===================================

    @Nested
    @DisplayName("Coordinator")
    class Coordinator {

        @Test
        @DisplayName("counts the queue, and the distinct teachers in it, from one read")
        void countsQueueAndAuthors() {
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, new ApprovalQueue(List.of(
                    submission(1, "Dana Cohen"),
                    submission(2, "Dana Cohen"),
                    submission(3, "Rina Barak")), true));

            CoordinatorDashboardSession session =
                    new CoordinatorDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.cards().get(0).value()).as("three exams waiting").isEqualTo("3");
            assertThat(session.cards().get(1).value()).as("from two teachers").isEqualTo("2");
        }

        @Test
        @DisplayName("the same teacher spelled two ways is still one teacher")
        void authorCountIsCaseInsensitive() {
            assertThat(CoordinatorDashboardSession.distinctAuthors(List.of(
                    submission(1, "Dana Cohen"),
                    submission(2, "dana cohen "),
                    submission(3, "")))).isEqualTo(1);
        }

        @Test
        @DisplayName("⚑ a failed queue read fails both cards rather than showing zeroes")
        void failureIsNotZero() {
            connection.replyError(Verb.APPROVALS_QUEUE_GET, ErrorCode.INTERNAL, "no");

            CoordinatorDashboardSession session =
                    new CoordinatorDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.cards()).allSatisfy(card ->
                    assertThat(card.state()).isEqualTo(DashboardCard.State.FAILED));
        }
    }

    // ===================== Student =======================================

    @Nested
    @DisplayName("Student")
    class Student {

        @Test
        @DisplayName("the latest grade is the most recently approved, not the highest")
        void latestIsByApprovalTime() {
            connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of(
                    grade(1, 95, NOW.minusSeconds(86400)),
                    grade(2, 61, NOW))));

            StudentDashboardSession session =
                    new StudentDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            // 95 is the better mark and 61 is the newer one. A card called "latest"
            // that showed 95 would be quietly answering a different question.
            assertThat(session.cards().get(0).value()).isEqualTo("61");
            assertThat(session.cards().get(0).hint()).isEqualTo("Algebra midterm");
        }

        @Test
        @DisplayName("a row with no approval time sorts oldest rather than throwing")
        void missingApprovalTimeIsSurvivable() {
            assertThat(StudentDashboardSession.newest(List.of(
                    grade(1, 70, null),
                    grade(2, 80, NOW))).orElseThrow().gradeId()).isEqualTo(2);
        }

        @Test
        @DisplayName("no grades yet is EMPTY and says when one will appear")
        void noGradesYet() {
            connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of()));

            StudentDashboardSession session =
                    new StudentDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.cards().get(0).state()).isEqualTo(DashboardCard.State.EMPTY);
            assertThat(session.cards().get(0).hint())
                    .isEqualTo(DashboardCopy.LATEST_GRADE_EMPTY);
        }

        @Test
        @DisplayName("the bot card needs no server and always opens the chat")
        void theBotCardIsADoor() {
            StudentDashboardSession session =
                    new StudentDashboardSession(dispatcher, new DirectFxThreadPoster());

            DashboardCard bot = session.cards().get(1);
            assertThat(bot.routeId()).isEqualTo(Routes.BOT_CHAT.id());
            assertThat(bot.state()).isEqualTo(DashboardCard.State.READY);
        }

        @Test
        @DisplayName("⚑ a failed grades read says so rather than showing a zero mark")
        void failureIsNotZero() {
            connection.replyError(Verb.MY_GRADES_GET, ErrorCode.INTERNAL, "no");

            StudentDashboardSession session =
                    new StudentDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            // "0" on a card called "latest grade" is the worst possible wrong answer.
            assertThat(session.cards().get(0).state()).isEqualTo(DashboardCard.State.FAILED);
            assertThat(session.cards().get(0).value()).isNotEqualTo("0");
        }
    }

    // ===================== Principal =====================================

    @Nested
    @DisplayName("Principal")
    class Principal {

        @Test
        @DisplayName("counts both catalogues, each card opening the list it counted")
        void countsBothCatalogues() {
            connection.replyOk(Verb.DATA_EXAMS_GET, new DataExams(List.of()));
            connection.replyOk(Verb.DATA_RESULTS_GET, new DataResults(List.of()));

            PrincipalDashboardSession session =
                    new PrincipalDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.cards()).extracting(DashboardCard::routeId)
                    .containsExactly(Routes.DATA.id(), Routes.REPORTS.id());
            assertThat(session.cards()).allSatisfy(card ->
                    assertThat(card.state()).isEqualTo(DashboardCard.State.EMPTY));
        }

        @Test
        @DisplayName("⚑ one failing catalogue leaves the other card intact")
        void oneFailureDoesNotBlankThePage() {
            connection.replyError(Verb.DATA_EXAMS_GET, ErrorCode.INTERNAL, "no");
            connection.replyOk(Verb.DATA_RESULTS_GET, new DataResults(List.of()));

            PrincipalDashboardSession session =
                    new PrincipalDashboardSession(dispatcher, new DirectFxThreadPoster());
            session.load();

            assertThat(session.cards().get(0).state()).isEqualTo(DashboardCard.State.FAILED);
            assertThat(session.cards().get(1).state()).isEqualTo(DashboardCard.State.EMPTY);
        }
    }
}
