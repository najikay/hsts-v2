package client.ui;

import common.entities.Question;
import common.network.Message;
import common.network.Message.Command;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.List;

/**
 * The single prototype screen (Presentation tier), built purely in Java (no FXML).
 *
 * <p>Layout: a {@link ListView} of questions on the left; an editable
 * {@link TextField}/{@link TextArea} and a "Save Update" {@link Button} on the
 * right. On show it requests {@code GET_ALL_QUESTIONS}; on save it sends
 * {@code UPDATE_QUESTION} with the mutated {@link Question}. Server responses are
 * delivered (already on the FX thread) to {@link #onServerMessage(Message)},
 * which calls {@link #updateData(List)} for SUCCESS.
 */
public class QuestionsView extends AbstractScreenUI {

    private final ListView<Question> listView = new ListView<>();
    private final TextArea questionField = new TextArea();
    private final TextArea answerArea = new TextArea();
    private final Button saveButton = new Button("Save Update");
    private final Label statusLabel = new Label("Connecting…");

    @Override
    public Parent render() {
        // Register this view as the handler for server responses.
        client().setServerMessageHandler(this::onServerMessage);

        // ----- Left: list of questions -----
        listView.setPrefWidth(320);
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Question q, boolean empty) {
                super.updateItem(q, empty);
                setText((empty || q == null) ? null : "#" + q.getId() + "  " + q.getQuestionText());
            }
        });
        listView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldSel, newSel) -> showSelected(newSel));

        // ----- Right: editor -----
        questionField.setPromptText("Question text");
        questionField.setPrefRowCount(2);
        questionField.setWrapText(true);
        answerArea.setPromptText("Answer");
        answerArea.setPrefRowCount(3);
        answerArea.setWrapText(true);
        saveButton.setOnAction(e -> onSave());
        saveButton.setDisable(true);

        VBox editor = new VBox(8,
                new Label("Edit selected question:"),
                new Label("Question:"), questionField,
                new Label("Answer:"), answerArea,
                saveButton,
                statusLabel);
        editor.setPadding(new Insets(10));
        editor.setPrefWidth(360);

        BorderPane root = new BorderPane();
        root.setLeft(listView);
        root.setCenter(editor);
        root.setPadding(new Insets(10));
        root.setPrefSize(700, 380);
        return root;
    }

    @Override
    protected void onShown() {
        // Kick off the initial load once the screen is rendered.
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

    private void onSave() {
        Question selected = listView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        // Mutate the selected question from the editor fields.
        selected.setQuestionText(questionField.getText());
        selected.setAnswer(answerArea.getText());
        try {
            statusLabel.setText("Saving update…");
            client().send(new Message(Command.UPDATE_QUESTION, selected));
        } catch (IOException e) {
            statusLabel.setText("Update failed: " + e.getMessage());
        }
    }

    // ===== Inbound responses (already on the JavaFX thread) ===============

    /** Routes a server response. Invoked on the FX thread by HSTSClient. */
    @SuppressWarnings("unchecked")
    public void onServerMessage(Message msg) {
        switch (msg.getCommand()) {
            case SUCCESS:
                Object payload = msg.getPayload();
                if (payload instanceof List) {
                    updateData((List<Question>) payload);
                    statusLabel.setText("Done.");
                }
                break;
            case ERROR:
                statusLabel.setText("Server error.");
                Alert alert = new Alert(Alert.AlertType.ERROR,
                        String.valueOf(msg.getPayload()));
                alert.setHeaderText("Server returned an error");
                alert.showAndWait();
                break;
            default:
                statusLabel.setText("Unexpected response: " + msg.getCommand());
        }
    }

    /**
     * Public update hook: refreshes the ListView from a server-provided list,
     * preserving the current selection by id where possible.
     */
    public void updateData(List<Question> list) {
        Question previouslySelected = listView.getSelectionModel().getSelectedItem();
        int keepId = previouslySelected == null ? -1 : previouslySelected.getId();

        listView.getItems().setAll(list);

        if (keepId != -1) {
            for (Question q : list) {
                if (q.getId() == keepId) {
                    listView.getSelectionModel().select(q);
                    break;
                }
            }
        }
    }

    private void showSelected(Question q) {
        boolean hasSelection = q != null;
        saveButton.setDisable(!hasSelection);
        if (hasSelection) {
            questionField.setText(q.getQuestionText());
            answerArea.setText(q.getAnswer());
        } else {
            questionField.clear();
            answerArea.clear();
        }
    }
}
