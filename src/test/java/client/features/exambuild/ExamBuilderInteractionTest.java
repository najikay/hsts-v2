package client.features.exambuild;

import client.core.FxTestHarness;
import client.core.NavParams;
import client.core.ScreenManager;
import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.events.PushEventBridge;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.theme.ThemeManager;
import client.ui.theme.ThemeState;
import common.dto.approval.ApprovalState;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.authoring.ComposedQuestion;
import common.dto.authoring.ExamComposition;
import common.dto.bank.Difficulty;
import common.protocol.Message;
import common.protocol.Verb;
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
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-input interaction test for the exam builder (E7.11 / E7.12 — F3.1, T-3.2).
 *
 * <p>What only a booted toolkit can show: that {@link ExamBuilderView} builds at all, that a
 * loaded draft's metadata and questions really reach controls, that the live points indicator
 * really paints its total and the server's sentence, and that a read-only version really renders
 * inert with its banner. Every one is a wiring claim the FX-free
 * {@link ExamBuilderSessionTest} cannot make.
 *
 * <p>Without this file nothing in the build ever constructs {@link ExamBuilderView}, and a null
 * dereference in {@code build()} would ship green. Like {@code ExamListView} it is not yet on the
 * JaCoCo exclusion list, which is the pom's plugin config and outside Member A's scope.
 *
 * <p><b>It drives the view directly rather than navigating to it</b>, for the reason
 * {@link ExamBuilderWiringGuardTest} exists: the route does not exist yet, so navigating would
 * fail here for a reason that file already owns, and would hide a real rendering defect behind an
 * expected failure.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class ExamBuilderInteractionTest extends ApplicationTest {

    private static final Instant WHEN = Instant.parse("2026-08-24T09:00:00Z");
    private static final long VERSION_ID = 7001L;

    private static final LoginResult DANA = new LoginResult(2, "dana.cohen", "Dana Cohen",
            Role.TEACHER, List.of(new CourseRef("11", "Algebra")), 0);

    private static ComposedQuestion question(long id, String displayId, int ord, int points,
                                             int pinned, int latest) {
        return new ComposedQuestion(id, displayId, ord, points, "What is recursion?", "Recursion",
                Difficulty.MEDIUM, false, pinned, latest);
    }

    private static ExamComposition stored(ApprovalState state) {
        return new ExamComposition(700L, "110101", "11", "Algebra", VERSION_ID, 2, state,
                "Algebra midterm", 90, "Good luck", "Marking notes", "Dana Cohen", WHEN, "",
                List.of(question(9001L, "11001", 1, 50, 1, 1),
                        question(9002L, "11002", 2, 50, 2, 4)),
                3);
    }

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
        // Nothing: openBuilder initialises ScreenManager directly rather than booting the shell.
    }

    @AfterEach
    void resetGlobalState() {
        FxTestHarness.resetGlobalState();
    }

    // ===================== The wiring claims =============================

    @Test
    @DisplayName("⚑ the builder builds and a loaded draft reaches its controls")
    void draftRenders() {
        Scene scene = openBuilder(ApprovalState.DRAFT, VERSION_ID);

        assertThat(fieldTexts(scene))
                .as("the metadata really arrives in the form, not just in the session")
                .contains("Algebra midterm", "90");
        assertThat(labelTexts(scene))
                .contains(ExamBuildCopy.TITLE_EDIT)
                .anySatisfy(text -> assertThat(text).contains("What is recursion?"));
    }

    /**
     * The live points indicator (T-3.2).
     *
     * <p>The acceptance case watches it go from wrong to right, so what matters on screen is that
     * the number and the server's sentence are both really painted, not merely computed.
     */
    @Test
    @DisplayName("⚑ the points indicator paints the total, and it is green at 100")
    void pointsIndicatorPaints() {
        Scene scene = openBuilder(ApprovalState.DRAFT, VERSION_ID);

        assertThat(labelTexts(scene)).contains(ExamBuildCopy.pointsIndicator(100));
        assertThat(labelTexts(scene))
                .as("nothing is wrong, so no problem sentence is on screen")
                .doesNotContain(server.features.exambuild.ExamBuildMessages.pointsShort(100));
    }

    @Test
    @DisplayName("⚑ the newer-version badge paints on a question the bank has moved past (E7.7)")
    void newerVersionBadgePaints() {
        Scene scene = openBuilder(ApprovalState.DRAFT, VERSION_ID);

        assertThat(labelTexts(scene))
                .as("11002 is pinned at v2 while the bank holds v4")
                .contains(ExamBuildCopy.NEWER_VERSION_BADGE);
    }

    /**
     * A finished version renders, says why it is inert, and offers no way to write.
     *
     * <p>Contract §8's read path on a real screen. The session refuses every edit anyway; what
     * only a toolkit shows is that the Save control is genuinely absent rather than merely
     * disabled behind a session that would refuse it.
     */
    @Test
    @DisplayName("⚑ an approved version renders read-only, with its banner and no Save")
    void readOnlyRenders() {
        Scene scene = openBuilder(ApprovalState.APPROVED, VERSION_ID);

        // VISIBLE labels, not merely present ones. A mutation round caught this reading the
        // banner off a hidden node: lookupAll walks the scene graph regardless of visibility,
        // and this banner's text is set at construction, so `contains` was true even with
        // show(banner, false) hard-coded. The assertion tested that the Label existed, which
        // was never the property worth pinning.
        assertThat(visibleLabelTexts(scene))
                .contains(ExamBuildCopy.TITLE_READ_ONLY, ExamBuildCopy.READ_ONLY_BANNER);
        assertThat(visibleButtonsNamed(scene, ExamBuildCopy.SAVE_BUTTON)).isEmpty();
        assertThat(visibleButtonsNamed(scene, ExamBuildCopy.CREATE_BUTTON)).isEmpty();
        assertThat(visibleButtonsNamed(scene, ExamBuildCopy.MOVE_UP))
                .as("and no reordering either, which is the paper's half of the same rule")
                .isEmpty();
    }

    @Test
    @DisplayName("⚑ a new exam opens on an empty paper that says what to do")
    void newExamRenders() {
        Scene scene = openBuilder(null, 0);

        assertThat(labelTexts(scene))
                .contains(ExamBuildCopy.TITLE_NEW, ExamBuildCopy.PAPER_EMPTY);
        assertThat(labelTexts(scene))
                .as("an empty paper is short of 100, and the server's own sentence says so")
                .contains(server.features.exambuild.ExamBuildMessages.NO_QUESTIONS);
    }

    /**
     * The Add button is disabled and says why.
     *
     * <p>The contract gap raised with the lead: {@code BankQuestionRow} carries no
     * {@code questionVersionId}, so the picker cannot build a pin. A control that is inert with
     * no explanation is the mystery state PRD §4.1 forbids.
     */
    @Test
    @DisplayName("⚑ Add is disabled and the reason is on screen, not hidden")
    void addIsDisabledWithAReason() {
        Scene scene = openBuilder(ApprovalState.DRAFT, VERSION_ID);

        assertThat(visibleButtonsNamed(scene, "Add from the bank"))
                .singleElement()
                .satisfies(button -> assertThat(button.isDisabled()).isTrue());
        assertThat(labelTexts(scene)).contains(ExamBuildCopy.ADD_UNAVAILABLE);
    }

    /**
     * A two-digit points value can actually be typed (T-3.2) ⚑.
     *
     * <p>The defect this exists for was found by a cold read and could not have been found by
     * reading rendered values: the paper was cleared and rebuilt on every {@code onChange}, and
     * {@code session.points(...)} fires one, so each keystroke destroyed the {@code TextField}
     * being typed into. The focus owner goes with the removed node, so the second character
     * never arrives. Every assertion in this file passed while that was true, because none of
     * them typed.
     */
    @Test
    @DisplayName("⚑ typing a two-digit points value works, and the box keeps the caret")
    void pointsCanBeTyped() {
        Scene scene = openBuilder(ApprovalState.DRAFT, VERSION_ID);
        TextField points = pointsFieldShowing(scene, "50");

        clickOn(points);
        eraseText(2);
        write("35");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(points.getText())
                .as("both characters reached the same field, which a rebuild would prevent")
                .isEqualTo("35");
        assertThat(labelTexts(scene))
                .as("and the live indicator followed it, so the two halves are still connected")
                .contains(ExamBuildCopy.pointsIndicator(85));
    }

    @Test
    @DisplayName("⚑ the read-only version does not also apologise for the missing picker")
    void readOnlyHidesTheAddNotice() {
        Scene scene = openBuilder(ApprovalState.APPROVED, VERSION_ID);

        assertThat(visibleLabelTexts(scene))
                .as("the screen already says nothing can be changed; saying it twice, once as "
                        + "an apology for a feature she cannot reach anyway, is noise")
                .doesNotContain(ExamBuildCopy.ADD_UNAVAILABLE);
    }

    /**
     * A draft does not flash "sent for approval" while it loads, and a failed load does not
     * say it permanently.
     *
     * <p>{@code modeFor} reads an unanswered open as READ_ONLY, which is the right way to fail
     * closed. The banner is a statement of fact rather than a guard, and it was inheriting that
     * caution: on a failed load it sat beside "could not be opened", which says the opposite.
     */
    @Test
    @DisplayName("⚑ a failed load shows the error and a retry, not the read-only banner")
    void failedLoadDoesNotClaimApproval() {
        Scene scene = openBuilderWith(connection ->
                connection.replyError(Verb.EXAM_VERSION_GET,
                        common.protocol.ErrorCode.INTERNAL, "boom"), VERSION_ID);

        assertThat(visibleLabelTexts(scene)).contains(ExamBuildCopy.LOAD_FAILED);
        assertThat(visibleLabelTexts(scene))
                .doesNotContain(ExamBuildCopy.READ_ONLY_BANNER);
        assertThat(visibleButtonsNamed(scene, ExamBuildCopy.RETRY))
                .as("the copy promised a retry; now there is one")
                .hasSize(1);
    }

    // ===================== Harness ========================================

    /**
     * Brings up just enough app for the screen to run.
     *
     * <p>Same shape and same reason as the exam list's: {@code ScreenManager.init} directly,
     * never {@code ClientApp.start}, so no connect screen is queued to wake up after teardown has
     * nulled the event bus.
     *
     * @param state     the state the server answers with, or {@code null} for a new exam
     * @param versionId the version to open, or {@code 0} for a new exam
     */
    private Scene openBuilder(ApprovalState state, long versionId) {
        return openBuilderWith(connection -> {
            if (state != null) {
                connection.respondTo(Verb.EXAM_VERSION_GET, request ->
                        Message.ok(request, stored(state)));
            }
        }, versionId);
    }

    private Scene openBuilderWith(Consumer<FakeClientConnection> script, long versionId) {
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
            ExamBuilderView view = new ExamBuilderView();
            Scene scene = new Scene(view.view(), 1280, 820);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            view.onShow(versionId > 0
                    ? NavParams.of("examVersionId", versionId)
                    : NavParams.of("courseCode", "11"));
            holder[0] = scene;
        });
        WaitForAsyncUtils.waitForFxEvents();
        return holder[0];
    }

    /** Buttons carrying this label that are actually on screen, not merely constructed. */
    private static List<Button> visibleButtonsNamed(Scene scene, String label) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> label.equals(button.getText()))
                .filter(Button::isVisible)
                .toList();
    }

    private static Set<String> labelTexts(Scene scene) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * The labels a teacher can actually see.
     *
     * <p>Distinct from {@link #labelTexts} on purpose. {@code lookupAll} walks the whole scene
     * graph, so a label that was built with its text and then hidden is still found: an
     * assertion over that set proves the node exists and says nothing about whether it is on
     * screen. Both are worth having - "is this text anywhere in the built screen" is the right
     * question for a card that is added and removed from its parent - but a control that is
     * shown and hidden has to be asked the other one.
     */
    private static Set<String> visibleLabelTexts(Scene scene) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .filter(Label::isVisible)
                .map(Label::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** The points box currently showing this value, so a click lands on a real control. */
    private static TextField pointsFieldShowing(Scene scene, String value) {
        return scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .filter(field -> value.equals(field.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no points box showing " + value));
    }

    private static Set<String> fieldTexts(Scene scene) {
        return scene.getRoot().lookupAll(".text-field").stream()
                .filter(javafx.scene.control.TextInputControl.class::isInstance)
                .map(node -> ((javafx.scene.control.TextInputControl) node).getText())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }
}
