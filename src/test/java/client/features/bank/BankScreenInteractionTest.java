package client.features.bank;

import client.core.FxTestHarness;
import client.core.NavParams;
import client.core.ScreenManager;
import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.events.PushEventBridge;
import client.ui.theme.ThemeManager;
import client.ui.theme.ThemeState;
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
import common.dto.lock.EntityRef;
import common.dto.lock.LockChange;
import common.dto.lock.LockHolder;
import common.dto.lock.LocksSnapshot;
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
import server.features.bank.QuestionLockKey;

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

    /** rina.barak: coordinates subject 10, teaches nothing, by design. */
    private static final LoginResult READ_ONLY_RINA = new LoginResult(4, "rina.barak",
            "Rina Barak", Role.COORDINATOR, List.of(), 0);

    private static final BankQuestionRow LINEAR = new BankQuestionRow("11001", "11", "Algebra",
            "Solve the linear equation", "Equations", Difficulty.EASY, 701L, 1, false, SPRING);
    private static final BankQuestionRow GEOMETRY = new BankQuestionRow("11005", "11", "Algebra",
            "Read the diagram", "Geometry", Difficulty.HARD, 702L, 2, true, SPRING);

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
        // Nothing here: openBankAs initialises ScreenManager directly rather than booting the
        // shell, so no connect screen exists to race the teardown. See its javadoc.
    }

    @AfterEach
    void resetGlobalState() {
        FxTestHarness.resetGlobalState();
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

    /**
     * The Editing column, on a real table, through the real event bus (E6.14 ⚑).
     *
     * <p>Two wiring claims the FX-free session test cannot make. That the column exists on
     * {@code BankView}'s table at all and renders its sentence into a cell — and that a push
     * travelling the app's <em>real</em> {@code ClientEventBus} reaches
     * {@code BankRowLocks.onServerPush}.
     *
     * <p>The second one is not hypothetical. The subscriber is invoked reflectively from the bus
     * package, so a package-private subscriber class registers without complaint and then throws
     * {@code IllegalAccessException} on every push, which {@code RequestDispatcher} catches and
     * logs rather than rethrows. The screen simply never updates and no test fails. That is
     * exactly what happened while this was being built.
     */
    @Test
    @DisplayName("⚑ the Editing column paints, and a live push repaints it (real bus)")
    void editingColumnPaintsAndFollowsPushes() {
        FakeClientConnection[] wire = new FakeClientConnection[1];
        Scene scene = openBank(connection -> {
            wire[0] = connection;
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.LOCKS_SNAPSHOT, new LocksSnapshot(EntityRef.QUESTION,
                    java.util.Map.of(QuestionLockKey.of("11005").entityId(),
                            new LockHolder(7L, "Ron Levi"))));
        });

        assertThat(cellTexts(scene))
                .as("the snapshot at load is what a teacher sees before she clicks anything")
                .contains("Editing · Ron Levi");
        assertThat(cellTexts(scene))
                .as("and a row nobody holds carries no chip at all")
                .doesNotContain("Editing · Dana Cohen", "Editing · you");

        interact(() -> wire[0].pushToClient(Verb.PUSH_LOCK_CHANGED,
                LockChange.released(QuestionLockKey.of("11005"))));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(cellTexts(scene))
                .as("he closed the editor, so the row is free on screen with no refresh: a push "
                        + "that never reaches the subscriber leaves this chip up forever")
                .doesNotContain("Editing · Ron Levi");
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

    // ===================== The editor's door ⚑ ============================

    @Test
    @DisplayName("⚑ Edit stays disabled while an illustrated question's picture is still coming")
    void editIsClosedUntilTheImageArrives() {
        Scene scene = openBank(connection -> {
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.QUESTION_GET, new QuestionDetail("11005", "11", "Algebra",
                    2, 2, "Read the diagram", List.of("A", "B", "C", "D"), 1, "Geometry",
                    Difficulty.HARD, true, "Dana Cohen", SPRING));
            // No responder for QUESTION_IMAGE_GET: the picture never arrives, which is the
            // state the components report warns about.
        });

        clickOn(rowShowing(scene, "Read the diagram"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(buttonNamed(scene, BankCopy.EDIT).isDisabled())
                .as("QuestionEditorSession.forEdit takes the bytes as a required argument, and "
                        + "this button is the only route into it from a question. If it fired "
                        + "now, the editor would open showing 'No illustration' about a question "
                        + "that has one, and a Remove she pressed would be silently ignored.")
                .isTrue();
    }

    @Test
    @DisplayName("Edit opens as soon as the picture is in hand")
    void editOpensOnceTheImageIsThere() {
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

        assertThat(buttonNamed(scene, BankCopy.EDIT).isDisabled())
                .as("otherwise the gate would be a button nobody can ever press")
                .isFalse();
    }

    @Test
    @DisplayName("⚑ an empty blob is not a picture, so Edit stays shut rather than crashing")
    void editIsClosedOnAnEmptyBlob() {
        Scene scene = openBank(connection -> {
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.QUESTION_GET, new QuestionDetail("11005", "11", "Algebra",
                    2, 2, "Read the diagram", List.of("A", "B", "C", "D"), 1, "Geometry",
                    Difficulty.HARD, true, "Dana Cohen", SPRING));
            // QuestionImage normalises a null blob to an empty array, so this is a well-formed
            // OK carrying no picture. The response arrives, the state goes READY, and a check
            // that asked the state enum or merely non-null would open the editor here.
            connection.replyOk(Verb.QUESTION_IMAGE_GET,
                    new QuestionImage("11005", 2, "image/png", new byte[0]));
        });

        clickOn(rowShowing(scene, "Read the diagram"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(buttonNamed(scene, BankCopy.EDIT).isDisabled())
                .as("forEdit refuses an illustrated question with no bytes, so enabling the "
                        + "button here would make pressing it throw")
                .isTrue();
    }

    @Test
    @DisplayName("⚑ a coordinator sees a course she cannot author in, with both controls shut")
    void writeControlsAreShutOutsideTheTaughtSet() {
        Scene scene = openBankAs(READ_ONLY_RINA, connection -> {
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.QUESTION_GET, GEOMETRY_V2);
        });

        clickOn(rowShowing(scene, "Read the diagram"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(buttonNamed(scene, BankCopy.DELETE).isDisabled())
                .as("her read scope shows her the row; her write scope does not cover it, and "
                        + "the server would refuse a delete")
                .isTrue();
        assertThat(buttonNamed(scene, BankCopy.EDIT).isDisabled()).isTrue();
        assertThat(buttonNamed(scene, BankCopy.DELETE).getTooltip())
                .as("a greyed control with no reason is a defect of its own here: she reached "
                        + "this state by doing nothing wrong")
                .isNotNull();
    }

    @Test
    @DisplayName("a question with no illustration is editable immediately")
    void editOpensWithNoImageAtAll() {
        Scene scene = openBank(connection -> {
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.QUESTION_GET, GEOMETRY_V2);
        });

        clickOn(rowShowing(scene, "Read the diagram"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(buttonNamed(scene, BankCopy.EDIT).isDisabled()).isFalse();
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
        return openBankAs(DANA, script);
    }

    /**
     * Brings up just enough app for a screen to run, and deliberately not the whole shell.
     *
     * <p><b>It does not call {@code ClientApp.start}, and that is the point.</b> Booting the app
     * also boots the connect screen, which schedules its connect attempt with
     * {@code applyLater}. When a test ends, {@code resetForTests} nulls the manager's event bus,
     * and a connect attempt still sitting in the FX queue then wakes up and dereferences it:
     * {@code NullPointerException: eventBus} from {@code ConnectWiring.forEndpoint}, in a test
     * that was never about connecting. It is a teardown race, it is timing-dependent, and it
     * surfaced here only because this file grew a twelfth test.
     *
     * <p>{@code ScreenManager.init} is the seam the shell itself uses, so initialising it
     * directly gives these tests a real manager, a real event bus and a real navigator with no
     * connect screen anywhere near them. {@code ThemeState.ephemeral} keeps it off the user's
     * home directory too.
     *
     * <p><b>The race is not fixed by this, only avoided here.</b> Fourteen test classes share the
     * boot-and-reset shape; this file and the editor's stop being exposed. Raised with the lead
     * as a suite-wide item.
     */
    private Scene openBankAs(LoginResult who, Consumer<FakeClientConnection> script) {
        ScreenManager manager = ScreenManager.getInstance();
        interact(() -> {
            ClientEventBus bus = new ClientEventBus(ClientEventBus.newBus(),
                    new DirectFxThreadPoster());
            manager.init(new Stage(), bus, new ThemeManager(ThemeState.ephemeral(bus)));
        });
        WaitForAsyncUtils.waitForFxEvents();

        interact(() -> {
            FakeClientConnection connection = new FakeClientConnection("demo-server", 5555);
            try {
                connection.connect();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            connection.replyOk(Verb.LOGIN, who);
            connection.replyOk(Verb.LOGOUT, null);
            script.accept(connection);

            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            connection.setServerMessageHandler(dispatcher::dispatchIncoming);
            manager.setClient(connection);
            manager.setDispatcher(dispatcher);
            dispatcher.setPushListener(new PushEventBridge(manager.eventBus()));
            manager.setSignedInUser(who);
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

    /** One button by its label, so a test cannot pass by finding a different control. */
    private static javafx.scene.control.Button buttonNamed(Scene scene, String label) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(javafx.scene.control.Button.class::isInstance)
                .map(javafx.scene.control.Button.class::cast)
                .filter(button -> label.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button labelled " + label));
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
