package client.features.exambuild;

import client.core.NavParams;
import client.core.ScreenManager;
import client.features.locks.EditLockState;
import client.features.locks.FxHeartbeat;
import client.features.locks.LockAwareEditor;
import client.features.locks.LockBanner;
import client.ui.components.Buttons;
import client.ui.components.FormField;
import client.ui.components.logic.AsyncViewState;
import client.ui.screen.AbstractScreen;
import common.dto.authoring.Shortfall;
import common.dto.bank.BankQuestionRow;
import common.dto.auth.LoginResult;
import common.dto.lock.EntityRef;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * The exam builder (Presentation tier, E7.11 to E7.14 — F3.1, F3.3, F3.5, S-11, T-3.2).
 *
 * <p>A renderer over {@link ExamBuilderSession}, which owns every decision it makes: which of
 * three things this screen is doing, what the paper says, and whether the points are right.
 *
 * <h2>One screen, three modes, and the title says which</h2>
 *
 * <p>New exam, open draft, and a finished version rendered read-only (contract §8's read path,
 * ruled 2026-08-25). The mode is derived by the session from the server's answer and never set
 * here, so this class cannot put the screen into a state the data does not support.
 *
 * <h2>The points indicator is the save rule, showing its working ⚑</h2>
 *
 * <p>T-3.2 watches the indicator go from wrong to right, so it shows the total <i>and</i> the
 * server's own sentence about it, from {@code ExamValidator.pointsProblem}. There is no
 * arithmetic in this file: a screen that computed its own total would eventually disagree with
 * the rule that refuses the save, and the teacher would be told a green form is invalid.
 *
 * <h2>Tabs are the segmented control, not {@code TabPane} ⚑</h2>
 *
 * <p>Both tab strips on this screen - the student/teacher texts and the manual/auto composer -
 * are a {@code ToggleGroup} of {@code ToggleButton}s under {@code .hsts-segmented}, which is the
 * idiom {@code DataView} and the results screens use and the only one the stylesheet dresses.
 *
 * <p><b>The texts were a {@code TabPane} until 2026-08-26</b>, the only one in the client. It
 * went unnoticed while this screen had one tab strip; E7.13 puts a second one directly above it,
 * and two tab idioms stacked on one screen is the kind of thing T-21 is a scenario about. Cost of
 * reversing: this method and the two fields it builds.
 *
 * <h2>The picker lives here rather than in a screen of its own</h2>
 *
 * <p>Deliberate, and the reason is measurement rather than taste. A separate {@code *View} would
 * be a new thin FX class needing its own JaCoCo exclusion, and the pom's plugin config is not
 * Member A's to edit. Every decision it makes is in {@link ExamBuilderSession} regardless - which
 * rows are offered, which are already on the paper, what the filter matches - so the split would
 * have bought a file and a coverage argument, and nothing else.
 */
public final class ExamBuilderView extends AbstractScreen {

    /**
     * The nav key carrying the course of a NEW exam, owned by the screen that reads it.

     * <p>House convention, the same shape as {@code QuestionEditorView.PARAM_COURSE} and the
     * four bot screens: the receiving view spells the key and every navigator imports it, so a
     * rename cannot leave a producer and a consumer disagreeing silently. A door that navigates
     * here with the wrong spelling opens {@code openNew(null)}, which is a builder whose bank
     * picker is scoped to nothing and whose save carries no course.
     */
    public static final String PARAM_COURSE = "courseCode";

    private final BorderPane root = new BorderPane();

    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final Label readOnlyBanner = new Label(ExamBuildCopy.READ_ONLY_BANNER);
    private final LockBanner lockBanner = new LockBanner();
    private LockAwareEditor locks;

    /** True between {@code onShow} and {@code onHide}, so a release cannot re-open its own lock. */
    private boolean showing;
    private final Label loadError = new Label();

    private final FormField nameField =
            FormField.text(ExamBuildCopy.NAME_LABEL, "").hint(ExamBuildCopy.nameHint());
    private final FormField durationField =
            FormField.text(ExamBuildCopy.DURATION_LABEL, "").hint(ExamBuildCopy.durationHint());
    private final TextArea studentText = new TextArea();
    private final TextArea teacherText = new TextArea();

    private final VBox paper = new VBox(10);
    private final Label pointsIndicator = new Label();
    private final Label pointsProblem = new Label();
    private final Button addQuestion = Buttons.secondary(ExamBuildCopy.ADD_BUTTON);
    private final Button saveButton = Buttons.primary(ExamBuildCopy.SAVE_BUTTON);
    private final Button retryLoad = Buttons.outline(ExamBuildCopy.RETRY);

    // --- the texts, one segment each --------------------------------------
    private final VBox studentPane = new VBox();
    private final VBox teacherPane = new VBox(6);

    // --- the bank picker (E7.12) ------------------------------------------
    private final VBox pickerBox = new VBox(12);
    private final Label pickerTitle = new Label();
    private final TextField pickerSearch = new TextField();
    private final VBox pickerRows = new VBox(6);
    private final Label pickerMessage = new Label();
    private final Button pickerRetry = Buttons.outline(ExamBuildCopy.RETRY);
    private final Button pickerClose = Buttons.secondary(ExamBuildCopy.PICKER_CLOSE);

    /** Shown while a row has been re-pinned and not yet saved (E7.14). */
    private final Label repinnedNotice = new Label(ExamBuildCopy.REPINNED_NOTICE);

    // --- the two composer tabs (E7.13) ------------------------------------
    private final VBox manualPane = new VBox(12);
    private final VBox autoPane = new VBox(12);
    private final HBox composerSegments = new HBox();
    private ToggleButton manualSegment;
    private ToggleButton autoSegment;
    private final VBox criteriaRows = new VBox(8);
    private final Label criteriaProblem = new Label();
    private final Button generate = Buttons.primary(ExamBuildCopy.GENERATE);
    private final Label composeStatus = new Label();
    private final VBox report = new VBox(4);
    private String criteriaShape;

    private ExamBuilderSession session;

    /** Suppresses the write-back while a render is setting control values. */
    private boolean rendering;

    /** The paper shape currently on screen, so a repoint does not rebuild the boxes. */
    private String paperShape;

    @Override
    protected Parent build() {
        session = new ExamBuilderSession(dispatcher(), onFxThread()).onChange(this::onSessionChanged);

        wireLocks();
        wireFields();

        root.getStyleClass().add("exam-builder");
        root.setTop(buildHeader());
        root.setCenter(buildBody());
        root.setBottom(buildFooter());
        return root;
    }

    /**
     * How the app enters the builder.
     *
     * <p>Three doors, one screen. {@code examVersionId} opens a stored version and the session
     * decides from its state whether that is an edit or a read; {@code courseCode} with no
     * version is a new exam. A call with neither is a navigation defect rather than a state to
     * render, and it lands on the new-exam form rather than on a blank screen.
     */
    @Override
    public void onShow(NavParams params) {
        showing = true;
        long versionId = params.getLong("examVersionId", 0);
        if (versionId > 0) {
            // No syncLock here. It was called on this line and it was dead: open() begins with
            // resetLoaded, which zeroes examVersionId, and the answer arrives asynchronously, so
            // the id syncLock needs does not exist yet. The first render after the answer is what
            // opens the lock.
            session.open(versionId);
            return;
        }
        session.openNew(params.getString(PARAM_COURSE, null));
    }

    /**
     * Gives the lock back the moment she leaves rather than waiting for the TTL (E18.5).
     *
     * <p>The server's sweep frees an abandoned hold eventually, so a client that dies still
     * releases. That is the safety net and not the mechanism: without this call the builder keeps
     * telling the next teacher that this version "is being edited by Dana" for the whole TTL
     * after she has navigated away, which is the failure the banner exists to prevent, wearing
     * the opposite sign.
     */
    @Override
    public void onHide() {
        // Before the close, not after. close() publishes an IDLE snapshot, IDLE is not OWNED, so
        // renderLockState sets the session locked-out, which fires onChange, which runs
        // onSessionChanged, which calls syncLock - and syncLock would find examVersionId still
        // set and locks.entity() just nulled, and open the lock straight back up. The release and
        // the re-acquire went out on the same pulse, and the heartbeat then renewed it for the
        // life of the process, so the hold outlived the TTL that was supposed to be the backstop.
        //
        // Found by a cold read. The test that named this behaviour asserted only that a
        // LOCK_RELEASE was sent, which stayed true while the lock was immediately retaken, so it
        // could not have failed on it. It now asserts nothing acquires after the last release.
        showing = false;
        if (locks != null) {
            locks.close();
        }
    }

    /**
     * One session change: paint, then reconcile the lock. <b>In that order, and never nested.</b>
     *
     * <p>{@code syncLock} was called from the top of {@link #render()} first, and it re-entered:
     * {@code locks.open} publishes CHECKING synchronously through the event bus's poster, which
     * lands in {@code renderLockState}, which moves the session flag, which fires this listener
     * again - all before the outer render had set its {@code rendering} guard or finished
     * rebuilding the paper. The screen came back with a paper the second render thought was
     * already current, so the newer-version and Save buttons were simply absent, and three
     * existing tests failed on empty lookups rather than on anything they were testing.
     *
     * <p>Calling the lock after the paint keeps the recursion one level deep and terminating:
     * the inner change re-enters here, renders, and finds {@code syncLock} a no-op because the
     * entity it would open is the one already held.
     */
    private void onSessionChanged() {
        render();
        syncLock();
    }

    /**
     * Opens the edit lock once there is a row to lock, and not before (E18.5).
     *
     * <p><b>A new exam cannot be locked, because it does not exist.</b> {@code Mode.CREATE} runs
     * with {@code examVersionId == 0} until {@code EXAM_CREATE} answers, and an
     * {@code EntityRef} built on 0 would name a row nobody can hold. So the only caller is
     * {@link #onSessionChanged}, which runs after every render: on the change that carries the
     * loaded version, and again on the one where a first save supplies the id. This javadoc named
     * two other call sites until the cold read; {@code onShow} called it on a line where the id
     * was always still zero, and {@code render} called it re-entrantly, which is the defect
     * {@code onSessionChanged} documents. Both are gone. Calling it repeatedly is safe:
     * {@code LockAwareEditor.open} on the entity it already holds is a no-op, and the guard below
     * stops the acquire storm that would otherwise ride every keystroke.
     *
     * <p>The key is {@code new EntityRef(EXAM_VERSION, examVersionId)} because that is the exact
     * expression {@code ExamService.lockHolderOtherThan} builds when it decides whether to refuse
     * a write. A banner keyed on anything else would never appear and never fail a test either:
     * both sides would simply be talking about different rows.
     *
     * <p>Three gates, and each one is a case that went wrong. {@code showing} keeps
     * {@code onHide}'s own release from being undone by the render it causes. A zero id means
     * {@code Mode.CREATE}, where there is no row. And a READ_ONLY version is not lockable in any
     * sense worth having: an edit lock is exclusive and heartbeated, so previewing an approved
     * exam would take a hold nobody can use on a screen where no edit is possible, and every past
     * version a teacher ever looked at would sit under her name.
     */
    private void syncLock() {
        if (locks == null || !showing) {
            return;
        }
        if (session.mode() == ExamBuilderSession.Mode.READ_ONLY) {
            return;
        }
        long versionId = session.examVersionId();
        if (versionId <= 0) {
            return;
        }
        EntityRef target = new EntityRef(EntityRef.EXAM_VERSION, versionId);
        if (target.equals(locks.entity())) {
            return;
        }
        locks.open(target);
    }

    /** The banner's offer, taken. The server decides; this only asks. */
    private void takeOver() {
        if (locks != null) {
            locks.takeOver();
        }
    }

    /**
     * Paints another teacher's hold onto the builder.
     *
     * <p><b>No {@code Platform.runLater} here, deliberately.</b> {@code LockAwareEditor.publish}
     * delivers through {@code ClientEventBus}'s poster, and that class's javadoc states the rule
     * for the whole tier: every event reaches subscribers on the FX thread, so "screens therefore
     * never call {@code Platform.runLater} themselves". {@code QuestionEditorView} has a hop and a
     * paragraph calling it mandatory; that paragraph describes the bus as it was before
     * 2026-08-24 and is corrected in this commit.
     *
     * <p>The refusal goes to the session rather than only to the button, because
     * {@link ExamBuilderSession#isEditable()} is the one gate every mutator already consults.
     * Disabling Save alone would leave the form writable underneath a banner saying it is not.
     */
    private void renderLockState(EditLockState.Snapshot state) {
        // The banner is painted here and the form by the session's own change notification:
        // setLockedOut fires onChange when the flag actually moves, which is the house pattern
        // (QuestionEditorSession.setReadOnly). Calling render() here as well would paint twice on
        // every acquire, and re-entering render from inside a listener that render can itself
        // trigger is the shape a loop grows out of.
        lockBanner.show(state, ExamBuildCopy.LOCK_NOUN);
        session.setLockedOut(!state.isEditable());
    }

    @Override
    public boolean listensToEvents() {
        // Locks do not need this. LockAwareEditor.open registers itself on the bus and
        // close unregisters, so its pushes arrive whatever this answers; the flag governs
        // only @Subscribe methods on the screen itself, and this screen has none.
        return false;
    }

    // ===================== Wiring =========================================

    /**
     * Sends every keystroke to the session and nothing back.
     *
     * <p>{@link #rendering} is the loop-breaker: {@code render()} writes into these controls, and
     * a listener that fired on those writes would call the session, which fires {@code onChange},
     * which renders again. The bank editor guards its bindings the same way.
     */
    private void wireFields() {
        nameField.textField().textProperty().addListener((obs, was, now) -> {
            if (!rendering) {
                session.name(now);
            }
        });
        durationField.textField().textProperty().addListener((obs, was, now) -> {
            if (!rendering) {
                session.durationMinutes(parseMinutes(now));
            }
        });
        studentText.textProperty().addListener((obs, was, now) -> {
            if (!rendering) {
                session.studentText(now);
            }
        });
        teacherText.textProperty().addListener((obs, was, now) -> {
            if (!rendering) {
                session.teacherText(now);
            }
        });
        addQuestion.setOnAction(e -> session.openPicker());
        pickerClose.setOnAction(e -> session.closePicker());
        pickerRetry.setOnAction(e -> session.retryPicker());
        pickerSearch.textProperty().addListener((obs, old, typed) -> {
            if (!rendering) {
                session.pickerSearch(typed);
            }
        });
        retryLoad.setOnAction(e -> session.reopen());
        saveButton.setOnAction(e -> session.save());
    }

    /**
     * @param typed what is in the duration box
     * @return the number, or {@code 0} for anything that is not one. Zero is out of range, so a
     *         non-numeric duration is refused by the very rule that refuses a zero one rather
     *         than by a second rule about parsing
     */
    private static int parseMinutes(String typed) {
        try {
            return Integer.parseInt(typed == null ? "" : typed.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /**
     * What she typed into a criteria bucket, or a refusal she can see ⚑.
     *
     * <p><b>Deliberately not {@link #parseMinutes}, and this method exists because it was.</b>
     * That one falls back to zero and justifies it with "zero is out of range" - true of a
     * duration, false of a bucket count, where zero is the legal default every row starts at. So
     * a mistyped count became a silent, legal zero: the box went on showing what she typed, the
     * live rule found nothing wrong, and she composed an exam missing the bucket she was looking
     * at a number in.
     *
     * <p>The fallback here is {@code -1}, which {@code quotaProblem} refuses by its own
     * negative-bucket rule ({@code ExamBuildMessages.QUOTA_NEGATIVE}). Same trick the duration
     * box uses, aimed at a value that is actually out of range for this field: no second rule
     * about parsing, and no way for unreadable input to pass as a number she did not type.
     *
     * @param typed what is in one count box
     * @return the number, or {@code -1} for anything that is not one
     */
    private static int parseCount(String typed) {
        try {
            return Integer.parseInt(typed == null ? "" : typed.trim());
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    // ===================== Rendering ======================================

    private void render() {
        rendering = true;
        try {
            renderHeader();
            renderMetadata();
            renderPaper();
            renderPicker();
            renderAuto();
            renderPoints();
            renderFooter();
        } finally {
            rendering = false;
        }
        renderNotices();
    }

    private void renderHeader() {
        ExamBuilderSession.Mode mode = session.mode();
        title.setText(ExamBuildCopy.title(mode));
        subtitle.setText(subtitleFor());

        // Only once an answer has landed ⚑. modeFor treats an opened version with no state yet
        // as READ_ONLY, which is the right way to fail closed but the wrong thing to SAY: before
        // the answer arrives the banner would tell her this version "has been sent for approval"
        // on every open of a draft, and would sit there permanently beside "could not be opened"
        // when the load failed. A guard that fails closed and a sentence that states a fact are
        // different things, and only the first should run early.
        show(readOnlyBanner, mode == ExamBuilderSession.Mode.READ_ONLY
                && session.state() == AsyncViewState.READY);

        boolean failed = session.loadError().isPresent();
        loadError.setText(session.loadError().orElse(""));
        show(loadError, failed);
        show(retryLoad, failed);
    }

    private String subtitleFor() {
        String id = session.displayId6();
        String course = session.courseName() == null || session.courseName().isBlank()
                ? session.courseCode()
                : session.courseName();
        if (id == null || id.isBlank()) {
            return course == null ? "" : course;
        }
        return id + (course == null || course.isBlank() ? "" : " · " + course);
    }

    private void renderMetadata() {
        setIfChanged(nameField.textField(), session.name());
        setIfChanged(durationField.textField(), String.valueOf(session.durationMinutes()));
        setIfChanged(studentText, session.studentText());
        setIfChanged(teacherText, session.teacherText());

        boolean editable = session.isEditable();
        nameField.textField().setEditable(editable);
        durationField.textField().setEditable(editable);
        studentText.setEditable(editable);
        teacherText.setEditable(editable);
    }

    /**
     * Writes a value into a control only when it differs.
     *
     * <p>Unconditional writes move the caret to the end on every keystroke, which makes editing
     * the middle of an exam name impossible. The bank editor learned this the same way.
     */
    private static void setIfChanged(TextField field, String value) {
        String next = value == null ? "" : value;
        if (!next.equals(field.getText())) {
            field.setText(next);
        }
    }

    private static void setIfChanged(TextArea area, String value) {
        String next = value == null ? "" : value;
        if (!next.equals(area.getText())) {
            area.setText(next);
        }
    }

    /**
     * Rebuilds the paper, but only when the paper's <b>shape</b> changed ⚑.
     *
     * <p>The naive version cleared and rebuilt on every {@code onChange}, and a cold read found
     * what that costs: {@code session.points(...)} fires {@code onChange}, so every keystroke in
     * a points box destroyed the very {@code TextField} being typed into and built a new one.
     * The focus owner goes with the removed node, so the second digit never arrives and a
     * two-digit value cannot be entered at all. That is the control T-3.2 is demonstrated on.
     *
     * <p>The shape is the questions and their order; the points are not part of it, which is
     * exactly why a repoint must not rebuild. {@link #setIfChanged} already guards the metadata
     * against the milder version of this (a caret jumping to the end), and the paper needed the
     * stronger form.
     */
    private void renderPaper() {
        // Outside the shape check on purpose: re-pinning changes the shape and rebuilds the
        // cards, but the notice outlives that rebuild and has to be re-decided every render,
        // because what clears it is the save's re-read rather than anything about the list.
        show(repinnedNotice, session.hasRepinned());

        var lines = session.lines();
        String shape = shapeOf(lines);
        if (!shape.equals(paperShape)) {
            paperShape = shape;
            paper.getChildren().clear();
            if (lines.isEmpty()) {
                Label empty = new Label(ExamBuildCopy.PAPER_EMPTY);
                empty.getStyleClass().addAll("small", "muted");
                empty.setWrapText(true);
                paper.getChildren().add(empty);
                return;
            }
            for (int index = 0; index < lines.size(); index++) {
                paper.getChildren().add(questionCard(index, lines.get(index), lines.size()));
            }
        }
    }

    /**
     * @param lines the paper
     * @return what has to change for the cards to be rebuilt: which questions, in what order.
     *         Points are deliberately absent, because a repoint must leave the boxes standing
     */
    /**
     * The key that decides whether the paper is rebuilt, and what it must and must not include.
     *
     * <p><b>Not the points.</b> The cards hold the {@code TextField}s she types points into, and
     * rebuilding on a value change destroys the box mid-keystroke, which is PR23 §4.2's defect
     * and the reason this key exists at all.
     *
     * <p><b>The stem, and this was missing until a cold read found it.</b> The key was the pinned
     * version ids alone. A re-pin changes an id, so the card rebuilt at the click carrying the old
     * wording by design; the save's re-read then answered with those same ids, the key matched,
     * nothing rebuilt, and the corrected wording never arrived. The stem cannot change while she
     * is typing, so including it costs no rebuild she is in the middle of.
     *
     * <p>{@code showsSupersededDetails} is in the key for the same reason one step later: it is
     * what the row's own staleness marker is drawn from, and the save clears it.
     *
     * <p><b>And {@code latestVersionNo}</b>, added after a second cold read pointed out the key
     * was still one term short. The badge and its action are drawn from
     * {@code pinnedVersionNo < latestVersionNo}, and neither number was here: if a colleague
     * publishes a new version of a pinned question while she has the builder open, her next
     * save's re-read carries a higher {@code latestVersionNo} on a row whose id, marker and stem
     * are all unchanged, so nothing rebuilt and the badge never appeared.
     *
     * <p><b>The rule this key follows:</b> it holds every term a server re-read can change and
     * the cards draw from, and nothing she can be halfway through typing. Points are the
     * exception that proves it and are deliberately absent.
     */
    private static String shapeOf(List<ExamBuilderSession.Line> lines) {
        StringBuilder shape = new StringBuilder();
        for (ExamBuilderSession.Line line : lines) {
            shape.append(line.questionVersionId()).append('@')
                    .append(line.pinnedVersionNo()).append('@')
                    .append(line.latestVersionNo()).append('@')
                    .append(line.showsSupersededDetails() ? '1' : '0').append('@')
                    .append(line.text()).append('');
        }
        return shape.toString();
    }

    /**
     * One question on the paper, with its points box and its two movers.
     *
     * <p>The index is captured per card and every control on it acts on that index, so a card
     * cannot move or repoint a different row than the one it is drawn for.
     */
    private Node questionCard(int index, ExamBuilderSession.Line line, int total) {
        Label position = new Label(String.valueOf(index + 1) + ".");
        position.getStyleClass().addAll("small", "muted");

        Label stem = new Label(line.text());
        stem.getStyleClass().add("body");
        stem.setWrapText(true);

        Label summary = new Label(ExamBuildCopy.questionSummary(line));
        summary.getStyleClass().addAll("small", "muted");

        VBox text = new VBox(4, stem, summary);
        HBox.setHgrow(text, Priority.ALWAYS);

        if (line.hasNewerVersion()) {
            Label badge = new Label(ExamBuildCopy.NEWER_VERSION_BADGE);
            badge.getStyleClass().addAll("small", "warn-text");
            // The action goes beside the badge, not in the row's button group with move and
            // remove: it is only ever offered on the rows carrying that badge, and a control
            // that appears and disappears from a fixed group reads as a layout glitch. On a
            // read-only version the badge still shows, because "the bank has moved on" is a
            // fact about the paper, and only the button is withheld.
            if (session.isEditable()) {
                Button useNewer = Buttons.styled(ExamBuildCopy.USE_NEWER_VERSION,
                        Buttons.GHOST, Buttons.SMALL);
                useNewer.setOnAction(e -> session.updateToLatest(index));
                HBox badgeRow = new HBox(8, badge, useNewer);
                badgeRow.setAlignment(Pos.CENTER_LEFT);
                text.getChildren().add(badgeRow);
            } else {
                text.getChildren().add(badge);
            }
        }

        TextField points = new TextField(String.valueOf(line.points()));
        points.setPrefWidth(70);
        points.setEditable(session.isEditable());
        points.textProperty().addListener((obs, was, now) -> {
            if (!rendering) {
                session.points(index, parseMinutes(now));
            }
        });

        Label pointsLabel = new Label(ExamBuildCopy.POINTS_LABEL);
        pointsLabel.getStyleClass().addAll("small", "muted");
        VBox pointsBox = new VBox(4, pointsLabel, points);

        HBox card = new HBox(12, position, text, pointsBox);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().addAll("hsts-card", "exam-question-row");

        if (session.isEditable()) {
            Button up = Buttons.styled(ExamBuildCopy.MOVE_UP, Buttons.GHOST, Buttons.SMALL);
            up.setDisable(index == 0);
            up.setOnAction(e -> session.moveUp(index));

            Button down = Buttons.styled(ExamBuildCopy.MOVE_DOWN, Buttons.GHOST, Buttons.SMALL);
            down.setDisable(index == total - 1);
            down.setOnAction(e -> session.moveDown(index));

            Button remove = Buttons.styled(ExamBuildCopy.REMOVE, Buttons.GHOST, Buttons.SMALL);
            remove.setOnAction(e -> session.remove(index));

            card.getChildren().add(new HBox(4, up, down, remove));
        }
        return card;
    }

    private void renderPoints() {
        pointsIndicator.setText(ExamBuildCopy.pointsIndicator(session.pointsTotal()));
        pointsIndicator.getStyleClass().removeAll("ok-text", "danger-text");
        pointsIndicator.getStyleClass().add(session.pointsAreRight() ? "ok-text" : "danger-text");

        String problem = session.pointsProblem().orElse("");
        pointsProblem.setText(problem);
        show(pointsProblem, !problem.isBlank());
    }

    private void renderFooter() {
        saveButton.setText(ExamBuildCopy.saveButton(session.mode()));
        saveButton.setDisable(!session.isEditable() || session.isSaving()
                || !session.pointsAreRight());
        show(saveButton, session.isEditable());

        // Hidden rather than disabled while the picker is open: the picker IS the add path, and
        // a second control offering to open what is already open is a click that does nothing.
        show(addQuestion, session.isEditable() && !session.isPickerOpen());
        addQuestion.setDisable(!session.canAddFromBank());
    }

    private void renderNotices() {
        // A compose that landed moved her to another tab and replaced her paper, which is the
        // largest thing this screen does without asking twice. A toast is how every other
        // completed action here announces itself, and silence would leave her looking at a
        // different paper wondering whether she pressed the right thing.
        session.composeNotice().ifPresent(notice -> {
            toasts().success(notice);
            session.dismissComposeNotice();
        });
        session.saveNotice().ifPresent(notice -> {
            toasts().success(notice);
            session.dismissNotice();
        });
        session.saveError().ifPresent(sentence -> {
            toasts().error(ExamBuildCopy.title(session.mode()), sentence);
            session.dismissSaveError();
        });
    }

    // ===================== Layout =========================================

    /**
     * Builds the lock editor, or leaves it null when the collaborators are absent (E18.5).
     *
     * <p>The null case is the console harness and the screen-gallery, which build views without a
     * dispatcher or a signed-in user. Every call site checks, exactly as the bank editor does:
     * a screen that cannot reach the server still has to render.
     */
    private void wireLocks() {
        LoginResult user = ScreenManager.getInstance().signedInUser();
        if (dispatcher() == null || user == null || eventBus() == null) {
            return;
        }
        locks = new LockAwareEditor(dispatcher(), eventBus(), user.userId(), new FxHeartbeat(),
                ExamBuildCopy.LOCK_NOUN);
        locks.onStateChanged(this::renderLockState);
        lockBanner.setOnTakeOver(this::takeOver);
    }

    private Node buildHeader() {
        title.getStyleClass().add("h1");
        subtitle.getStyleClass().addAll("small", "muted");

        readOnlyBanner.getStyleClass().addAll("small", "hsts-card", "warn-banner");
        readOnlyBanner.setWrapText(true);
        show(readOnlyBanner, false);

        loadError.getStyleClass().addAll("small", "danger-text");
        loadError.setWrapText(true);
        show(loadError, false);

        show(retryLoad, false);

        // Above the read-only banner, which is a layout choice and nothing more. This comment
        // used to justify the order with "when a version is both APPROVED and held, the lock is
        // the one she can act on with Take over" - which was false twice over: syncLock no longer
        // takes a lock on a READ_ONLY version at all, and taking one over would clear lockedOut
        // while leaving mode() READ_ONLY, so the form would stay inert behind a button that
        // changed nothing. The two banners cannot now appear together.
        VBox header = new VBox(10, new VBox(4, title, subtitle), lockBanner, readOnlyBanner,
                new VBox(8, loadError, retryLoad));
        header.setPadding(new Insets(24, 28, 12, 28));
        return header;
    }

    private Node buildBody() {
        Label detailsTitle = new Label(ExamBuildCopy.DETAILS_TITLE);
        detailsTitle.getStyleClass().add("h3");

        studentText.setWrapText(true);
        studentText.setPrefRowCount(4);
        teacherText.setWrapText(true);
        teacherText.setPrefRowCount(4);

        Label teacherHint = new Label(ExamBuildCopy.TEACHER_TEXT_HINT);
        teacherHint.getStyleClass().addAll("small", "muted");
        teacherHint.setWrapText(true);

        studentPane.getChildren().add(studentText);
        teacherPane.getChildren().addAll(teacherHint, teacherText);

        VBox details = new VBox(12, detailsTitle, nameField, durationField,
                buildTextSegments(), studentPane, teacherPane);
        details.getStyleClass().add("hsts-card");

        Label paperTitle = new Label(ExamBuildCopy.PAPER_TITLE);
        paperTitle.getStyleClass().add("h3");

        HBox paperHeader = new HBox(10, paperTitle, Buttons.spacer(), addQuestion);
        paperHeader.setAlignment(Pos.CENTER_LEFT);

        repinnedNotice.getStyleClass().addAll("small", "muted");
        repinnedNotice.setWrapText(true);
        show(repinnedNotice, false);

        manualPane.getChildren().addAll(paperHeader, buildPicker(), repinnedNotice, paper);

        VBox all = new VBox(20, details, buildComposerSegments(), manualPane, buildAutoPane());
        all.setPadding(new Insets(0, 28, 24, 28));

        ScrollPane scroll = new ScrollPane(all);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        return scroll;
    }

    /**
     * The student/teacher text switch, as a segmented control.
     *
     * <p>Which of the two is showing is view state and stays here rather than going into the
     * session: both texts are already loaded and both are already bound, so switching shows a
     * panel rather than deciding anything. {@code DataView} puts its tab in its session because
     * each of its tabs fetches different rows, which is the case this one is not.
     */
    private Node buildTextSegments() {
        ToggleGroup group = new ToggleGroup();
        ToggleButton student = new ToggleButton(ExamBuildCopy.STUDENT_TEXT_TAB);
        ToggleButton teacher = new ToggleButton(ExamBuildCopy.TEACHER_TEXT_TAB);
        for (ToggleButton segment : List.of(student, teacher)) {
            segment.setToggleGroup(group);
        }
        student.setSelected(true);
        student.setOnAction(e -> showTextPane(false));
        teacher.setOnAction(e -> showTextPane(true));

        HBox segmented = new HBox(student, teacher);
        segmented.getStyleClass().addAll("hsts-segmented", "exam-text-toggle");
        showTextPane(false);
        return segmented;
    }

    private void showTextPane(boolean teacherShowing) {
        show(studentPane, !teacherShowing);
        show(teacherPane, teacherShowing);
    }

    /** The bank picker, built once and shown only while the session says it is open. */
    private Node buildPicker() {
        pickerTitle.getStyleClass().add("h3");

        pickerSearch.setPromptText(ExamBuildCopy.PICKER_SEARCH_PROMPT);
        pickerSearch.getStyleClass().add("exam-picker-search");

        pickerMessage.getStyleClass().addAll("small", "muted");
        pickerMessage.setWrapText(true);

        HBox header = new HBox(10, pickerTitle, Buttons.spacer(), pickerClose);
        header.setAlignment(Pos.CENTER_LEFT);

        ScrollPane rows = new ScrollPane(pickerRows);
        rows.setFitToWidth(true);
        rows.setPrefHeight(240);
        rows.getStyleClass().add("edge-to-edge");

        pickerBox.getChildren().addAll(header, pickerSearch, pickerMessage, pickerRetry, rows);
        pickerBox.getStyleClass().addAll("hsts-card", "exam-picker");
        show(pickerBox, false);
        // Hidden in its own right, not only by its hidden parent. A Node inside an invisible
        // container still answers isVisible() with true, so a lookup over the scene finds it: the
        // builder's own "there is exactly one Try again on a failed load" assertion caught this
        // picker's retry standing beside the load's while the picker was shut.
        show(pickerRetry, false);
        return pickerBox;
    }

    /**
     * Paints the picker from the session, including which of its four empty states applies.
     *
     * <p>The four are distinct on purpose: a bank that is empty, a filter that matches nothing, a
     * load that failed and a course with more pages than the picker fetches are four different
     * things to be told, and one shared "nothing here" would be wrong in three of them.
     */
    private void renderPicker() {
        boolean open = session.isPickerOpen();
        show(pickerBox, open);
        if (!open) {
            pickerRows.getChildren().clear();
            show(pickerRetry, false);
            show(pickerMessage, false);
            return;
        }
        pickerTitle.setText(ExamBuildCopy.pickerTitle(session.courseName()));
        setIfChanged(pickerSearch, session.pickerSearch());

        AsyncViewState state = session.pickerState();
        show(pickerRetry, state == AsyncViewState.ERROR);

        List<BankQuestionRow> rows = session.pickerRows();
        pickerRows.getChildren().clear();
        for (BankQuestionRow row : rows) {
            pickerRows.getChildren().add(pickerCard(row));
        }

        String message = pickerMessageFor(state, rows.isEmpty());
        pickerMessage.setText(message == null ? "" : message);
        show(pickerMessage, message != null);
    }

    private String pickerMessageFor(AsyncViewState state, boolean noRows) {
        if (state == AsyncViewState.ERROR) {
            return session.pickerError().orElse(ExamBuildCopy.PICKER_LOAD_FAILED);
        }
        if (state == AsyncViewState.LOADING) {
            return ExamBuildCopy.PICKER_LOADING;
        }
        if (noRows) {
            return session.pickerSearch().isBlank()
                    ? ExamBuildCopy.PICKER_EMPTY : ExamBuildCopy.PICKER_NO_MATCH;
        }
        // A full page count reached the bound: rows ARE showing, so this rides above them
        // rather than replacing them.
        return session.pickerError().orElse(null);
    }

    private Node pickerCard(BankQuestionRow row) {
        Label id = new Label(row.displayId5());
        id.getStyleClass().addAll("small", "mono", "muted");

        Label stem = new Label(row.text());
        stem.setWrapText(true);
        stem.setMaxWidth(460);

        Label meta = new Label(row.topic() + " · " + row.difficulty());
        meta.getStyleClass().addAll("small", "muted");

        VBox text = new VBox(2, id, stem, meta);

        boolean already = session.isOnPaper(row);
        Button add = Buttons.secondary(already ? ExamBuildCopy.PICKER_ALREADY_ADDED
                : ExamBuildCopy.PICKER_ADD);
        add.setDisable(already);
        add.setOnAction(e -> session.addFromBank(row));

        HBox card = new HBox(12, text, Buttons.spacer(), add);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("exam-picker-row");
        return card;
    }

    /** The manual/auto switch, on the same idiom as the texts above it. */
    private Node buildComposerSegments() {
        ToggleGroup group = new ToggleGroup();
        manualSegment = new ToggleButton(ExamBuildCopy.MANUAL_TAB);
        autoSegment = new ToggleButton(ExamBuildCopy.AUTO_TAB);
        manualSegment.setToggleGroup(group);
        autoSegment.setToggleGroup(group);
        manualSegment.setSelected(true);
        manualSegment.setOnAction(e -> session.tab(ExamBuilderSession.Tab.MANUAL));
        autoSegment.setOnAction(e -> session.tab(ExamBuilderSession.Tab.AUTO));

        composerSegments.getChildren().addAll(manualSegment, autoSegment);
        composerSegments.getStyleClass().addAll("hsts-segmented", "exam-composer-toggle");
        return composerSegments;
    }

    /**
     * The criteria form and its report (E7.13, F3.3).
     *
     * <p>Built once and shown by tab. The grid itself is rebuilt only when its shape changes,
     * the way the paper is, and for the same reason: a rebuild on every keystroke destroys the
     * box being typed into, which is the defect T-3.2 caught on the points fields.
     */
    private Node buildAutoPane() {
        Label title = new Label(ExamBuildCopy.CRITERIA_TITLE);
        title.getStyleClass().add("h3");

        Button addTopic = Buttons.secondary(ExamBuildCopy.ADD_TOPIC);
        addTopic.setOnAction(e -> session.addCriterion());

        criteriaProblem.getStyleClass().addAll("small", "danger-text");
        criteriaProblem.setWrapText(true);
        show(criteriaProblem, false);

        Label replaces = new Label(ExamBuildCopy.GENERATE_REPLACES);
        replaces.getStyleClass().addAll("small", "muted");
        replaces.setWrapText(true);

        generate.setOnAction(e -> session.generate());

        composeStatus.getStyleClass().addAll("small", "muted");
        composeStatus.setWrapText(true);
        show(composeStatus, false);

        HBox actions = new HBox(12, generate, Buttons.spacer(), addTopic);
        actions.setAlignment(Pos.CENTER_LEFT);

        autoPane.getChildren().addAll(title, criteriaRows, criteriaProblem, actions, replaces,
                composeStatus, report);
        autoPane.getStyleClass().add("hsts-card");
        show(autoPane, false);
        return autoPane;
    }

    /** Paints the criteria grid, the live rule and whatever the last compose answered. */
    private void renderAuto() {
        boolean auto = session.tab() == ExamBuilderSession.Tab.AUTO;
        show(autoPane, auto);
        show(manualPane, !auto);
        show(composerSegments, session.isEditable());
        if (manualSegment != null) {
            manualSegment.setSelected(!auto);
            autoSegment.setSelected(auto);
        }
        if (!auto) {
            return;
        }

        List<ExamBuilderSession.Criterion> rows = session.criteria();
        String shape = String.valueOf(rows.size());
        if (!shape.equals(criteriaShape)) {
            criteriaShape = shape;
            criteriaRows.getChildren().clear();
            for (int index = 0; index < rows.size(); index++) {
                criteriaRows.getChildren().add(criterionRow(index, rows.get(index)));
            }
        }

        // The server's own sentence, live. Never composed here: §7.3a's refusal has to name both
        // legal shapes and ExamBuildMessages owns that wording (ruling 4).
        String problem = session.criteriaProblem().orElse(null);
        criteriaProblem.setText(problem == null ? "" : problem);
        show(criteriaProblem, problem != null);
        generate.setDisable(!session.canGenerate());

        String status = session.isComposing()
                ? ExamBuildCopy.COMPOSING
                : session.composeError().orElse(null);
        composeStatus.setText(status == null ? "" : status);
        show(composeStatus, status != null);

        renderReport();
    }

    /** The infeasibility report: a heading, one sentence per shortfall, and what to do (§7.1). */
    private void renderReport() {
        List<Shortfall> shortfalls = session.shortfalls();
        report.getChildren().clear();
        show(report, !shortfalls.isEmpty());
        if (shortfalls.isEmpty()) {
            return;
        }
        Label heading = new Label(ExamBuildCopy.INFEASIBLE_TITLE);
        heading.getStyleClass().addAll("body", "danger-text");
        heading.setWrapText(true);
        report.getChildren().add(heading);

        for (Shortfall shortfall : shortfalls) {
            Label line = new Label(ExamBuildCopy.shortfallLine(shortfall));
            line.getStyleClass().add("small");
            line.setWrapText(true);
            report.getChildren().add(line);
        }

        Label hint = new Label(ExamBuildCopy.INFEASIBLE_HINT);
        hint.getStyleClass().addAll("small", "muted");
        hint.setWrapText(true);
        report.getChildren().add(hint);
    }

    private Node criterionRow(int index, ExamBuilderSession.Criterion criterion) {
        Node identity;
        if (index == 0) {
            Label courseWide = new Label(ExamBuildCopy.COURSE_WIDE_ROW);
            courseWide.getStyleClass().add("body");
            identity = courseWide;
        } else {
            TextField topic = new TextField(criterion.topic() == null ? "" : criterion.topic());
            topic.setPromptText(ExamBuildCopy.TOPIC_PROMPT);
            topic.setPrefWidth(180);
            topic.textProperty().addListener((obs, was, now) -> {
                if (!rendering) {
                    session.criterionTopic(index, now);
                }
            });
            identity = topic;
        }

        HBox row = new HBox(8, identity);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(identity, Priority.ALWAYS);

        row.getChildren().addAll(
                countBox(index, ExamBuildCopy.EASY_LABEL, ExamBuilderSession.Bucket.EASY,
                        criterion.easy()),
                countBox(index, ExamBuildCopy.MEDIUM_LABEL, ExamBuilderSession.Bucket.MEDIUM,
                        criterion.medium()),
                countBox(index, ExamBuildCopy.HARD_LABEL, ExamBuilderSession.Bucket.HARD,
                        criterion.hard()),
                countBox(index, ExamBuildCopy.ANY_LABEL, ExamBuilderSession.Bucket.ANY,
                        criterion.any()));

        if (index > 0) {
            Button remove = Buttons.styled(ExamBuildCopy.REMOVE_TOPIC, Buttons.GHOST, Buttons.SMALL);
            remove.setOnAction(e -> session.removeCriterion(index));
            row.getChildren().add(remove);
        }
        return row;
    }

    private Node countBox(int index, String label, ExamBuilderSession.Bucket bucket, int value) {
        TextField field = new TextField(String.valueOf(value));
        field.setPrefWidth(56);
        field.textProperty().addListener((obs, was, now) -> {
            if (!rendering) {
                session.criterionCount(index, bucket, parseCount(now));
            }
        });

        Label caption = new Label(label);
        caption.getStyleClass().addAll("small", "muted");
        return new VBox(2, caption, field);
    }

    private Node buildFooter() {
        pointsIndicator.getStyleClass().addAll("h3");
        pointsProblem.getStyleClass().addAll("small", "danger-text");
        pointsProblem.setWrapText(true);
        show(pointsProblem, false);

        VBox totals = new VBox(2, pointsIndicator, pointsProblem);

        HBox footer = new HBox(16, totals, Buttons.spacer(), saveButton);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(12, 28, 20, 28));
        footer.getStyleClass().add("exam-builder-footer");
        return footer;
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
