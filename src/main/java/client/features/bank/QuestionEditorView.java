package client.features.bank;

import client.core.ScreenManager;
import client.features.locks.EditLockState;
import client.features.locks.FxHeartbeat;
import client.features.locks.LockAwareEditor;
import client.features.locks.LockBanner;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import client.core.NavParams;
import client.ui.components.Buttons;
import client.ui.components.FormField;
import client.ui.components.ImagePicker;
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
import javafx.scene.control.RadioButton;
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
 * bytes of that version's illustration, so the editor makes no read of its own.
 *
 * <p><b>What it is handed is a fresh read, not the pane's copy</b> (2026-08-30, Findings.txt,
 * U-49). This javadoc used to say the bank "already holds both, because its detail pane is
 * showing them", and that was the defect written down as a design. {@code detail.versionNo()} is
 * the staleness token this editor's next save carries, and a pane can be showing a version the
 * teacher herself replaced one visit ago; the editor then opened on it and the server refused
 * her own save as somebody else's. So Edit now goes through
 * {@code BankSession.refreshDetailThen}, which re-issues {@code QUESTION_GET} and hands over
 * what comes back. Nothing here changed, and that is the point: the parameter this screen takes
 * is the same one, and the guarantee about it belongs to the screen that fills it in.
 *
 * <p>That is also what keeps the components report's one remaining screen-level trap unreachable.
 * {@link QuestionEditorSession#forEdit} takes the bytes as a required argument, so an editor
 * cannot exist before its illustration does. Opening this route without a {@code detail} is
 * therefore a programming error rather than a state to render, and it navigates back rather than
 * drawing a half-editor.
 *
 * <h2>The answer rows</h2>
 *
 * <p>Four labelled boxes, each with the correct-answer radio at its side (U-70; one shared
 * ToggleGroup, so C-8's at-most-one is untouched). The
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
    /** U-68: marks an intentional blank-course create, telling the guard it is not a wiring error. */
    public static final String PARAM_NEW = "newQuestion";

    private final VBox root = new VBox(16);
    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final Label unsaved = new Label(QuestionEditorCopy.UNSAVED);

    private final TextArea textBox = new TextArea();
    private final FormField textField = new FormField(QuestionEditorCopy.TEXT_LABEL, textBox);
    private final List<FormField> answerFields = new ArrayList<>();
    /**
     * 2026-08-31, U-70 (Naji, round 5): "it'd make a lot more sense if the check box was in
     * the same section as the answers". The four radios sit beside their own boxes now, in
     * ONE ToggleGroup, which is still what makes two correct answers unrepresentable (C-8).
     */
    private final javafx.scene.control.ToggleGroup correctGroup =
            new javafx.scene.control.ToggleGroup();
    private final List<RadioButton> correctRadios = new ArrayList<>();
    private final Label answersError = new Label();
    private final FormField topicField =
            FormField.text(QuestionEditorCopy.TOPIC_LABEL, QuestionEditorCopy.TOPIC_PROMPT);
    /** U-68: create mode only; the course the new question belongs to. */
    private final ComboBox<CourseRef> courseBox = new ComboBox<>();
    private final FormField courseField =
            new FormField(QuestionEditorCopy.COURSE_LABEL, courseBox);
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
    /**
     * Whether the long-lived controls' listeners are attached (2026-08-31, round 5 sweep).
     *
     * <p>{@code buildForm} runs once per visit and the screen is cached for the life of the
     * process, so a listener added there unconditionally is added again on every visit. Each
     * lambda reads the {@code session} field rather than capturing a session, so the
     * duplicates were idempotent rather than wrong; what they were is a leak that grows with
     * every visit. The per-visit controls (the answer rows and their radios) are rebuilt
     * fresh each time and keep their wiring in the loop that builds them.
     */
    private boolean wiredOnce;

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

        boolean newQuestion = "true".equals(params.getString(PARAM_NEW, ""));
        if (detail == null && course == null && !newQuestion) {
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
        // No hop here, and the paragraph that used to argue for one is retracted rather than
        // deleted (E18.5, 2026-08-27). It read "the hop, and it is not optional", on the grounds
        // that LockAwareEditor publishes from applyAnswer inside whenComplete and so arrives on
        // the network reader thread. That was true when it was written, and the fix it asked for
        // was made where it belonged: publish now goes through eventBus.poster(), so every
        // snapshot reaches every listener on the FX thread. ClientEventBus states the rule for
        // the tier - "screens therefore never call Platform.runLater themselves" - and this
        // screen was the last one still doing it.
        //
        // The second hop was not merely redundant. It deferred the banner an extra pulse, and
        // BotManagerView records that in tests it can land past the harness teardown that nulls
        // the bus. ExamBuilderView, written against this pattern, has none.
        lockBanner.show(state, QuestionEditorCopy.LOCK_NOUN);
        session.setReadOnly(!state.isEditable());
        render();
    }

    // ===================== Building =======================================

    private void buildForm() {
        root.getChildren().clear();
        answerFields.clear();
        // The radios are rebuilt per visit but the ToggleGroup is a field, so the previous
        // visit's four have to leave the group or it grows by four per visit, forever (the
        // screen is cached). Detaching the selected one fires the group listener with null,
        // which the null guard below ignores.
        for (RadioButton radio : correctRadios) {
            radio.setToggleGroup(null);
        }
        correctRadios.clear();

        textBox.setPromptText(QuestionEditorCopy.TEXT_PROMPT);
        textBox.setWrapText(true);
        textBox.setPrefRowCount(3);
        textField.required();

        VBox answers = new VBox(8);
        answers.getStyleClass().add("editor-answers");
        for (int i = 1; i <= QuestionEditorSession.ANSWER_COUNT; i++) {
            int position = i;
            FormField field = FormField.text(BankCopy.answerLabel(i),
                    QuestionEditorCopy.answerPrompt(i));
            field.required();
            RadioButton radio = new RadioButton(QuestionEditorCopy.CORRECT_MARK);
            radio.getStyleClass().add("radio-option");
            radio.setToggleGroup(correctGroup);
            radio.setUserData(position);
            radio.setAccessibleText(QuestionEditorCopy.correctMarkAccessible(position));
            correctRadios.add(radio);
            field.trailing(radio);
            field.textField().textProperty().addListener((observable, old, value) -> {
                if (!filling) {
                    session.setAnswer(position, value);
                }
            });
            answerFields.add(field);
            answers.getChildren().add(field);
        }

        answersError.getStyleClass().add("field-message");
        answersError.setWrapText(true);
        setShown(answersError, false);

        topicField.required();
        wireOnce();

        // U-68: the create form owns its course. Options are the courses she may write in
        // (LoginResult.courses is the taught set, the same set QUESTION_CREATE checks). Absent
        // in edit mode, where the course is a fact of the stored question. Guarded for the
        // gallery and tests, where nobody is signed in.
        boolean creating = session.mode() == QuestionEditorSession.Mode.CREATE;
        if (creating) {
            LoginResult user = ScreenManager.getInstance().signedInUser();
            courseBox.getItems().setAll(user == null ? List.<CourseRef>of() : user.courses());
            courseBox.getStyleClass().add("question-course");
            courseBox.setPromptText(QuestionEditorCopy.COURSE_PROMPT);
            courseBox.setCellFactory(view -> new CourseNameCell());
            courseBox.setButtonCell(new CourseNameCell());
            courseField.required();
        }
        courseField.setVisible(creating);
        courseField.setManaged(creating);

        difficultyBox.getStyleClass().add("question-difficulty");
        // Items set once: this box is a reused field on a cached screen, and re-running
        // setAll on every visit can drop the current value out of the button cell (U-98).
        if (difficultyBox.getItems().isEmpty()) {
            difficultyBox.getItems().setAll(Difficulty.values());
        }
        // 2026-09-02, U-96: a StringConverter, not a custom button cell. The old button cell
        // (U-56/U-92) rendered only when JavaFX chose to refresh it, which raced the skin on
        // real Windows - the box opened blank until an open/discard/reopen forced a refresh.
        // A converter is consulted by the default button cell's own text binding on every
        // setValue, so the difficulty shows from the first render with no re-drive and no
        // timing hack. It renders the popup list too, so DifficultyCell is gone entirely.
        difficultyBox.setConverter(new javafx.util.StringConverter<Difficulty>() {
            @Override
            public String toString(Difficulty difficulty) {
                return difficulty == null ? null : BankCopy.difficulty(difficulty);
            }

            @Override
            public Difficulty fromString(String label) {
                return null;
            }
        });
        difficultyField.required();

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

        VBox form = new VBox(16, courseField, textField, answers, answersError, hint(), topicField,
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

    /**
     * Attaches the listeners of the controls that outlive a visit, exactly once.
     *
     * <p>Every lambda reads the {@code session} field at event time, so the wiring survives
     * the session being replaced on the next {@code onShow}. The course listener is attached
     * whether or not this visit is a create: in edit mode the box is hidden, its items are
     * empty and {@code QuestionEditorSession.setCourse} ignores every call anyway.
     */
    private void wireOnce() {
        if (wiredOnce) {
            return;
        }
        wiredOnce = true;
        textBox.textProperty().addListener((observable, old, value) -> {
            if (!filling) {
                session.setText(value);
            }
        });
        correctGroup.selectedToggleProperty().addListener((observable, old, picked) -> {
            if (!filling && picked != null) {
                session.setCorrectAnswer((Integer) picked.getUserData());
            }
        });
        topicField.textField().textProperty().addListener((observable, old, value) -> {
            if (!filling) {
                session.setTopic(value);
            }
        });
        difficultyBox.getSelectionModel().selectedItemProperty()
                .addListener((observable, old, value) -> {
                    if (!filling) {
                        session.setDifficulty(value);
                    }
                });
        courseBox.getSelectionModel().selectedItemProperty()
                .addListener((observable, old, value) -> {
                    if (!filling) {
                        session.setCourse(value == null ? null : value.code());
                    }
                });
    }

    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private Node hint() {
        Label label = new Label(QuestionEditorCopy.ANSWERS_HINT);
        label.getStyleClass().addAll("small", "muted");
        label.setWrapText(true);
        return label;
    }

    /**
     * Shows a difficulty in the box, reliably on the FIRST open of the cached editor (U-98).
     *
     * <p>The screen is cached, so on its first ever show the ComboBox has no skin yet, and a
     * value set before the skin exists leaves the default button cell blank until something
     * forces a refresh - which is why editing the first question of a session showed an empty
     * difficulty until an open/discard/reopen (U-56 and U-92 chased this with a pulse and a
     * scene listener and still lost the race; the skin, not the scene, is what renders the
     * cell). The fix keys on the skin itself: set the value now, and if the skin is not there
     * yet, re-set it the instant it appears - a definite event, not a guessed pulse.
     */
    private void showDifficulty(Difficulty value) {
        difficultyBox.setValue(value);
        if (difficultyBox.getSkin() == null) {
            difficultyBox.skinProperty().addListener(new javafx.beans.value.ChangeListener<>() {
                @Override
                public void changed(javafx.beans.value.ObservableValue<? extends javafx.scene.control.Skin<?>> obs,
                                    javafx.scene.control.Skin<?> was, javafx.scene.control.Skin<?> now) {
                    if (now != null) {
                        difficultyBox.skinProperty().removeListener(this);
                        filling = true;
                        try {
                            difficultyBox.setValue(null);
                            difficultyBox.setValue(value);
                        } finally {
                            filling = false;
                        }
                    }
                }
            });
        }
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
                Integer chosen = session.correctAnswer();
                for (RadioButton radio : correctRadios) {
                    radio.setSelected(radio.getUserData().equals(chosen));
                }
            }
            topicField.textField().setText(session.topic());
            if (session.courseCode() != null) {
                courseBox.getItems().stream()
                        .filter(course -> course.code().equals(session.courseCode()))
                        .findFirst()
                        .ifPresent(course -> courseBox.getSelectionModel().select(course));
            }
            if (session.difficulty() != null) {
                showDifficulty(session.difficulty());
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
        save.setTooltip(session.isUnchangedEdit()
                ? new javafx.scene.control.Tooltip(QuestionEditorCopy.NOTHING_CHANGED) : null);
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
        setShown(answersError, false);
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
                case CORRECT_ANSWER -> {
                    answersError.setText(problem.message());
                    setShown(answersError, true);
                }
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

    /** U-68: course options render by name; the id is a bank detail, not a choice. */
    private static final class CourseNameCell extends ListCell<CourseRef> {
        @Override
        protected void updateItem(CourseRef course, boolean empty) {
            super.updateItem(course, empty);
            setText(empty || course == null ? null : course.name());
        }
    }

}
