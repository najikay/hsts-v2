package client.features.exambuild;

import client.core.FxTestHarness;
import client.core.NavEntry;
import client.core.NavParams;
import client.core.NavigationEvent;
import client.core.Routes;
import client.features.approval.ExamPreviewView;
import client.features.bank.BankCopy;
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
import common.dto.bank.QuestionRequest;
import common.dto.bank.QuestionVersionDetail;
import common.dto.bank.VersionHistory;
import common.dto.lock.EntityRef;
import common.dto.lock.LockChange;
import common.dto.lock.LockHolder;
import common.dto.lock.LockRequest;
import common.dto.lock.LockResponse;
import common.protocol.Message;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.service.query.PointQuery;
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
 * Real-input interaction test for the exam builder (E7.11 to E7.14 — F3.1, F3.3, T-3.2).
 *
 * <p>What only a booted toolkit can show: that {@link ExamBuilderView} builds at all, that a
 * loaded draft's metadata and questions really reach controls, that the live points indicator
 * really paints its total and the server's sentence, and that a read-only version really renders
 * inert with its banner. Every one is a wiring claim the FX-free
 * {@link ExamBuilderSessionTest} cannot make.
 *
 * <p>Without this file nothing in the build ever constructs {@link ExamBuilderView}, and a null
 * dereference in {@code build()} would ship green. It is on the JaCoCo exclusion list as of the
 * lead's assembly commit, so this file is the only thing that executes it at all.
 *
 * <p><b>It drives the view directly rather than navigating to it.</b> That was originally forced -
 * {@code exams.build} did not exist and navigating would have failed for a reason
 * {@link ExamBuilderWiringGuardTest} already owns. <b>The route exists since assembly 3
 * (2026-08-26) and this paragraph said otherwise until PR24 corrected it.</b> Driving it directly
 * is now a choice rather than a constraint, and it is the right one: navigation is that guard's
 * subject, and routing every render case through it would make a wiring failure and a rendering
 * failure indistinguishable here.
 *
 * <h2>Every click scrolls first, and cannot not ⚑</h2>
 *
 * <p>The robot presses screen coordinates. A control the headless screen cannot show either throws
 * or, worse, takes no click while everything downstream reads a screen the test never touched.
 * Both happened in this file during PR24, and the silent one cost the longer debugging.
 *
 * <p>{@link #point(Node)} is overridden to scroll the node into view first. Every robot method
 * that takes a {@code Node} resolves its target through it, so this holds by construction for all
 * of them rather than by convention. It was a separate {@code clickOnNode} helper until
 * 2026-08-26, and one call site had already grown past it. The query forms
 * ({@code clickOn("#id")} and friends) resolve through a different overload and are not used here.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class ExamBuilderInteractionTest extends ApplicationTest {

    private static final Instant WHEN = Instant.parse("2026-08-24T09:00:00Z");
    private static final long VERSION_ID = 7001L;

    /** The builder the last {@code openBuilderWith} put on screen. */
    private ExamBuilderView lastBuilder;

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
    @DisplayName("⚑ opening a draft asks for its lock, keyed exactly as the server keys it (E18.5)")
    void openingAcquiresTheLockOnTheRightEntity() {
        FakeClientConnection[] held = new FakeClientConnection[1];
        openBuilderWith(connection -> {
            held[0] = connection;
            connection.respondTo(Verb.EXAM_VERSION_GET, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT)));
            connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request, bank()));
        }, VERSION_ID);

        Message acquire = held[0].sentMessages().stream()
                .filter(message -> message.getVerb() == Verb.LOCK_ACQUIRE)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("the builder never asked for the lock"));

        // The whole feature is this one value agreeing with the server. ExamService's
        // lockHolderOtherThan builds new EntityRef(EXAM_VERSION, version.getId()) when it decides
        // whether to refuse a write; a client keyed on anything else - the exam id instead of the
        // version id, or the QUESTION type - would ask about a row nobody holds, be told it is
        // free for ever, and paint no banner. Nothing else in the suite would notice, because
        // both sides would be internally consistent and talking about different rows.
        //
        // So the expected value is written out as literals rather than rebuilt from the constant
        // the production code used. EntityRef.EXAM_VERSION here would agree with itself if the
        // constant were ever changed, which is the tautology this file has been bitten by before.
        assertThat(((LockRequest) acquire.getPayload()).entity())
                .isEqualTo(new EntityRef("exam-version", VERSION_ID));
    }

    @Test
    @DisplayName("⚑ a new exam asks for no lock, because there is no row to hold (E18.5)")
    void createModeAcquiresNothing() {
        FakeClientConnection[] held = new FakeClientConnection[1];
        openBuilderWith(connection -> {
            held[0] = connection;
            connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request, bank()));
        }, 0);

        // Mode.CREATE runs with examVersionId == 0 until EXAM_CREATE answers. An EntityRef on 0
        // names a row that cannot exist, and asking for it would put every unsaved builder in the
        // school on one shared lock.
        assertThat(held[0].sentMessages())
                .noneSatisfy(message ->
                        assertThat(message.getVerb()).isEqualTo(Verb.LOCK_ACQUIRE));
    }

    @Test
    @DisplayName("⚑ another teacher's hold paints the banner and freezes the form (E18.5)")
    void anotherHolderFreezesTheBuilder() {
        FakeClientConnection[] held = new FakeClientConnection[1];
        Scene scene = openBuilderWith(connection -> {
            held[0] = connection;
            connection.respondTo(Verb.EXAM_VERSION_GET, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT)));
            connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request, bank()));
        }, VERSION_ID);

        interact(() -> held[0].pushToClient(Verb.PUSH_LOCK_CHANGED,
                new LockChange(new EntityRef("exam-version", VERSION_ID),
                        LockChange.Kind.ACQUIRED, new LockHolder(4, "Avi Mizrahi"))));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(labelTexts(scene))
                .as("she is told who holds it before she types, not after she saves")
                .anySatisfy(text -> assertThat(text).contains("Avi Mizrahi"));
        assertThat(visibleButtonsNamed(scene, ExamBuildCopy.saveButton(ExamBuilderSession.Mode.EDIT)))
                .as("the form is inert rather than merely warned about: isEditable gates the "
                        + "whole footer, so Save leaves the screen entirely")
                .isEmpty();
    }

    @Test
    @DisplayName("⚑ leaving the builder gives the lock back at once (E18.5)")
    void hidingReleasesTheLock() {
        FakeClientConnection[] held = new FakeClientConnection[1];
        openBuilderWith(connection -> {
            held[0] = connection;
            connection.respondTo(Verb.EXAM_VERSION_GET, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT)));
            connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request, bank()));
        }, VERSION_ID);

        assertThat(verbsSentOn(held[0]))
                .as("it holds the lock to begin with, or the release proves nothing")
                .contains(Verb.LOCK_ACQUIRE);

        // The instance the scene is showing, driven through the hook ScreenLifecycle calls.
        interact(() -> lastBuilder.onHide());
        WaitForAsyncUtils.waitForFxEvents();

        // Without this the server keeps telling the next teacher that Dana is editing for the
        // whole TTL after she has navigated away. The sweep is the safety net, not the mechanism.
        List<Verb> after = verbsSentOn(held[0]);
        assertThat(after).contains(Verb.LOCK_RELEASE);

        // And nothing takes it back. This assertion is the finding: the first version of this
        // test asserted only that a release was SENT, and that stayed true while close()'s own
        // IDLE snapshot drove the session locked-out, fired onChange, and had syncLock re-acquire
        // on the same pulse. The release was real and the lock was never given up - a test naming
        // the behaviour while surviving its inversion, which is P-6's shape exactly.
        assertThat(after.subList(after.lastIndexOf(Verb.LOCK_RELEASE), after.size()))
                .as("the release must be the last word on this lock, not the middle of a cycle")
                .doesNotContain(Verb.LOCK_ACQUIRE);
    }

    @Test
    @DisplayName("⚑ a finished version is read, not locked (E18.5)")
    void readOnlyVersionTakesNoLock() {
        FakeClientConnection[] held = new FakeClientConnection[1];
        openBuilderWith(connection -> {
            held[0] = connection;
            connection.respondTo(Verb.EXAM_VERSION_GET, request ->
                    Message.ok(request, stored(ApprovalState.APPROVED)));
            connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request, bank()));
        }, VERSION_ID);

        // An edit lock is exclusive and heartbeated. Taking one to READ a finished version would
        // hold a row nobody can edit, on a screen that refuses every edit anyway, and would leave
        // every past version a teacher ever opened sitting under her name for as long as the
        // screen stayed up. Contract section 8's read path is a read.
        assertThat(verbsSentOn(held[0])).doesNotContain(Verb.LOCK_ACQUIRE);
    }

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

        // Reachable because the click scrolls this row into view first, NOT because the harness
        // sizes the window past the whole form - this comment claimed the latter until 2026-08-26
        // and measurement disproved it: with the scroll removed from the override, this exact line
        // throws BoundsLocatorException. E7.13 added a pane above this one, and the window is not
        // tall enough to make that irrelevant.
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

    /**
     * The whole point of pressing "Use the newer version": seeing the newer version ⚑.
     *
     * <p>Found by a cold read. The paper is rebuilt only when its <em>shape</em> changes, and the
     * shape was the list of pinned version ids. A re-pin changes an id, so the card rebuilt at the
     * click, carrying the old stem by design. The save's re-read then returned rows with those
     * same ids, so the shape was identical and nothing rebuilt: the corrected wording never
     * reached the screen, while the notice that had promised it would was hidden because the save
     * had "kept the promise".
     *
     * <p>The saved pin was correct throughout, which makes it worse rather than better. She sees
     * an unchanged question, no badge, no notice and no control left to press, and the only
     * available conclusion is that the update silently failed.
     */
    @Test
    @DisplayName("⚑ after saving a re-pin, the corrected wording is actually on the paper")
    void savingARepinShowsTheNewWording() {
        Scene scene = openBuilderWith(connection -> {
            connection.respondTo(Verb.EXAM_VERSION_GET, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT)));
            connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request, bank()));
            // The server's re-read after the save: 11002 now pinned at the version it was moved
            // to, and carrying THAT version's wording, which is the whole reason she pressed it.
            connection.respondTo(Verb.EXAM_VERSION_SAVE, request -> Message.ok(request,
                    new ExamComposition(700L, "110101", "11", "Algebra", VERSION_ID, 2,
                            ApprovalState.DRAFT, "Algebra midterm", 90, "Good luck",
                            "Marking notes", "Dana Cohen", WHEN, "",
                            List.of(question(9001L, "11001", 1, 50, 1, 1),
                                    new ComposedQuestion(509002L, "11002", 2, 50,
                                            "What is a base case, corrected?", "Recursion",
                                            Difficulty.MEDIUM, false, 4, 4, 509002L)),
                            4)));
        }, VERSION_ID);

        clickOn(visibleButtonsNamed(scene, ExamBuildCopy.USE_NEWER_VERSION).get(0));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(visibleLabelTexts(scene))
                .as("the act landed: the row is re-pinned and says it is showing stale details")
                .contains(ExamBuildCopy.REPINNED_NOTICE);

        clickOn(visibleButtonsNamed(scene, ExamBuildCopy.SAVE_BUTTON).get(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(visibleLabelTexts(scene))
                .as("the corrected stem the server sent back is the one on her paper")
                .contains("What is a base case, corrected?");
        assertThat(visibleLabelTexts(scene))
                .as("and the notice has gone because it is true that nothing is stale any more")
                .doesNotContain(ExamBuildCopy.REPINNED_NOTICE);
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

    // ===================== Round 3: the header and the way out ============

    /**
     * The builder says which course the paper is for (U-30).
     *
     * <p>It never did. The course is picked in the exam list's New exam menu and then travels as
     * a nav parameter, so the one screen that spends it - the bank picker is scoped to it,
     * {@code EXAM_CREATE} carries it - was the one screen that never named it. A teacher with
     * four courses had no way to check she was in the right one short of opening the picker.
     */
    @Test
    @DisplayName("⚑ U-30: an open draft names its course in the header")
    void editNamesItsCourse() {
        Scene scene = openBuilder(ApprovalState.DRAFT, VERSION_ID);

        assertThat(visibleLabelTexts(scene))
                .as("the code and the name, in the spelling the exam list uses")
                .contains(ExamBuildCopy.courseLine("11", "Algebra"));
    }

    /**
     * And so does a new exam, which is the half that needed the sign-in payload ⚑.
     *
     * <p>{@code ExamBuilderSession} carries a course NAME only for a version it has loaded:
     * {@code openNew} takes the code as given and blanks the name. So in {@code Mode.CREATE} the
     * word "Algebra" can only have come from {@code LoginResult.courses()}, resolved against the
     * code the navigation carried. That is the whole claim, and asserting the code alone would
     * pass without it.
     */
    @Test
    @DisplayName("⚑ U-30: a new exam names its course, resolved from the sign-in payload")
    void newExamNamesItsCourse() {
        Scene scene = openBuilder(null, 0);

        assertThat(visibleLabelTexts(scene))
                .as("the session holds no name in CREATE, so this one came off the payload")
                .contains(ExamBuildCopy.courseLine("11", "Algebra"));
        assertThat(visibleLabelTexts(scene)).contains(ExamBuildCopy.TITLE_NEW);
    }

    /**
     * A save that lands takes her back to the list, with the saved exam selected (U-31).
     *
     * <p>Saving used to leave her exactly where she was: a toast reading "Saved." over a screen
     * that looked identical before and after. The list is where a saved exam lives.
     *
     * <p><b>Asserted on the navigation rather than on a rendered list.</b> This file drives the
     * builder directly and never boots the shell, so what is checkable here is that the screen
     * asks for the right destination with the right parameter; that the exam list then selects
     * on it is {@code ExamListInteractionTest.deepLinkOpensTheOwningExam}, which drives the same
     * parameter from the other side.
     *
     * <p>The navigation hangs off {@code saveNotice}, which {@code settleSave} sets on a
     * successful answer and nowhere else, so it is mode-independent by construction: EDIT is
     * exercised here because CREATE cannot press the button until its paper reaches 100 points.
     */
    @Test
    @DisplayName("⚑ U-31: a landed save leaves for the exam list, carrying the saved version")
    void savingReturnsToTheList() {
        Scene scene = openBuilderWith(connection -> {
            connection.respondTo(Verb.EXAM_VERSION_GET, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT)));
            connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request, bank()));
            connection.respondTo(Verb.EXAM_VERSION_SAVE, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT)));
        }, VERSION_ID);

        List<NavigationEvent> navigations = registerTheExamList();

        clickOn(visibleButtonsNamed(scene, ExamBuildCopy.SAVE_BUTTON).get(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(navigations).as("the act landed: something navigated").hasSize(1);
        NavEntry target = navigations.get(0).to();
        assertThat(target.routeId())
                .as("the exam list, named rather than remembered: a back() would land on "
                        + "whatever she came from, which for a notification is the dashboard")
                .isEqualTo(Routes.EXAMS.id());
        assertThat(target.params().getLong("examVersionId", 0))
                .as("⚑ and the version the server just wrote, which is the parameter "
                        + "ExamListView.onShow already reads")
                .isEqualTo(VERSION_ID);
    }

    /**
     * A refused save keeps her on the builder, with the paper the refusal is about (U-31).
     *
     * <p>The other half of the rule, and the one worth a test of its own: navigating away from a
     * failure would take the teacher off the only screen where the sentence means anything and
     * leave the unsaved paper behind.
     */
    @Test
    @DisplayName("⚑ U-31: a refused save stays put, message and paper intact")
    void aRefusedSaveStaysOnTheBuilder() {
        Scene scene = openBuilderWith(connection -> {
            connection.respondTo(Verb.EXAM_VERSION_GET, request ->
                    Message.ok(request, stored(ApprovalState.DRAFT)));
            connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request, bank()));
            connection.replyError(Verb.EXAM_VERSION_SAVE,
                    common.protocol.ErrorCode.CONFLICT, "Somebody else saved this first.");
        }, VERSION_ID);

        List<NavigationEvent> navigations = registerTheExamList();

        clickOn(visibleButtonsNamed(scene, ExamBuildCopy.SAVE_BUTTON).get(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(navigations)
                .as("nothing navigated, because nothing was saved")
                .isEmpty();
        assertThat(visibleLabelTexts(scene))
                .as("and she is still looking at the paper the refusal is about")
                .contains(ExamBuildCopy.PAPER_TITLE);
    }

    /**
     * Registers the exam list so a navigation to it can complete, and records what arrives.
     *
     * <p>A stub screen, exactly as the exam list's own interaction test stubs the builder: the
     * claim under test is which destination is asked for and with what, not what that screen
     * then renders.
     *
     * @return the list the navigator appends to, empty until something navigates
     */
    private List<NavigationEvent> registerTheExamList() {
        ScreenManager manager = ScreenManager.getInstance();
        manager.navigator().register(Routes.EXAMS);
        manager.screens().register(Routes.EXAMS.id(), StubScreen::new);

        List<NavigationEvent> navigations = new java.util.ArrayList<>();
        manager.navigator().addListener(navigations::add);
        return navigations;
    }

    /**
     * Registers the exam preview so the header's Preview has somewhere to go (U-53).
     *
     * <p>The same stub shape as {@link #registerTheExamList}: what is under test is which
     * destination is asked for and with what, not what that screen renders. The real one is
     * driven by {@code ApprovalInteractionTest}, which is where the author's view of it lives.
     *
     * @return the list the navigator appends to, empty until something navigates
     */
    private List<NavigationEvent> registerThePreview() {
        ScreenManager manager = ScreenManager.getInstance();
        manager.navigator().register(Routes.EXAM_PREVIEW);
        manager.screens().register(Routes.EXAM_PREVIEW.id(), StubScreen::new);

        List<NavigationEvent> navigations = new java.util.ArrayList<>();
        manager.navigator().addListener(navigations::add);
        return navigations;
    }

    /** Stands in for the exam list so a navigation can complete without booting it. */
    private static final class StubScreen extends client.ui.screen.AbstractScreen {
        @Override
        protected javafx.scene.Parent build() {
            return new javafx.scene.layout.VBox();
        }
    }

    // ===================== The answers on a picked row (U-53) =============

    /**
     * Findings.txt U-53, on the real toolkit ⚑.
     *
     * <p>"A teacher composing an exam cannot see what the exam says." The session test proves the
     * right version is selected; this proves the four options and the word "Correct" are really
     * in the scene graph after a real click, which is the claim the finding is about.
     */
    @Test
    @DisplayName("⚑ Show answers puts the four options and the key on screen (U-53)")
    void showingAnswersRevealsTheFourOptionsAndTheKey() {
        Scene scene = openBuilder(ApprovalState.DRAFT, VERSION_ID);

        assertThat(visibleLabelTexts(scene))
                .as("a row is collapsed until she asks: the answers are not merely hidden, "
                        + "they have not been read")
                .doesNotContain("A method that calls itself");

        clickOn(visibleButtonsNamed(scene, ExamBuildCopy.SHOW_ANSWERS).get(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(visibleLabelTexts(scene))
                .as("the four options of the version this paper pins, with the key marked "
                        + "in a word rather than in a colour")
                .contains("A method that calls itself", "A while loop", "A kind of array",
                        "A compiler flag", BankCopy.CORRECT_MARK);
        assertThat(verbsSentOn((FakeClientConnection) ScreenManager.getInstance().getClient()))
                .as("read with the bank's own history verb, which is the only one that can "
                        + "answer for the version the paper pins rather than the newest")
                .contains(Verb.QUESTION_VERSIONS);
    }

    @Test
    @DisplayName("the toggle names the state she is moving to, and moves back")
    void theToggleFlipsBack() {
        Scene scene = openBuilder(ApprovalState.DRAFT, VERSION_ID);

        clickOn(visibleButtonsNamed(scene, ExamBuildCopy.SHOW_ANSWERS).get(0));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(visibleButtonsNamed(scene, ExamBuildCopy.HIDE_ANSWERS)).hasSize(1);

        clickOn(visibleButtonsNamed(scene, ExamBuildCopy.HIDE_ANSWERS).get(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(visibleLabelTexts(scene)).doesNotContain("A method that calls itself");
        assertThat(visibleButtonsNamed(scene, ExamBuildCopy.HIDE_ANSWERS)).isEmpty();
    }

    /**
     * The rebuild rule, from the other side ⚑.
     *
     * <p>{@code renderAnswers} runs outside {@code shapeOf} precisely so an answers read landing
     * cannot destroy a points box mid-keystroke. What that buys is asserted here rather than
     * argued: the box she was typing in still holds what she typed, and still has the focus, on
     * the render the answers arrived on.
     */
    @Test
    @DisplayName("⚑ answers arriving do not rebuild the card under the points box")
    void answersDoNotDestroyThePointsBox() {
        Scene scene = openBuilder(ApprovalState.DRAFT, VERSION_ID);
        TextField points = pointsFieldShowing(scene, "50");
        clickOn(points);
        eraseText(2);
        write("7");
        WaitForAsyncUtils.waitForFxEvents();

        clickOn(visibleButtonsNamed(scene, ExamBuildCopy.SHOW_ANSWERS).get(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(points.getScene())
                .as("the very TextField she was typing in is still in the scene")
                .isSameAs(scene);
        assertThat(points.getText()).isEqualTo("7");
        assertThat(visibleLabelTexts(scene)).contains("A method that calls itself");
    }

    // ===================== The preview (U-53) =============================

    @Test
    @DisplayName("⚑ Preview is inert on a new exam, which has no saved version to read")
    void previewIsInertBeforeTheFirstSave() {
        Scene scene = openBuilder(null, 0);

        Button preview = visibleButtonsNamed(scene, ExamBuildCopy.PREVIEW_BUTTON).get(0);
        assertThat(preview.isDisable())
                .as("EXAM_PREVIEW_GET is addressed by version, and a new exam has none")
                .isTrue();
        assertThat(preview.getTooltip())
                .as("and it says why, rather than being a control that simply does nothing")
                .isNotNull();
    }

    @Test
    @DisplayName("⚑ Preview opens the saved version, carrying its id and the door it came from")
    void previewNavigatesToTheSavedVersion() {
        Scene scene = openBuilder(ApprovalState.DRAFT, VERSION_ID);
        List<NavigationEvent> navigations = registerThePreview();

        Button preview = visibleButtonsNamed(scene, ExamBuildCopy.PREVIEW_BUTTON).get(0);
        assertThat(preview.isDisable())
                .as("an opened draft is already saved, so there is a version to read")
                .isFalse();

        clickOn(preview);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(navigations).as("the act landed: something navigated").hasSize(1);
        NavEntry target = navigations.get(0).to();
        assertThat(target.routeId())
                .as("the coordinator's own preview route, not a second screen built for authors")
                .isEqualTo(Routes.EXAM_PREVIEW.id());
        assertThat(target.params().getLong("examVersionId", 0))
                .as("⚑ and the version on screen, which is what the preview is addressed by")
                .isEqualTo(VERSION_ID);
        assertThat(target.params().getString(ExamPreviewView.PARAM_FROM, ""))
                .as("so the preview's Back can name the builder rather than the approvals queue")
                .isEqualTo(ExamBuildRoutes.BUILDER);
    }

    /**
     * The preview is a read, so the two things that stop every write do not stop it.
     *
     * <p>A version sent for approval is exactly the one a teacher opens to check what students
     * will be asked, and that screen has no Save button at all.
     */
    @Test
    @DisplayName("a read-only version still offers Preview, where it offers nothing else")
    void readOnlyStillPreviews() {
        Scene scene = openBuilder(ApprovalState.PENDING, VERSION_ID);

        assertThat(visibleButtonsNamed(scene, ExamBuildCopy.SAVE_BUTTON)).isEmpty();
        assertThat(visibleButtonsNamed(scene, ExamBuildCopy.PREVIEW_BUTTON))
                .singleElement()
                .satisfies(preview -> assertThat(preview.isDisable()).isFalse());
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
            connection.respondTo(Verb.QUESTION_VERSIONS, request -> Message.ok(request,
                    history(((QuestionRequest) request.getPayload()).displayId5())));
        }, versionId);
    }

    /**
     * The version history a picked row's Show answers reads (U-53).
     *
     * <p>Two versions with different options and different keys, because the fixture's second
     * row pins v2 while the bank holds v4: an assertion that only proved "four options appeared"
     * would pass on either version, and which one appears is the whole claim.
     */
    private static VersionHistory history(String displayId5) {
        return new VersionHistory(displayId5, List.of(
                new QuestionVersionDetail(4, "What is recursion, restated?",
                        List.of("A call to itself", "A loop", "A stack", "A queue"), 2,
                        "Recursion", Difficulty.MEDIUM, false, "Dana Cohen", WHEN),
                new QuestionVersionDetail(1, "What is recursion?",
                        List.of("A method that calls itself", "A while loop",
                                "A kind of array", "A compiler flag"), 1,
                        "Recursion", Difficulty.MEDIUM, false, "Dana Cohen", WHEN)));
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

            // The builder takes an edit lock on open (E18.5), and an unanswered acquire is
            // fail-closed: LockAwareEditor.applyAnswer treats "cannot prove the lock is ours" as
            // not-editable, isEditable goes false, and the footer and Add leave the screen. So a
            // test that scripted no lock would be asserting about a builder nobody can type in -
            // which is exactly what happened when this was added: five tests that had nothing to
            // do with locking started failing on empty lookups. Granting is the ordinary case;
            // anotherHolderFreezesTheBuilder pushes the refusal on top instead.
            //
            // The grant echoes the entity it was asked about rather than naming one, so this
            // harness cannot disguise a client that keys the lock wrongly. That is
            // openingAcquiresTheLockOnTheRightEntity's job, and it holds a literal for it.
            connection.respondTo(Verb.LOCK_ACQUIRE, request -> Message.ok(request,
                    LockResponse.granted(((LockRequest) request.getPayload()).entity(),
                            new LockHolder(DANA.userId(), DANA.displayName()),
                            Instant.now().plusSeconds(120))));
            connection.replyOk(Verb.LOCK_RELEASE, null);
            connection.replyOk(Verb.LOCK_RENEW, null);
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
            // Kept so a test can drive the lifecycle hooks on the instance the scene is actually
            // showing. hidingReleasesTheLock needs onHide on that one; a fresh view holds nothing
            // and would pass the assertion by never having taken a lock at all.
            lastBuilder = view;
            // Deliberately a plausible window rather than a tall one. The robot presses
            // coordinates, so a control the headless screen cannot show is a control the click
            // misses - silently, leaving the assertions afterwards reading a screen the test
            // never touched. Growing the scene to 1600 fixed that for the picker and broke it for
            // the footer, whose Save then sat below the screen: the BorderPane's bottom is
            // pinned to the SCENE, and only the body scrolls. So the scene stays a size a screen
            // can show, and anything inside the scrolling body is brought into view by
            // bringIntoView before the robot is allowed to aim at it.
            Scene scene = new Scene(view.view(), 1280, 700);
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

    /**
     * Bring a node into view before the robot is allowed to aim at it ⚑.
     *
     * <p>TestFX's robot presses screen coordinates. A node that is in the scene but below what the
     * headless screen shows takes no click, and nothing fails: the assertions afterwards read a
     * screen the test never touched. That failure has appeared twice in this file, once for the
     * picker and once for the footer, and both times the symptom was a value that had simply not
     * moved.
     *
     * <p><b>Why this hook and not {@code clickOn}.</b> This began as a {@code clickOnNode} helper
     * whose javadoc claimed "every click in this file goes through here". That was true when
     * written and stopped being true as the file grew: by 2026-08-26 one call site, the Generate
     * button in the auto tab, called {@code clickOn} directly and skipped the scroll. It passed,
     * though whether because that button sat above the fold or because an earlier click in the
     * same test had already scrolled it there was never separated. The first
     * repair overrode {@code clickOn(Node, MouseButton...)} and claimed the gap was now
     * structural. <b>It was not, and a cold read caught it.</b> That signature is an interface
     * default on {@code FxRobotInterface} whose whole body forwards to the abstract
     * {@code clickOn(Node, Motion, MouseButton...)}, so {@code clickOn(node, Motion.DIRECT)},
     * {@code doubleClickOn(node)} and {@code rightClickOn(node)} all still reached an unscrolled
     * click. The claim was the same defect it was fixing, one level up.
     *
     * <p>{@code point(Node)} is the real choke point: it is abstract on the interface, and
     * {@code FxRobot}'s node-targeted methods resolve their target through it - verified against
     * {@code testfx-core-4.0.18} with {@code javap -c}, where both
     * {@code clickOn(Node, Motion, MouseButton...)} and
     * {@code doubleClickOn(Node, Motion, MouseButton...)} call {@code point(Node)} as their first
     * instruction. Scrolling here is also the honest semantics: {@code point(Node)} means "aim at
     * this node", and aiming at a node the screen cannot show is the bug.
     *
     * <p><b>Not covered:</b> the query forms - {@code clickOn("#id")}, {@code clickOn(matcher)},
     * {@code clickOn(predicate)} - resolve through their own {@code point} overloads. This file
     * uses none of them, and a future call site that does would be outside this guarantee.
     */
    @Override
    public PointQuery point(Node node) {
        bringIntoView(node);
        return super.point(node);
    }

    /**
     * Scrolls every {@code ScrollPane} ancestor so the node is on the screen the robot aims at.
     *
     * <p>Does nothing for a node outside any {@code ScrollPane} - the footer is pinned to the
     * scene rather than scrolled - which is why the scene itself is a size a screen can show.
     */
    private void bringIntoView(Node node) {
        // EVERY ScrollPane ancestor, outermost first, re-measuring between each. The picker's rows
        // sit in a ScrollPane inside the body's ScrollPane, and scrolling only the innermost left
        // the whole picker off the screen with the row neatly centred inside it. Outermost first
        // because scrolling an outer pane moves the inner one's coordinates and not the reverse.
        List<ScrollPane> scrollers = new java.util.ArrayList<>();
        for (Node at = node.getParent(); at != null; at = at.getParent()) {
            if (at instanceof ScrollPane scroller) {
                scrollers.add(0, scroller);
            }
        }
        for (ScrollPane scroller : scrollers) {
            interact(() -> {
                Node content = scroller.getContent();
                double contentHeight = content.getBoundsInLocal().getHeight();
                double viewport = scroller.getViewportBounds().getHeight();
                if (contentHeight <= viewport) {
                    return;
                }
                double nodeTop = node.localToScene(node.getBoundsInLocal()).getMinY()
                        - content.localToScene(content.getBoundsInLocal()).getMinY();
                scroller.setVvalue(Math.clamp(nodeTop / (contentHeight - viewport), 0.0, 1.0));
            });
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    /** Buttons carrying this label that are actually on screen, not merely constructed. */
    private static List<Button> visibleButtonsNamed(Scene scene, String label) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> label.equals(button.getText()))
                .filter(ExamBuilderInteractionTest::reallyVisible)
                .toList();
    }

    /** @return every verb sent so far, so a test can assert one was or was not among them */
    private static List<Verb> verbsSentOn(FakeClientConnection connection) {
        return connection.sentMessages().stream().map(Message::getVerb).toList();
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
