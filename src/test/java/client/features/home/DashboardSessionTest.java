package client.features.home;

import client.core.Routes;
import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.approval.ApprovalQueue;
import common.dto.approval.ApprovalRow;
import common.dto.approval.ApprovalState;
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
 * <p><b>Cards settle independently.</b> The teacher's three reads are three verbs
 * and one of them failing must not blank the other two. The test drives exactly
 * that: answer two, refuse one, assert the survivors still carry their numbers.
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
                GradeState.APPROVED, null, null, approvedAt, "Algebra midterm", "11");
    }

    // ===================== Teacher =======================================

    @Nested
    @DisplayName("Teacher")
    class Teacher {

        @Test
        @DisplayName("counts live and scheduled sittings, and ignores the finished ones")
        void countsOnlyCurrentSittings() {
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

            // A card that counted last term's closed sittings would answer a question
            // nobody asked; "today and next" is the one a teacher opens the app for.
            assertThat(session.cards().get(0).value()).isEqualTo("2");
            assertThat(session.cards().get(0).state()).isEqualTo(DashboardCard.State.READY);
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
                            Routes.RESULTS.id());
        }

        @Test
        @DisplayName("⚑ one failing read leaves the other two cards intact")
        void oneFailureDoesNotBlankThePage() {
            connection.replyOk(Verb.RELEASE_LIST_GET, new ReleaseList(NOW,
                    List.of(release(1, ReleaseState.LIVE))));
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
            assertThat(session.cards().get(0).hint()).isEqualTo(DashboardCopy.SITTINGS_EMPTY);
            assertThat(session.cards().get(1).hint()).isEqualTo(DashboardCopy.GRADING_EMPTY);
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
