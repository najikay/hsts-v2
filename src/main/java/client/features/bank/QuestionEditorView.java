package client.features.bank;

import client.core.ScreenManager;
import client.features.locks.EditLockState;
import client.features.locks.FxHeartbeat;
import client.features.locks.LockAwareEditor;
import client.features.locks.LockBanner;
import common.dto.auth.LoginResult;
import client.core.NavParams;
import client.ui.components.Buttons;
import client.ui.components.FormField;
import client.ui.components.ImagePicker;
import client.ui.components.RadioGroup;
import client.ui.components.WarnConfirm;
import client.ui.components.logic.ValidationState;
import client.ui.screen.AbstractScreen;
import common.dto.bank.Difficulty;
import common.dto.bank.QuestionDetail;
import server.features.bank.QuestionLockKey;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * The question editor (Presentation tier, E6.10 / E6.11 — F2.1, F2.2, C-8, T-2.2).
 *
 * <p>A renderer over {@link QuestionEditorSession}. Every rule, every refusal and every mapping
 * from a server sentence to a box is decided there and tested without a toolkit; this class owns
 * nodes, which is why it is on the coverage exclusion list by name.
 *
 * <h2>How it is opened, and why that is not a fetch</h2>
 *
 * <p>It is a non-rail route reached from the bank list, and it takes its question through
 * {@link NavParams}: {@code "detail"} carries the {@code QuestionDetail} and {@code "image"} the
 * bytes of that version's illustration. The bank screen already holds both, because its detail
 * pane is showing them, so the editor never re-asks for what the previous screen has in hand.
 *
 * <p>That is also what keeps the components report's one remaining screen-level trap unreachable.
 * {@link QuestionEditorSession#forEdit} takes the bytes as a required argument, so an editor
 * cannot exist before its illustration does. Opening this route without a {@code detail} is
 * therefore a programming error rather than a state to render, and it navigates back rather than
 * drawing a half-editor.
 *
 * <h2>The answer rows</h2>
 *
 * <p>Four labelled boxes, then one {@link RadioGroup} naming which of them is correct. The
 * component is used exactly as its recipe describes rather than being taken apart to interleave
 * radios with text fields: {@code ToggleGroup} is what guarantees C-8's at-most-one, and that
 * guarantee is worth more than a tighter row.
 */
public final class QuestionEditorView extends AbstractScreen {

    /** Nav parameter carrying the version being edited. */
    public static final String PARAM_DETAIL = "detail";

    /** Nav parameter carrying that version's illustration, absent when it has none. */
    public static final String PARAM_IMAGE = "image";

    /** Nav parameter carrying the course, for a new question. */
    public static final String PARAM_COURSE = "courseCode";

    private final VBox root = new VBox(16);
    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final Label unsaved = new Label(QuestionEditorCopy.UNSAVED);

    private final TextArea textBox = new TextArea();
    private final FormField textField = new FormField(QuestionEditorCopy.TEXT_LABEL, textBox);
    private final List<FormField> answerFields = new ArrayList<>();
    private final RadioGroup<Integer> correct = RadioGroup.indexed(
            QuestionEditorCopy.ANSWERS_LABEL, answerOptionLabels());
    private final FormField topicField =
            FormField.text(QuestionEditorCopy.TOPIC_LABEL, QuestionEditorCopy.TOPIC_PROMPT);
    private final ComboBox<Difficulty> difficultyBox = new ComboBox<>();
    private final FormField difficultyField =
            new FormField(QuestionEditorCopy.DIFFICULTY_LABEL, difficultyBox);

    private final Button save = Buttons.primary(QuestionEditorCopy.SAVE);
    private final Button cancel = Buttons.styled(QuestionEditorCopy.CANCEL, Buttons.GHOST);

    private final LockBanner lockBanner = new LockBanner();
    private LockAwareEditor locks;
    private ImagePicker picker;
    private QuestionEditorSession session;
    private boolean filling;
    private boolean showingDialog;

    private static List<String> answerOptionLabels() {
        List<String> labels = new ArrayList<>(QuestionEditorSession.ANSWER_COUNT);
        for (int i = 1; i <= QuestionEditorSession.ANSWER_COUNT; i++) {
            labels.add(BankCopy.answerLabel(i));
        }
        return labels;
    }

    @Override
    protected Parent build() {
        root.getStyleClass().addAll("hsts-page", "question-editor");
        root.setPadding(new Insets(24, 28, 24, 24));
        title.getStyleClass().add("h1");
        subtitle.getStyleClass().addAll("small", "muted");
        subtitle.setWrapText(true);
        unsaved.getStyleClass().addAll("small", "warn-text", "editor-unsaved");
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        QuestionDetail detail = params.get(PARAM_DETAIL, QuestionDetail.class).orElse(null);
        byte[] image = params.get(PARAM_IMAGE, byte[].class).orElse(null);
        String course = params.getString(PARAM_COURSE, null);

        if (detail == null && course == null) {
            // Neither mode is possible. Nothing links here without one, so this is a wiring
            // error rather than a state, and drawing an empty form would hide it.
            navigator().navigate(BankRoutes.LIST);
            return;
        }
        session = detail != null
                ? QuestionEditorSession.forEdit(dispatcher(), onFxThread(), detail, image)
                : QuestionEditorSession.forCreate(dispatcher(), onFxThread(), course);
        session.onChange(this::render);

        buildForm();
        openLock(detail);
        render();
    }

    /**
     * Takes the edit lock on the question being edited (E6.14, E18.5).
     *
     * <p>Edit mode only: a question that does not exist yet cannot be locked, and there is
     * nothing for another teacher to collide with.
     *
     * <p>The key comes from {@link QuestionLockKey}, which is the one place the display-id
     * numbering lives. It sits in the server tier because {@code QuestionService}'s write-path
     * consult has to arrive at the same key this call locks under, and one rule with two
     * implementations is the drift that ruling exists to prevent. Skipped without a session,
     * because this screen is reachable from the gallery and from tests with no signed-in user,
     * and refusing to build there would be worse than being unlocked there.
     */
    private void openLock(QuestionDetail detail) {
        if (detail == null) {
            return;
        }
        LoginResult user = ScreenManager.getInstance().signedInUser();
        if (dispatcher() == null || user == null || eventBus() == null) {
            return;
        }
        locks = new LockAwareEditor(dispatcher(), eventBus(), user.userId(), new FxHeartbeat(),
                QuestionEditorCopy.LOCK_NOUN);
        locks.onStateChanged(this::renderLockState);
        lockBanner.setOnTakeOver(this::takeOver);
        locks.open(QuestionLockKey.of(detail.displayId5()));
    }

    /**
     * Gives the lock back the moment she leaves, rather than waiting for it to expire.
     *
     * <p>The server sweeps expired holds, so a client that crashes still frees the question
     * eventually (E18's sweep-on-access plus {@code sweepExpired}). That is the safety net and
     * not the mechanism: without this call the row keeps saying "being edited by Dana" for the
     * whole TTL after she has closed the editor, and the next teacher waits on nothing.
     */
    @Override
    public void onHide() {
        if (locks != null) {
            locks.close();
        }
    }

    /** The banner's offer, taken. The server decides; this only asks. */
    private void takeOver() {
        if (locks != null) {
            locks.takeOver();
        }
    }

    /**
     * Paints somebody else's lock onto the form.
     *
     * <p>The refusal lives on the banner and never under a field: she has typed nothing wrong,
     * and a red box would blame her form for another teacher's lock. The session is told too, so
     * the save is refused rather than merely greyed.
     */
    private void renderLockState(EditLockState.Snapshot state) {
        // The hop, and it is not optional. LockAwareEditor publishes from applyAnswer, which
        // runs inside dispatcher.send(...).whenComplete(...) — and RequestDispatcher's own
        // javadoc says futures complete on whichever thread delivered the outcome, with the
        // FX crossing belonging to FxThreadPoster (ARCHITECTURE section 6: exactly one crossing
        // point). Without this, a LOCK_ACQUIRE answer mutates the scene graph from the network
        // reader thread and writes session.readOnly with no happens-before edge to the FX thread
        // that reads it in canSave().
        //
        // No test here can catch it: FakeClientConnection delivers inline on the calling thread,
        // which in these tests is the FX thread. It was found by a cold read, and it presents in
        // production as "the banner sometimes does not update" rather than as a crash.
        //
        // The recipe in LockAwareEditor's javadoc shows this method without a hop, and both
        // consumers copied it. Raised with the lead: one hop inside publish would cover every
        // future editor instead of one per screen.
        onFxThread().run(() -> {
            lockBanner.show(state, QuestionEditorCopy.LOCK_NOUN);
            session.setReadOnly(!state.isEditable());
            render();
        });
    }

    // ===================== Building =======================================

    private void buildForm() {
        root.getChildren().clear();
        answerFields.clear();

        textBox.setPromptText(QuestionEditorCopy.TEXT_PROMPT);
        textBox.setWrapText(true);
        textBox.setPrefRowCount(3);
        textField.required();
        textBox.textProperty().addListener((observable, old, value) -> {
            if (!filling) {
                session.setText(value);
            }
        });

        VBox answers = new VBox(8);
        answers.getStyleClass().add("editor-answers");
        for (int i = 1; i <= QuestionEditorSession.ANSWER_COUNT; i++) {
            int position = i;
            FormField field = FormField.text(BankCopy.answerLabel(i),
                    QuestionEditorCopy.answerPrompt(i));
            field.required();
            field.textField().textProperty().addListener((observable, old, value) -> {
                if (!filling) {
                    session.setAnswer(position, value);
                }
            });
            answerFields.add(field);
            answers.getChildren().add(field);
        }

        correct.required();
        correct.setOnSelect(index -> {
            if (!filling) {
                session.setCorrectAnswer(index);
            }
        });

        topicField.required();
        topicField.textField().textProperty().addListener((observable, old, value) -> {
            if (!filling) {
                session.setTopic(value);
            }
        });

        difficultyBox.getItems().setAll(Difficulty.values());
        difficultyBox.setCellFactory(view -> new DifficultyCell());
        difficultyBox.setButtonCell(new DifficultyCell());
        difficultyField.required();
        difficultyBox.getSelectionModel().selectedItemProperty()
                .addListener((observable, old, value) -> {
                    if (!filling) {
                        session.setDifficulty(value);
                    }
                });

        // The picker is built over the session's own logic, which was loaded at construction.
        // Two objects, one state: there is no second copy of "what is happening to the picture".
        picker = new ImagePicker(QuestionEditorCopy.IMAGE_LABEL, session.imageLogic());
        picker.setOnChange(action -> session.imageChanged());

        save.setText(session.mode() == QuestionEditorSession.Mode.CREATE
                ? QuestionEditorCopy.CREATE : QuestionEditorCopy.SAVE);
        save.setOnAction(event -> session.save());
        cancel.setOnAction(event -> leave());

        HBox actions = new HBox(10, cancel, Buttons.spacer(), unsaved, save);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("editor-actions");

        VBox form = new VBox(16, textField, answers, correct, hint(), topicField,
                difficultyField, picker);
        form.getStyleClass().add("editor-form");

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("editor-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // The banner sits above the form, not inside the scroll: "somebody else is editing this"
        // has to be visible without scrolling to it.
        lockBanner.hide();
        root.getChildren().addAll(new VBox(2, title, subtitle), lockBanner, scroll, actions);
        fillFromSession();
    }

    private Node hint() {
        Label label = new Label(QuestionEditorCopy.ANSWERS_HINT);
        label.getStyleClass().addAll("small", "muted");
        label.setWrapText(true);
        return label;
    }

    /**
     * Puts the session's values into the controls without echoing them back.
     *
     * <p>{@code RadioGroup.select} is silent by contract, but the text properties are not, so the
     * flag is what stops filling the form in from marking it dirty.
     */
    private void fillFromSession() {
        filling = true;
        try {
            textBox.setText(session.text());
            List<String> values = session.answers();
            for (int i = 0; i < answerFields.size(); i++) {
                answerFields.get(i).textField().setText(values.get(i));
            }
            if (session.correctAnswer() != null) {
                correct.select(session.correctAnswer());
            }
            topicField.textField().setText(session.topic());
            if (session.difficulty() != null) {
                difficultyBox.getSelectionModel().select(session.difficulty());
            }
        } finally {
            filling = false;
        }
    }

    // ===================== Rendering ======================================

    private void render() {
        if (session.mode() == QuestionEditorSession.Mode.CREATE) {
            title.setText(QuestionEditorCopy.TITLE_NEW);
            subtitle.setText(QuestionEditorCopy.NEW_SUBTITLE);
        } else {
            title.setText(QuestionEditorCopy.titleEdit(session.displayId5()));
            subtitle.setText(QuestionEditorCopy.editSubtitle(session.baseVersionNo()));
        }

        boolean dirty = session.isDirty();
        unsaved.setVisible(dirty);
        unsaved.setManaged(dirty);
        save.setDisable(!session.canSave());
        save.setText(session.isSaving() ? QuestionEditorCopy.SAVING
                : session.mode() == QuestionEditorSession.Mode.CREATE
                        ? QuestionEditorCopy.CREATE : QuestionEditorCopy.SAVE);

        applyProblems();
        handleOutcome();
    }

    /**
     * Paints the live rules and the server's refusals onto the boxes.
     *
     * <p>One pass over every control, clearing first, so a refusal that has been fixed cannot
     * survive on a field nobody touched. The two sources are merged rather than ranked: a live
     * problem and a server refusal about the same box say the same sentence, because both come
     * from {@code BankMessages}.
     */
    private void applyProblems() {
        textField.clearValidation();
        answerFields.forEach(FormField::clearValidation);
        correct.clearValidation();
        topicField.clearValidation();
        difficultyField.clearValidation();
        picker.clearMessage();

        List<QuestionEditorCopy.Refusal> problems = new ArrayList<>(session.liveProblems());
        if (session.outcome() == QuestionEditorSession.Outcome.REFUSED) {
            problems.addAll(session.refusals());
        }
        for (QuestionEditorCopy.Refusal problem : problems) {
            switch (problem.field()) {
                case TEXT -> textField.apply(ValidationState.invalid(problem.message()));
                case ANSWER -> {
                    int index = problem.position() - 1;
                    if (index >= 0 && index < answerFields.size()) {
                        answerFields.get(index)
                                .apply(ValidationState.invalid(problem.message()));
                    }
                }
                case CORRECT_ANSWER -> correct.showError(problem.message());
                case TOPIC -> topicField.apply(ValidationState.invalid(problem.message()));
                case DIFFICULTY ->
                        difficultyField.apply(ValidationState.invalid(problem.message()));
                case IMAGE -> picker.showError(problem.message());
                case FORM -> { /* shown as a dialog by handleOutcome */ }
            }
        }
    }

    private void handleOutcome() {
        if (showingDialog) {
            return;
        }
        // A save settles whenever the network says so, which can be after she has walked away.
        // Without this, a CONFLICT arriving while she is on the grading queue opens a modal over
        // it and then throws her to the bank list, about a question she stopped looking at.
        // BankSession solves the same problem with a generation counter; here the screen's own
        // liveness is the honest question, because the session dies with the screen.
        if (navigator() != null && !navigator().isCurrent(BankRoutes.EDITOR)) {
            return;
        }
        switch (session.outcome()) {
            case SAVED -> {
                session.saved().ifPresent(detail -> {
                    if (toasts() != null) {
                        toasts().success(QuestionEditorCopy.SAVED,
                                session.mode() == QuestionEditorSession.Mode.CREATE
                                        ? QuestionEditorCopy.created(detail.displayId5())
                                        : QuestionEditorCopy.versionWritten(detail.displayId5(),
                                                detail.versionNo()));
                    }
                });
                session.dismissOutcome();
                navigator().navigate(BankRoutes.LIST);
            }
            case STALE -> dialog(QuestionEditorCopy.STALE_TITLE, QuestionEditorCopy.STALE_BODY,
                    QuestionEditorCopy.STALE_CONFIRM, true);
            case GONE -> dialog(QuestionEditorCopy.GONE_TITLE, QuestionEditorCopy.GONE_BODY,
                    QuestionEditorCopy.STALE_CONFIRM, true);
            case FAILED -> {
                if (toasts() != null) {
                    toasts().error(QuestionEditorCopy.SAVE_REFUSED_TITLE,
                            QuestionEditorCopy.SAVE_FAILED);
                }
                session.dismissOutcome();
            }
            case REFUSED -> formLevelRefusal();
            case NONE -> { /* nothing to say */ }
        }
    }

    private void formLevelRefusal() {
        session.refusals().stream()
                .filter(refusal -> refusal.field() == QuestionEditorCopy.Field.FORM)
                .findFirst()
                .ifPresent(refusal -> {
                    if (toasts() != null) {
                        toasts().error(QuestionEditorCopy.SAVE_REFUSED_TITLE, refusal.message());
                    }
                    session.dismissOutcome();
                });
    }

    private void dialog(String heading, String body, String confirmText, boolean leaveAfter) {
        showingDialog = true;
        try {
            WarnConfirm.show(window(), WarnConfirm.spec(heading)
                    .explanation(body)
                    .confirmText(confirmText)
                    .warn());
        } finally {
            showingDialog = false;
        }
        session.dismissOutcome();
        if (leaveAfter) {
            navigator().navigate(BankRoutes.LIST);
        }
    }

    /** Cancel, with the unsaved-changes prompt when there is something to lose. */
    private void leave() {
        if (session.isDirty() && !showingDialog) {
            showingDialog = true;
            boolean discard;
            try {
                discard = WarnConfirm.show(window(),
                        WarnConfirm.spec(QuestionEditorCopy.DISCARD_TITLE)
                                .explanation(QuestionEditorCopy.DISCARD_BODY)
                                .confirmText(QuestionEditorCopy.DISCARD_CONFIRM)
                                .cancelText(QuestionEditorCopy.DISCARD_CANCEL)
                                .warn());
            } finally {
                showingDialog = false;
            }
            if (!discard) {
                return;
            }
        }
        navigator().navigate(BankRoutes.LIST);
    }

    private javafx.stage.Window window() {
        return view().getScene() == null ? null : view().getScene().getWindow();
    }

    private static final class DifficultyCell extends ListCell<Difficulty> {
        @Override
        protected void updateItem(Difficulty difficulty, boolean empty) {
            super.updateItem(difficulty, empty);
            setText(empty || difficulty == null ? null : BankCopy.difficulty(difficulty));
        }
    }
}
