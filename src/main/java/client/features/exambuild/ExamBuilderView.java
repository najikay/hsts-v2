package client.features.exambuild;

import client.core.NavParams;
import client.ui.components.Buttons;
import client.ui.components.FormField;
import client.ui.components.logic.AsyncViewState;
import client.ui.screen.AbstractScreen;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * The exam builder (Presentation tier, E7.11 / E7.12 — F3.1, F3.5, S-11, T-3.2).
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
 * <h2>The Add button is disabled and says why ⚑</h2>
 *
 * <p>The picker's add path needs a {@code questionVersionId} and the frozen bank wire carries
 * none, which is a contract gap raised with the lead rather than something a teacher did wrong.
 * A control that is simply inert reads as broken, so it carries
 * {@link ExamBuildCopy#ADD_UNAVAILABLE}. Editing a paper that already has questions is
 * unaffected and fully live.
 */
public final class ExamBuilderView extends AbstractScreen {

    private final BorderPane root = new BorderPane();

    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final Label readOnlyBanner = new Label(ExamBuildCopy.READ_ONLY_BANNER);
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
    private final Button addQuestion = Buttons.secondary("Add from the bank");
    private final Button saveButton = Buttons.primary(ExamBuildCopy.SAVE_BUTTON);
    private final Button retryLoad = Buttons.outline(ExamBuildCopy.RETRY);

    private ExamBuilderSession session;

    /** Suppresses the write-back while a render is setting control values. */
    private boolean rendering;

    /** The paper shape currently on screen, so a repoint does not rebuild the boxes. */
    private String paperShape;

    /** The "not available yet" line, hidden on a version nothing can be added to anyway. */
    private Label addUnavailable;

    @Override
    protected Parent build() {
        session = new ExamBuilderSession(dispatcher(), onFxThread()).onChange(this::render);

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
        long versionId = params.getLong("examVersionId", 0);
        if (versionId > 0) {
            session.open(versionId);
            return;
        }
        session.openNew(params.getString("courseCode", null));
    }

    @Override
    public boolean listensToEvents() {
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
        addQuestion.setOnAction(e -> session.addFromBank());
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

    // ===================== Rendering ======================================

    private void render() {
        rendering = true;
        try {
            renderHeader();
            renderMetadata();
            renderPaper();
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
    private static String shapeOf(List<ExamBuilderSession.Line> lines) {
        StringBuilder shape = new StringBuilder();
        for (ExamBuilderSession.Line line : lines) {
            shape.append(line.questionVersionId()).append(',');
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
            text.getChildren().add(badge);
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

        addQuestion.setDisable(!session.canAddFromBank() || !session.isEditable());
        show(addQuestion, session.isEditable());
        // The apology goes with the button. On a read-only version the screen already says
        // nothing can be changed, and adding a second sentence apologising for not being able
        // to add questions says one thing twice and contradicts neither.
        show(addUnavailable, session.isEditable());
    }

    private void renderNotices() {
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

        VBox header = new VBox(10, new VBox(4, title, subtitle), readOnlyBanner,
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

        TabPane texts = new TabPane(
                new Tab(ExamBuildCopy.STUDENT_TEXT_TAB, studentText),
                new Tab(ExamBuildCopy.TEACHER_TEXT_TAB, new VBox(6, teacherHint, teacherText)));
        texts.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        texts.setPrefHeight(190);

        VBox details = new VBox(12, detailsTitle, nameField, durationField, texts);
        details.getStyleClass().add("hsts-card");

        Label paperTitle = new Label(ExamBuildCopy.PAPER_TITLE);
        paperTitle.getStyleClass().add("h3");

        Label unavailable = new Label(ExamBuildCopy.ADD_UNAVAILABLE);
        unavailable.getStyleClass().addAll("small", "muted");
        unavailable.setWrapText(true);

        HBox paperHeader = new HBox(10, paperTitle, Buttons.spacer(), addQuestion);
        paperHeader.setAlignment(Pos.CENTER_LEFT);

        addUnavailable = unavailable;
        VBox paperBox = new VBox(12, paperHeader, unavailable, paper);

        VBox all = new VBox(20, details, paperBox);
        all.setPadding(new Insets(0, 28, 24, 28));

        ScrollPane scroll = new ScrollPane(all);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        return scroll;
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
