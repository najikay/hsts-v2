package client.ui;

import client.core.ClientApp;
import client.core.FxTestHarness;
import client.core.Routes;
import client.core.ScreenManager;
import client.events.PushEventBridge;
import client.features.exam.ExamCopy;
import client.features.login.ShellBoot;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.exam.AttemptForm;
import common.dto.exam.AttemptState;
import common.dto.exam.AttemptTiming;
import common.dto.exam.ExamHeader;
import common.dto.exam.ExamQuestion;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-4 reproduction: the exact form the server answers for maya.levi joining execution
 * 2075 (captured by an acceptance probe on 2026-08-28), driven through the real entry
 * flow. Omar's manual round found the screen goes blank on this paper; the fixture the
 * existing interaction test uses (three questions, no images) passes, so the difference
 * lives somewhere in this form's content.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class M4ReproTest extends ApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-20T15:30:00Z");
    private static final Instant ENDS = NOW.plus(Duration.ofMinutes(75));
    private static final long EXECUTION = 4L;

    private static final LoginResult MAYA = new LoginResult(2001, "maya.levi", "Maya Levi",
            Role.STUDENT, List.of(new CourseRef("11", "Algebra")), 0);

    private static final String GENERAL =
            "Read each question to the end. Only a basic calculator may be used.";

    private static final ExamHeader JOIN_HEADER = new ExamHeader(EXECUTION, "Midterm: Algebra",
            "11", "Algebra", 75, GENERAL, 7, AttemptState.NOT_STARTED);

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
        // Boots per test, as the other interaction tests do.
    }

    @AfterEach
    void resetGlobalState() {
        FxTestHarness.resetGlobalState();
    }

    @Test
    @DisplayName("the real 2075 paper, answered off the FX thread as the real socket answers, still renders (M-4)")
    void thePaperRendersWhenTheAnswerArrivesOnTheReaderThread() throws Exception {
        ScreenManager manager = signIn(false);
        interact(() -> manager.navigator().navigate(Routes.TAKE_EXAM.id()));
        WaitForAsyncUtils.waitForFxEvents();

        typeInto(manager.scene(), "2075");
        clickOn(buttonNamed(manager.scene(), ExamCopy.CODE_BUTTON));
        WaitForAsyncUtils.waitForFxEvents();

        typeInto(manager.scene(), "374301851");
        clickOn(buttonNamed(manager.scene(), ExamCopy.START_BUTTON));
        WaitForAsyncUtils.waitForFxEvents();

        // The production socket answers on OCSF's reader thread, never on the FX thread.
        // This is the only difference from the passing test below, and it is M-4.
        FakeClientConnection connection = (FakeClientConnection) manager.getClient();
        common.protocol.Message request = connection.sentMessages().stream()
                .filter(m -> m.getVerb() == Verb.ATTEMPT_START)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("ATTEMPT_START was never sent"));
        Thread reader = new Thread(
                () -> connection.deliver(common.protocol.Message.ok(request, realForm())),
                "fake-ocsf-reader");
        reader.start();
        reader.join();
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.scene().getRoot().lookupAll(".question-card"))
                .as("the paper is on screen, not a blank")
                .hasSize(1);
        assertThat(manager.scene().getRoot().lookupAll(".nav-chip"))
                .as("a chip per question")
                .hasSize(7);
    }

    @Test
    @DisplayName("the real 2075 paper renders instead of a blank screen (M-4)")
    void theRealPaperRenders() {
        ScreenManager manager = signIn(true);
        interact(() -> manager.navigator().navigate(Routes.TAKE_EXAM.id()));
        WaitForAsyncUtils.waitForFxEvents();

        typeInto(manager.scene(), "2075");
        clickOn(buttonNamed(manager.scene(), ExamCopy.CODE_BUTTON));
        WaitForAsyncUtils.waitForFxEvents();

        typeInto(manager.scene(), "374301851");
        clickOn(buttonNamed(manager.scene(), ExamCopy.START_BUTTON));
        WaitForAsyncUtils.waitForFxEvents();

        Scene form = manager.scene();
        assertThat(form.getRoot().lookupAll(".question-card"))
                .as("the paper is on screen, not a blank")
                .hasSize(1);
        assertThat(form.getRoot().lookupAll(".nav-chip"))
                .as("a chip per question")
                .hasSize(7);
    }

    // ===================== Fixture =======================================

    private ScreenManager signIn(boolean scriptAttemptStart) {
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
            connection.replyOk(Verb.EXAM_JOIN, JOIN_HEADER);
            if (scriptAttemptStart) {
                connection.replyOk(Verb.ATTEMPT_START, realForm());
            }

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

    /** The 2075 paper as the server actually serves it: seed content, seed images. */
    private static AttemptForm realForm() {
        return new AttemptForm(17,
                new ExamHeader(EXECUTION, "Midterm: Algebra", "11", "Algebra", 75, GENERAL,
                        7, AttemptState.IN_PROGRESS),
                List.of(
                        new ExamQuestion(1, "11001", 1, 15, "Solve: 3x + 6 = 18",
                                "x = 4", "x = 6", "x = 2", "x = 12", null),
                        new ExamQuestion(2, "11002", 2, 15, "Solve: 5x - 7 = 2x + 8",
                                "x = 3", "x = 5", "x = 15", "x = 1", null),
                        new ExamQuestion(5, "11005", 3, 15, "What are the roots of x² - 5x + 6 = 0?",
                                "2, 3", "1, 6", "-2, -3", "0, 5", seedImage("q11005.png")),
                        new ExamQuestion(7, "11007", 4, 15,
                                "How many x-axis intercepts does the parabola y = x² + 2x + 5 have?",
                                "Two", "One", "None", "Infinitely many", seedImage("q11007.png")),
                        new ExamQuestion(9, "11009", 5, 15, "Solve: 2x - 4 > 6",
                                "x > 5", "x > 1", "x < 5", "x > 10", null),
                        new ExamQuestion(10, "11010", 6, 15, "Solve: x² - 4 < 0",
                                "x < -2", "-2 < x < 2", "x > 2", "all real x", seedImage("q11010.png")),
                        new ExamQuestion(11, "11011", 7, 10,
                                "For which values of x does (x-1)/(x+2) ≥ 0 hold?",
                                "x ≥ 1", "-2 < x ≤ 1", "x < -2 or x ≥ 1", "x ≤ -2 or x ≥ 1", null)),
                List.of(),
                AttemptTiming.between(NOW, NOW, ENDS), AttemptState.IN_PROGRESS, null);
    }

    private static byte[] seedImage(String name) {
        try (InputStream in = M4ReproTest.class.getResourceAsStream("/seed/img/" + name)) {
            assertThat(in).as("seed image %s on the classpath", name).isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    // ===================== Robot helpers =================================

    private void typeInto(Scene scene, String text) {
        TextField field = scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .filter(M4ReproTest::onScreen)
                .min(Comparator.comparingDouble(f ->
                        f.localToScene(f.getLayoutBounds()).getMinY()))
                .orElseThrow(() -> new AssertionError("no visible text field"));
        clickOn(field);
        write(text);
    }

    /**
     * Effective visibility, walked up the parents: a node's own flag stays true inside a
     * hidden card, which is how a robot ends up typing into the wrong field (found by
     * agent C's fixture repair in TakeExamInteractionTest, 2026-08-28).
     */
    private static boolean onScreen(Node node) {
        for (Node walk = node; walk != null; walk = walk.getParent()) {
            if (!walk.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static Button buttonNamed(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button named " + text));
    }

    private static java.util.Set<String> labelTexts(Scene scene) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
    }
}
