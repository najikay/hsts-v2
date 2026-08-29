package client.ui;

import client.core.ClientApp;
import client.core.FxTestHarness;
import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.events.PushEventBridge;
import client.features.exam.ExamCopy;
import client.features.home.StudentHomeSession;
import client.features.login.ShellBoot;
import client.ui.components.BackLink;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.exam.AttemptForm;
import common.dto.exam.AttemptOutcome;
import common.dto.exam.AttemptState;
import common.dto.exam.AttemptSummaryEntry;
import common.dto.exam.AttemptTiming;
import common.dto.exam.ExamHeader;
import common.dto.exam.ExamQuestion;
import common.dto.exam.SaveAnswerResult;
import common.dto.exam.TimerExtended;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-input interaction test for taking an exam (E10.9–E10.14 ⚑).
 *
 * <p>House policy: a smoke test that only checks nodes exist proves very little. This one
 * drives the actual affordances with the actual robot — typing the code, typing the ID,
 * clicking an option — and asserts the consequences all the way to the Time Up takeover.
 *
 * <p>What only a booted toolkit can prove, and this therefore does:
 *
 * <ul>
 *   <li>the two entry screens really do swap, and the identity step really is what sends
 *       {@code ATTEMPT_START} (S-18);</li>
 *   <li>the paper renders with four options and no fifth field anywhere;</li>
 *   <li>a click on a radio button really reaches the session and the autosave indicator
 *       moves;</li>
 *   <li><b>the Time Extended moment plays</b>: the toast appears, naming the teacher
 *       (F7.1 ⚑);</li>
 *   <li><b>the Time Up takeover appears on a push</b>, covers the paper, asks nothing, and
 *       carries exactly one action (F6.4 ⚑).</li>
 * </ul>
 *
 * <p>Same escape hatch as the other UI tests: {@code ./mvnw verify -Dhsts.uitests=false}.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class TakeExamInteractionTest extends ApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant ENDS = NOW.plus(Duration.ofMinutes(45));
    private static final long EXECUTION = 5001L;
    private static final long ATTEMPT = 42L;

    private static final LoginResult MAYA = new LoginResult(2001, "maya.levi", "Maya Levi",
            Role.STUDENT, List.of(new CourseRef("21", "Java Programming")), 0);

    private static final ExamHeader HEADER = new ExamHeader(EXECUTION, "Java Midterm", "21",
            "Java Programming", 45, "Answer every question. Good luck.", 3,
            AttemptState.NOT_STARTED);

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
    @DisplayName("code, then ID, then the paper: the whole entry flow with real input (E10.9)")
    void entryFlowStartsTheExam() {
        ScreenManager manager = signIn();
        openTakeExam(manager);

        Scene scene = manager.scene();
        assertThat(labelTexts(scene))
                .as("the code screen is what she lands on")
                .contains(ExamCopy.CODE_TITLE);

        typeInto(scene, 0, "4B7Q");
        clickOn(buttonNamed(scene, ExamCopy.CODE_BUTTON));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(labelTexts(manager.scene()))
                .as("the identity screen names the exam she is about to sit")
                .contains(ExamCopy.ID_TITLE, "Java Midterm");
        assertThat(manager.scene().getRoot().lookupAll(".question-card"))
                .as("and the paper does not exist on her machine yet (S-18)")
                .isEmpty();

        typeInto(manager.scene(), 0, "374301851");
        clickOn(buttonNamed(manager.scene(), ExamCopy.START_BUTTON));
        WaitForAsyncUtils.waitForFxEvents();

        Scene form = manager.scene();
        assertThat(form.getRoot().lookupAll(".question-card"))
                .as("now the paper is here")
                .hasSize(1);
        assertThat(form.getRoot().lookupAll(".question-option"))
                .as("four options, and only four (C-7)")
                .hasSize(4);
        assertThat(form.getRoot().lookupAll(".nav-chip"))
                .as("the navigator strip has a chip per question")
                .hasSize(3);
        assertThat(labelTexts(form))
                .contains(ExamCopy.progress(0, 3), "Answer every question. Good luck.");
        assertThat(form.getRoot().lookup(".hsts-countdown"))
                .as("and the countdown is running")
                .isNotNull();
    }

    @Test
    @DisplayName("\u26a1 arriving from the dashboard card confirms the code instead of asking for it")
    void dashboardCodeBecomesAConfirmation() {
        ScreenManager manager = signIn();

        interact(() -> manager.navigator().navigate(Routes.TAKE_EXAM.id(),
                NavParams.of(StudentHomeSession.CODE_PARAM, "4B7Q")));
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = manager.scene();
        assertThat(labelTexts(scene))
                .as("2026-08-28, manual round 1: the code she pressed is stated, not asked for")
                .contains(ExamCopy.CONFIRM_TITLE, ExamCopy.confirmSubtitle("4B7Q"));
        assertThat(labelTexts(scene)).doesNotContain(ExamCopy.CODE_TITLE);

        TextField code = visibleTextFields(scene).get(0);
        assertThat(code.getText()).isEqualTo("4B7Q");
        assertThat(code.isEditable())
                .as("the field shows the code; it is not a question any more")
                .isFalse();

        Button confirm = buttonNamed(scene, ExamCopy.CONFIRM_BUTTON);
        assertThat(confirm.isDisabled())
                .as("live without a keystroke, which is the bug the session-level prefill fixes")
                .isFalse();

        clickOn(confirm);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(labelTexts(manager.scene()))
                .as("the same EXAM_JOIN, so the identity step still follows (S-18)")
                .contains(ExamCopy.ID_TITLE, "Java Midterm");
    }

    @Test
    @DisplayName("Use a different code hands the code step back, empty")
    void useADifferentCodeReturnsToTyping() {
        ScreenManager manager = signIn();

        interact(() -> manager.navigator().navigate(Routes.TAKE_EXAM.id(),
                NavParams.of(StudentHomeSession.CODE_PARAM, "4B7Q")));
        WaitForAsyncUtils.waitForFxEvents();

        clickOn(linkNamed(manager.scene(), ExamCopy.DIFFERENT_CODE));
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = manager.scene();
        assertThat(labelTexts(scene)).contains(ExamCopy.CODE_TITLE);
        TextField code = visibleTextFields(scene).get(0);
        assertThat(code.getText()).isEmpty();
        assertThat(code.isEditable()).isTrue();
        assertThat(buttonNamed(scene, ExamCopy.CODE_BUTTON).isDisabled())
                .as("nothing typed yet, so nothing to send")
                .isTrue();
    }

    @Test
    @DisplayName("\u26a1 Back on Confirm it is you returns to the code step, code intact")
    void backLeavesTheIdentityStep() {
        ScreenManager manager = signIn();
        openTakeExam(manager);

        typeInto(manager.scene(), 0, "4B7Q");
        clickOn(buttonNamed(manager.scene(), ExamCopy.CODE_BUTTON));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(labelTexts(manager.scene())).contains(ExamCopy.ID_TITLE);

        // 2026-08-29, manual round 2: this screen is a rail route, so the shell draws no back
        // control over it. Before this, the only way off the identity step was to start the
        // clock.
        clickOn(buttonNamed(manager.scene(), BackLink.LABEL));
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = manager.scene();
        assertThat(onScreen(scene.getRoot().lookup(".exam-summary")))
                .as("the identity card is behind her, not merely retitled")
                .isFalse();
        assertThat(labelTexts(scene)).contains(ExamCopy.CODE_TITLE);

        TextField code = visibleTextFields(scene).get(0);
        assertThat(code.getText())
                .as("the code she typed is still there, ready to be corrected not retyped")
                .isEqualTo("4B7Q");
        assertThat(buttonNamed(scene, ExamCopy.CODE_BUTTON).isDisabled())
                .as("and Continue is live, so the way forward is one click")
                .isFalse();
        assertThat(manager.navigator().currentRouteId())
                .as("a step back, not a navigation")
                .isEqualTo(Routes.TAKE_EXAM.id());
    }

    @Test
    @DisplayName("\u26a1 Back to my dashboard leaves the code step, which had no exit at all")
    void backToDashboardLeavesTheCodeStep() {
        ScreenManager manager = signIn();
        openTakeExam(manager);

        clickOn(linkNamed(manager.scene(), ExamCopy.BACK_TO_DASHBOARD));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.navigator().currentRouteId())
                .as("nothing was started, so leaving is just leaving")
                .isEqualTo(Routes.HOME_STUDENT.id());
    }

    @Test
    @DisplayName("clicking an option saves it and the indicator says so (E10.11)")
    void clickingAnOptionAutosaves() {
        ScreenManager manager = signIn();
        openTakeExam(manager);
        enterTheExam(manager);

        Node option = manager.scene().getRoot().lookupAll(".question-option").iterator().next();
        clickOn(option);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(((RadioButton) option).isSelected()).isTrue();
        // The debounce is real here (400 ms), so what this proves is the click reaching the
        // model and the indicator moving off "All changes saved"; the write itself is
        // asserted deterministically in ExamAttemptSessionTest.
        assertThat(indicatorText(manager.scene())).isNotEqualTo(ExamCopy.SAVED_INDICATOR);
    }

    @Test
    @DisplayName("the Time Extended moment plays on a push, naming the teacher (F7.1 ⚑)")
    void extensionPlaysTheDesignedMoment() {
        ScreenManager manager = signIn();
        FakeClientConnection connection = (FakeClientConnection) manager.getClient();
        openTakeExam(manager);
        enterTheExam(manager);

        interact(() -> connection.pushToClient(Verb.PUSH_TIMER_EXTENDED, extension()));
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = manager.scene();
        assertThat(scene.getRoot().lookup(".hsts-toast"))
                .as("time added is never silent (F7.1)")
                .isNotNull();
        assertThat(labelTexts(scene)).contains(ExamCopy.EXTENSION_TOAST_TITLE);
        assertThat(labelTexts(scene)).anySatisfy(text ->
                assertThat(text).contains("Dana Cohen").contains("15 minutes"));
        assertThat(scene.getRoot().lookup(".countdown-gain"))
                .as("and the floating gain rises off the chip")
                .isNotNull();
    }

    @Test
    @DisplayName("a force-submit takes the screen over, asks nothing, and offers one way out (F6.4 ⚑)")
    void forceSubmitTakesTheScreenOver() {
        ScreenManager manager = signIn();
        FakeClientConnection connection = (FakeClientConnection) manager.getClient();
        openTakeExam(manager);
        enterTheExam(manager);

        interact(() -> connection.pushToClient(Verb.PUSH_FORCE_SUBMITTED, outcome()));
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = manager.scene();
        Node takeover = scene.getRoot().lookup(".exam-done");
        assertThat(takeover).as("the takeover is showing").isNotNull();
        assertThat(takeover.isVisible()).isTrue();
        assertThat(takeover.getStyleClass())
                .as("locked, not celebratory")
                .contains("timed-out");
        assertThat(labelTexts(scene))
                .contains(ExamCopy.TIMED_OUT_TITLE, ExamCopy.TIMED_OUT_SUBTITLE);
        assertThat(scene.getRoot().lookupAll(".answer-cell"))
                .as("with the summary of what the server handed in")
                .hasSize(3);

        // Exactly one action, and no confirmation anywhere: it has already happened.
        List<Button> actions = ((javafx.scene.Parent) takeover).lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .toList();
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).getText()).isEqualTo(ExamCopy.BACK_TO_DASHBOARD);
        assertThat(scene.getRoot().lookup(".hsts-dialog"))
                .as("no confirmation dialog is raised (F6.4)")
                .isNull();
    }

    @Test
    @DisplayName("Back to my dashboard leaves, and the exam is not re-enterable (E10.14 ⚑)")
    void backToDashboardLeavesForGood() {
        ScreenManager manager = signIn();
        FakeClientConnection connection = (FakeClientConnection) manager.getClient();
        openTakeExam(manager);
        enterTheExam(manager);
        interact(() -> connection.pushToClient(Verb.PUSH_FORCE_SUBMITTED, outcome()));
        WaitForAsyncUtils.waitForFxEvents();

        clickOn(buttonNamed(manager.scene(), ExamCopy.BACK_TO_DASHBOARD));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.navigator().currentRouteId())
                .as("she is back on her dashboard")
                .isEqualTo(Routes.HOME_STUDENT.id());

        // Re-entering starts at the code screen again, and the server's own answer is what
        // tells her she has already handed this one in: there is no local memory to go stale.
        interact(() -> {
            connection.replyOk(Verb.EXAM_JOIN, new ExamHeader(EXECUTION, "Java Midterm", "21",
                    "Java Programming", 45, "", 3, AttemptState.SUBMITTED));
            manager.navigator().navigate(Routes.TAKE_EXAM.id());
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.scene().getRoot().lookupAll(".question-card"))
                .as("the paper is gone")
                .isEmpty();
        assertThat(labelTexts(manager.scene())).contains(ExamCopy.CODE_TITLE);
    }

    // ===================== Fixture =======================================

    /** Boots the app, attaches a scripted server, and enters Maya's shell. */
    private ScreenManager signIn() {
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
            connection.replyOk(Verb.LOGIN, MAYA);
            connection.replyOk(Verb.LOGOUT, null);
            connection.replyOk(Verb.EXAM_JOIN, HEADER);
            connection.replyOk(Verb.ATTEMPT_START, liveForm());
            connection.replyOk(Verb.ATTEMPT_RESUME, liveForm());
            connection.replyOk(Verb.ANSWER_SAVE, new SaveAnswerResult(1001, 1, 1, 3,
                    AttemptTiming.between(NOW, NOW, ENDS)));

            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            connection.setServerMessageHandler(dispatcher::dispatchIncoming);
            manager.setClient(connection);
            manager.setDispatcher(dispatcher);
            dispatcher.setPushListener(new PushEventBridge(manager.eventBus()));

            ShellBoot.enter(manager, MAYA);
        });
        WaitForAsyncUtils.waitForFxEvents();
        return manager;
    }

    /**
     * Opens the screen the way the rail does: navigate, carrying nothing.
     *
     * <p>⚑ U-1 enabled that rail item, and this is the entry it produces. Nothing here
     * changed to make it work — {@code TakeExamView.onShow} has always treated the dashboard's
     * pre-validated code as an {@code ifPresent} pre-fill on top of a code screen it builds
     * either way — which is what makes the rail item a one-line swap and this method the proof
     * of it rather than a new test.
     */
    private void openTakeExam(ScreenManager manager) {
        interact(() -> manager.navigator().navigate(Routes.TAKE_EXAM.id()));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Types the code and the ID, ending on the live paper. */
    private void enterTheExam(ScreenManager manager) {
        typeInto(manager.scene(), 0, "4B7Q");
        clickOn(buttonNamed(manager.scene(), ExamCopy.CODE_BUTTON));
        WaitForAsyncUtils.waitForFxEvents();
        typeInto(manager.scene(), 0, "374301851");
        clickOn(buttonNamed(manager.scene(), ExamCopy.START_BUTTON));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Real keyboard input into the nth visible text field. */
    private void typeInto(Scene scene, int index, String text) {
        List<TextField> fields = visibleTextFields(scene);
        assertThat(fields).as("a visible text field to type into").hasSizeGreaterThan(index);
        clickOn(fields.get(index));
        write(text);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static List<TextField> visibleTextFields(Scene scene) {
        return scene.getRoot().lookupAll(".text-input").stream()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .filter(TakeExamInteractionTest::onScreen)
                .toList();
    }

    /**
     * Effective visibility, not the node's own flag ⚑.
     *
     * <p>The three entry cards share one node graph and swap which of them is showing, so the
     * code field's own {@code visible} stays {@code true} while the card holding it is hidden.
     * Filtering on {@code Node::isVisible} therefore returned it as field zero on the identity
     * screen, and the robot clicked the coordinates a hidden node happened to have last: it hit
     * the ID field by luck of the layout, and stopped hitting it the moment the code card
     * changed shape. Walking the parents is the difference between a test that passes and a
     * test that means something.
     */
    private static boolean onScreen(Node node) {
        for (Node walk = node; walk != null; walk = walk.getParent()) {
            if (!walk.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private Hyperlink linkNamed(Scene scene, String text) {
        return scene.getRoot().lookupAll(".hyperlink").stream()
                .filter(Hyperlink.class::isInstance)
                .map(Hyperlink.class::cast)
                .filter(link -> text.equals(link.getText()))
                .filter(TakeExamInteractionTest::onScreen)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no visible link labelled " + text));
    }

    private Button buttonNamed(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .filter(TakeExamInteractionTest::onScreen)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no visible button labelled " + text));
    }

    private static String indicatorText(Scene scene) {
        Node node = scene.getRoot().lookup(".save-indicator");
        return node instanceof Label label ? label.getText() : "";
    }

    private static Set<String> labelTexts(Scene scene) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static AttemptForm liveForm() {
        return new AttemptForm(ATTEMPT,
                new ExamHeader(EXECUTION, "Java Midterm", "21", "Java Programming", 45,
                        "Answer every question. Good luck.", 3, AttemptState.IN_PROGRESS),
                List.of(question(1), question(2), question(3)), List.of(),
                AttemptTiming.between(NOW, NOW, ENDS), AttemptState.IN_PROGRESS, null);
    }

    private static ExamQuestion question(int ordinal) {
        return new ExamQuestion(1000 + ordinal, "2100" + ordinal, ordinal, 10,
                "What does question " + ordinal + " ask?",
                "First option", "Second option", "Third option", "Fourth option", null);
    }

    private static AttemptOutcome outcome() {
        return new AttemptOutcome(ATTEMPT, AttemptState.TIMED_OUT, "Java Midterm",
                ENDS, 45, 1, 3,
                List.of(new AttemptSummaryEntry(1, "21001", true),
                        new AttemptSummaryEntry(2, "21002", false),
                        new AttemptSummaryEntry(3, "21003", false)));
    }

    private static TimerExtended extension() {
        Instant newEnd = ENDS.plus(Duration.ofMinutes(15));
        return new TimerExtended(EXECUTION, "Java Midterm", "Dana Cohen", 15,
                new AttemptTiming(NOW, newEnd, Duration.ofMinutes(60).toMillis(),
                        Duration.ofMinutes(60).toMillis()));
    }
}
