package client.ui.components;

import client.ui.components.logic.ImagePickerLogic;
import common.dto.bank.ImageAction;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Pick, preview, remove: a question's illustration as one control (Presentation tier, for
 * E6.10 — F2.1, E6.6).
 *
 * <p>Thin, on the {@link CountdownTimer} pattern. Every decision the picker makes — whether a
 * file may be used, what to say when it may not, which of {@link ImageAction}'s three states
 * the teacher has just expressed, what the thumbnail's caption reads — belongs to
 * {@link ImagePickerLogic} and is unit tested there. This class owns nodes and the file
 * chooser, and nothing else.
 *
 * <h2>The cancel path is the whole design</h2>
 *
 * <p>{@link FileChooser#showOpenDialog} returns {@code null} when the teacher changes her mind,
 * and the tempting shape ("no file, so clear the picture") is the exact defect the server side
 * of this feature had to be fixed for. Here the cancel branch hands the logic a {@code null},
 * which is defined to change nothing, and the only route to {@link ImageAction#REMOVE} is the
 * Remove button. So a cancelled chooser cannot clear an illustration however the branch is
 * written, which is a stronger guarantee than remembering to write it correctly.
 *
 * <h2>The chooser is not the seam</h2>
 *
 * <p>A native file dialog cannot open in a headless test, so the byte path and the dialog path
 * are separated: {@link #chooseBytes} takes a file that has already been read and is what the
 * gallery and the interaction test drive, while {@link #openChooser} is the six lines that turn
 * a {@link File} into a call to it. Everything worth asserting is on the first of those.
 *
 * <h2>Three states on screen</h2>
 *
 * <ul>
 *   <li><b>Nothing</b> — "No illustration", with the accepted formats and the size cap, because
 *       a teacher should not have to discover the 2MB limit by hitting it.</li>
 *   <li><b>A picture</b> — thumbnail plus "PNG, 148 KB". Whether it is the stored one or one
 *       she just chose is the caption's job, not the thumbnail's.</li>
 *   <li><b>Removed</b> — not the empty state. "Illustration removed" says the picture is gone
 *       <i>because she removed it</i> and that saving is what will make that true, which is a
 *       different sentence from "this question never had one".</li>
 * </ul>
 */
public final class ImagePicker extends VBox {

    /** Widest the thumbnail is allowed to get; the picker sits in a form column. */
    private static final double THUMB_WIDTH = 220;

    /** Tallest the thumbnail is allowed to get, so a portrait photo cannot stretch the form. */
    private static final double THUMB_HEIGHT = 150;

    private final ImagePickerLogic logic;

    private final Label labelNode = new Label();
    private final StackPane frame = new StackPane();
    private final EmptyState emptyState = new EmptyState(Icons.IMAGE,
            ImagePickerLogic.EMPTY_TITLE, ImagePickerLogic.capLabel());
    private final EmptyState removedState = new EmptyState(Icons.IMAGE_OFF,
            ImagePickerLogic.REMOVED_TITLE, ImagePickerLogic.REMOVED_HINT);
    private final VBox preview = new VBox(6);
    private final ImageView thumbnail = new ImageView();
    private final Label caption = new Label();

    private final Button chooseButton = Buttons.withIcon("Choose image", Icons.UPLOAD,
            Buttons.SECONDARY);
    private final Button removeButton = Buttons.withIcon("Remove", Icons.DELETE, Buttons.GHOST);

    private final HBox messageRow = new HBox(6);
    private final Label messageLabel = new Label();

    private Consumer<ImageAction> onChange;

    /** Builds a picker for a question with no illustration yet (the create path). */
    public ImagePicker(String labelText) {
        this(labelText, new ImagePickerLogic());
    }

    /**
     * @param labelText the field label, or {@code null} for a picker inside a labelled card
     * @param logic     the state machine to render; inject a pre-loaded one for the edit path
     */
    public ImagePicker(String labelText, ImagePickerLogic logic) {
        this.logic = Objects.requireNonNull(logic, "logic");

        // hsts-field first, for the same reason RadioGroup does it: the label, the message row
        // and the invalid treatment are FormField's and are reused rather than reimplemented.
        getStyleClass().addAll("hsts-field", "hsts-image-picker");
        setSpacing(8);
        setFillWidth(true);

        labelNode.setText(labelText == null ? "" : labelText);
        labelNode.getStyleClass().add("field-label");
        setShown(labelNode, labelText != null && !labelText.isBlank());

        thumbnail.setPreserveRatio(true);
        thumbnail.setFitWidth(THUMB_WIDTH);
        thumbnail.setFitHeight(THUMB_HEIGHT);
        thumbnail.getStyleClass().add("picker-thumb");
        caption.getStyleClass().addAll("small", "muted", "picker-meta");
        preview.getStyleClass().add("picker-preview");
        preview.setAlignment(Pos.CENTER);
        preview.getChildren().addAll(thumbnail, caption);

        // The removed state is the standard EmptyState wearing one extra class, so its
        // danger tint lives in CSS beside every other state rather than in a second component.
        removedState.getStyleClass().add("removed");

        frame.getStyleClass().add("picker-frame");
        frame.setAlignment(Pos.CENTER);
        frame.setMinHeight(THUMB_HEIGHT);
        frame.getChildren().addAll(emptyState, removedState, preview);

        chooseButton.getStyleClass().add("picker-choose");
        chooseButton.setOnAction(event -> openChooser());
        removeButton.getStyleClass().add("picker-remove");
        removeButton.setOnAction(event -> applyOutcome(logic.remove()));
        removeButton.setTooltip(new Tooltip(
                "Takes the picture off the next version. The versions already using it keep it."));

        HBox actions = new HBox(8, chooseButton, removeButton);
        actions.getStyleClass().add("picker-actions");
        actions.setAlignment(Pos.CENTER_LEFT);

        messageLabel.getStyleClass().add("field-message");
        messageLabel.setWrapText(true);
        messageRow.getStyleClass().add("field-message-row");
        messageRow.setAlignment(Pos.CENTER_LEFT);
        messageRow.getChildren().addAll(
                Icons.of(Icons.ERROR, 13, "field-message-icon"), messageLabel);
        setShown(messageRow, false);

        getChildren().addAll(labelNode, frame, actions, messageRow);
        refresh();
    }

    /** @return the decision layer; read {@code action()} and {@code chosenBytes()} off it. */
    public ImagePickerLogic logic() {
        return logic;
    }

    /** @return what the current state instructs the server to do. */
    public ImageAction action() {
        return logic.action();
    }

    /** Runs after every change the teacher makes, for a form that tracks dirtiness. */
    public void setOnChange(Consumer<ImageAction> handler) {
        this.onChange = handler;
    }

    /**
     * Installs the illustration this version already has and repaints.
     *
     * <p>The load step: call it with the answer to {@code QUESTION_IMAGE_GET} before the
     * teacher sees the picker, never afterwards.
     *
     * @param existing the stored bytes, or {@code null} for a version with no picture
     */
    public void loadExisting(byte[] existing) {
        logic.loadExisting(existing);
        clearMessage();
        refresh();
    }

    /**
     * Offers a file that has already been read from disk.
     *
     * <p>The seam the gallery and the interaction test use, and the second half of
     * {@link #openChooser}. Rejections land in the message row rather than as an exception:
     * choosing the wrong file is a thing teachers do, not an error condition.
     *
     * @param bytes    the file's contents, or {@code null} for a cancelled pick
     * @param fileName the file's name, for the extension check
     * @return what happened, so a caller (or a test) can assert on it
     */
    public ImagePickerLogic.Outcome chooseBytes(byte[] bytes, String fileName) {
        return applyOutcome(logic.choose(bytes, fileName));
    }

    /** Clears the illustration, exactly as pressing Remove does. */
    public ImagePickerLogic.Outcome remove() {
        return applyOutcome(logic.remove());
    }

    /** Shows an error under the picker (a server-side rejection, not a local rule). */
    public void showError(String message) {
        if (message == null || message.isBlank()) {
            clearMessage();
            return;
        }
        messageLabel.setText(message);
        setShown(messageRow, true);
        if (!getStyleClass().contains("invalid")) {
            getStyleClass().add("invalid");
        }
    }

    /** Hides whatever the message row was saying. */
    public void clearMessage() {
        messageLabel.setText("");
        setShown(messageRow, false);
        getStyleClass().remove("invalid");
    }

    // ===================== Internals ======================================

    /**
     * The native dialog, and the null that comes back from Cancel.
     *
     * <p>Note what the cancel branch does <b>not</b> do: it does not clear, it does not warn,
     * and it does not return early into a state the rest of the picker has to cope with. It
     * hands the logic the {@code null} it already knows means "nothing happened".
     */
    private void openChooser() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose an illustration");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"));

        File file = chooser.showOpenDialog(window());
        if (file == null) {
            applyOutcome(logic.choose(null, null));
            return;
        }
        if (ImagePickerLogic.exceedsCap(file.length())) {
            // Refused on the directory entry, before the bytes are read. A teacher who picks a
            // 4GB video gets the same sentence as one who picks a 3MB photo, rather than the
            // editor spending a heap's worth of memory to reach the same conclusion.
            showError(ImagePickerLogic.TOO_LARGE);
            return;
        }
        try {
            chooseBytes(Files.readAllBytes(file.toPath()), file.getName());
        } catch (IOException e) {
            // A file she just picked can still fail to read: a share that went away, a
            // permission that changed. Say so rather than nothing, and keep whatever
            // illustration the question already had.
            showError(ImagePickerLogic.UNREADABLE);
        }
    }

    private ImagePickerLogic.Outcome applyOutcome(ImagePickerLogic.Outcome outcome) {
        if (outcome.isRejected()) {
            showError(outcome.message());
        } else {
            clearMessage();
        }
        refresh();
        if (outcome.isAccepted() && onChange != null) {
            onChange.accept(logic.action());
        }
        return outcome;
    }

    /** Reads the logic and repaints. Called after every change. */
    private void refresh() {
        boolean hasPreview = logic.hasPreview();
        setShown(preview, hasPreview);
        setShown(removedState, !hasPreview && logic.isRemoved());
        setShown(emptyState, !hasPreview && !logic.isRemoved());

        removeButton.setDisable(!logic.canRemove());
        caption.setText(logic.previewLabel());
        thumbnail.setImage(hasPreview ? decode(logic.previewBytes()) : null);
        setAccessibleText(hasPreview ? "Illustration attached, " + logic.previewLabel()
                : ImagePickerLogic.EMPTY_TITLE);
    }

    /**
     * Turns bytes into an image, or into nothing.
     *
     * <p>The bytes have already been sniffed, so this can only fail on a file that is a
     * truncated or corrupt PNG. A broken thumbnail must not take the editor down with it: the
     * teacher keeps her form, and the caption still tells her what she attached.
     */
    private static Image decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            Image image = new Image(new ByteArrayInputStream(bytes));
            return image.isError() ? null : image;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    /** Keeps {@code managed} in step with {@code visible} so hidden rows take no space. */
    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
