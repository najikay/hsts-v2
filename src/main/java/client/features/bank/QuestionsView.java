package client.features.bank;

import client.core.NavParams;
import client.core.ScreenManager;
import client.features.locks.EditLockState;
import client.features.locks.FxHeartbeat;
import client.features.locks.LockAwareEditor;
import client.features.locks.LockBanner;
import client.features.locks.LockCopy;
import client.net.RequestDispatcher;
import client.ui.screen.AbstractScreen;
import client.ui.components.Logo;
import client.ui.components.WarnConfirm;
import common.dto.auth.LoginResult;
import common.dto.bank.Question;
import common.dto.bank.QuestionUpdate;
import common.dto.lock.EntityRef;
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

    /** Advisory edit lock for the selected question (E18.5). */
    private LockAwareEditor locks;
    private final LockBanner lockBanner = new LockBanner();

    @Override
    protected Parent build() {
        Parent root = loadFxml(FXML_PATH);
        java.net.URL legacyCss = getClass().getResource(LEGACY_STYLESHEET);
        if (legacyCss != null) {
            root.getStylesheets().add(legacyCss.toExternalForm());
        }
        return root;
    }

    /** Wired automatically by FXMLLoader after {@code @FXML} fields are injected. */
    @FXML
    private void initialize() {
        logoBox.getChildren().add(Logo.create(34));
        initLocks();

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
    public void onShow(NavParams params) {
        requestAllQuestions();
    }

    /** Leaving the screen gives the lock back straight away (E18.3). */
    @Override
    public void onHide() {
        if (locks != null) {
            locks.close();
        }
    }

    // ===== Edit locks (E18.5) ============================================

    /**
     * Wires the reusable {@link LockAwareEditor} in, following the recipe in its
     * javadoc. This is the working proof for E18: the other editors (exam
     * builder, bot sources, release schedule, grading review) compose the same
     * helper when their epics land.
     */
    private void initLocks() {
        editorBox.getChildren().add(0, lockBanner);
        lockBanner.hide();

        RequestDispatcher dispatcher = dispatcher();
        LoginResult user = ScreenManager.getInstance().signedInUser();
        if (dispatcher == null || user == null) {
            // The screen is reachable from the gallery and from tests without a
            // session; it stays fully editable there rather than refusing to build.
            return;
        }
        locks = new LockAwareEditor(dispatcher, eventBus(), user.userId(),
                new FxHeartbeat(), ENTITY_NOUN);
        locks.onStateChanged(this::renderLockState);
        lockBanner.setOnTakeOver(this::confirmTakeOver);
    }

    /** Applies a lock state to the editor: banner, read-only fields, save button. */
    private void renderLockState(EditLockState.Snapshot state) {
        lockBanner.show(state, ENTITY_NOUN);

        boolean editable = state.isEditable();
        questionField.setEditable(editable);
        answerArea.setEditable(editable);
        saveButton.setDisable(!editable || listView.getSelectionModel().getSelectedItem() == null);
        if (!editable) {
            revertButton.setDisable(true);
        } else {
            refreshDirtyState();
        }
        if (state.offersTakeover()) {
            // The banner carries the offer; a modal here would interrupt someone who
            // is reading the question rather than waiting to edit it.
            statusLabel.setText(LockCopy.TAKEOVER_TITLE);
        }
    }

    /** Asks before taking the lock — never a silent grab (E18.3, state c). */
    private void confirmTakeOver() {
        EditLockState.Snapshot state = locks.state();
        boolean confirmed = WarnConfirm.show(window(), WarnConfirm.spec(LockCopy.TAKEOVER_TITLE)
                .explanation(state.reason() == null ? ""
                        : LockCopy.takeoverExplanation(state.reason(), ENTITY_NOUN))
                .confirmText(LockCopy.TAKEOVER_CONFIRM)
                .cancelText(LockCopy.TAKEOVER_CANCEL)
                .info());
        if (confirmed) {
            locks.takeOver();
        } else {
            locks.declineTakeover();
        }
    }

    /**
     * The stale-write dialog (E18.4). Reloading is destructive to what is on
     * screen, so it is confirmed rather than done automatically.
     */
    private void confirmReloadAfterConflict() {
        boolean reload = WarnConfirm.show(window(), WarnConfirm.spec(LockCopy.CONFLICT_TITLE)
                .explanation(LockCopy.CONFLICT_EXPLANATION)
                .confirmText(LockCopy.CONFLICT_CONFIRM)
                .cancelText(LockCopy.CONFLICT_CANCEL)
                .warn());
        if (reload) {
            requestAllQuestions();
        }
    }

    private javafx.stage.Window window() {
        return view().getScene() == null ? null : view().getScene().getWindow();
    }

    /**
     * Takes the lock on the newly selected question and gives back the previous
     * one. Selecting nothing releases: a lock must not survive a user who has
     * stopped looking at what it protects.
     */
    private void openLockFor(Question q) {
        if (locks == null) {
            return;
        }
        if (q == null) {
            locks.close();
            lockBanner.hide();
            return;
        }
        locks.open(EntityRef.question(q.getId()));
    }

    // ===== Outbound requests =============================================

    private void requestAllQuestions() {
        statusLabel.setText("Loading questions…");
        dispatcher().send(Verb.GET_ALL_QUESTIONS, null)
                .whenComplete((response, failure) ->
                        onFxThread().run(() -> onQuestionList(response, failure, false)));
    }

    @FXML
    private void onSave() {
        Question selected = listView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        // The values the editor was based on go with the edit, so the server can
        // refuse a write that would overwrite somebody else's save (E18.4).
        QuestionUpdate update = new QuestionUpdate(copyWithEdits(selected), originalQuestion, originalAnswer);

        clearSavedBadge();
        statusLabel.setText("Saving update…");
        dispatcher().send(Verb.UPDATE_QUESTION, update)
                .whenComplete((response, failure) ->
                        onFxThread().run(() -> onQuestionList(response, failure, true)));
    }

    /**
     * The edited values on a detached copy: the list's own instance must not
     * change until the server confirms, or a rejected save would leave the list
     * showing text that was never written.
     */
    private Question copyWithEdits(Question selected) {
        return new Question(selected.getId(), questionField.getText(), answerArea.getText());
    }

    @FXML
    private void onRevert() {
        questionField.setText(originalQuestion);
        answerArea.setText(originalAnswer);
        statusLabel.setText("Reverted to last saved values.");
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
            // Somebody else saved first (E18.4). Not a failure to apologise for: the
            // user is offered the newer version, and keeps their text if they decline.
            statusLabel.setText("Not saved. This question changed while you were editing.");
            confirmReloadAfterConflict();
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

        // The lock has the last word, so it goes last: acquiring is answered on this
        // very thread when the server is quick, and doing it first would let the
        // lines above re-enable a Save that read-only mode had just switched off.
        openLockFor(q);
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
