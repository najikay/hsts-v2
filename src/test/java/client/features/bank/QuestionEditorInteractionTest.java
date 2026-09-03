package client.features.bank;

import client.core.FxTestHarness;
import client.core.NavParams;
import client.core.ScreenManager;
import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.ui.theme.ThemeManager;
import client.ui.theme.ThemeState;
import client.events.PushEventBridge;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.bank.Difficulty;
import common.dto.bank.QuestionDetail;
import common.dto.lock.EntityRef;
import common.dto.lock.LockHolder;
import common.dto.lock.LockResponse;
import common.protocol.Message;
import common.protocol.ErrorCode;
import common.protocol.Verb;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
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
 * Real-input interaction test for the question editor (E6.10 / E6.11 — F2.1, C-8, T-2.2).
 *
 * <p>What only a booted toolkit can show: that {@link QuestionEditorView} builds at all, that
 * the form really fills in from a {@code QuestionDetail} <b>without</b> marking itself dirty,
 * that typing a duplicate answer really turns a box red while she types, and that the radio
 * group really is the C-8 guarantee rather than four checkboxes.
 *
 * <p>It exists because nothing else in the build constructs this class. It is on the JaCoCo
 * exclusion list by name, so without this file a null dereference in {@code onShow} would ship
 * green and the first person to press Edit would find out.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class QuestionEditorInteractionTest extends ApplicationTest {

    private static final Instant SPRING = Instant.parse("2026-03-10T07:00:00Z");

    /** The view and the wire the last openEditor produced, so the lock lifecycle is visible. */
    private QuestionEditorView openedView;
    private FakeClientConnection openedConnection;

    private static final LoginResult DANA = new LoginResult(2, "dana.cohen", "Dana Cohen",
            Role.TEACHER, List.of(new CourseRef("11", "Algebra")), 0);

    /** The key the editor locks under, from the one place that decides it. */
    private static final EntityRef BANK_LOCK = EntityRef.question(11005L);

    private static final QuestionDetail GEOMETRY = new QuestionDetail("11005", "11", "Algebra",
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
        // Nothing here: openEditor initialises ScreenManager directly, no shell, no connect screen.
    }

    @AfterEach
    void resetGlobalState() {
        FxTestHarness.resetGlobalState();
    }

    // ===================== The wiring claims =============================

    @Test
    @DisplayName("⚑ the editor builds and fills itself in from the version it was given")
    void editorFillsIn() {
        Scene scene = openEditor(connection -> { },
                NavParams.of(QuestionEditorView.PARAM_DETAIL, GEOMETRY));

        assertThat(textAreaText(scene)).isEqualTo("Read the diagram and answer");
        assertThat(fieldTexts(scene))
                .contains("Twelve", "Fourteen", "Sixteen", "Eighteen", "Geometry");
        assertThat(labelTexts(scene))
                .as("and it says what saving will do, which is not what a teacher expects")
                .contains(QuestionEditorCopy.titleEdit("11005"),
                        QuestionEditorCopy.editSubtitle(2));
    }

    @Test
    @DisplayName("⚑ filling the form in does not mark it dirty")
    void openingIsNotAnEdit() {
        Scene scene = openEditor(connection -> { },
                NavParams.of(QuestionEditorView.PARAM_DETAIL, GEOMETRY));

        assertThat(labelTexts(scene))
                .as("RadioGroup.select is silent by contract and the text boxes are not, so the "
                        + "filling flag is the only thing stopping the discard prompt firing on "
                        + "every Cancel")
                .doesNotContain(QuestionEditorCopy.UNSAVED);
    }

    @Test
    @DisplayName("⚑ typing a duplicate answer turns its box red while she types (real input)")
    void duplicateShowsWhileTyping() {
        Scene scene = openEditor(connection -> { },
                NavParams.of(QuestionEditorView.PARAM_DETAIL, GEOMETRY));

        TextField second = answerBox(scene, 2);
        clickOn(second);
        eraseText(second.getText().length());
        write("twelve");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(messageTexts(scene))
                .as("case alone does not separate two answers under the storage constraint, and "
                        + "she has to be told before she saves rather than after")
                .contains(server.features.bank.BankMessages.answersDuplicated(1, 2));
        assertThat(buttonNamed(scene, QuestionEditorCopy.SAVE).isDisabled())
                .as("blocked, not warned")
                .isTrue();
    }

    @Test
    @DisplayName("the radio group is one choice, not four, which is C-8 on screen")
    void oneCorrectAnswerOnly() {
        Scene scene = openEditor(connection -> { },
                NavParams.of(QuestionEditorView.PARAM_DETAIL, GEOMETRY));

        List<RadioButton> radios = scene.getRoot().lookupAll(".radio-button").stream()
                .filter(RadioButton.class::isInstance)
                .map(RadioButton.class::cast)
                .toList();
        assertThat(radios).hasSize(4);

        clickOn(radios.get(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(radios.stream().filter(RadioButton::isSelected).count())
                .as("ToggleGroup is what guarantees at-most-one, and it is the reason the "
                        + "component was not taken apart to interleave radios with text boxes")
                .isEqualTo(1);
        assertThat(labelTexts(scene))
                .as("and changing the key is an edit")
                .contains(QuestionEditorCopy.UNSAVED);
    }

    @Test
    @DisplayName("a server refusal lands under the box it names (T-2.2)")
    void serverRefusalLandsOnItsBox() {
        Scene scene = openEditor(connection -> connection.replyError(Verb.QUESTION_UPDATE,
                        ErrorCode.VALIDATION, server.features.bank.BankMessages.TOPIC_REQUIRED),
                NavParams.of(QuestionEditorView.PARAM_DETAIL, GEOMETRY));

        // Driven through the controls rather than through the robot, deliberately. The form
        // lives in a ScrollPane and TestFX clicks raw screen coordinates without scrolling to
        // them first, so a click aimed at a field below the viewport lands on whatever is
        // rendered there instead. An earlier version of this test aimed at an answer box and
        // silently pressed the image picker's Remove button; it then asserted about a form it
        // had never typed into. Setting the property exercises the same listener and the same
        // render path, and the claim here is about where a refusal lands, not about the robot.
        // duplicateShowsWhileTyping keeps the real keyboard, on a box that is genuinely in view.
        Button saveButton = buttonNamed(scene, QuestionEditorCopy.SAVE);
        interact(() -> answerBox(scene, 1).setText("Ten"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(saveButton.isDisabled())
                .as("the form has to be savable, or pressing Save below proves nothing")
                .isFalse();
        interact(saveButton::fire);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(messageTexts(scene))
                .as("the server refused on the topic, so the sentence belongs under the topic "
                        + "box and not in a dialog (T-2.2)")
                .contains(server.features.bank.BankMessages.TOPIC_REQUIRED);
    }

    @Test
    @DisplayName("a new question opens on an empty form that says its id comes later")
    void createMode() {
        Scene scene = openEditor(connection -> { },
                NavParams.of(QuestionEditorView.PARAM_COURSE, "11"));

        assertThat(labelTexts(scene))
                .contains(QuestionEditorCopy.TITLE_NEW, QuestionEditorCopy.NEW_SUBTITLE);
        assertThat(textAreaText(scene)).isEmpty();
        assertThat(buttonNamed(scene, QuestionEditorCopy.CREATE).isDisabled())
                .as("an empty form is not savable")
                .isTrue();
    }

    // ===================== Round 5 (U-55, U-56, U-57) ======================

    @Test
    @DisplayName("a new question starts with Answer 1 really marked, not merely focused (U-55)")
    void newQuestionStartsOnAnswerOne() {
        Scene scene = openEditor(connection -> { },
                NavParams.of(QuestionEditorView.PARAM_COURSE, "11"));

        javafx.scene.control.RadioButton first = (javafx.scene.control.RadioButton)
                scene.getRoot().lookup(".radio-option");
        assertThat(first).isNotNull();
        assertThat(first.isSelected())
                .as("the fill on focus used to make Answer 1 look chosen while the session held "
                        + "null; now it is chosen, so leaving it alone is one tab, not three")
                .isTrue();
    }

    @Test
    @DisplayName("an edit opens with the difficulty showing in the box, not blank (U-56)")
    void editShowsItsDifficulty() {
        Scene scene = openEditor(connection -> { },
                NavParams.of(QuestionEditorView.PARAM_DETAIL, GEOMETRY));
        WaitForAsyncUtils.waitForFxEvents();

        @SuppressWarnings("unchecked")
        javafx.scene.control.ComboBox<Difficulty> box = (javafx.scene.control.ComboBox<Difficulty>)
                scene.getRoot().lookup(".question-difficulty");
        assertThat(box).isNotNull();
        assertThat(box.getValue())
                .as("the value is set from the session on the first render (U-96)")
                .isEqualTo(Difficulty.HARD);
        assertThat(box.getConverter().toString(box.getValue()))
                .as("and the converter renders it, so the button shows text from the jump "
                        + "with no re-drive and no reopen (U-96)")
                .isEqualTo(BankCopy.difficulty(Difficulty.HARD));
        // U-96 value-independence: the converter renders every difficulty the same way, so
        // there is no "easy works, hard does not" - the pre-converter build's race signature.
        for (Difficulty d : Difficulty.values()) {
            assertThat(box.getConverter().toString(d)).isEqualTo(BankCopy.difficulty(d));
        }
        // U-98: the BUTTON CELL actually shows the text on first render - the property the
        // old getValue-only assertion missed, and the whole point of the skin re-assert.
        assertThat(renderedButtonText(box))
                .as("the difficulty is visible in the box from the first open, no reopen")
                .isEqualTo(BankCopy.difficulty(Difficulty.HARD));
        assertThat(labelTexts(scene)).doesNotContain(QuestionEditorCopy.UNSAVED);
    }

    @Test
    @DisplayName("every difficulty renders in the box on a first open (U-98, no reopen)")
    void everyDifficultyShowsOnFirstOpen() {
        for (Difficulty difficulty : Difficulty.values()) {
            QuestionDetail detail = new QuestionDetail("11005", "11", "Algebra", 2, 2,
                    "Read the diagram and answer",
                    List.of("Twelve", "Fourteen", "Sixteen", "Eighteen"), 3, "Geometry",
                    difficulty, false, "Dana Cohen", SPRING);
            Scene scene = openEditor(connection -> { },
                    NavParams.of(QuestionEditorView.PARAM_DETAIL, detail));
            WaitForAsyncUtils.waitForFxEvents();
            @SuppressWarnings("unchecked")
            javafx.scene.control.ComboBox<Difficulty> box =
                    (javafx.scene.control.ComboBox<Difficulty>)
                            scene.getRoot().lookup(".question-difficulty");
            assertThat(renderedButtonText(box))
                    .as("difficulty " + difficulty + " must show in the box on the first open")
                    .isEqualTo(BankCopy.difficulty(difficulty));
        }
    }

    /** The text the ComboBox button cell actually displays (U-98). */
    private static String renderedButtonText(javafx.scene.control.ComboBox<Difficulty> box) {
        javafx.scene.control.ListCell<Difficulty> cell = box.getButtonCell();
        if (cell != null && cell.getText() != null) {
            return cell.getText();
        }
        return box.getValue() == null ? null : box.getConverter().toString(box.getValue());
    }

    @Test
    @DisplayName("Save as a new version is off until something changed (U-57)")
    void unchangedEditCannotBeSaved() {
        Scene scene = openEditor(connection -> { },
                NavParams.of(QuestionEditorView.PARAM_DETAIL, GEOMETRY));

        javafx.scene.control.Button save = buttonNamed(scene, QuestionEditorCopy.SAVE);
        assertThat(save.isDisabled())
                .as("every save writes version n+1, so a save with nothing changed is a copy")
                .isTrue();
        assertThat(save.getTooltip()).isNotNull();
        assertThat(save.getTooltip().getText()).isEqualTo(QuestionEditorCopy.NOTHING_CHANGED);

        clickOn(scene.getRoot().lookup(".text-area")).write("?");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(save.isDisabled()).as("one real change and it is savable again").isFalse();
        assertThat(save.getTooltip()).isNull();
    }

    @Test
    @DisplayName("a revisit rebuilds the radios; the first visit's do not linger in the group (U-70)")
    void aRevisitDoesNotAccumulateRadios() {
        Scene scene = openEditor(connection -> { },
                NavParams.of(QuestionEditorView.PARAM_DETAIL, GEOMETRY));

        // Leave and come back, the way the cached screen is really re-shown: the bank
        // navigates away (onHide) and later back to the same instance (onShow).
        interact(() -> {
            openedView.onHide();
            openedView.onShow(NavParams.of(QuestionEditorView.PARAM_DETAIL, GEOMETRY));
        });
        WaitForAsyncUtils.waitForFxEvents();

        RadioButton first = (RadioButton) scene.getRoot().lookup(".radio-option");
        assertThat(first).isNotNull();
        assertThat(first.getToggleGroup().getToggles())
                .as("buildForm runs once per visit over one long-lived ToggleGroup, and the "
                        + "screen is cached for the life of the process: a visit that does not "
                        + "detach the previous four radios grows the group by four, forever")
                .hasSize(4);

        List<RadioButton> radios = scene.getRoot().lookupAll(".radio-option").stream()
                .filter(RadioButton.class::isInstance)
                .map(RadioButton.class::cast)
                .toList();
        clickOn(radios.get(1));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(radios.stream().filter(RadioButton::isSelected).count())
                .as("and C-8's exactly-one still holds on the rebuilt form")
                .isEqualTo(1);
    }

    /**
     * The keyboard story of the U-70 layout, measured rather than assumed.
     *
     * <p>The radios left the {@code RadioGroup} component for the answer rows, and that
     * component's javadoc warns that JavaFX gives the arrow half of radio traversal wrong for
     * loose radios. It turns out the warning is about radios with no group semantics in the
     * traversal path: for four {@code RadioButton}s sharing one {@code ToggleGroup} the
     * toolkit's own convention holds, and this test pins each leg of it with real key
     * presses so a regression (a radio dropped from the group, a focusTraversable toggle) is
     * caught by the gesture a keyboard user would actually make:
     * <ul>
     *   <li>Tab enters the group once, on the selected toggle - unselected radios are not
     *       tab stops, so the form stays four Tabs shorter than four loose radios;</li>
     *   <li>arrows move focus AND selection through the group, wherever the rows are;</li>
     *   <li>Space confirms without stealing the mark;</li>
     *   <li>Tab leaves the group for the next text box.</li>
     * </ul>
     */
    @Test
    @DisplayName("the keyboard walks the answer-row radios: Tab onto the group, arrows move the mark (U-70)")
    void keyboardReachesTheAnswerRowRadios() {
        Scene scene = openEditor(connection -> { },
                NavParams.of(QuestionEditorView.PARAM_DETAIL, GEOMETRY));

        List<RadioButton> radios = scene.getRoot().lookupAll(".radio-option").stream()
                .filter(RadioButton.class::isInstance)
                .map(RadioButton.class::cast)
                .toList();
        assertThat(radios).hasSize(4);
        assertThat(radios.get(2).isSelected()).as("GEOMETRY's key names answer 3").isTrue();

        // Tab out of answer box 3 enters the group on its selected toggle.
        clickOn(answerBox(scene, 3));
        type(javafx.scene.input.KeyCode.TAB);
        assertThat(scene.getFocusOwner())
                .as("Tab enters the radio group on the selected toggle, which is the "
                        + "platform convention the moved radios must keep")
                .isSameAs(radios.get(2));

        // Arrows move the mark itself, row to row: selection follows focus in a group.
        type(javafx.scene.input.KeyCode.UP);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(scene.getFocusOwner()).isSameAs(radios.get(1));
        assertThat(radios.get(1).isSelected())
                .as("an arrow within the group moves the selection with the focus")
                .isTrue();
        type(javafx.scene.input.KeyCode.LEFT);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(radios.get(0).isSelected())
                .as("and again: the mark walked 3 to 2 to 1 on arrows alone")
                .isTrue();
        assertThat(radios.stream().filter(RadioButton::isSelected).count())
                .as("one shared ToggleGroup: a purely keyboard-driven change cannot "
                        + "double-mark (C-8)")
                .isEqualTo(1);
        assertThat(labelTexts(scene))
                .as("moving the key from answer 3 to answer 1 is an edit")
                .contains(QuestionEditorCopy.UNSAVED);

        // Space confirms without stealing; Tab leaves the group for the next box.
        type(javafx.scene.input.KeyCode.SPACE);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(radios.get(0).isSelected()).isTrue();
        type(javafx.scene.input.KeyCode.TAB);
        assertThat(scene.getFocusOwner())
                .as("Tab leaves the group for the next text box rather than crawling "
                        + "through three more radios")
                .isInstanceOf(TextField.class);
    }

    // ===================== E6.14, the edit lock ⚑ =========================

    @Test
    @DisplayName("⚑ a question somebody else is editing opens read-only, and names her")
    void lockedByAnotherTeacher() {
        List<EntityRef> lockedEntities = new java.util.ArrayList<>();
        Scene scene = openEditor(connection -> connection.respondTo(Verb.LOCK_ACQUIRE,
                        request -> {
                            lockedEntities.add(((common.dto.lock.LockRequest) request.getPayload())
                                    .entity());
                            return Message.ok(request, LockResponse.refused(BANK_LOCK,
                                    new LockHolder(9, "Avi Mizrahi"),
                                    Instant.now().plusSeconds(120)));
                        }),
                NavParams.of(QuestionEditorView.PARAM_DETAIL, GEOMETRY));

        // ⚑ The half of the key agreement that lives on this side of the wire. Every other test
        // of this feature builds its own EntityRef, so keying the editor on anything else - the
        // primary key, a second entityType - would leave the server consulting a slot nobody
        // ever locks and the whole consult silently refusing nothing, with every suite green.
        assertThat(lockedEntities)
                .as("the editor has to lock the key QuestionService consults: display id 11005 "
                        + "under the shared question type")
                .containsExactly(server.features.bank.QuestionLockKey.of("11005"));

        assertThat(buttonNamed(scene, QuestionEditorCopy.SAVE).isDisabled())
                .as("a client claim, and since 2026-08-24 a courtesy in front of a real server "
                        + "check rather than the whole of the mutual exclusion: QuestionService "
                        + "consults EditLockGuard on both write verbs. Greying Save still earns "
                        + "its place by telling her before she types, not after she saves.")
                .isTrue();
        assertThat(labelTexts(scene))
                .as("and the banner names who has it, because 'locked' without a name leaves her "
                        + "with nobody to go and ask")
                .anySatisfy(text -> assertThat(text).contains("Avi Mizrahi"));
    }

    @Test
    @DisplayName("⚑ leaving the editor gives the lock back rather than waiting for the sweeper")
    void leavingReleasesTheLock() {
        Scene scene = openEditor(connection -> { },
                NavParams.of(QuestionEditorView.PARAM_DETAIL, GEOMETRY));

        interact(() -> openedView.onHide());
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(sentVerbs())
                .as("the server sweeps expired holds, so a crash frees it eventually. That is "
                        + "the safety net, not the mechanism: without an explicit release the row "
                        + "says 'being edited by Dana' for the whole TTL after she has gone.")
                .contains(Verb.LOCK_RELEASE);
    }

    @Test
    @DisplayName("a new question takes no lock, because there is nothing yet to collide over")
    void createModeTakesNoLock() {
        openEditor(connection -> { }, NavParams.of(QuestionEditorView.PARAM_COURSE, "11"));

        assertThat(sentVerbs()).doesNotContain(Verb.LOCK_ACQUIRE);
    }

    // ===================== Harness ========================================

    /**
     * The same shell-free setup {@code BankScreenInteractionTest.openBankAs} uses, and for the
     * same reason: booting {@code ClientApp} also boots the connect screen, whose deferred
     * connect attempt can outlive the test and dereference an event bus that
     * {@code resetForTests} has already nulled. Initialising {@link ScreenManager} directly gives
     * the editor a real manager with no connect screen behind it.
     */
    private Scene openEditor(Consumer<FakeClientConnection> script, NavParams params) {
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
            // The editor takes an edit lock on open (E6.14). Until the acquire is answered the
            // form is CHECKING, which E18 defines as "disabled, not read-only", so a test that
            // scripted no lock would be asserting about a form nobody can type in. Granting it
            // is the ordinary case; lockedByAnotherTeacher scripts the refusal instead.
            connection.respondTo(Verb.LOCK_ACQUIRE, request -> Message.ok(request,
                    LockResponse.granted(BANK_LOCK,
                            new LockHolder(DANA.userId(), DANA.displayName()),
                            Instant.now().plusSeconds(120))));
            connection.replyOk(Verb.LOCK_RELEASE, null);
            connection.replyOk(Verb.LOCK_RENEW, null);
            script.accept(connection);

            openedConnection = connection;
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
            QuestionEditorView view = new QuestionEditorView();
            Scene scene = new Scene(view.view(), 1100, 900);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            view.onShow(params);
            holder[0] = scene;
            openedView = view;
        });
        WaitForAsyncUtils.waitForFxEvents();
        return holder[0];
    }

    private static String textAreaText(Scene scene) {
        return scene.getRoot().lookupAll(".text-area").stream()
                .filter(TextArea.class::isInstance)
                .map(node -> ((TextArea) node).getText())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no text area on the editor"));
    }

    /** The nth answer box, in the order they are laid out. */
    private static TextField answerBox(Scene scene, int oneBased) {
        List<TextField> fields = scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .toList();
        assertThat(fields.size()).isGreaterThanOrEqualTo(oneBased);
        return fields.get(oneBased - 1);
    }

    private static TextField fieldWithText(Scene scene, String text) {
        return scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .filter(field -> text.equals(field.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field holding " + text));
    }

    private static Set<String> fieldTexts(Scene scene) {
        return scene.getRoot().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance)
                .map(node -> ((TextField) node).getText())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static Button buttonNamed(Scene scene, String label) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> label.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button labelled " + label));
    }

    /**
     * Every label a person can actually see.
     *
     * <p>Visibility matters here more than on most screens: the unsaved marker and every
     * FormField message row live in the scene graph permanently and are shown by toggling
     * { visible}. An unfiltered lookup finds their text whether or not it is on screen, so
     * a "does not contain" assertion over it can never fail. This test file learned that by
     * writing one and watching it fail against correct code.
     */
    /**
     * The text of every SHOWN validation message row.
     *
     * <p>Scoped to { .field-message} and to the row's own visibility, because FormField
     * hides the ROW and leaves the label inside it visible. Filtering on the label alone would
     * report a message that is not on screen, which is the opposite of what these tests ask.
     */
    private static Set<String> messageTexts(Scene scene) {
        return scene.getRoot().lookupAll(".field-message").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .filter(label -> label.getParent() != null && label.getParent().isVisible())
                .map(Label::getText)
                .filter(Objects::nonNull)
                .filter(text -> !text.isBlank())
                .collect(Collectors.toSet());
    }

    /** Every verb the editor put on the wire, in order. */
    private List<Verb> sentVerbs() {
        return openedConnection.sentMessages().stream().map(Message::getVerb).toList();
    }

    private static Set<String> labelTexts(Scene scene) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .filter(Label::isVisible)
                .filter(label -> label.getScene() != null)
                .map(Label::getText)
                .filter(Objects::nonNull)
                .filter(text -> !text.isBlank())
                .collect(Collectors.toSet());
    }
}
