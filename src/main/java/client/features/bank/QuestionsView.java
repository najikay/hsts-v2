package client.features.bank;

import client.core.NavParams;
import client.core.ScreenManager;
import client.net.RequestDispatcher;
import client.ui.components.Buttons;
import client.ui.screen.AbstractScreen;
import client.ui.components.Logo;
import common.dto.auth.LoginResult;
import common.dto.bank.Question;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * The main prototype screen (Presentation tier), defined in FXML.
 *
 * <p>Master-detail: a styled {@link ListView} of questions on the left, an editor
 * card on the right. This class is the FXML controller (set via
 * {@code loader.setController(this)} so the {@link AbstractScreen} Template
 * Method and the Singleton/Adapter wiring stay intact).

 * <p>Kept alive through E4 so the app stays runnable end-to-end (connect →
 * questions) while E6 rewrites the real bank screens. It is the one screen still
 * on the legacy {@code css/app.css}, which it loads onto its own root rather
 * than onto the Scene, so the prototype's styling cannot leak into the design
 * system.
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
 * <p>Requests go through the shared {@code RequestDispatcher} (protocol v2): the
 * screen gets a future per request instead of a global message handler, and the
 * result is applied inside {@code onFxThread()} — the single documented hop back
 * onto the JavaFX Application Thread.
 */
public class QuestionsView extends AbstractScreen {

    private static final String FXML_PATH = "/fxml/QuestionsView.fxml";

    /** What the lock banner and the takeover prompt call the thing being edited. */
    private static final String ENTITY_NOUN = "question";

    /** Prototype stylesheet, scoped to this screen's root only. */
    private static final String LEGACY_STYLESHEET = "/css/app.css";

    /** The versioned bank's route id, until this class is retired (E6.9, temporary). */
    private static final String NEW_BANK_ROUTE = BankRoutes.LIST;

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


    @Override
    protected Parent build() {
        Parent root = loadFxml(FXML_PATH);
        java.net.URL legacyCss = getClass().getResource(LEGACY_STYLESHEET);
        if (legacyCss != null) {
            root.getStylesheets().add(legacyCss.toExternalForm());
        }
        Node banner = newBankBanner();
        if (banner == null) {
            return root;
        }
        VBox wrapper = new VBox(banner, root);
        VBox.setVgrow(root, javafx.scene.layout.Priority.ALWAYS);
        return wrapper;
    }

    /**
     * The way into the versioned bank, for as long as both screens exist (E6.9, temporary).
     *
     * <p>The lead ruled on 2026-08-23 that rail id {@code questions} keeps serving this screen
     * until E6's retirement PR, so the replacement is registered as a non-rail route and needs
     * something to be reached from. This is that something, and it is deliberately built in Java
     * rather than added to the FXML: the FXML is outside Member A's scope, and this whole method
     * is deleted in the same PR that deletes the class.
     *
     * <p>It answers {@code null} when the route is not registered, which is the state of this
     * branch until the assembly PR lands. A button that navigated nowhere would teach a teacher
     * that the screen is broken, which is slower to unlearn than a link that was not there yet.
     *
     * @return the banner, or {@code null} when there is nothing to link to
     */
    private Node newBankBanner() {
        if (navigator() == null || !navigator().isRegistered(NEW_BANK_ROUTE)) {
            return null;
        }
        Label note = new Label("A new question bank has replaced this screen.");
        note.getStyleClass().addAll("small", "muted");
        Button open = Buttons.styled("Open the new bank", Buttons.LINK);
        open.setOnAction(event -> navigator().navigate(NEW_BANK_ROUTE));
        HBox banner = new HBox(10, note, open);
        banner.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        banner.setPadding(new javafx.geometry.Insets(10, 16, 10, 16));
        banner.getStyleClass().add("legacy-bank-banner");
        return banner;
    }

    /** Wired automatically by FXMLLoader after {@code @FXML} fields are injected. */
    @FXML
    private void initialize() {
        logoBox.getChildren().add(Logo.create(34));
        makeReadOnly();

        // Multi-line cells: the full question wraps and an answer preview shows,
        // so every question is readable at a glance without truncation.
        listView.setCellFactory(lv -> new QuestionCell());
        listView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldSel, newSel) -> showSelected(newSel));

        showSelected(null);
    }

    /**
     * Turns the prototype into a reader (the lead's condition, 2026-08-23).
     *
     * <p>The controls come from {@code QuestionsView.fxml}, which is outside Member A's scope,
     * so they are hidden here rather than deleted there. Hidden and unmanaged rather than
     * disabled: a permanently greyed Save invites a teacher to work out how to un-grey it,
     * where an absent one simply says the screen does not do that.
     *
     * <p>The text areas stay populated and selectable so a question can still be read and
     * copied; they are just not writable. Everything here dies with the retirement PR.
     */
    private void makeReadOnly() {
        questionField.setEditable(false);
        answerArea.setEditable(false);
        for (Node control : new Node[] {saveButton, revertButton, dirtyLabel, savedLabel}) {
            control.setVisible(false);
            control.setManaged(false);
        }
    }

    @Override
    public void onShow(NavParams params) {
        requestAllQuestions();
    }

    // ===== Edit locks: removed, and why ==================================

    /*
     * This screen used to compose LockAwareEditor and was E18's working proof. It no longer
     * takes a lock at all, on the lead's condition of 2026-08-23 for splitting the retirement
     * out of PR-B.
     *
     * The reason is the one the EntityRef ruling exists for. Locks are keyed
     * (type, long id), and there is no third numbering scheme available: this screen keyed
     * EntityRef.QUESTION by the questions PRIMARY KEY, while the versioned editor keys the same
     * type by displayId5. With both screens live and both taking locks, two teachers editing one
     * question can hold two different keys and never see each other, and two teachers editing
     * DIFFERENT questions can collide on one key. Read-only legacy keeps exactly one scheme live
     * at every moment between now and the retirement PR.
     *
     * Nothing replaces it here, because a read-only screen has nothing to protect. The versioned
     * editor holds the lock for anyone actually writing.
     */

    // ===== Outbound requests =============================================

    private void requestAllQuestions() {
        statusLabel.setText("Loading questions…");
        dispatcher().send(Verb.GET_ALL_QUESTIONS, null)
                .whenComplete((response, failure) ->
                        onFxThread().run(() -> onQuestionList(response, failure, false)));
    }

    /**
     * Kept, and deliberately empty: this screen no longer writes (E6.14 condition).
     *
     * <p>The button is hidden and this method cannot be reached from the UI, but
     * {@code QuestionsView.fxml} binds it by name in {@code onAction="#onSave"} and the
     * FXMLLoader fails to load a controller that does not have it. The FXML is outside
     * Member A's scope, so the method outlives its button until the retirement PR takes both.
     */
    @FXML
    private void onSave() {
        // Intentionally nothing. See the class javadoc: the versioned editor owns writing.
    }

    /** Kept for the same reason as {@link #onSave()}: {@code onAction="#onRevert"} in the FXML. */
    @FXML
    private void onRevert() {
        // Intentionally nothing.
    }

    // ===== Inbound responses (posted back onto the JavaFX thread) =========

    /**
     * Applies the outcome of a GET_ALL_QUESTIONS / UPDATE_QUESTION future. Both
     * verbs answer with the full, freshly read list, so one renderer serves both.
     */
    @SuppressWarnings("unchecked")
    private void onQuestionList(Message response, Throwable failure, boolean wasSave) {
        if (failure != null) {
            showServerError(rootCause(failure).getMessage());
            return;
        }
        if (response.getErrorCode() == ErrorCode.CONFLICT) {
            // Unreachable now that this screen never writes: CONFLICT was the stale-write
            // answer to UPDATE_QUESTION. Kept as a plain message rather than deleted, because
            // this method still serves GET_ALL_QUESTIONS and swallowing an unexpected error
            // code would leave the list silently empty.
            statusLabel.setText("That did not load. Open the question bank instead.");
            return;
        }
        if (response.isError()) {
            showServerError(response.errorMessage());
            return;
        }
        if (!(response.getPayload() instanceof List)) {
            statusLabel.setText("Unexpected response payload from the server.");
            return;
        }

        updateData((List<Question>) response.getPayload());
        if (wasSave) {
            // Show the badge AFTER the re-render so it isn't cleared by it.
            showSavedBadge();
            statusLabel.setText("Update saved to the database.");
        } else {
            statusLabel.setText("Questions loaded.");
        }
    }

    private void showServerError(String detail) {
        statusLabel.setText("Server error.");
        Alert alert = new Alert(Alert.AlertType.ERROR, String.valueOf(detail));
        alert.setHeaderText("Server returned an error");
        alert.showAndWait();
    }

    private static Throwable rootCause(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
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

        // No lock is taken here any more, and the ordering note that used to live at the end
        // of this method went with it: nothing on this screen can re-enable a Save, because
        // there is no Save.
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
