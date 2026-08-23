package client.ui;

import client.core.InMemoryPropertiesStore;
import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.ui.components.ImagePicker;
import client.ui.components.RadioGroup;
import client.ui.components.logic.ImagePickerLogic;
import client.ui.components.logic.ValidationState;
import client.ui.theme.ThemeManager;
import client.ui.theme.ThemeState;
import common.dto.bank.ImageAction;
import common.dto.bank.QuestionImage;
import javafx.geometry.NodeOrientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-input interaction tests for the two components E6.10 binds to (F2.1, C-8, E6.6).
 *
 * <p>{@code RadioGroupLogicTest} and {@code ImagePickerLogicTest} own every rule; what only a
 * booted toolkit can show is that the rules are actually wired to a keyboard and a mouse. Two
 * headline cases, driven the way a teacher drives them rather than by calling setters:
 *
 * <ul>
 *   <li>a genuine <b>arrow key press</b> moves the radio group's selection, wraps at the end
 *       and steps over a disabled option, and never leaks focus out of the group;</li>
 *   <li>a genuine <b>click on Remove</b> puts the picker into {@link ImageAction#REMOVE} and
 *       shows the removed state rather than the empty one.</li>
 * </ul>
 *
 * <p>The choose path cannot be driven the same way: {@link javafx.stage.FileChooser} is a
 * native dialog and has nothing to open onto in a headless run. That is exactly why
 * {@link ImagePicker#chooseBytes} exists as a seam — the dialog reduces to "read a file and
 * call it", the reading is the JDK's, and everything after it is asserted here with injected
 * bytes.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class QuestionEditorComponentsInteractionTest extends ApplicationTest {

    private static final List<String> ANSWERS = List.of(
            "Answer one", "Answer two", "Answer three", "Answer four");

    /** A real PNG signature followed by filler: enough to pass the sniff. */
    private static final byte[] PNG = pngOf(96);

    private RadioGroup<Integer> group;
    private ImagePicker picker;
    private Scene scene;

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
        ThemeState theme = new ThemeState(new InMemoryPropertiesStore(), () -> false,
                new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster()));

        group = RadioGroup.indexed("Correct answer", ANSWERS).required();
        picker = new ImagePicker("Illustration");
        picker.loadExisting(PNG);

        VBox root = new VBox(16, group, picker);
        root.setPrefSize(520, 640);
        scene = new Scene(root, 520, 640);
        new ThemeManager(theme).attach(scene);

        stage.setScene(scene);
        stage.show();
    }

    // ===================== RadioGroup ====================================

    @Test
    @DisplayName("a real Down arrow moves the radio selection, and wraps at the end ⚑")
    void arrowKeysSelect() {
        clickOn(button(0));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(group.selected()).isEqualTo(1);

        press(KeyCode.DOWN).release(KeyCode.DOWN);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(group.selected())
                .as("a real arrow key press must move the selection, not just the focus")
                .isEqualTo(2);
        assertThat(button(1).isFocused()).isTrue();

        press(KeyCode.DOWN).release(KeyCode.DOWN);
        press(KeyCode.DOWN).release(KeyCode.DOWN);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(group.selected()).isEqualTo(4);

        press(KeyCode.DOWN).release(KeyCode.DOWN);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(group.selected())
                .as("Down on the last answer wraps rather than walking into the next field")
                .isEqualTo(1);
        assertThat(button(0).isFocused())
                .as("and focus stays inside the group")
                .isTrue();
    }

    @Test
    @DisplayName("Up wraps backwards, and the arrows step over a disabled option")
    void arrowKeysSkipDisabledOptions() {
        interact(() -> group.setOptionDisabled(3, true));
        clickOn(button(1));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(group.selected()).isEqualTo(2);

        press(KeyCode.DOWN).release(KeyCode.DOWN);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(group.selected())
                .as("answer 3 is disabled, so Down lands on answer 4")
                .isEqualTo(4);

        press(KeyCode.UP).release(KeyCode.UP);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(group.selected())
                .as("and Up steps back over it the same way")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Space confirms the focused option, so the keyboard habit still works")
    void spaceSelects() {
        // Focus without selecting, which is the only state Space has anything to do in.
        interact(() -> {
            group.clearSelection();
            button(2).requestFocus();
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(group.selected()).isNull();

        press(KeyCode.SPACE).release(KeyCode.SPACE);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(group.selected())
                .as("RadioButton's own Space binding survives the arrow-key filter above it")
                .isEqualTo(3);
        assertThat(button(2).isSelected()).isTrue();
    }

    @Test
    @DisplayName("with Hebrew answers the group mirrors, and so does Left")
    void arrowsMirrorUnderRtl() {
        interact(() -> group.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT));
        clickOn(button(0));
        WaitForAsyncUtils.waitForFxEvents();

        press(KeyCode.LEFT).release(KeyCode.LEFT);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(group.selected())
                .as("right to left, Left travels towards the NEXT option")
                .isEqualTo(2);

        press(KeyCode.RIGHT).release(KeyCode.RIGHT);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(group.selected()).isEqualTo(1);
    }

    @Test
    @DisplayName("only one answer can be selected at a time (C-8), whatever the input")
    void exactlyOneSelection() {
        clickOn(button(0));
        clickOn(button(2));
        press(KeyCode.DOWN).release(KeyCode.DOWN);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(group.buttons().stream().filter(RadioButton::isSelected))
                .as("the invariant C-8 is about, enforced by the widget rather than by a rule")
                .hasSize(1);
    }

    @Test
    @DisplayName("the group renders errors the way every other field does")
    void validationLooksLikeAFormField() {
        interact(() -> group.showError("Choose which answer is the correct one."));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(group.getStyleClass()).contains("hsts-field", "invalid");
        assertThat(labelTexts()).contains("Choose which answer is the correct one.");
        assertThat(group.lookup(".field-message-row")).isNotNull();

        interact(() -> group.apply(ValidationState.pristine()));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(group.getStyleClass()).doesNotContain("invalid");
    }

    @Test
    @DisplayName("loading a question into the group does not look like the teacher typing")
    void selectIsSilent() {
        int[] callbacks = {0};
        interact(() -> {
            group.setOnSelect(id -> callbacks[0]++);
            group.select(3);
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(group.selected()).isEqualTo(3);
        assertThat(callbacks[0])
                .as("filling a form in from a QuestionDetail must not mark it dirty")
                .isZero();

        clickOn(button(0));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(callbacks[0]).isOne();
    }

    // ===================== ImagePicker ===================================

    @Test
    @DisplayName("a real click on Remove drives the picker to REMOVE ⚑")
    void removeClickDrivesRemoveState() {
        assertThat(picker.action()).isEqualTo(ImageAction.KEEP);
        assertThat(picker.logic().hasPreview()).isTrue();

        clickOn(removeButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(picker.action())
                .as("the one button that is allowed to clear a picture, clicked for real")
                .isEqualTo(ImageAction.REMOVE);
        assertThat(labelTexts())
                .as("and the removed state says so, rather than showing the empty one")
                .contains(ImagePickerLogic.REMOVED_TITLE)
                .doesNotContain(ImagePickerLogic.EMPTY_TITLE);
        assertThat(scene.getRoot().lookup(".hsts-empty-state.removed")).isNotNull();
    }

    @Test
    @DisplayName("Remove is disabled when there is nothing to remove")
    void removeIsDisabledWithNothingToRemove() {
        clickOn(removeButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(removeButton().isDisabled())
                .as("a button that cannot do anything must not look like it can")
                .isTrue();
    }

    @Test
    @DisplayName("injected bytes drive the picker to REPLACE and show the new thumbnail")
    void chosenBytesDriveReplaceState() {
        ImagePickerLogic.Outcome outcome = pick(PNG, "diagram.png");

        assertThat(outcome.isAccepted()).isTrue();
        assertThat(picker.action()).isEqualTo(ImageAction.REPLACE);
        assertThat(picker.logic().chosenBytes()).isEqualTo(PNG);
        assertThat(labelTexts())
                .as("the caption reports the file, not the format the name claimed")
                .contains("PNG, 96 B");
        assertThat(scene.getRoot().lookup(".picker-preview").isVisible()).isTrue();
    }

    @Test
    @DisplayName("a cancelled chooser leaves the picture and the action exactly as they were ⚑")
    void cancelledChooserChangesNothing() {
        ImagePickerLogic.Outcome outcome = pick(null, null);

        assertThat(outcome.isUnchanged()).isTrue();
        assertThat(picker.action())
                .as("the defect this component exists to make unrepresentable")
                .isEqualTo(ImageAction.KEEP);
        assertThat(picker.logic().previewBytes()).isEqualTo(PNG);
        assertThat(labelTexts())
                .as("and no error is shown, because changing her mind is not an error")
                .doesNotContain(ImagePickerLogic.UNREADABLE);
        assertThat(scene.getRoot().lookup(".picker-preview").isVisible()).isTrue();
    }

    @Test
    @DisplayName("an oversized file is refused with a sentence, and the picture survives")
    void oversizedFileIsRefused() {
        ImagePickerLogic.Outcome outcome =
                pick(pngOf(QuestionImage.MAX_BYTES + 1), "huge.png");

        assertThat(outcome.isRejected()).isTrue();
        assertThat(labelTexts()).contains(ImagePickerLogic.TOO_LARGE);
        assertThat(picker.getStyleClass()).contains("invalid");
        assertThat(picker.action())
                .as("a refused file must not be able to clear the illustration either")
                .isEqualTo(ImageAction.KEEP);
        assertThat(picker.logic().previewBytes()).isEqualTo(PNG);
    }

    @Test
    @DisplayName("a valid file after a refusal clears the message")
    void aGoodFileClearsTheMessage() {
        pick("not an image".getBytes(StandardCharsets.UTF_8), "diagram.png");
        assertThat(labelTexts()).contains(ImagePickerLogic.WRONG_CONTENT);

        pick(PNG, "diagram.png");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(labelTexts()).doesNotContain(ImagePickerLogic.WRONG_CONTENT);
        assertThat(picker.getStyleClass()).doesNotContain("invalid");
    }

    @Test
    @DisplayName("the change callback fires for real changes and not for a cancel")
    void onChangeFiresOnlyForRealChanges() {
        java.util.List<ImageAction> seen = new java.util.ArrayList<>();
        interact(() -> picker.setOnChange(seen::add));

        pick(null, null);
        assertThat(seen).isEmpty();

        pick("nope".getBytes(StandardCharsets.UTF_8), "a.png");
        assertThat(seen).as("a refusal is not a change either").isEmpty();

        pick(PNG, "a.png");
        clickOn(removeButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(seen).containsExactly(ImageAction.REPLACE, ImageAction.REMOVE);
    }

    @Test
    @DisplayName("bytes that pass the sniff but will not decode do not take the form down")
    void undecodableBytesDegradeGracefully() {
        // A PNG signature over 96 bytes of zeros: valid to the sniff, meaningless to a decoder.
        ImagePickerLogic.Outcome outcome = pick(PNG, "truncated.png");

        assertThat(outcome.isAccepted()).isTrue();
        assertThat(picker.action())
                .as("the state is right even when the thumbnail cannot be drawn")
                .isEqualTo(ImageAction.REPLACE);
        assertThat(labelTexts()).contains("PNG, 96 B");
    }

    // ===================== Helpers =======================================

    private ImagePickerLogic.Outcome pick(byte[] bytes, String fileName) {
        ImagePickerLogic.Outcome[] outcome = new ImagePickerLogic.Outcome[1];
        interact(() -> outcome[0] = picker.chooseBytes(bytes, fileName));
        WaitForAsyncUtils.waitForFxEvents();
        return outcome[0];
    }

    private RadioButton button(int index) {
        return group.buttons().get(index);
    }

    private Button removeButton() {
        Node node = scene.getRoot().lookup(".picker-remove");
        assertThat(node).isInstanceOf(Button.class);
        return (Button) node;
    }

    /**
     * Every label the teacher can actually read.
     *
     * <p>Visibility is filtered rather than ignored, and it is load-bearing: the picker keeps
     * all three of its states in the scene graph and hides two of them, so a plain
     * {@code lookupAll} would report "No illustration" as being on screen while the removed
     * state is showing, and every {@code doesNotContain} below would pass for the wrong reason.
     */
    private Set<String> labelTexts() {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .filter(QuestionEditorComponentsInteractionTest::onScreen)
                .map(node -> ((Label) node).getText())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static boolean onScreen(Node node) {
        for (Node walk = node; walk != null; walk = walk.getParent()) {
            if (!walk.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static byte[] pngOf(int length) {
        byte[] bytes = new byte[length];
        byte[] magic = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        return bytes;
    }
}
