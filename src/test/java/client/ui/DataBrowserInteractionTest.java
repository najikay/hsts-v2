package client.ui;

import client.core.AppArgs;
import client.core.ClientApp;
import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.events.PushEventBridge;
import client.features.data.DataCopy;
import client.features.data.DataTab;
import client.features.login.ShellBoot;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.bank.BankListRequest;
import common.dto.bank.BankPage;
import common.dto.bank.BankQuestionRow;
import common.dto.bank.Difficulty;
import common.dto.report.DataExamRow;
import common.dto.report.DataExams;
import common.dto.report.DataResults;
import common.dto.report.ReportRow;
import common.dto.results.ResultStatistics;
import common.protocol.Message;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
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
 * Real-input interaction test for the principal's Data browser (E15.2 — F9.3, T-11).
 *
 * <p>What only a booted toolkit can show: that the three segments really swap which table is on
 * screen, that <b>typing in the filter box really narrows the rows</b>, and that a tab whose list
 * is empty really draws its own explanation. Every one of those is a wiring claim rather than a
 * logic claim, and the FX-free session tests cannot make any of them.
 *
 * <p>It also walks T-11.3 the only way a test can: it asserts that the screen holds no button
 * except its own tabs, which is what "look for any create, edit or delete control anywhere in
 * her shell" comes down to on the busiest screen the role has.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class DataBrowserInteractionTest extends ApplicationTest {

    private static final Instant SPRING = Instant.parse("2026-03-10T07:00:00Z");
    private static final Instant SUMMER = Instant.parse("2026-08-07T06:00:00Z");

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
        // Each test boots the app itself, as UiSmokeTest does.
    }

    @AfterEach
    void resetGlobalState() throws Exception {
        java.lang.reflect.Method reset = ScreenManager.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
        System.clearProperty(AppArgs.PROP_GALLERY);
    }

    // ===================== The wiring claims =============================

    @Test
    @DisplayName("⚑ the bank renders on open, through the verb E6 already served her")
    void questionsRenderOnOpen() {
        ScreenManager manager = signIn(this::everythingLoads);
        openData(manager);

        Set<String> cells = cellTexts(manager.scene(), DataTab.QUESTIONS);
        assertThat(cells)
                .as("the bank, school-wide: every course, not a teacher's slice (F9.3)")
                .contains("Q11001", "Q12001", "Solve the linear equation", "Evaluate the limit");
        assertThat(cells).contains("Algebra (11)", "Calculus (12)");
        assertThat(labelTexts(screen(manager.scene()))).contains("3 questions");
    }

    @Test
    @DisplayName("⚑ switching tab, then filtering, leaves one row on screen (real input)")
    void tabSwitchThenFilterLeavesOneRow() {
        ScreenManager manager = signIn(this::everythingLoads);
        openData(manager);

        clickOn(toggleNamed(manager.scene(), DataTab.RESULTS.segment()));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(cellTexts(manager.scene(), DataTab.RESULTS))
                .as("both closed sittings, newest first, with their frozen figures")
                .contains("Algebra quiz · 5150", "Algebra midterm · 4821", "72.5", "17.5",
                        "7 of 8 (87.5%)");

        clickOn(filterBox(manager.scene())).write("4821");
        WaitForAsyncUtils.waitForFxEvents();

        Set<String> cells = cellTexts(manager.scene(), DataTab.RESULTS);
        assertThat(cells).contains("Algebra midterm · 4821");
        assertThat(cells)
                .as("the other sitting is gone from the table, not merely hidden behind a panel")
                .doesNotContain("Algebra quiz · 5150");
        assertThat(labelTexts(screen(manager.scene())))
                .as("and the count line says the list is narrowed rather than short")
                .contains("1 of 2 sittings");
    }

    @Test
    @DisplayName("the Exams tab lists the school's catalogue, author included")
    void examsTabListsTheCatalogue() {
        ScreenManager manager = signIn(this::everythingLoads);
        openData(manager);

        clickOn(toggleNamed(manager.scene(), DataTab.EXAMS.segment()));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(cellTexts(manager.scene(), DataTab.EXAMS))
                .contains("101101", "Algebra midterm", "Dana Cohen", "v2 of 2",
                        "101201", "Calculus quiz", "Rina Barak", "v1");
    }

    @Test
    @DisplayName("a filter that matches nothing says so, rather than that the tab is empty")
    void filteredToNothingIsExplained() {
        ScreenManager manager = signIn(this::everythingLoads);
        openData(manager);

        clickOn(filterBox(manager.scene())).write("zzzz");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(labelTexts(table(manager.scene(), DataTab.QUESTIONS)))
                .contains(DataCopy.NO_MATCHES.title(), DataCopy.NO_MATCHES.hint())
                .as("the bank is not empty and the screen must not say it is")
                .doesNotContain(DataCopy.NO_QUESTIONS.title());
        assertThat(cellTexts(manager.scene(), DataTab.QUESTIONS))
                .as("and the rows really are gone rather than sitting behind the panel")
                .doesNotContain("Q11001");
    }

    @Test
    @DisplayName("a tab with nothing in it draws its own explanation, not a blank rectangle")
    void emptyTabIsExplained() {
        ScreenManager manager = signIn(connection -> {
            connection.replyOk(Verb.BANK_LIST, new BankPage(List.of(), 0,
                    BankListRequest.MAX_PAGE_SIZE, 0, 0));
            connection.replyOk(Verb.DATA_RESULTS_GET, DataResults.EMPTY);
        });
        openData(manager);

        assertThat(labelTexts(table(manager.scene(), DataTab.QUESTIONS)))
                .contains(DataCopy.NO_QUESTIONS.title(), DataCopy.NO_QUESTIONS.hint());

        clickOn(toggleNamed(manager.scene(), DataTab.RESULTS.segment()));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(labelTexts(table(manager.scene(), DataTab.RESULTS)))
                .contains(DataCopy.NO_RESULTS.title(), DataCopy.NO_RESULTS.hint());
    }

    @Test
    @DisplayName("⚑ T-11.3: the screen holds no control that could change anything")
    void nothingOnTheScreenWrites() {
        ScreenManager manager = signIn(this::everythingLoads);
        openData(manager);

        Node screen = screen(manager.scene());

        assertThat(screen.lookupAll(".button"))
                .as("not one push button on the busiest screen this role has (S-7)")
                .isEmpty();
        assertThat(screen.lookupAll(".toggle-button"))
                .as("the only toggles are the three tabs, and a tab changes nothing")
                .hasSize(DataTab.values().length);
        assertThat(screen.lookupAll(".text-field"))
                .as("the only editable field is the filter box, and it never leaves the client")
                .hasSize(1);
        assertThat(labelTexts(screen))
                .as("and the screen says so, so 'no buttons' cannot be read as 'not built yet'")
                .contains(DataCopy.READ_ONLY_NOTE);
    }

    // ===================== Fixture =======================================

    private static BankQuestionRow question(String id, String course, String courseName,
                                            String text, String topic, Difficulty difficulty) {
        return new BankQuestionRow(id, course, courseName, text, topic, difficulty, 1, false,
                SPRING);
    }

    /** SEED_CONTENT section 9.1's frozen record. */
    private static ResultStatistics seeded() {
        return new ResultStatistics(8, 72.5, 72.5, 17.5, 45, 100, 7, 0.875,
                List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2));
    }

    private static ResultStatistics quiet() {
        return new ResultStatistics(4, 65, 65, Math.sqrt(125), 50, 80, 3, 0.75,
                List.of(0, 0, 0, 0, 0, 1, 1, 1, 1, 0));
    }

    private void everythingLoads(FakeClientConnection connection) {
        connection.replyOk(Verb.BANK_LIST, new BankPage(List.of(
                question("11001", "11", "Algebra", "Solve the linear equation", "Equations",
                        Difficulty.EASY),
                question("11002", "11", "Algebra", "Factor the quadratic", "Equations",
                        Difficulty.MEDIUM),
                question("12001", "12", "Calculus", "Evaluate the limit", "Limits",
                        Difficulty.HARD)),
                0, BankListRequest.MAX_PAGE_SIZE, 3, 1));
        connection.replyOk(Verb.DATA_EXAMS_GET, new DataExams(List.of(
                new DataExamRow("101101", "Algebra midterm", "11", "Algebra", "Dana Cohen", 2,
                        SUMMER),
                new DataExamRow("101201", "Calculus quiz", "12", "Calculus", "Rina Barak", 1,
                        SPRING))));
        connection.replyOk(Verb.DATA_RESULTS_GET, new DataResults(List.of(
                new ReportRow(2, "5150", "Algebra quiz", "11", "Algebra", SUMMER,
                        SUMMER.plusSeconds(7200), 4, quiet()),
                new ReportRow(1, "4821", "Algebra midterm", "11", "Algebra", SPRING,
                        SPRING.plusSeconds(7200), 8, seeded()))));
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

    private void openData(ScreenManager manager) {
        interact(() -> manager.navigator().navigate(Routes.DATA.id(), NavParams.empty()));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static TextField filterBox(Scene scene) {
        Node box = scene.getRoot().lookup(".data-filter");
        assertThat(box).isInstanceOf(TextField.class);
        return (TextField) box;
    }

    private static ToggleButton toggleNamed(Scene scene, String text) {
        return scene.getRoot().lookupAll(".toggle-button").stream()
                .filter(ToggleButton.class::isInstance)
                .map(ToggleButton.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no toggle labelled " + text));
    }

    /** The screen root, so nothing on the shell around it can satisfy an assertion. */
    private static Node screen(Scene scene) {
        Node node = scene.getRoot().lookup(".principal-data");
        assertThat(node).as("the data browser is on screen").isNotNull();
        return node;
    }

    /**
     * One tab's table, by its own style class.
     *
     * <p>Scoped rather than screen-wide on purpose: all three tables live in the scene at once
     * and only one is managed, so an unscoped {@code .table-cell} lookup would happily satisfy
     * an assertion from the tab that is not showing.
     */
    private static Node table(Scene scene, DataTab tab) {
        String styleClass = switch (tab) {
            case QUESTIONS -> ".data-questions";
            case EXAMS -> ".data-exams";
            case RESULTS -> ".data-sittings";
        };
        Node node = screen(scene).lookup(styleClass);
        assertThat(node).as("the %s table is in the scene", tab).isNotNull();
        assertThat(node.isManaged()).as("the %s table is the one showing", tab).isTrue();
        return node;
    }

    /** Table cells are {@code Labeled}s with their own style class, not {@code .label} nodes. */
    private static Set<String> cellTexts(Scene scene, DataTab tab) {
        return table(scene, tab).lookupAll(".table-cell").stream()
                .filter(javafx.scene.control.Labeled.class::isInstance)
                .map(node -> ((javafx.scene.control.Labeled) node).getText())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** Every label under one node, so an assertion cannot be met by a hidden tab's panel. */
    private static Set<String> labelTexts(Node parent) {
        return parent.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }
}
