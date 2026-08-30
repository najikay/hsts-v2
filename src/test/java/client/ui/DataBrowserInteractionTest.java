package client.ui;

import client.core.ClientApp;
import client.core.FxTestHarness;
import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.events.PushEventBridge;
import client.features.bank.BankCopy;
import client.features.data.DataCopy;
import client.features.data.DataDetailCopy;
import client.features.data.DataTab;
import client.features.login.ShellBoot;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.bank.BankListRequest;
import common.dto.bank.BankPage;
import common.dto.bank.BankQuestionRow;
import common.dto.approval.ApprovalRow;
import common.dto.approval.ApprovalState;
import common.dto.approval.ExamPreview;
import common.dto.approval.PreviewAnswerRow;
import common.dto.approval.TeacherOnlyBlock;
import common.dto.bank.Difficulty;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionVersionDetail;
import common.dto.bank.VersionHistory;
import common.dto.exam.ExamQuestion;
import common.dto.report.DataExamRow;
import common.dto.report.DataExams;
import common.dto.report.DataResults;
import common.dto.report.ReportRow;
import common.dto.results.ResultStatistics;
import common.protocol.Message;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
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
 *
 * <p><b>Since 2026-08-30 (live session, U-44) it walks the three screens the rows open</b>, one
 * case per tab: click a row, and the detail screen is on the scene with the content that tab
 * promised and no control on it that writes. That is the other half of T-11.3 — "anywhere in her
 * shell" now includes three more screens — and it is also the wiring claim the FX-free session
 * tests cannot make: that the click really navigates and the parameter really arrives.
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
    void resetGlobalState() {
        FxTestHarness.resetGlobalState();
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

    // ===================== The rows open (U-44, 2026-08-30) ===============

    /**
     * Every label a mutating control in this application wears.
     *
     * <p>The T-11.3 assertion on the three detail screens is phrased against these rather than
     * against "no button at all": a detail screen may legitimately carry a control that changes
     * nothing (the histogram's Count/Percent toggle does), and a rule that banned every button
     * would either fail on one of those or be quietly relaxed later. What must never appear is a
     * control that writes, and these are what the app calls them.
     */
    private static final Set<String> MUTATING_LABELS = Set.of(
            "Edit", "Edit question", "Delete", "Delete question", "New question", "New exam",
            "Approve", "Send back", "Reject", "Save", "Save draft", "Submit",
            "Submit for approval", "Release", "Cancel sitting", "Add time", "Publish");

    @Test
    @DisplayName("⚑ a Questions row opens the question, its key and its history, and nothing else")
    void aQuestionRowOpensTheQuestion() {
        ScreenManager manager = signIn(this::everythingLoads);
        openData(manager);

        clickOn(firstRow(manager.scene(), DataTab.QUESTIONS));
        WaitForAsyncUtils.waitForFxEvents();

        Node screen = detail(manager.scene(), ".principal-data-question");
        Set<String> labels = labelTexts(screen);
        assertThat(labels)
                .as("the bank's own detail rendering: the id, the stem, the four options")
                .contains("Question Q11001", "#11001", "Solve the linear equation",
                        "x = 1", "x = 2", "x = 3", "x = 4");
        assertThat(labels)
                .as("she may see the key (F9.3, QuestionDetail's licence of 2026-08-21)")
                .contains("Correct");
        assertThat(labels)
                .as("and the version history beside it")
                .contains(DataDetailCopy.HISTORY_TITLE)
                .anySatisfy(text -> assertThat(text).startsWith("Version 2"));
        assertNothingMutates(screen);

        // U-50 reaches this screen through the shared renderer, and the point of the sharing is
        // that it does. The principal reads the bank to see what was entered (F9.3), so a
        // history that named v1 without showing it left her the same half-answer it left a
        // teacher (2026-08-30, Findings.txt, U-50).
        assertThat(labels)
                .as("collapsed to begin with, so the timeline is still a timeline")
                .doesNotContain("Solve 2x + 3 = 7", "x = 5", "x = 7");
        Button older = screen.lookupAll(".bank-history-toggle").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> BankCopy.HISTORY_SHOW_VERSION.equals(button.getText()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no history toggle on the question"));
        clickOn(older);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(labelTexts(screen))
                .as("v1 as it read, with the key marked, on the screen U-44 built over the "
                        + "bank's own renderer")
                .contains("Solve 2x + 3 = 7", "x = 5", "x = 7", "Correct");
        assertNothingMutates(screen);
    }

    @Test
    @DisplayName("⚑ an Exams row opens the student's own paper, with no decision under it")
    void anExamRowOpensThePaper() {
        ScreenManager manager = signIn(this::everythingLoads);
        openData(manager);

        clickOn(toggleNamed(manager.scene(), DataTab.EXAMS.segment()));
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(firstRow(manager.scene(), DataTab.EXAMS));
        WaitForAsyncUtils.waitForFxEvents();

        Node screen = detail(manager.scene(), ".principal-data-exam");
        assertThat(labeledTexts(screen))
                .as("the paper, drawn by the student's own card component: the heading each "
                        + "card prints, the stem, and the four options as a student read them")
                .contains("Algebra midterm", "Question 1 of 2", "What are the roots?",
                        "1 and 6", "2 and 3");
        assertThat(labelTexts(screen))
                .as("and the fenced staff-only block beside it, answer key included")
                .contains("Teacher only", "Answer key", "Written by Dana Cohen",
                        "Q1 · option 2");
        assertNothingMutates(screen);
    }

    @Test
    @DisplayName("⚑ a Results row opens the sitting's frozen figures and its distribution")
    void aResultsRowOpensTheSitting() {
        ScreenManager manager = signIn(this::everythingLoads);
        openData(manager);

        clickOn(toggleNamed(manager.scene(), DataTab.RESULTS.segment()));
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(firstRow(manager.scene(), DataTab.RESULTS));
        WaitForAsyncUtils.waitForFxEvents();

        Node screen = detail(manager.scene(), ".principal-data-sitting");
        Set<String> labels = labelTexts(screen);
        assertThat(labels)
                .as("the newest sitting, which is the row the list puts first")
                .contains("Algebra quiz · 5150");
        assertThat(labels)
                .as("E14's own six cards, unchanged, so one sitting reads the same on both screens")
                .contains("Average", "Median", "Std deviation", "Pass rate", "Participants");
        assertThat(labels).contains(DataDetailCopy.DISTRIBUTION_TITLE,
                DataDetailCopy.DISTRIBUTION_HINT);
        assertThat(cellTextsIn(screen))
                .as("the frozen buckets, lowest band first. Only the rows the table has "
                        + "realised are in the scene graph, so this asserts the head of the "
                        + "list; that there are exactly ten and that the tenth reads "
                        + "\"90 to 100\" is DataDetailCopyTest's, where it can be asserted "
                        + "without a viewport")
                .contains("0 to 9", "10 to 19");
        assertNothingMutates(screen);
    }

    /** T-11.3, one screen at a time: not one control here writes anything. */
    private static void assertNothingMutates(Node screen) {
        assertThat(screen.lookupAll(".button").stream()
                .filter(javafx.scene.control.Labeled.class::isInstance)
                .map(node -> ((javafx.scene.control.Labeled) node).getText())
                .filter(java.util.Objects::nonNull)
                .toList())
                .as("no control on a principal's detail screen may write (S-7, T-11.3)")
                .doesNotContainAnyElementsOf(MUTATING_LABELS);
        assertThat(screen.lookupAll(".text-field"))
                .as("and nothing on it is typed into")
                .isEmpty();
        assertThat(labelTexts(screen))
                .as("the screen says it is read only, so 'no buttons' cannot be read as "
                        + "'the buttons are not built yet'")
                .contains(DataDetailCopy.READ_ONLY_NOTE);
    }

    /** The first row of a tab's table, which is what a principal clicks. */
    private Node firstRow(Scene scene, DataTab tab) {
        Node row = table(scene, tab).lookupAll(".table-row-cell").stream()
                .filter(node -> node instanceof javafx.scene.control.TableRow<?> candidate
                        && !candidate.isEmpty())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row in the " + tab + " table"));
        return row;
    }

    /** One detail screen's root, so nothing on the shell around it satisfies an assertion. */
    private static Node detail(Scene scene, String styleClass) {
        Node node = scene.getRoot().lookup(styleClass);
        assertThat(node).as("%s is on screen", styleClass).isNotNull();
        return node;
    }

    /**
     * Every {@code Labeled} under one node, not only the {@code .label} ones.
     *
     * <p>An exam card renders its options as radio buttons, which are {@code Labeled} and are
     * not {@code .label} nodes. A student reads them, so a test claiming the principal sees the
     * student's paper has to look at them too.
     */
    private static Set<String> labeledTexts(Node parent) {
        return parent.lookupAll("*").stream()
                .filter(javafx.scene.control.Labeled.class::isInstance)
                .map(node -> ((javafx.scene.control.Labeled) node).getText())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static Set<String> cellTextsIn(Node parent) {
        return parent.lookupAll(".table-cell").stream()
                .filter(javafx.scene.control.Labeled.class::isInstance)
                .map(node -> ((javafx.scene.control.Labeled) node).getText())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    // ===================== Fixture =======================================

    private static BankQuestionRow question(String id, String course, String courseName,
                                            String text, String topic, Difficulty difficulty) {
        return new BankQuestionRow(id, course, courseName, text, topic, difficulty, 701L, 1, false,
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

    private static QuestionDetail linear() {
        return new QuestionDetail("11001", "11", "Algebra", 2, 2, "Solve the linear equation",
                List.of("x = 1", "x = 2", "x = 3", "x = 4"), 2, "Equations", Difficulty.EASY,
                false, "Dana Cohen", SUMMER);
    }

    /**
     * One version of 11001, and the two of them read <b>differently</b> on purpose.
     *
     * <p>They were identical until 2026-08-30 (Findings.txt, U-50), which was enough while the
     * history panel showed only dates and authors. Now that an entry opens to show the version
     * it names, two versions with the same words could not tell an expanded v1 from the v2 in
     * the pane above it, and the assertion would pass on the wrong node.
     */
    private static QuestionVersionDetail linearVersion(int versionNo) {
        return versionNo == 1
                ? new QuestionVersionDetail(1, "Solve 2x + 3 = 7",
                        List.of("x = 1", "x = 2", "x = 5", "x = 7"), 2, "Equations",
                        Difficulty.EASY, false, "Dana Cohen", SPRING)
                : new QuestionVersionDetail(versionNo, "Solve the linear equation",
                        List.of("x = 1", "x = 2", "x = 3", "x = 4"), 2, "Equations",
                        Difficulty.EASY, false, "Dana Cohen", SUMMER);
    }

    private static ExamQuestion paperQuestion(int ordinal) {
        return new ExamQuestion(900L + ordinal, "1200" + ordinal, ordinal, 50,
                "What are the roots?", "1 and 6", "2 and 3", "minus 2 and minus 3", "0 and 5",
                null);
    }

    private static ExamPreview midtermPreview() {
        return new ExamPreview(
                new ApprovalRow(1102L, "101101", "Algebra midterm", "11", "Algebra", 2,
                        "Dana Cohen", SUMMER, 2, 60, ApprovalState.APPROVED, "", false, 0),
                "Answer every question.",
                List.of(paperQuestion(1), paperQuestion(2)),
                new TeacherOnlyBlock("Mark question 2 generously.", "Dana Cohen",
                        List.of(new PreviewAnswerRow(901, 1, (byte) 2),
                                new PreviewAnswerRow(902, 2, (byte) 2))));
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
                        SUMMER, 1102L),
                new DataExamRow("101201", "Calculus quiz", "12", "Calculus", "Rina Barak", 1,
                        SPRING, 1201L))));
        connection.replyOk(Verb.DATA_RESULTS_GET, new DataResults(List.of(
                new ReportRow(2, "5150", "Algebra quiz", "11", "Algebra", SUMMER,
                        SUMMER.plusSeconds(7200), 4, quiet()),
                new ReportRow(1, "4821", "Algebra midterm", "11", "Algebra", SPRING,
                        SPRING.plusSeconds(7200), 8, seeded()))));
        // U-44's three details. All four verbs are reads she already held or was admitted to
        // by APPROVAL amendment A1; none of them has a mutating sibling on this wire.
        connection.replyOk(Verb.QUESTION_GET, linear());
        connection.replyOk(Verb.QUESTION_VERSIONS,
                new VersionHistory("11001", List.of(linearVersion(2), linearVersion(1))));
        connection.replyOk(Verb.EXAM_PREVIEW_GET, midtermPreview());
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
