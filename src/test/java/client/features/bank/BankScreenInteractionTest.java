package client.features.bank;

import client.core.AppArgs;
import client.core.ClientApp;
import client.core.NavParams;
import client.core.ScreenManager;
import client.events.PushEventBridge;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.bank.BankListRequest;
import common.dto.bank.BankPage;
import common.dto.bank.BankQuestionRow;
import common.dto.bank.Difficulty;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionImage;
import common.protocol.Message;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableRow;
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
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-input interaction test for the question bank screen (E6.9 / E6.12 / E6.13 — T-2).
 *
 * <p>What only a booted toolkit can show: that {@link BankView} builds at all, that rows really
 * arrive in the table, that <b>clicking a row really opens the detail pane</b> with its four
 * answers and the key marked, and that an empty bank really draws its own explanation. Every one
 * of those is a wiring claim rather than a logic claim, and the FX-free {@link BankSessionTest}
 * cannot make any of them.
 *
 * <p>It matters more here than on most screens, because {@link BankView} is on the JaCoCo
 * exclusion list by name. Without this file nothing in the build ever constructs it, and a null
 * dereference in {@code build()} would ship green.
 *
 * <h2>Two deviations from the house layout, both deliberate</h2>
 *
 * <p><b>It lives beside the feature rather than in {@code client.ui}</b>, where the other
 * screens' interaction tests are. The scope guard opens {@code client/features/bank/**} to this
 * epic and not {@code client/ui/**}, and a test that had to be written outside the feature's own
 * package would be a scope request for no gain: nothing here needs anything the shell's tests
 * need.
 *
 * <p><b>It drives the view directly rather than navigating to it.</b> The route is registered by
 * {@code SessionRoutes}, which lands in the assembly PR, so navigating would make this file fail
 * for the same reason {@link BankScreenWiringGuardTest} already fails. That would be one gap
 * reported twice, and it would hide a real rendering defect behind an expected failure. The guard
 * owns the reachability claim; this file owns the rendering claim; the two fail for different
 * reasons.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class BankScreenInteractionTest extends ApplicationTest {

    private static final Instant SPRING = Instant.parse("2026-03-10T07:00:00Z");

    private static final LoginResult DANA = new LoginResult(2, "dana.cohen", "Dana Cohen",
            Role.TEACHER,
            List.of(new CourseRef("11", "Algebra"), new CourseRef("12", "Calculus")), 0);

    private static final BankQuestionRow LINEAR = new BankQuestionRow("11001", "11", "Algebra",
            "Solve the linear equation", "Equations", Difficulty.EASY, 1, false, SPRING);
    private static final BankQuestionRow GEOMETRY = new BankQuestionRow("11005", "11", "Algebra",
            "Read the diagram", "Geometry", Difficulty.HARD, 2, true, SPRING);

    private static final QuestionDetail GEOMETRY_V2 = new QuestionDetail("11005", "11", "Algebra",
            2, 2, "Read the diagram and answer",
            List.of("Twelve", "Fourteen", "Sixteen", "Eighteen"), 3, "Geometry", Difficulty.HARD,
            false, "Dana Cohen", SPRING);

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
    @DisplayName("⚑ the bank builds and its rows reach the table")
    void rowsRender() {
        Scene scene = openBank(this::bankHasTwoQuestions);

        assertThat(cellTexts(scene)).contains("#11001", "Solve the linear equation", "Equations",
                "Easy", "#11005", "Read the diagram", "Geometry", "Hard");
        assertThat(labelTexts(scene)).contains("2 questions");
    }

    @Test
    @DisplayName("⚑ clicking a row opens it, four answers and the key marked (real input)")
    void clickingARowOpensIt() {
        Scene scene = openBank(connection -> {
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.QUESTION_GET, GEOMETRY_V2);
        });

        clickOn(rowShowing(scene, "Read the diagram"));
        WaitForAsyncUtils.waitForFxEvents();

        Set<String> labels = labelTexts(scene);
        assertThat(labels)
                .as("the pane describes the row that was clicked")
                .contains("#11005", "Read the diagram and answer");
        assertThat(labels)
                .as("all four options, one-based, as C-8 numbers them")
                .contains("Answer 1", "Answer 2", "Answer 3", "Answer 4",
                        "Twelve", "Fourteen", "Sixteen", "Eighteen");
        assertThat(labels)
                .as("and the key is visible to an author, which is what this wire exists for")
                .contains(BankCopy.CORRECT_MARK);
    }

    @Test
    @DisplayName("the illustration is fetched and drawn for a question that has one")
    void illustrationIsDrawn() {
        Scene scene = openBank(connection -> {
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.QUESTION_GET, new QuestionDetail("11005", "11", "Algebra",
                    2, 2, "Read the diagram", List.of("A", "B", "C", "D"), 1, "Geometry",
                    Difficulty.HARD, true, "Dana Cohen", SPRING));
            connection.replyOk(Verb.QUESTION_IMAGE_GET,
                    new QuestionImage("11005", 2, "image/png", onePixelPng()));
        });

        clickOn(rowShowing(scene, "Read the diagram"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(scene.getRoot().lookup(".bank-image"))
                .as("the picture is on screen rather than the 'no illustration' sentence")
                .isNotNull();
        assertThat(labelTexts(scene)).doesNotContain(BankCopy.NO_IMAGE);
    }

    @Test
    @DisplayName("an empty bank draws its own explanation, not a blank rectangle")
    void emptyBankIsExplained() {
        Scene scene = openBank(connection -> connection.replyOk(Verb.BANK_LIST,
                new BankPage(List.of(), 0, BankListRequest.DEFAULT_PAGE_SIZE, 0, 0)));

        assertThat(labelTexts(scene))
                .contains(BankCopy.NO_QUESTIONS.title(), BankCopy.NO_QUESTIONS.hint())
                .doesNotContain(BankCopy.NO_MATCHES.title());
    }

    @Test
    @DisplayName("nothing selected is a described state, not an empty half of the screen")
    void nothingSelectedIsDescribed() {
        Scene scene = openBank(this::bankHasTwoQuestions);

        assertThat(labelTexts(scene))
                .contains(BankCopy.NOTHING_SELECTED.title(), BankCopy.NOTHING_SELECTED.hint());
    }

    @Test
    @DisplayName("the course picker is built from the sign-in payload, not from a round trip")
    void coursePickerComesFromTheSession() {
        Scene scene = openBank(this::bankHasTwoQuestions);

        ComboBox<?> picker = (ComboBox<?>) scene.getRoot().lookup(".bank-course-picker");
        assertThat(picker).isNotNull();
        assertThat(picker.getItems())
                .as("two courses plus the 'all courses' entry, straight from LoginResult")
                .hasSize(3);
    }

    @Test
    @DisplayName("the topic picker is absent while its lookup verb does not exist")
    void topicPickerIsHiddenUntilItCanBeFilled() {
        Scene scene = openBank(this::bankHasTwoQuestions);

        Node picker = scene.getRoot().lookup(".bank-topic-picker");
        assertThat(picker).isNotNull();
        assertThat(picker.isManaged())
                .as("an empty picker is worse than no picker: it offers a filter that can only "
                        + "match nothing (contract ruling 7.6)")
                .isFalse();
    }

    // ===================== Fixture and harness ============================

    private void bankHasTwoQuestions(FakeClientConnection connection) {
        connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request,
                new BankPage(List.of(LINEAR, GEOMETRY), 0, BankListRequest.DEFAULT_PAGE_SIZE,
                        2, 1)));
    }

    /**
     * Boots the shell, signs Dana in, then puts a {@link BankView} on its own stage.
     *
     * <p>The shell boot is what gives {@code AbstractScreen} its dispatcher and its signed-in
     * user; the separate stage is what lets the screen be driven without a route.
     */
    private Scene openBank(Consumer<FakeClientConnection> script) {
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
            manager.setSignedInUser(DANA);
        });
        WaitForAsyncUtils.waitForFxEvents();

        Scene[] holder = new Scene[1];
        interact(() -> {
            BankView view = new BankView();
            Scene scene = new Scene(view.view(), 1280, 820);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            view.onShow(NavParams.empty());
            holder[0] = scene;
        });
        WaitForAsyncUtils.waitForFxEvents();
        return holder[0];
    }

    /** The table row whose cells contain {@code text}, so a click lands on a real row. */
    private static Node rowShowing(Scene scene, String text) {
        return scene.getRoot().lookupAll(".table-row-cell").stream()
                .filter(TableRow.class::isInstance)
                .filter(row -> row.lookupAll(".table-cell").stream()
                        .filter(javafx.scene.control.Labeled.class::isInstance)
                        .map(cell -> ((javafx.scene.control.Labeled) cell).getText())
                        .filter(Objects::nonNull)
                        .anyMatch(cellText -> cellText.contains(text)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row showing " + text));
    }

    private static Set<String> cellTexts(Scene scene) {
        return scene.getRoot().lookupAll(".table-cell").stream()
                .filter(javafx.scene.control.Labeled.class::isInstance)
                .map(node -> ((javafx.scene.control.Labeled) node).getText())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static Set<String> labelTexts(Scene scene) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * A genuine 1x1 PNG.
     *
     * <p>Genuine rather than a stub, for the reason the gallery's demo image is: the product
     * sniffs image bytes and refuses a fake, so a test that demonstrated the image path with
     * something the product would reject would be demonstrating nothing.
     */
    private static byte[] onePixelPng() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwA"
                        + "EhQGAhKmMIQAAAABJRU5ErkJggg==");
    }
}
