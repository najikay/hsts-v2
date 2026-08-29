package client.ui;

import client.core.ClientApp;
import client.core.FxTestHarness;
import client.core.NavParams;
import client.core.Route;
import client.core.Routes;
import client.core.ScreenManager;
import client.core.SessionRoutes;
import client.events.PushEventBridge;
import client.features.bank.QuestionEditorView;
import client.features.bot.BotChatView;
import client.features.exam.ExecutionMonitorView;
import client.features.exambuild.ExamBuilderView;
import client.features.home.StudentHomeSession;
import client.features.login.ShellBoot;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.approval.ApprovalQueue;
import common.dto.approval.ApprovalRow;
import common.dto.approval.ApprovalState;
import common.dto.approval.ExamPreview;
import common.dto.approval.PreviewAnswerRow;
import common.dto.approval.TeacherOnlyBlock;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.authoring.ExamList;
import common.dto.authoring.ExamListRow;
import common.dto.authoring.ExamVersionRow;
import common.dto.bank.BankPage;
import common.dto.bank.BankQuestionRow;
import common.dto.bank.Difficulty;
import common.dto.bot.BotActivityPoint;
import common.dto.bot.BotAnalytics;
import common.dto.bot.BotManagerPage;
import common.dto.bot.BotProfile;
import common.dto.bot.BotSessionRow;
import common.dto.bot.BotSessionsPage;
import common.dto.bot.BotSourceKind;
import common.dto.bot.BotSourceRow;
import common.dto.bot.BotTopQuestion;
import common.dto.exam.AttemptState;
import common.dto.exam.AttentionSummary;
import common.dto.exam.ExamHeader;
import common.dto.exam.ExamQuestion;
import common.dto.exam.ExecutionMonitor;
import common.dto.exam.IntegrityFlag;
import common.dto.exam.MonitorCounts;
import common.dto.exam.MonitorRow;
import common.dto.grading.AnswerReviewRow;
import common.dto.grading.CheckedForm;
import common.dto.grading.ExecutionGrades;
import common.dto.grading.ExecutionGradingSummary;
import common.dto.grading.GradeState;
import common.dto.grading.GradingQueue;
import common.dto.grading.MyGrades;
import common.dto.grading.StudentGradeRow;
import common.dto.notify.NotificationsPage;
import common.dto.release.ReleasableVersion;
import common.dto.release.ReleaseList;
import common.dto.release.ReleaseOptions;
import common.dto.release.ReleaseRow;
import common.dto.release.ReleaseState;
import common.dto.report.DataExamRow;
import common.dto.report.DataExams;
import common.dto.report.DataResults;
import common.dto.report.ReportDimension;
import common.dto.report.ReportRequest;
import common.dto.report.ReportResult;
import common.dto.report.ReportRow;
import common.dto.report.ReportSubject;
import common.dto.report.ReportSubjects;
import common.dto.report.ReportSubjectsRequest;
import common.dto.report.ReportSummary;
import common.dto.results.ExamResultRow;
import common.dto.results.ExecutionResultRow;
import common.dto.results.ExecutionResults;
import common.dto.results.ExecutionState;
import common.dto.results.ResultStatistics;
import common.dto.results.TeacherResults;
import common.protocol.Message;
import common.protocol.Verb;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No screen in this app may cut a word in half (2026-08-29, manual rounds 3-4, U-28).
 *
 * <h2>Why this exists</h2>
 *
 * <p>Rounds 3 and 4 turned up the same defect in every role: "Edit questi…" on a
 * button, a version chip rendered as three dots, the take-exam confirmation's
 * title and subtitle clipped, actions on the student's Study Bot screen with
 * their labels eaten. Each one is a two-line fix and each one comes back the
 * moment somebody adds a longer word, so fixing them one at a time is not a
 * solution — this is. It walks <b>every route every role has</b>, at two window
 * sizes, and fails with the list.
 *
 * <p>The two sizes are the ones that matter: {@code 1200x760} is the window the
 * demo laptop opens at, and {@code 1024x700} is the smallest the shell allows
 * ({@code ScreenManager} pins the stage minimum at 1024x680). A layout that
 * survives both survives the room.
 *
 * <p>The rule itself lives in {@link TruncationProbe}, with its own unit test, so
 * that a red run here always means "this screen is wrong" and never "the
 * measurement is arguable". Two things are exempt and nothing else is: a control
 * with no text at all (an icon button says what it does through its glyph and its
 * tooltip), and a control whose full text is on its own tooltip — see
 * {@link TruncationProbe#fullTextIsOnHover(javafx.scene.control.Labeled)}, which
 * is what lets a table column hold a question stem nobody could have sized for.
 *
 * <h2>What it costs</h2>
 *
 * <p>One app boot per role, four in all, each visiting its whole route table
 * twice. Booting the toolkit is nearly all of the cost, so a per-route boot
 * would be roughly thirty times the price for the same coverage.
 *
 * <p>Same escape hatch as the other UI tests:
 * {@code ./mvnw verify -Dhsts.uitests=false}.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class TruncatedTextGuardTest extends ApplicationTest {

    /** The demo window, and the smallest window the shell allows. */
    private static final List<Window> WINDOWS =
            List.of(new Window(1200, 760), new Window(1024, 700));

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final long EXECUTION = 5001L;
    private static final long VERSION = 7001L;
    private static final long GRADE = 4001L;
    private static final long CALCULUS_V1 = 31L;

    private static final LoginResult DANA = new LoginResult(1001, "dana.cohen", "Dana Cohen",
            Role.TEACHER, List.of(new CourseRef("11", "Algebra 11"),
                    new CourseRef("21", "Java Programming")), 1);

    private static final LoginResult RINA = new LoginResult(3, "rina.barak", "Rina Barak",
            Role.COORDINATOR, List.of(new CourseRef("12", "Calculus 12")), 2);

    private static final LoginResult MAYA = new LoginResult(2001, "maya.levi", "Maya Levi",
            Role.STUDENT, List.of(new CourseRef("11", "Algebra 11"),
                    new CourseRef("21", "Java Programming")), 0);

    private static final LoginResult AVIA = new LoginResult(1, "principal.avia", "Avia Shalev",
            Role.PRINCIPAL, List.of(), 0);

    @BeforeAll
    static void headless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("glass.platform", "Monocle");
        System.setProperty("monocle.platform", "Headless");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
    }

    @Override
    public void start(Stage stage) {
        // Each role boots the app itself, as the other interaction tests do.
    }

    @AfterEach
    void resetGlobalState() {
        FxTestHarness.resetGlobalState();
    }

    // ===================== One test per role, one boot per test ===========

    @Test
    @DisplayName("⚡ no teacher screen cuts a word in half, at either window size")
    void teacherScreensReadInFull() {
        assertNothingIsTruncated(DANA, this::teachingServer, teachingStops(Role.TEACHER));
    }

    @Test
    @DisplayName("⚡ no coordinator screen cuts a word in half, at either window size")
    void coordinatorScreensReadInFull() {
        List<Stop> stops = new ArrayList<>(teachingStops(Role.COORDINATOR));
        stops.add(new Stop(Routes.APPROVALS.id(), NavParams.empty()));
        stops.add(new Stop(Routes.EXAM_PREVIEW.id(),
                NavParams.of("examVersionId", CALCULUS_V1)));
        assertNothingIsTruncated(RINA, this::coordinatingServer, stops);
    }

    @Test
    @DisplayName("⚡ no student screen cuts a word in half, at either window size")
    void studentScreensReadInFull() {
        assertNothingIsTruncated(MAYA, this::studentServer, List.of(
                new Stop(Routes.HOME_STUDENT.id(), NavParams.empty()),
                new Stop(Routes.SETTINGS.id(), NavParams.empty()),
                new Stop(Routes.TAKE_EXAM.id(), NavParams.empty()),
                // The confirmation step, which is where rounds 3-4 found the clipped
                // title and subtitle: a code the dashboard has already validated.
                new Stop(Routes.TAKE_EXAM.id(),
                        NavParams.of(StudentHomeSession.CODE_PARAM, "2075")),
                new Stop(Routes.BOT_CHAT.id(), NavParams.of(BotChatView.PARAM_COURSE, "11")),
                new Stop(Routes.BOT_HISTORY.id(), NavParams.of(BotChatView.PARAM_COURSE, "11")),
                new Stop(Routes.MY_GRADES.id(), NavParams.empty()),
                new Stop(Routes.CHECKED_FORM.id(), NavParams.of("gradeId", GRADE))));
    }

    @Test
    @DisplayName("⚡ no principal screen cuts a word in half, at either window size")
    void principalScreensReadInFull() {
        assertNothingIsTruncated(AVIA, this::principalServer, List.of(
                new Stop(Routes.HOME_PRINCIPAL.id(), NavParams.empty()),
                new Stop(Routes.SETTINGS.id(), NavParams.empty()),
                new Stop(Routes.REPORTS.id(), NavParams.empty()),
                new Stop(Routes.DATA.id(), NavParams.empty())));
    }

    // ===================== The walk =======================================

    /** Both teaching roles share a rail; the coordinator adds two screens to it. */
    private List<Stop> teachingStops(Role role) {
        return List.of(
                new Stop(Routes.home(role).id(), NavParams.empty()),
                new Stop(Routes.SETTINGS.id(), NavParams.empty()),
                new Stop(Routes.QUESTIONS.id(), NavParams.empty()),
                new Stop(Routes.QUESTION_EDIT.id(),
                        NavParams.of(QuestionEditorView.PARAM_COURSE, "11")),
                new Stop(Routes.RELEASES.id(), NavParams.empty()),
                new Stop(Routes.MONITOR.id(),
                        NavParams.of(ExecutionMonitorView.PARAM_EXECUTION, EXECUTION)),
                new Stop(Routes.BOT_MANAGER.id(), NavParams.of("courseCode", "11")),
                new Stop(Routes.BOT_ANALYTICS.id(), NavParams.of("courseCode", "11")),
                new Stop(Routes.RESULTS.id(), NavParams.empty()),
                new Stop(Routes.GRADING.id(), NavParams.empty()),
                new Stop(Routes.EXAMS.id(), NavParams.empty()),
                new Stop(Routes.EXAM_BUILD.id(),
                        NavParams.of(ExamBuilderView.PARAM_COURSE, "11")));
    }

    /**
     * Signs the role in, then visits every stop at both window sizes and fails
     * with the whole list rather than with the first offender: fixing these one
     * red run at a time is exactly the habit this guard exists to replace.
     */
    private void assertNothingIsTruncated(LoginResult user,
                                          Consumer<FakeClientConnection> script,
                                          List<Stop> stops) {
        ScreenManager manager = signIn(user, script);
        assertEveryRouteIsVisited(user.role(), stops);

        List<String> offenders = new ArrayList<>();
        for (Window window : WINDOWS) {
            resize(manager, window);
            for (Stop stop : stops) {
                interact(() -> manager.navigator().navigate(stop.routeId(), stop.params()));
                WaitForAsyncUtils.waitForFxEvents();
                offenders.addAll(scan(manager, user.role(), stop, window));
            }
        }

        assertThat(offenders)
                .as("text that does not fit the box it was given\n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    /**
     * The guard is only a guard while it covers the whole rail. A route added to
     * {@link SessionRoutes} and not to the stop list above would otherwise be
     * silently exempt, which is how a guard rots.
     */
    private void assertEveryRouteIsVisited(Role role, List<Stop> stops) {
        Set<String> visited = new LinkedHashSet<>();
        for (Stop stop : stops) {
            visited.add(stop.routeId());
        }
        List<String> registered = SessionRoutes.routesFor(role).stream().map(Route::id).toList();
        assertThat(visited)
                .as("every route this role has is walked by this guard")
                .containsAll(registered);
    }

    /** Sets the window to exactly this size and lets the layout settle. */
    private void resize(ScreenManager manager, Window window) {
        Stage stage = manager.getPrimaryStage();
        interact(() -> {
            stage.setWidth(window.width());
            stage.setHeight(window.height());
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** @return one report line per control on this screen whose text does not fit. */
    private List<String> scan(ScreenManager manager, Role role, Stop stop, Window window) {
        List<String> found = new ArrayList<>();
        interact(() -> {
            for (Labeled labeled : TruncationProbe.visibleLabeled(manager.scene().getRoot())) {
                if (!TruncationProbe.onScreen(labeled) || !TruncationProbe.isTruncated(labeled)
                        || TruncationProbe.fullTextIsOnHover(labeled)) {
                    continue;
                }
                found.add("%s · %s · %dx%d · %s [%s] short by %.0fpx (box %.0f) · \"%s\"".formatted(
                        role, stop.routeId(), (int) window.width(), (int) window.height(),
                        labeled.getClass().getSimpleName(),
                        String.join(".", labeled.getStyleClass()),
                        TruncationProbe.overflowPx(labeled),
                        labeled.isWrapText() ? labeled.getHeight() : labeled.getWidth(),
                        labeled.getText()));
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
        return found;
    }

    /** Where the guard goes and what it carries when it gets there. */
    private record Stop(String routeId, NavParams params) {
    }

    /** A window size, in the units the stage takes. */
    private record Window(double width, double height) {
    }

    // ===================== The scripted server ============================

    private void teachingServer(FakeClientConnection connection) {
        commonServer(connection);
        connection.replyOk(Verb.RELEASE_LIST_GET, new ReleaseList(NOW, List.of(liveRelease())));
        connection.replyOk(Verb.RELEASE_OPTIONS_GET, new ReleaseOptions(
                List.of(new ReleasableVersion(VERSION, "101101", "Algebra Midterm", 1, "11",
                        "Algebra 11", 45, 12)), true));
        connection.replyOk(Verb.EXECUTION_MONITOR_GET, monitor());
        connection.replyOk(Verb.GRADING_QUEUE_GET, new GradingQueue(List.of(gradingSummary())));
        connection.replyOk(Verb.GRADING_EXECUTION_GET, new ExecutionGrades(gradingSummary(),
                List.of(gradeRow(1, "Maya Levi", 100), gradeRow(2, "Omer Katz", 40))));
        connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of(
                new ExamResultRow(1, "101101", "Algebra midterm", "11", "Algebra 11",
                        List.of(closedSitting())))));
        connection.replyOk(Verb.RESULTS_EXECUTION_GET, new ExecutionResults(closedSitting(),
                "Algebra midterm", "11", "Algebra 11",
                List.of(gradeRow(1, "Maya Levi", 100), gradeRow(2, "Omer Katz", 40)),
                statistics()));
        connection.replyOk(Verb.EXAM_LIST, new ExamList(List.of(examListRow())));
        connection.replyOk(Verb.BANK_LIST, bankPage());
        connection.replyOk(Verb.BOT_MANAGER_GET, BotManagerPage.of(
                new BotProfile(9L, "11", "Algebra 11", "Algebra study bot", true),
                List.of(new BotSourceRow(5L, BotSourceKind.PDF,
                        "Week 3 handout on quadratic equations", "Michal Sharon", NOW, 1, 4200))));
        connection.replyOk(Verb.BOT_ANALYTICS_GET, new BotAnalytics("Algebra 11", 12,
                List.of(new BotActivityPoint(LocalDate.of(2026, 8, 19), 4),
                        new BotActivityPoint(LocalDate.of(2026, 8, 20), 8)),
                List.of(new BotTopQuestion("How do I complete the square?", 5))));
    }

    private void coordinatingServer(FakeClientConnection connection) {
        teachingServer(connection);
        connection.replyOk(Verb.APPROVALS_QUEUE_GET,
                new ApprovalQueue(List.of(pendingApproval()), true));
        connection.replyOk(Verb.EXAM_PREVIEW_GET, new ExamPreview(pendingApproval(),
                "Answer every question. Calculators are not allowed.",
                List.of(previewQuestion(1), previewQuestion(2)),
                new TeacherOnlyBlock("Mark question 2 generously; it was covered late.",
                        "Dana Cohen",
                        List.of(new PreviewAnswerRow(901, 1, (byte) 2),
                                new PreviewAnswerRow(902, 2, (byte) 2)))));
    }

    private void studentServer(FakeClientConnection connection) {
        commonServer(connection);
        connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of(studentGrade())));
        connection.replyOk(Verb.CHECKED_FORM_GET, new CheckedForm(studentGrade(),
                "Algebra midterm", "11", "Dana Cohen", AttemptState.SUBMITTED, 70,
                List.of(new AnswerReviewRow(1, "11001",
                        "What are the roots of x squared minus 5x plus 6?",
                        "1 and 6", "2 and 3", "minus 2 and minus 3", "0 and 5",
                        15, (byte) 2, (byte) 2, true, 15))));
        connection.replyOk(Verb.EXAM_JOIN, new ExamHeader(EXECUTION, "Algebra midterm", "11",
                "Algebra 11", 45, "Answer every question. Good luck.", 3,
                AttemptState.NOT_STARTED));
        connection.replyOk(Verb.BOT_SESSIONS_GET, new BotSessionsPage("11", "Algebra 11",
                List.of(new BotSessionRow(9L, NOW, NOW, 3, "How do I complete the square?"))));
    }

    private void principalServer(FakeClientConnection connection) {
        commonServer(connection);
        connection.replyOk(Verb.BANK_LIST, bankPage());
        connection.replyOk(Verb.DATA_EXAMS_GET, new DataExams(List.of(
                new DataExamRow("101101", "Algebra midterm", "11", "Algebra 11", "Dana Cohen",
                        2, NOW))));
        connection.replyOk(Verb.DATA_RESULTS_GET, new DataResults(List.of(reportRow())));
        connection.respondTo(Verb.REPORT_SUBJECTS_GET, request -> {
            ReportDimension dimension =
                    ((ReportSubjectsRequest) request.getPayload()).dimension();
            return Message.ok(request, new ReportSubjects(dimension,
                    dimension == ReportDimension.BY_COURSE
                            ? List.of(new ReportSubject("11", "Algebra 11", "Course 11", 2))
                            : List.of(new ReportSubject("2", "Dana Cohen", "dana.cohen", 2))));
        });
        connection.respondTo(Verb.REPORT_GET, request -> {
            ReportRequest ask = (ReportRequest) request.getPayload();
            ReportSubject subject = ask.dimension() == ReportDimension.BY_COURSE
                    ? new ReportSubject("11", "Algebra 11", "Course 11", 2)
                    : new ReportSubject("2", "Dana Cohen", "dana.cohen", 2);
            List<ReportRow> rows = List.of(reportRow());
            return Message.ok(request, new ReportResult(ask.dimension(), subject, rows,
                    ReportSummary.across(rows)));
        });
    }

    private void commonServer(FakeClientConnection connection) {
        connection.replyOk(Verb.NOTIFICATIONS_GET, new NotificationsPage(List.of(), 0));
    }

    // ===================== Fixture ========================================

    private static ReleaseRow liveRelease() {
        return new ReleaseRow(EXECUTION, VERSION, "Algebra midterm", "11", "Algebra 11",
                "4B7Q", NOW.minus(Duration.ofMinutes(5)), NOW.plus(Duration.ofHours(1)),
                0, 45, ReleaseState.LIVE, new MonitorCounts(8, 3, 0));
    }

    private static ExecutionMonitor monitor() {
        List<MonitorRow> rows = List.of(
                new MonitorRow(2001, "Maya Levi", AttemptState.IN_PROGRESS, NOW, null,
                        Duration.ofMinutes(25).toMillis(), 2, 3, null, null,
                        new AttentionSummary(2, 40_000, NOW)),
                new MonitorRow(2002, "Noam Bar", AttemptState.SUBMITTED, NOW,
                        NOW.plus(Duration.ofMinutes(20)), 0, 3, 3, 20, null),
                new MonitorRow(2003, "Ori Katz", AttemptState.IN_PROGRESS, NOW, null,
                        Duration.ofMinutes(10).toMillis(), 1, 3, null,
                        new IntegrityFlag("11", "Algebra 11", NOW)));
        return new ExecutionMonitor(EXECUTION, "Algebra midterm", "11", "4B7Q", true, NOW,
                NOW.plus(Duration.ofHours(2)), 0, 45, new MonitorCounts(3, 1, 0), rows);
    }

    private static ExecutionGradingSummary gradingSummary() {
        return new ExecutionGradingSummary(EXECUTION, "Algebra midterm", "11", "4B7Q",
                NOW, 8, 8, 8);
    }

    private static StudentGradeRow gradeRow(long gradeId, String name, int auto) {
        return new StudentGradeRow(gradeId, gradeId, name, auto, null, auto, GradeState.AUTO,
                null, null, null);
    }

    private static StudentGradeRow studentGrade() {
        return new StudentGradeRow(GRADE, 2001, "Maya Levi", 71, 71, 71, GradeState.APPROVED,
                null, "Strong work on the inequalities.", NOW, "Algebra midterm", "11",
                "Dana Cohen");
    }

    private static ExecutionResultRow closedSitting() {
        return new ExecutionResultRow(EXECUTION, "4B7Q", NOW, NOW.plusSeconds(7200),
                ExecutionState.CLOSED, 8, 8, true, false);
    }

    private static ResultStatistics statistics() {
        return new ResultStatistics(8, 72.5, 72.5, 17.5, 45, 100, 7, 0.875,
                List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));
    }

    private static ReportRow reportRow() {
        return new ReportRow(EXECUTION, "4B7Q", "Algebra midterm", "11", "Algebra 11",
                NOW, NOW.plusSeconds(7200), 8, statistics());
    }

    private static ExamListRow examListRow() {
        return new ExamListRow(900L, "110101", "11", "Algebra 11", "Algebra midterm", 3,
                List.of(new ExamVersionRow(9003L, 3, ApprovalState.DRAFT, "", 12, 90, NOW, 1),
                        new ExamVersionRow(9002L, 2, ApprovalState.REJECTED,
                                "Question 2 has two correct answers.", 12, 90, NOW, 1),
                        new ExamVersionRow(9001L, 1, ApprovalState.APPROVED, "", 10, 60,
                                NOW, 1)));
    }

    private static BankPage bankPage() {
        return new BankPage(List.of(
                new BankQuestionRow("11001", "11", "Algebra 11",
                        "What are the roots of x squared minus 5x plus 6?", "Equations",
                        Difficulty.EASY, 701L, 1, false, NOW),
                new BankQuestionRow("11002", "11", "Algebra 11",
                        "Factor the quadratic expression completely", "Equations",
                        Difficulty.MEDIUM, 702L, 2, false, NOW)),
                0, 50, 2, 1);
    }

    private static ApprovalRow pendingApproval() {
        return new ApprovalRow(CALCULUS_V1, "101201", "Calculus Midterm", "12", "Calculus 12",
                1, "Dana Cohen", NOW, 2, 60, ApprovalState.PENDING, "", false, 0);
    }

    private static ExamQuestion previewQuestion(int ordinal) {
        return new ExamQuestion(900 + ordinal, "1200" + ordinal, ordinal, 50,
                "What are the roots of x squared minus 5x plus 6?",
                "1 and 6", "2 and 3", "minus 2 and minus 3", "0 and 5", null);
    }

    /** Boots the app, attaches a scripted server, and enters the user's shell. */
    private ScreenManager signIn(LoginResult user, Consumer<FakeClientConnection> script) {
        interact(() -> new ClientApp().start(new Stage()));
        WaitForAsyncUtils.waitForFxEvents();

        ScreenManager manager = ScreenManager.getInstance();
        interact(() -> {
            FakeClientConnection connection = new FakeClientConnection("demo-server", 5555);
            try {
                connection.connect();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            connection.replyOk(Verb.LOGIN, user);
            connection.replyOk(Verb.LOGOUT, null);
            script.accept(connection);

            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            connection.setServerMessageHandler(dispatcher::dispatchIncoming);
            manager.setClient(connection);
            manager.setDispatcher(dispatcher);
            dispatcher.setPushListener(new PushEventBridge(manager.eventBus()));

            ShellBoot.enter(manager, user);
        });
        WaitForAsyncUtils.waitForFxEvents();
        return manager;
    }
}
