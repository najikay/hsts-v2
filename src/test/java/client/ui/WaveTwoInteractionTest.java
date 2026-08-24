package client.ui;

import client.core.ClientApp;
import client.core.FxTestHarness;
import client.core.Routes;
import client.core.ScreenManager;
import client.events.PushEventBridge;
import client.features.home.DashboardCopy;
import client.features.login.ShellBoot;
import client.features.results.MyGradesCopy;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.Kicker;
import client.ui.components.logic.KickerText;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.exam.ExecutionMonitor;
import common.dto.exam.MonitorCounts;
import common.dto.grading.GradeState;
import common.dto.grading.GradingQueue;
import common.dto.grading.MyGrades;
import common.dto.grading.StudentGradeRow;
import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import common.dto.notify.NotificationsPage;
import common.dto.release.ReleaseList;
import common.dto.release.ReleaseRow;
import common.dto.release.ReleaseState;
import common.dto.results.TeacherResults;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wave-2 remodel, on a booted toolkit (UI wave 2).
 *
 * <p>Everything wave 2 decides is decided in an FX-free class and tested there:
 * what a card's kicker says, what the summary sentence is, which bar is the
 * mode, how full the ring is. What those tests cannot answer is whether the
 * decisions <b>reach the screen</b> — whether the view actually builds the nodes
 * the design asked for, in the surfaces the design named. A remodel is exactly
 * the kind of change that can be complete in the model and half-applied in the
 * views, and it is exactly the kind nobody notices because every screen still
 * renders something.
 *
 * <p>So these are structural assertions rather than pixel ones: the kickers
 * exist, the cards are cards, the popover has badges, the grid has a slot at the
 * end. Colour, spacing and the two palettes go on the manual checklist, where a
 * human eye is the right instrument.
 *
 * <p>Same escape hatch as the other UI tests:
 * {@code ./mvnw verify -Dhsts.uitests=false}.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class WaveTwoInteractionTest extends ApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-24T09:00:00Z");

    private static final LoginResult DANA = new LoginResult(1001, "dana.cohen", "Dana Cohen",
            Role.TEACHER, List.of(new CourseRef("11", "Algebra")), 1);

    private static final LoginResult MAYA = new LoginResult(1010, "maya.levi", "Maya Levi",
            Role.STUDENT, List.of(new CourseRef("11", "Algebra")), 0);

    private static final ReleaseRow LIVE = new ReleaseRow(4821, 9001, "Algebra midterm", "11",
            "Algebra", "4B7Q", NOW, NOW.plusSeconds(3600), 0, 60, ReleaseState.LIVE,
            new MonitorCounts(8, 3, 0));

    private static final ExecutionMonitor MONITOR = new ExecutionMonitor(4821, "Algebra midterm",
            "11", "4B7Q", true, NOW, NOW.plusSeconds(1800), 0, 60,
            new MonitorCounts(8, 3, 0), List.of());

    private static final StudentGradeRow GRADE = new StudentGradeRow(1, 1010, "Maya Levi",
            71, 71, 71, GradeState.APPROVED, null, null, NOW, "Algebra midterm", "11");

    private static final NotificationDto UNREAD = new NotificationDto(1L,
            NotificationType.GRADE_PUBLISHED, "Your grade was published",
            "Algebra midterm is available in My Grades.", NavRef.to("grades", 1L), NOW, null);

    private static final NotificationDto READ = new NotificationDto(2L,
            NotificationType.APPROVAL_REJECTED, "Your exam was sent back",
            "Rina Barak asked for a change.", NavRef.none(), NOW.minusSeconds(3600), NOW);

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
        // Each test boots the app itself, as UiSmokeTest does.
    }

    @AfterEach
    void resetGlobalState() {
        FxTestHarness.resetGlobalState();
    }

    // ===================== The dashboard ==================================

    @Test
    @DisplayName("⚑ the teacher dashboard renders four cards, each with a kicker and a link")
    void theDashboardIsRemodelled() {
        ScreenManager manager = signIn(DANA, this::teacherData);

        Set<Node> cards = manager.scene().getRoot().lookupAll(".hsts-dashboard-card");
        assertThat(cards)
                .as("LIVE NOW, AWAITING GRADING, NEXT RELEASE, LAST CLOSED SITTING")
                .hasSize(4);

        // A kicker per card. Looked up by the component's own style class, so a
        // card built without going through Kicker fails here rather than looking
        // subtly different on screen.
        assertThat(manager.scene().getRoot().lookupAll("." + Kicker.STYLE_CLASS))
                .as("every card carries the small uppercase label above its number")
                .hasSizeGreaterThanOrEqualTo(4);

        assertThat(manager.scene().getRoot().lookupAll(".card-link"))
                .as("every card names the screen it opens")
                .hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("⚑ a kicker on screen reads as the copy constant, uppercased")
    void kickersCarryTheCopy() {
        ScreenManager manager = signIn(DANA, this::teacherData);

        List<String> kickers = manager.scene().getRoot().lookupAll("." + Kicker.STYLE_CLASS)
                .stream()
                .filter(Label.class::isInstance)
                .map(node -> KickerText.untrack(((Label) node).getText()))
                .collect(Collectors.toList());

        // The transform is faked tracking, so the rendered string is not the
        // constant. This is the assertion that ties the two together on a real
        // screen: strip the tracking and the words must come back exactly.
        assertThat(kickers).contains(KickerText.plain(DashboardCopy.LIVE_KICKER),
                KickerText.plain(DashboardCopy.GRADING_KICKER),
                KickerText.plain(DashboardCopy.NEXT_RELEASE_KICKER),
                KickerText.plain(DashboardCopy.LAST_CLOSED_KICKER));
    }

    @Test
    @DisplayName("the greeting carries a live summary sentence under it")
    void theGreetingHasASummary() {
        ScreenManager manager = signIn(DANA, this::teacherData);

        Node summary = manager.scene().getRoot().lookup(".greeting-summary");
        assertThat(summary).as("the wave-2 header's middle line").isNotNull();
        assertThat(((Label) summary).getText())
                .as("built from the cards' own numbers, so it says what is happening")
                .isNotBlank()
                .endsWith(".");
    }

    @Test
    @DisplayName("a live sitting draws the pulsing dot and the progress bar")
    void theLiveCardIsRich() {
        ScreenManager manager = signIn(DANA, this::teacherData);

        assertThat(manager.scene().getRoot().lookup(".live-dot"))
                .as("the green dot the canvas puts beside a running sitting").isNotNull();
        assertThat(manager.scene().getRoot().lookup(".live-progress-fill"))
                .as("the slim submitted-so-far bar").isNotNull();
    }

    @Test
    @DisplayName("⚑ a card's number is the same node across renders, so it can roll")
    void theRollsOutliveTheCardsThatHoldThem() {
        // The grid is rebuilt from scratch on every settle. A roll built inside
        // that rebuild would be a brand-new node already showing the new value,
        // and the 240ms roll the motion spec asks for would never once play —
        // the feature would be present in the code and absent from the app.
        // This is the assertion that the mechanism keeping them alive works.
        ScreenManager manager = signIn(DANA, this::teacherData);
        Node before = manager.scene().getRoot().lookup(".hsts-number-roll");
        assertThat(before).isNotNull();

        interact(() -> manager.navigator().navigate(Routes.RELEASES.id()));
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> manager.navigator().navigate(Routes.HOME_TEACHER.id()));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.scene().getRoot().lookup(".hsts-number-roll"))
                .as("re-rendered into a fresh card, but the same roll")
                .isSameAs(before);
    }

    // ===================== The notification popover =======================

    @Test
    @DisplayName("⚑ the popover renders an icon badge per row and marks the unread one")
    void thePopoverIsRemodelled() {
        ScreenManager manager = signIn(DANA, this::teacherData);

        clickOn(manager.shell().bell());
        WaitForAsyncUtils.waitForFxEvents();

        Node panel = manager.scene().getRoot().lookup(".hsts-notification-panel");
        assertThat(panel).isNotNull();

        assertThat(panel.lookupAll(".row-badge"))
                .as("the 34px rounded square behind each row's type icon")
                .hasSize(2);
        assertThat(panel.lookupAll(".row-unread-dot"))
                .as("exactly one of the two fixtures is unread")
                .hasSize(1);
        assertThat(panel.lookupAll(".panel-row.unread"))
                .as("the tint and the dot are two signals for one state, not one")
                .hasSize(1);
    }

    @Test
    @DisplayName("a row's badge is tinted by what kind of news it is")
    void badgesAreTintedByType() {
        ScreenManager manager = signIn(DANA, this::teacherData);

        clickOn(manager.shell().bell());
        WaitForAsyncUtils.waitForFxEvents();

        Node panel = manager.scene().getRoot().lookup(".hsts-notification-panel");
        List<String> tones = panel.lookupAll(".row-badge").stream()
                .flatMap(node -> node.getStyleClass().stream())
                .filter(styleClass -> List.of("ok", "danger", "accent").contains(styleClass))
                .toList();

        // A published grade is good news; an exam sent back is not. Both are in
        // the fixture precisely so a single tone cannot pass this.
        assertThat(tones).contains("ok", "danger");
    }

    // ===================== Student My Grades ==============================

    @Test
    @DisplayName("⚑ My Grades renders cards, not a table, and closes the grid with a slot")
    void myGradesIsACardGrid() {
        ScreenManager manager = signIn(MAYA, this::studentData);
        interact(() -> manager.navigator().navigate(Routes.MY_GRADES.id()));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.scene().getRoot().lookupAll(".grade-card"))
                .as("one card per published grade")
                .hasSize(1);
        assertThat(manager.scene().getRoot().lookup(".grade-slot"))
                .as("the dashed placeholder that says what puts the next card there")
                .isNotNull();
    }

    @Test
    @DisplayName("the hero band carries the term-average ring")
    void theHeroCarriesTheRing() {
        ScreenManager manager = signIn(MAYA, this::studentData);
        interact(() -> manager.navigator().navigate(Routes.MY_GRADES.id()));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.scene().getRoot().lookup(".grades-hero-host"))
                .as("the accent band").isNotNull();
        assertThat(manager.scene().getRoot().lookup(".hsts-progress-ring"))
                .as("the 84px ring inside it").isNotNull();
    }

    @Test
    @DisplayName("⚑ the next-exam slot is absent, because no verb fills it")
    void theNextExamSlotStaysHidden() {
        // Hidden gracefully rather than rendered empty: an unfilled slot on a
        // hero band is a hole, and this one cannot be filled by anything on the
        // wire today (MyGradesSession.nextExam records why).
        ScreenManager manager = signIn(MAYA, this::studentData);
        interact(() -> manager.navigator().navigate(Routes.MY_GRADES.id()));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.scene().getRoot().lookup(".grades-next-exam")).isNull();
    }

    @Test
    @DisplayName("a card opens the marked paper on one click, as the row used to")
    void aCardOpensThePaper() {
        ScreenManager manager = signIn(MAYA, this::studentData);
        interact(() -> manager.navigator().navigate(Routes.MY_GRADES.id()));
        WaitForAsyncUtils.waitForFxEvents();

        // The view swap must not cost the drill-in F-8 gave the row.
        clickOn(manager.scene().getRoot().lookup(".grade-card"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.navigator().currentRouteId())
                .isEqualTo(Routes.CHECKED_FORM.id());
    }

    @Test
    @DisplayName("the empty slot says what fills it, in the copy catalogue's own words")
    void theSlotSaysWhatFillsIt() {
        ScreenManager manager = signIn(MAYA, this::studentData);
        interact(() -> manager.navigator().navigate(Routes.MY_GRADES.id()));
        WaitForAsyncUtils.waitForFxEvents();

        Node slot = manager.scene().getRoot().lookup(".grade-slot");
        assertThat(slot.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .toList())
                .anyMatch(MyGradesCopy.EMPTY_SLOT_HINT::equals);
    }

    // ===================== Fixture =======================================

    private void teacherData(FakeClientConnection connection) {
        connection.replyOk(Verb.RELEASE_LIST_GET, new ReleaseList(NOW, List.of(LIVE)));
        connection.replyOk(Verb.GRADING_QUEUE_GET, new GradingQueue(List.of()));
        connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of()));
        connection.replyOk(Verb.EXECUTION_MONITOR_GET, MONITOR);
        connection.replyOk(Verb.NOTIFICATIONS_GET,
                new NotificationsPage(List.of(UNREAD, READ), 1));
    }

    private void studentData(FakeClientConnection connection) {
        connection.replyOk(Verb.MY_GRADES_GET, new MyGrades(List.of(GRADE)));
        connection.replyOk(Verb.NOTIFICATIONS_GET, new NotificationsPage(List.of(), 0));
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
