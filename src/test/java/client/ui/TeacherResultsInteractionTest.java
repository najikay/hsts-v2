package client.ui;

import client.core.ClientApp;
import client.core.FxTestHarness;
import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.events.PushEventBridge;
import client.features.login.ShellBoot;
import client.features.results.ResultsCopy;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.BackLink;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.grading.GradeState;
import common.dto.grading.StudentGradeRow;
import common.dto.results.ExamResultRow;
import common.dto.results.ExecutionResultRow;
import common.dto.results.ExecutionResults;
import common.dto.results.ExecutionResultsRequest;
import common.dto.results.ExecutionState;
import common.dto.results.ResultStatistics;
import common.dto.results.TeacherResults;
import common.protocol.Message;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
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
 * Real-input interaction test for the teacher's results screen (E14.2/E14.3b/E14.4 — F9.2).
 *
 * <p>What only a booted toolkit can show: that opening Results renders the six stat cards with
 * the <b>seeded</b> figures, that clicking the T-10 toggle really swaps the table for the
 * histogram, that the histogram draws bars from the stored deciles, and that the print toggle
 * drops the navigation rail.
 *
 * <p>The fixture is the seeded execution 4821 (mean 72.5, median 72.5, σ 17.5, 7 of 8 passed,
 * deciles over finals 45, 55, 60, 70, 75, 85, 90, 100), so this test doubles as the visual QA
 * baseline the gallery section was built against: the numbers on screen are the numbers in
 * §9.1 of the seed document.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class TeacherResultsInteractionTest extends ApplicationTest {

    private static final Instant OPENED = Instant.parse("2026-08-07T06:00:00Z");
    private static final Instant CLOSED = Instant.parse("2026-08-07T08:00:00Z");

    private static final long GRADED = 10;
    private static final long LIVE = 11;

    private static final LoginResult DANA = new LoginResult(1001, "dana.cohen", "Dana Cohen",
            Role.TEACHER, List.of(new CourseRef("11", "Algebra")), 0);

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

    @Test
    @DisplayName("⚑ the stat cards render the seeded execution's frozen figures (F9.2)")
    void statCardsShowTheSeededNumbers() {
        ScreenManager manager = signIn(this::everythingLoads);
        openResults(manager);

        Set<String> labels = labelTexts(manager.scene());
        assertThat(labels)
                .as("F9.2's six cards, by name")
                .contains("Average", "Median", "Std deviation", "Min / max", "Pass rate",
                        "Participants");
        assertThat(labels)
                .as("mean 72.5, median 72.5, sigma 17.5 — read from the frozen column, not recomputed")
                .contains("72.5", "17.5", "45 to 100", "8");
        assertThat(labels)
                .as("the pass rate reads as both halves")
                .contains("7 of 8 (87.5%)");
        assertThat(labels).contains("מבחן אמצע: אלגברה");
        assertThat(manager.scene().getRoot().lookup(".results-unfinished").isVisible())
                .as("a fully graded sitting shows no 'grading is not finished' panel")
                .isFalse();
    }

    @Test
    @DisplayName("the table lists the marked students with the adjusted marker (S-23)")
    void tableListsStudents() {
        ScreenManager manager = signIn(this::everythingLoads);
        openResults(manager);

        Set<String> cells = cellTexts(manager.scene());
        assertThat(cells).contains("Maya Levi", "Yael Azulay");
        assertThat(cells)
                .as("the one overridden row is marked as adjusted, and its scores differ")
                .contains("Adjusted", "45", "55");
        assertThat(cells)
                .as("the state column says whether the student can see it yet (C-3)")
                .contains("Approved");
        assertThat(labelTexts(manager.scene()))
                .as("the gap between who sat it and what is marked is stated, not left to counting")
                .contains("2 of 8 papers marked");
    }

    @Test
    @DisplayName("⚑ clicking Histogram swaps the table for the chart, and back again (T-10)")
    void toggleSwapsTheViews() {
        ScreenManager manager = signIn(this::everythingLoads);
        openResults(manager);

        Node chart = manager.scene().getRoot().lookup(".hsts-stat-chart");
        assertThat(chart).isNotNull();
        assertThat(chart.isVisible()).as("the table is what she lands on").isFalse();

        clickOn(toggleNamed(manager.scene(), "Histogram"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.scene().getRoot().lookup(".hsts-stat-chart").isVisible()).isTrue();
        assertThat(manager.scene().getRoot().lookup(".hsts-table-wrapper").isVisible()).isFalse();
        assertThat(manager.scene().getRoot().lookupAll(".stat-bar"))
                .as("the bars are drawn from the stored deciles")
                .isNotEmpty();

        clickOn(toggleNamed(manager.scene(), "Table"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.scene().getRoot().lookup(".hsts-table-wrapper").isVisible()).isTrue();
        assertThat(manager.scene().getRoot().lookup(".hsts-stat-chart").isVisible()).isFalse();
    }

    @Test
    @DisplayName("the histogram's own count/percent toggle still works inside the screen")
    void chartScaleToggleWorks() {
        ScreenManager manager = signIn(this::everythingLoads);
        openResults(manager);
        clickOn(toggleNamed(manager.scene(), "Histogram"));
        WaitForAsyncUtils.waitForFxEvents();

        clickOn(toggleNamed(manager.scene(), "Percent"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(labelTexts(manager.scene()))
                .as("the axis is relabelled without the bars being recomputed from scores")
                .anySatisfy(text -> assertThat(text).endsWith("%"));
    }

    @Test
    @DisplayName("the print layout drops the navigation rail (E14.4)")
    void printLayoutDropsTheRail() {
        ScreenManager manager = signIn(this::everythingLoads);
        openResults(manager);
        assertThat(manager.scene().getRoot().lookup(".results-exam-rail")).isNotNull();

        clickOn(toggleNamed(manager.scene(), "Print layout"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.scene().getRoot().lookup(".teacher-results").getStyleClass())
                .contains(ResultsCopy.PRINT_STYLE_CLASS);
        assertThat(manager.scene().getRoot().lookup(".results-exam-rail"))
                .as("navigation is exactly what a printed page does not need")
                .isNull();

        // The chrome print mode drops used to include the toggle that turned it on, so
        // there was no way out of the print view at all. The exit is the one control the
        // layout leaves alone, which is why it is not styled as a back link.
        Node exit = manager.scene().getRoot().lookup("." + BackLink.EXIT_STYLE_CLASS);
        assertThat(exit).as("print mode has to leave one control that leaves print mode").isNotNull();
        assertThat(((Button) exit).getText()).isEqualTo(ResultsCopy.PRINT_EXIT);

        clickOn(exit);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.scene().getRoot().lookup(".teacher-results").getStyleClass())
                .as("and pressing it really leaves")
                .doesNotContain(ResultsCopy.PRINT_STYLE_CLASS);
        assertThat(manager.scene().getRoot().lookup(".results-exam-rail")).isNotNull();
    }

    @Test
    @DisplayName("a sitting whose grading is unfinished says so calmly, with no zeros")
    void unfinishedGradingIsExplained() {
        ScreenManager manager = signIn(connection -> {
            connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of(
                    new ExamResultRow(1, "202101", "Java Fundamentals", "21", "Java",
                            List.of(liveSitting())))));
            connection.replyOk(Verb.RESULTS_EXECUTION_GET, new ExecutionResults(liveSitting(),
                    "Java Fundamentals", "21", "Java", List.of(), null));
        });
        openResults(manager);

        assertThat(manager.scene().getRoot().lookup(".results-unfinished").isVisible())
                .as("the calm explanation is on screen, not merely present in the graph")
                .isTrue();
        assertThat(labelTexts(manager.scene()))
                .contains(ResultsCopy.GRADING_UNFINISHED_TITLE, ResultsCopy.GRADING_UNFINISHED_HINT);
        assertThat(labelTexts(manager.scene()))
                .as("no card shows a zero standing in for a number nobody has computed")
                .doesNotContain("Average", "Pass rate");
    }

    @Test
    @DisplayName("a teacher who has written nothing is told, rather than shown a blank screen")
    void emptyStateIsExplained() {
        ScreenManager manager = signIn(connection ->
                connection.replyOk(Verb.RESULTS_EXAMS_GET, TeacherResults.EMPTY));
        openResults(manager);

        assertThat(labelTexts(manager.scene()))
                .contains(ResultsCopy.NO_EXAMS_TITLE, ResultsCopy.NO_EXAMS_HINT);
    }

    @Test
    @org.junit.jupiter.api.Timeout(60)
    @DisplayName("⚑ S3, U-61: switching sittings through the real picker, twice, keeps working")
    void realPickerClicksSwitchSittings() {
        // The gesture the harness never made here: the ComboBox's REAL popup. The render
        // used to hand the picker a brand-new items list on every render, including
        // renders fired from inside the picker's own selection change - the exact storm
        // U-61 fixed on the reports screen next door.
        ScreenManager manager = signIn(this::everythingLoads);
        openResults(manager);
        await("the graded sitting to open first", () ->
                labelTexts(manager.scene()).contains("7 of 8 (87.5%)"));

        for (int round = 0; round < 2; round++) {
            pickSittingFromPopup(manager, "2075");
            await("the unfinished-grading panel", () ->
                    manager.scene().getRoot().lookup(".results-unfinished").isVisible());

            pickSittingFromPopup(manager, "4821");
            await("the frozen figures back on screen", () ->
                    labelTexts(manager.scene()).contains("7 of 8 (87.5%)")
                            && !manager.scene().getRoot()
                                    .lookup(".results-unfinished").isVisible());
        }
    }

    /** Opens the execution picker's real popup and clicks the sitting, as a teacher does. */
    private void pickSittingFromPopup(ScreenManager manager, String code) {
        Node picker = manager.scene().getRoot().lookup(".combo-box");
        assertThat(picker).as("the execution picker").isNotNull();
        clickOn(picker);
        WaitForAsyncUtils.waitForFxEvents();
        Node entry = lookup(".list-cell").queryAll().stream()
                .filter(node -> node instanceof javafx.scene.control.ListCell<?> cell
                        && cell.getText() != null && cell.getText().contains(code)
                        && !insideComboBox(node))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no popup entry for " + code));
        clickOn(entry);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** The button cell lives inside the ComboBox; the popup's cells do not. */
    private static boolean insideComboBox(Node node) {
        for (Node parent = node; parent != null; parent = parent.getParent()) {
            if (parent instanceof javafx.scene.control.ComboBox<?>) {
                return true;
            }
        }
        return false;
    }

    /** Polls rather than sleeps, the ReportsInteractionTest cure for runner speed. */
    private void await(String what, java.util.concurrent.Callable<Boolean> condition) {
        try {
            WaitForAsyncUtils.waitFor(10, java.util.concurrent.TimeUnit.SECONDS, condition);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("Timed out waiting for " + what, e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ===================== Fixture =======================================

    private void everythingLoads(FakeClientConnection connection) {
        connection.replyOk(Verb.RESULTS_EXAMS_GET, new TeacherResults(List.of(
                new ExamResultRow(1, "101101", "מבחן אמצע: אלגברה", "11", "אלגברה",
                        List.of(liveSitting(), gradedSitting())))));
        connection.respondTo(Verb.RESULTS_EXECUTION_GET, request -> {
            ExecutionResultsRequest ask = (ExecutionResultsRequest) request.getPayload();
            return Message.ok(request, ask.executionId() == GRADED
                    ? gradedResults() : new ExecutionResults(liveSitting(), "מבחן אמצע: אלגברה",
                            "11", "אלגברה", List.of(), null));
        });
    }

    private static ExecutionResultRow gradedSitting() {
        return new ExecutionResultRow(GRADED, "4821", OPENED, CLOSED, ExecutionState.CLOSED,
                8, 8, true, false);
    }

    private static ExecutionResultRow liveSitting() {
        return new ExecutionResultRow(LIVE, "2075", CLOSED, CLOSED.plusSeconds(7200),
                ExecutionState.LIVE, 3, 0, false, false);
    }

    /** §9.1's frozen record and two of its eight rows, including the one override. */
    private static ExecutionResults gradedResults() {
        ResultStatistics stats = new ResultStatistics(8, 72.5, 72.5, 17.5, 45, 100, 7, 0.875,
                List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));
        List<StudentGradeRow> rows = List.of(
                new StudentGradeRow(1, 2001, "Maya Levi", 60, null, 60, GradeState.APPROVED,
                        null, null, CLOSED),
                new StudentGradeRow(2, 2002, "Yael Azulay", 45, 55, 55, GradeState.APPROVED,
                        "ניתן ניקוד חלקי.", null, CLOSED));
        return new ExecutionResults(gradedSitting(), "מבחן אמצע: אלגברה", "11", "אלגברה",
                rows, stats);
    }

    private ScreenManager signIn(Consumer<FakeClientConnection> script) {
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
            connection.replyOk(Verb.LOGIN, DANA);
            connection.replyOk(Verb.LOGOUT, null);
            script.accept(connection);

            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            connection.setServerMessageHandler(dispatcher::dispatchIncoming);
            manager.setClient(connection);
            manager.setDispatcher(dispatcher);
            dispatcher.setPushListener(new PushEventBridge(manager.eventBus()));

            ShellBoot.enter(manager, DANA);
        });
        WaitForAsyncUtils.waitForFxEvents();
        return manager;
    }

    private void openResults(ScreenManager manager) {
        interact(() -> manager.navigator().navigate(Routes.RESULTS.id(), NavParams.empty()));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static ToggleButton toggleNamed(Scene scene, String text) {
        return scene.getRoot().lookupAll(".toggle-button").stream()
                .filter(ToggleButton.class::isInstance)
                .map(ToggleButton.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no toggle labelled " + text));
    }

    /** Table cells are {@code Labeled}s with their own style class, not {@code .label} nodes. */
    private static Set<String> cellTexts(Scene scene) {
        return scene.getRoot().lookupAll(".table-cell").stream()
                .filter(javafx.scene.control.Labeled.class::isInstance)
                .map(node -> ((javafx.scene.control.Labeled) node).getText())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static Set<String> labelTexts(Scene scene) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }
}
