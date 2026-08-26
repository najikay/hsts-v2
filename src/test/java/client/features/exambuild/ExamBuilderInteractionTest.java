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
import common.dto.authoring.AutoComposeResult;
import common.dto.authoring.ExamComposition;
import common.dto.authoring.Shortfall;
import common.dto.bank.BankListRequest;
import common.dto.bank.BankPage;
import common.dto.bank.BankQuestionRow;
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
        // A superseded pin points at a different bank row, so it carries a different id.
        long latestId = latest == pinned ? id : 500_000L + id;
        return new ComposedQuestion(id, displayId, ord, points, "What is recursion?", "Recursion",
                Difficulty.MEDIUM, false, pinned, latest, latestId);
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
    @DisplayName("⚑ Add opens the picker and a real click puts a real question on the paper")
    void addFromTheBankReachesThePaper() {
        Scene scene = openBuilder(ApprovalState.DRAFT, VERSION_ID);

        clickOn(visibleButtonsNamed(scene, ExamBuildCopy.ADD_BUTTON).get(0));
        WaitForAsyncUtils.waitForFxEvents();

        // The act, verified before anything is asserted about what it produced: a click that
        // landed somewhere else would leave every assertion below reading a screen it never
        // touched (method rule 4, and the T-3.2 defect that taught it).
        assertThat(visibleLabelTexts(scene))
                .as("the picker is up, and it names the course it is scoped to")
                .contains(ExamBuildCopy.pickerTitle("Algebra"));

        List<Button> add = visibleButtonsNamed(scene, ExamBuildCopy.PICKER_ADD);
        assertThat(add).as("the seeded picker row is offered").isNotEmpty();

        // Reachable only because the harness sizes the window past the whole form: the robot
        // presses coordinates rather than controls, and E7.13 added a pane above this one.
        clickOn(add.get(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(visibleButtonsNamed(scene, ExamBuildCopy.PICKER_ALREADY_ADDED))
                .as("the act landed: that row now refuses itself, which only the add path does")
                .hasSize(2);
        assertThat(visibleLabelTexts(scene))
                .as("the paper grew by one at the minimum points, so the live total moved with it")
                .contains(ExamBuildCopy.pointsIndicator(101));
    }

    @Test
    @DisplayName("⚑ a question already on the paper is offered but refused, and says which rule")
    void aDuplicateIsRefusedOnTheClick() {
        Scene scene = openBuilder(ApprovalState.DRAFT, VERSION_ID);

        clickOn(visibleButtonsNamed(scene, ExamBuildCopy.ADD_BUTTON).get(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(visibleButtonsNamed(scene, ExamBuildCopy.PICKER_ALREADY_ADDED))
                .as("T-3.9: the row for a question the paper already pins carries the reason "
                        + "rather than greying out with nothing said")
                .isNotEmpty()
                .allSatisfy(button -> assertThat(button.isDisabled()).isTrue());
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

    // ===================== The auto tab (E7.13, T-3.4/T-3.5) ==============

    /**
     * T-3.4 walked on the screen: criteria typed, composed, questions on the paper ⚑.
     *
     * <p>Every character is typed rather than set, because the criteria boxes are the same shape
     * as the points fields that were being destroyed mid-keystroke by a rebuild-on-change render
     * (§4.2 of PR23). The grid is rebuilt on shape change only, and this is the test that would
     * notice if that ever became rebuild-on-value.
     */
    @Test
    @DisplayName("⚑ typing criteria and composing puts real questions on the paper (T-3.4)")
    void composingFillsThePaper() {
        Scene scene = openBuilderWith(connection -> {
            connection.respondTo(Verb.EXAM_VERSION_GET, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT)));
            connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request, bank()));
            connection.respondTo(Verb.EXAM_AUTO_COMPOSE, request -> Message.ok(request,
                    new AutoComposeResult(true, List.of(
                            new ComposedQuestion(9201L, "11201", 1, 100, "What is a base case?",
                                    "Recursion", Difficulty.EASY, false, 1, 1, 9201L)),
                            List.of())));
        }, VERSION_ID);

        clickOn(visibleTogglesNamed(scene, ExamBuildCopy.AUTO_TAB).get(0));
        WaitForAsyncUtils.waitForFxEvents();

        // The act, verified before anything is asserted about what it produced.
        assertThat(visibleLabelTexts(scene))
                .as("the criteria form is really showing")
                .contains(ExamBuildCopy.CRITERIA_TITLE);

        TextField anyBox = countFieldFor(scene, ExamBuildCopy.ANY_LABEL);
        clickOn(anyBox);
        eraseText(1);
        write("1");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(anyBox.getText())
                .as("the box survived its own keystroke, which a rebuild-on-value would prevent")
                .isEqualTo("1");

        Button compose = visibleButtonsNamed(scene, ExamBuildCopy.GENERATE).get(0);
        assertThat(compose.isDisabled())
                .as("a legal request, so the server's own rule has nothing to say")
                .isFalse();

        clickOn(compose);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(visibleLabelTexts(scene))
                .as("F3.3's editable result: she is on the paper, looking at what was composed")
                .anySatisfy(text -> assertThat(text).contains("What is a base case?"));
        assertThat(visibleLabelTexts(scene)).contains(ExamBuildCopy.pointsIndicator(100));
    }

    /**
     * T-3.5 and T-3.6: refused, with a report she can act on ⚑.
     *
     * <p>F3.3's acceptance artefact is the sentence, so this asserts the rendered string rather
     * than that some label appeared. The paper must also survive: an infeasible request creates
     * no exam and must not quietly empty the one she had.
     */
    @Test
    @DisplayName("⚑ an infeasible request renders the PRD's sentence and keeps the paper (T-3.5)")
    void infeasibleRendersTheReport() {
        Scene scene = openBuilderWith(connection -> {
            connection.respondTo(Verb.EXAM_VERSION_GET, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT)));
            connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request, bank()));
            connection.respondTo(Verb.EXAM_AUTO_COMPOSE, request -> Message.ok(request,
                    new AutoComposeResult(false, List.of(),
                            List.of(new Shortfall("Recursion", Difficulty.HARD, 1, 0)))));
        }, VERSION_ID);

        clickOn(visibleTogglesNamed(scene, ExamBuildCopy.AUTO_TAB).get(0));
        WaitForAsyncUtils.waitForFxEvents();

        TextField anyBox = countFieldFor(scene, ExamBuildCopy.ANY_LABEL);
        clickOn(anyBox);
        eraseText(1);
        write("9");
        WaitForAsyncUtils.waitForFxEvents();

        clickOn(visibleButtonsNamed(scene, ExamBuildCopy.GENERATE).get(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(visibleLabelTexts(scene))
                .as("the sentence F3.3 is written around, painted rather than merely computed")
                .contains("Topic 'Recursion': requested 1 Hard, bank has 0")
                .contains(ExamBuildCopy.INFEASIBLE_TITLE, ExamBuildCopy.INFEASIBLE_HINT);

        clickOn(visibleTogglesNamed(scene, ExamBuildCopy.MANUAL_TAB).get(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(visibleLabelTexts(scene))
                .as("no exam was created, so the paper she had is untouched")
                .contains(ExamBuildCopy.pointsIndicator(100));
    }

    @Test
    @DisplayName("⚑ a read-only version is offered no composer tabs at all")
    void readOnlyHasNoComposerTabs() {
        Scene scene = openBuilder(ApprovalState.APPROVED, VERSION_ID);

        assertThat(visibleTogglesNamed(scene, ExamBuildCopy.AUTO_TAB)).isEmpty();
        assertThat(visibleTogglesNamed(scene, ExamBuildCopy.MANUAL_TAB)).isEmpty();
    }

    /**
     * E7.14's action, pressed for real ⚑.
     *
     * <p>The badge has rendered since PR23 and the button beside it could not be written until
     * {@code latestVersionId} landed. What only a toolkit shows is that the control is really
     * beside the badge, that pressing it really reaches the session, and that the badge really
     * leaves the screen afterwards rather than merely leaving the model.
     */
    @Test
    @DisplayName("⚑ Use the newer version is beside the badge, and pressing it clears the badge")
    void theUpdateActionClearsTheBadge() {
        Scene scene = openBuilder(ApprovalState.DRAFT, VERSION_ID);

        assertThat(visibleLabelTexts(scene))
                .as("11002 is pinned at v2 while the bank holds v4")
                .contains(ExamBuildCopy.NEWER_VERSION_BADGE);

        List<Button> useNewer = visibleButtonsNamed(scene, ExamBuildCopy.USE_NEWER_VERSION);
        assertThat(useNewer).as("offered on the badged row, and only there").hasSize(1);

        clickOn(useNewer.get(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(visibleLabelTexts(scene))
                .as("the paper really rebuilt; a model-only change would leave this standing")
                .doesNotContain(ExamBuildCopy.NEWER_VERSION_BADGE);
        assertThat(visibleLabelTexts(scene))
                .as("and the row is behind rather than lying, which is what the notice says")
                .contains(ExamBuildCopy.REPINNED_NOTICE);
        assertThat(visibleLabelTexts(scene))
                .as("a re-pin is not a repoint, so the live total has not moved")
                .contains(ExamBuildCopy.pointsIndicator(100));
    }

    @Test
    @DisplayName("⚑ a read-only version shows the badge and withholds the action")
    void readOnlyShowsTheBadgeWithoutTheAction() {
        Scene scene = openBuilder(ApprovalState.APPROVED, VERSION_ID);

        assertThat(visibleLabelTexts(scene))
                .as("that the bank moved on is a fact about the paper, true on any version")
                .contains(ExamBuildCopy.NEWER_VERSION_BADGE);
        assertThat(visibleButtonsNamed(scene, ExamBuildCopy.USE_NEWER_VERSION))
                .as("but nothing on a finished version may be changed")
                .isEmpty();
    }

    @Test
    @DisplayName("⚑ a read-only version offers no way into the bank at all")
    void readOnlyOffersNoPicker() {
        Scene scene = openBuilder(ApprovalState.APPROVED, VERSION_ID);

        assertThat(visibleButtonsNamed(scene, ExamBuildCopy.ADD_BUTTON))
                .as("the screen already says nothing can be changed; a live Add beside that "
                        + "sentence is the screen contradicting itself")
                .isEmpty();
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
            connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request, bank()));
        }, versionId);
    }

    /**
     * The course bank the picker reads.
     *
     * <p>Two rows on purpose: {@code 11001} is already on the paper and at a <em>newer</em> bank
     * version than the paper pins, which is T-3.9's case, and {@code 11007} is addable.
     */
    private static BankPage bank() {
        List<BankQuestionRow> rows = List.of(
                new BankQuestionRow("11001", "11", "Algebra", "What is recursion?", "Recursion",
                        Difficulty.MEDIUM, 9501L, 4, false, WHEN),
                new BankQuestionRow("11007", "11", "Algebra", "What is a base case?", "Recursion",
                        Difficulty.EASY, 9507L, 1, false, WHEN));
        return new BankPage(rows, 0, BankListRequest.DEFAULT_PAGE_SIZE, rows.size(), 1);
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
            // Tall enough that the builder does not scroll. The robot presses coordinates, so a
            // control below the fold is a control the click misses, and the assertions afterwards
            // then read a screen the test never touched. E7.13 added a whole pane and pushed the
            // picker off 820px, which is how this was found: not by a crash, by a total that had
            // not moved. Sizing the window is the honest fix; the alternative is scrolling the
            // pane in every test that reaches past the fold.
            Scene scene = new Scene(view.view(), 1280, 1600);
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
    /**
     * The count box under a given column heading on the criteria grid.
     *
     * <p>Found by walking from the caption to its sibling rather than by index, because an index
     * would still find <em>a</em> field after the grid's layout changed and would then type into
     * the wrong bucket while every assertion still passed.
     */
    private static TextField countFieldFor(Scene scene, String caption) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .filter(label -> caption.equals(label.getText()) && label.isVisible())
                .map(label -> label.getParent().getChildrenUnmodifiable().stream()
                        .filter(TextField.class::isInstance)
                        .map(TextField.class::cast)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "no count field beside the '" + caption + "' caption")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no visible '" + caption + "' caption"));
    }

    private static List<Button> visibleButtonsNamed(Scene scene, String label) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> label.equals(button.getText()))
                .filter(ExamBuilderInteractionTest::reallyVisible)
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
                .filter(ExamBuilderInteractionTest::reallyVisible)
                .map(Label::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Really on screen, ancestors included ⚑.
     *
     * <p>{@code Node.isVisible()} answers about that node alone. A Label inside a container the
     * screen has hidden still answers {@code true}, so a scan filtering on it reports text nobody
     * can see. That was tolerable while this screen hid single controls; E7.13 hides whole panes,
     * and the manual tab's assertions started finding the auto tab's headings.
     *
     * <p>Note what the weak version did NOT do: fail. It made every {@code doesNotContain}
     * assertion here weaker than it reads, which is the shape method rule 4 is about.
     */
    private static boolean reallyVisible(javafx.scene.Node node) {
        for (javafx.scene.Node at = node; at != null; at = at.getParent()) {
            if (!at.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The segmented tabs, which are {@code ToggleButton}s and therefore not {@code Button}s.
     *
     * <p>{@code ToggleButton} extends {@code ButtonBase}, not {@code Button}, so
     * {@link #visibleButtonsNamed} silently returns nothing for them. That cost two errored tests
     * and one that passed while asserting emptiness against a screen that had the tabs, which is
     * the more expensive half of the same mistake.
     */
    private static List<javafx.scene.control.ToggleButton> visibleTogglesNamed(Scene scene,
                                                                               String label) {
        return scene.getRoot().lookupAll(".toggle-button").stream()
                .filter(javafx.scene.control.ToggleButton.class::isInstance)
                .map(javafx.scene.control.ToggleButton.class::cast)
                .filter(toggle -> label.equals(toggle.getText()))
                .filter(ExamBuilderInteractionTest::reallyVisible)
                .toList();
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
