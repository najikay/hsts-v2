package client.features.bank;

import client.core.AbstractScreenUI;
import client.ui.components.Logo;
import common.dto.bank.Question;
import common.protocol.Message;
import common.protocol.Message.Command;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/**
 * The main prototype screen (Presentation tier), defined in FXML.
 *
 * <p>Master-detail: a styled {@link ListView} of questions on the left, an editor
 * card on the right. This class is the FXML controller (set via
 * {@code loader.setController(this)} so the {@link AbstractScreenUI} Template
 * Method and the Singleton/Adapter wiring stay intact).
 *
 * <p>Interaction model:
 * <ul>
 *   <li>Selecting a question fills the editor; <b>Save</b> is enabled whenever a
 *       question is selected.</li>
 *   <li>Editing a field marks the question dirty: a "● Unsaved changes" hint and
 *       the <b>Revert</b> button appear.</li>
 *   <li>Saving sends {@code UPDATE_QUESTION}; once the server confirms the write,
 *       a small "✓ Saved" badge appears. It is cleared by the next interaction
 *       (selecting a question, focusing the editor, or typing) and shows again on
 *       the next successful save.</li>
 * </ul>
 *
 * <p>Server responses arrive (already on the FX thread) at
 * {@link #onServerMessage(Message)}.
 */
public class QuestionsView extends AbstractScreenUI {

    private static final String FXML_PATH = "/fxml/QuestionsView.fxml";

    @FXML private ListView<Question> listView;
    @FXML private TextArea questionField;
    @FXML private TextArea answerArea;
    @FXML private Button saveButton;
    @FXML private Button revertButton;
    @FXML private Label statusLabel;
    @FXML private Label savedLabel;
    @FXML private Label countBadge;
    @FXML private Label idBadge;
    @FXML private Label dirtyLabel;
    @FXML private VBox placeholderBox;
    @FXML private VBox editorBox;
    @FXML private StackPane logoBox;

    /** The values last loaded from the server for the selected question. */
    private String originalQuestion = "";
    private String originalAnswer = "";

    /** True while an update we sent is awaiting its server confirmation. */
    private boolean awaitingSaveConfirm = false;

    @Override
    public Parent render() {
        // Register this view as the handler for server responses.
        client().setServerMessageHandler(this::onServerMessage);

        FXMLLoader loader = new FXMLLoader(getClass().getResource(FXML_PATH));
        loader.setController(this);
        try {
            return loader.load();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + FXML_PATH, e);
        }
    }

    /** Wired automatically by FXMLLoader after {@code @FXML} fields are injected. */
    @FXML
    private void initialize() {
        logoBox.getChildren().add(Logo.create(34));

        // Multi-line cells: the full question wraps and an answer preview shows,
        // so every question is readable at a glance without truncation.
        listView.setCellFactory(lv -> new QuestionCell());
        listView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldSel, newSel) -> showSelected(newSel));

        // Any edit marks dirty and dismisses a lingering "Saved" badge.
        questionField.textProperty().addListener((obs, o, n) -> onEdited());
        answerArea.textProperty().addListener((obs, o, n) -> onEdited());

        // Clicking back into the editor also dismisses the "Saved" badge.
        questionField.focusedProperty().addListener((obs, was, is) -> { if (is) clearSavedBadge(); });
        answerArea.focusedProperty().addListener((obs, was, is) -> { if (is) clearSavedBadge(); });

        showSelected(null);
    }

    @Override
    protected void onShown() {
        requestAllQuestions();
    }

    // ===== Outbound requests =============================================

    private void requestAllQuestions() {
        try {
            statusLabel.setText("Loading questions…");
            client().send(new Message(Command.GET_ALL_QUESTIONS));
        } catch (IOException e) {
            statusLabel.setText("Send failed: " + e.getMessage());
        }
    }

    @FXML
    private void onSave() {
        Question selected = listView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        selected.setQuestionText(questionField.getText());
        selected.setAnswer(answerArea.getText());
        System.out.println("[QuestionsView] onSave -> UPDATE_QUESTION id=" + selected.getId());
        try {
            awaitingSaveConfirm = true;
            clearSavedBadge();
            statusLabel.setText("Saving update…");
            client().send(new Message(Command.UPDATE_QUESTION, selected));
        } catch (IOException e) {
            awaitingSaveConfirm = false;
            statusLabel.setText("Update failed: " + e.getMessage());
        }
    }

    @FXML
    private void onRevert() {
        questionField.setText(originalQuestion);
        answerArea.setText(originalAnswer);
        statusLabel.setText("Reverted to last saved values.");
    }

    // ===== Inbound responses (already on the JavaFX thread) ===============

    /** Routes a server response. Invoked on the FX thread by HSTSClient. */
    @SuppressWarnings("unchecked")
    public void onServerMessage(Message msg) {
        switch (msg.getCommand()) {
            case SUCCESS:
                Object payload = msg.getPayload();
                if (payload instanceof List) {
                    boolean wasSave = awaitingSaveConfirm;
                    awaitingSaveConfirm = false;
                    updateData((List<Question>) payload);
                    if (wasSave) {
                        // Show the badge AFTER the re-render so it isn't cleared by it.
                        showSavedBadge();
                        statusLabel.setText("Update saved to the database.");
                    } else {
                        statusLabel.setText("Questions loaded.");
                    }
                }
                break;
            case ERROR:
                awaitingSaveConfirm = false;
                statusLabel.setText("Server error.");
                Alert alert = new Alert(Alert.AlertType.ERROR, String.valueOf(msg.getPayload()));
                alert.setHeaderText("Server returned an error");
                alert.showAndWait();
                break;
            default:
                statusLabel.setText("Unexpected response: " + msg.getCommand());
        }
    }

    /**
     * Refreshes the ListView from a server-provided list, preserving the current
     * selection by id where possible, and updates the question count.
     */
    public void updateData(List<Question> list) {
        Question previouslySelected = listView.getSelectionModel().getSelectedItem();
        int keepId = previouslySelected == null ? -1 : previouslySelected.getId();

        listView.getItems().setAll(list);
        countBadge.setText(list.size() + (list.size() == 1 ? " question" : " questions"));

        if (keepId != -1) {
            for (Question q : list) {
                if (q.getId() == keepId) {
                    listView.getSelectionModel().select(q);
                    break;
                }
            }
        }
    }

    // ===== View state ====================================================

    private void showSelected(Question q) {
        boolean hasSelection = q != null;
        setNodeShown(editorBox, hasSelection);
        setNodeShown(placeholderBox, !hasSelection);
        setNodeShown(idBadge, hasSelection);
        clearSavedBadge();

        if (hasSelection) {
            idBadge.setText("#" + q.getId());
            originalQuestion = nullToEmpty(q.getQuestionText());
            originalAnswer = nullToEmpty(q.getAnswer());
            // Set originals first so the text listeners compute a clean (non-dirty) state.
            questionField.setText(originalQuestion);
            answerArea.setText(originalAnswer);
            statusLabel.setText("Editing question #" + q.getId());
        } else {
            originalQuestion = "";
            originalAnswer = "";
            questionField.clear();
            answerArea.clear();
        }

        // Save is available whenever a question is selected; Revert only when dirty.
        saveButton.setDisable(!hasSelection);
        refreshDirtyState();
    }

    /** Reacts to a manual edit of either field. */
    private void onEdited() {
        clearSavedBadge();
        refreshDirtyState();
    }

    private boolean isDirty() {
        return !Objects.equals(questionField.getText(), originalQuestion)
                || !Objects.equals(answerArea.getText(), originalAnswer);
    }

    private void refreshDirtyState() {
        boolean dirty = listView.getSelectionModel().getSelectedItem() != null && isDirty();
        revertButton.setDisable(!dirty);
        setNodeShown(dirtyLabel, dirty);
    }

    private void showSavedBadge() {
        setNodeShown(savedLabel, true);
    }

    private void clearSavedBadge() {
        setNodeShown(savedLabel, false);
    }

    private static void setNodeShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * A list cell that shows the full (wrapped) question plus an answer preview,
     * so the list is readable without selecting each row. Wrap width is bound to
     * the list width so text reflows instead of being clipped.
     */
    private final class QuestionCell extends ListCell<Question> {
        private final Label title = new Label();
        private final Label sub = new Label();
        private final VBox box = new VBox(3, title, sub);

        QuestionCell() {
            title.setWrapText(true);
            title.getStyleClass().add("q-title");
            sub.setWrapText(true);
            sub.getStyleClass().add("q-sub");
            box.setFillWidth(true);
            box.maxWidthProperty().bind(listView.widthProperty().subtract(60));
            title.maxWidthProperty().bind(box.maxWidthProperty());
            sub.maxWidthProperty().bind(box.maxWidthProperty());
            setText(null);
        }

        @Override
        protected void updateItem(Question q, boolean empty) {
            super.updateItem(q, empty);
            if (empty || q == null) {
                setGraphic(null);
            } else {
                title.setText("#" + q.getId() + "   " + nullToEmpty(q.getQuestionText()));
                String answer = nullToEmpty(q.getAnswer());
                sub.setText(answer.isEmpty() ? "No answer set" : "Answer: " + answer);
                setGraphic(box);
            }
        }
    }
}
