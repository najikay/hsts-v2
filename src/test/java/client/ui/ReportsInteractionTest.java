package client.ui;

import client.core.AppArgs;
import client.core.ClientApp;
import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.events.PushEventBridge;
import client.features.login.ShellBoot;
import client.features.reports.ReportsCopy;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.report.ReportDimension;
import common.dto.report.ReportRequest;
import common.dto.report.ReportResult;
import common.dto.report.ReportRow;
import common.dto.report.ReportSubject;
import common.dto.report.ReportSubjects;
import common.dto.report.ReportSubjectsRequest;
import common.dto.report.ReportSummary;
import common.dto.results.ResultStatistics;
import common.protocol.Message;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
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
 * Real-input interaction test for the principal's Reports screen (E15.4 — F9.4).
 *
 * <p>What only a booted toolkit can show: that the dimension segments really switch the subject
 * picker, that picking a subject really fills the rows table with the seeded figures, that
 * <b>clicking a row really repoints the chart</b>, and that the print toggle really drops the
 * pickers. Every one of those is a wiring claim rather than a logic claim, and the FX-free
 * session tests cannot make any of them.
 *
 * <p>The fixture is the seeded execution 4821 (mean 72.5, median 72.5, σ 17.5, 7 of 8 passed)
 * beside a second, quieter sitting, so the pooled figures on screen — average 70, median band
 * 70-79, σ 16.1, 10 of 12 — are the ones hand-computed in {@code ReportDtoTest}.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class ReportsInteractionTest extends ApplicationTest {

    private static final Instant SPRING = Instant.parse("2026-03-10T07:00:00Z");
    private static final Instant SUMMER = Instant.parse("2026-08-07T06:00:00Z");

    private static final LoginResult AVIA = new LoginResult(1, "principal.avia", "Avia Shalev",
            Role.PRINCIPAL, List.of(), 0);

    private static final ReportSubject DANA = new ReportSubject("2", "Dana Cohen", "dana.cohen", 2);
    private static final ReportSubject RINA = new ReportSubject("3", "Rina Barak", "rina.barak", 0);
    private static final ReportSubject ALGEBRA = new ReportSubject("11", "Algebra", "Course 11", 2);

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
    void resetGlobalState() throws Exception {
        java.lang.reflect.Method reset = ScreenManager.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
        System.clearProperty(AppArgs.PROP_GALLERY);
    }

    // ===================== The four wiring claims ========================

    @Test
    @DisplayName("⚑ the rows table renders the seeded sittings, and the summary is the pooled one")
    void rowsRenderSeededValues() {
        ScreenManager manager = signIn(this::everythingLoads);
        openReports(manager);

        Set<String> cells = cellTexts(manager.scene());
        assertThat(cells)
                .as("both sittings, oldest first, named by exam and code")
                .contains("Algebra midterm · 4821", "Algebra quiz · 5150");
        assertThat(cells)
                .as("the frozen figures of execution 4821, read and not recomputed")
                .contains("72.5", "17.5", "7 of 8 (87.5%)");

        Set<String> labels = labelTexts(manager.scene());
        assertThat(labels)
                .as("F9.4's cross-row cards, by name")
                .contains("Sittings", "Average", "Median", "Std deviation", "Pass rate",
                        "Results");
        assertThat(labels)
                .as("the hand-computed pooled figures: 840 over 12 is 70, sigma 16.1")
                .contains("70", "70-79", "16.1", "10 of 12 (83.3%)");
        assertThat(labels)
                .as("the hint that stops a reader checking the average against the column")
                .contains("weighted by participants");
        assertThat(labels).contains("12 students across 2 sittings");
    }

    @Test
    @DisplayName("⚑ picking a dimension repopulates the subject picker with that dimension's list")
    void dimensionSwitchesTheSubjectPicker() {
        ScreenManager manager = signIn(this::everythingLoads);
        openReports(manager);

        assertThat(subjectPicker(manager.scene()).getItems())
                .extracting(ReportSubject::label)
                .containsExactly("Rina Barak", "Dana Cohen");
        assertThat(labelTexts(manager.scene())).contains("Dana Cohen · by teacher");

        clickOn(toggleNamed(manager.scene(), ReportDimension.BY_COURSE.segment()));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(subjectPicker(manager.scene()).getItems())
                .extracting(ReportSubject::label)
                .containsExactly("Algebra");
        assertThat(labelTexts(manager.scene()))
                .as("the heading follows the segment rather than lagging a request behind it")
                .contains("Algebra · by course");
    }

    @Test
    @DisplayName("⚑ picking a subject from the picker runs its report")
    void pickingASubjectRunsItsReport() {
        ScreenManager manager = signIn(this::everythingLoads);
        openReports(manager);

        // Rina has nothing to compare: the picker opened past her, and choosing her must
        // produce the explained empty state rather than a blank table.
        ComboBox<ReportSubject> picker = subjectPicker(manager.scene());
        interact(() -> picker.getSelectionModel().select(RINA));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(labelTexts(manager.scene()))
                .contains(ReportsCopy.NO_EXECUTIONS.title(), ReportsCopy.NO_EXECUTIONS.hint());
        assertThat(manager.scene().getRoot().lookup(".reports-summary").isVisible())
                .as("no card prints a zero standing in for a number nobody has computed")
                .isFalse();
        assertThat(cellTexts(manager.scene()))
                .as("and the table really is empty, rather than holding the previous subject's "
                        + "rows behind an empty-state panel")
                .doesNotContain("Algebra midterm · 4821");
    }

    @Test
    @DisplayName("⚑ clicking a row repoints the chart at that sitting (real input)")
    void selectingARowUpdatesTheChart() {
        ScreenManager manager = signIn(this::everythingLoads);
        openReports(manager);

        Node chart = manager.scene().getRoot().lookup(".hsts-stat-chart");
        assertThat(chart).isNotNull();
        assertThat(labelTexts(manager.scene()))
                .as("the report opens on its most recent sitting")
                .contains("Algebra quiz · 5150");

        clickOn(cellWithText(manager.scene(), "Algebra midterm · 4821"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(chartHeading(manager.scene()))
                .as("the chart heading follows the selected row")
                .isEqualTo("Algebra midterm · 4821");
        assertThat(manager.scene().getRoot().lookupAll(".stat-bar"))
                .as("the bars are drawn from that row's stored deciles")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the print layout drops the pickers (E15.4)")
    void printLayoutDropsThePickers() {
        ScreenManager manager = signIn(this::everythingLoads);
        openReports(manager);
        assertThat(manager.scene().getRoot().lookup(".reports-dimension-toggle")).isNotNull();

        clickOn(toggleNamed(manager.scene(), "Print layout"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.scene().getRoot().lookup(".principal-reports").getStyleClass())
                .contains(ReportsCopy.PRINT_STYLE_CLASS);
        assertThat(manager.scene().getRoot().lookup(".reports-dimension-toggle").isVisible())
                .as("navigation is exactly what a printed page does not need")
                .isFalse();
        assertThat(labelTexts(manager.scene()))
                .as("the comparison itself stays: a printed page about nobody is worse than none")
                .contains("Dana Cohen · by teacher");
    }

    @Test
    @DisplayName("a dimension with no subjects at all is explained rather than left blank")
    void emptyDimensionIsExplained() {
        ScreenManager manager = signIn(connection ->
                connection.respondTo(Verb.REPORT_SUBJECTS_GET, request -> Message.ok(request,
                        new ReportSubjects(
                                ((ReportSubjectsRequest) request.getPayload()).dimension(),
                                List.of()))));
        openReports(manager);

        assertThat(labelTexts(manager.scene()))
                .contains(ReportsCopy.NO_SUBJECTS.title(), ReportsCopy.NO_SUBJECTS.hint());
    }

    // ===================== Fixture =======================================

    /** SEED_CONTENT section 9.1's frozen record. */
    private static ResultStatistics seeded() {
        return new ResultStatistics(8, 72.5, 72.5, 17.5, 45, 100, 7, 0.875,
                List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));
    }

    /** Scores 50, 60, 70, 80. */
    private static ResultStatistics quiet() {
        return new ResultStatistics(4, 65, 65, Math.sqrt(125), 50, 80, 3, 0.75,
                List.of(0, 0, 0, 0, 0, 1, 1, 1, 1, 0));
    }

    private static List<ReportRow> twoSittings() {
        return List.of(
                new ReportRow(1, "4821", "Algebra midterm", "11", "Algebra", SPRING,
                        SPRING.plusSeconds(7200), 8, seeded()),
                new ReportRow(2, "5150", "Algebra quiz", "11", "Algebra", SUMMER,
                        SUMMER.plusSeconds(7200), 4, quiet()));
    }

    private void everythingLoads(FakeClientConnection connection) {
        connection.respondTo(Verb.REPORT_SUBJECTS_GET, request -> {
            ReportDimension dimension =
                    ((ReportSubjectsRequest) request.getPayload()).dimension();
            return Message.ok(request, new ReportSubjects(dimension,
                    dimension == ReportDimension.BY_COURSE
                            ? List.of(ALGEBRA)
                            : List.of(RINA, DANA)));
        });
        connection.respondTo(Verb.REPORT_GET, request -> {
            ReportRequest ask = (ReportRequest) request.getPayload();
            ReportSubject subject = ask.dimension() == ReportDimension.BY_COURSE
                    ? ALGEBRA : ask.subjectId().equals(RINA.id()) ? RINA : DANA;
            List<ReportRow> rows = subject.hasNothingToReport() ? List.of() : twoSittings();
            return Message.ok(request, new ReportResult(ask.dimension(), subject, rows,
                    ReportSummary.across(rows)));
        });
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
            connection.replyOk(Verb.LOGIN, AVIA);
            connection.replyOk(Verb.LOGOUT, null);
            script.accept(connection);

            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            connection.setServerMessageHandler(dispatcher::dispatchIncoming);
            manager.setClient(connection);
            manager.setDispatcher(dispatcher);
            dispatcher.setPushListener(new PushEventBridge(manager.eventBus()));

            ShellBoot.enter(manager, AVIA);
        });
        WaitForAsyncUtils.waitForFxEvents();
        return manager;
    }

    private void openReports(ScreenManager manager) {
        interact(() -> manager.navigator().navigate(Routes.REPORTS.id(), NavParams.empty()));
        WaitForAsyncUtils.waitForFxEvents();
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<ReportSubject> subjectPicker(Scene scene) {
        Node picker = scene.getRoot().lookup(".reports-subject-picker");
        assertThat(picker).isInstanceOf(ComboBox.class);
        return (ComboBox<ReportSubject>) picker;
    }

    private static ToggleButton toggleNamed(Scene scene, String text) {
        return scene.getRoot().lookupAll(".toggle-button").stream()
                .filter(ToggleButton.class::isInstance)
                .map(ToggleButton.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no toggle labelled " + text));
    }

    private static Node cellWithText(Scene scene, String text) {
        return scene.getRoot().lookupAll(".table-cell").stream()
                .filter(javafx.scene.control.Labeled.class::isInstance)
                .filter(node -> text.equals(((javafx.scene.control.Labeled) node).getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no table cell reading " + text));
    }

    private static String chartHeading(Scene scene) {
        Node heading = scene.getRoot().lookup(".chart-heading");
        assertThat(heading).isInstanceOf(Label.class);
        return ((Label) heading).getText();
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
