package client.features.exam;

import client.ui.components.Buttons;
import client.ui.components.CountdownTimer;
import common.dto.exam.ExamQuestion;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * The exam paper (Presentation tier, E10.10/E10.11 — F6.1, F6.2, F6.3).
 *
 * <p>A renderer over {@link AttemptModel}: the general instructions, one question card at a
 * time, the navigator strip, the progress line, the autosave indicator and the countdown.
 * It holds no rules. Which questions are answered, what the indicator says and what the
 * clock is anchored to are all the model's, and the model is unit-tested without a toolkit.
 *
 * <h2>One question at a time, with a strip</h2>
 *
 * <p>A twenty-question exam rendered as one long scroll is how a student loses her place
 * and how she fails to notice question 14 is blank. The strip is the answer to both: it is
 * always visible, it colours answered and blank differently, and clicking a chip jumps
 * there. It is also literally the same chip list the submit dialog shows (F6.9), built
 * from the same {@link AttemptModel#chips()}, so the two cannot disagree.
 *
 * <h2>One card class, shared with the coordinator's preview</h2>
 *
 * <p>Each question is drawn by {@link QuestionCardView}, which is also what E8.4's approval
 * preview draws with, read-only, over the same {@code ExamQuestion}. That is deliberate: F4.1
 * asks for a coordinator to see the exam exactly as a student will, and a second card written
 * to the same specification would look identical on the day it was written and drift
 * afterwards. Sharing the class makes the guarantee structural.
 *
 * <h2>The countdown is a display</h2>
 *
 * <p>{@link CountdownTimer} is re-anchored from {@link AttemptModel#timing()} on every
 * refresh, and every refresh follows a server answer. It counts between them and decides
 * nothing; the server is what ends an exam.
 */
public final class ExamFormView extends BorderPane {

    private final AttemptModel model;
    private final CountdownTimer countdown;

    private final Label examName = new Label();
    private final Label courseLine = new Label();
    private final Label generalText = new Label();
    private final VBox generalBlock = new VBox(6);
    private final Label progressLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label saveIndicator = new Label();
    private final FlowPane navigator = new FlowPane();
    private final VBox questionHost = new VBox(16);
    private final Button previous = Buttons.secondary("Previous");
    private final Button next = Buttons.secondary("Next");
    private final Button submit = Buttons.primary("Hand in");

    private java.util.function.BiConsumer<Long, Integer> onSelect = (question, option) -> { };
    private Runnable onSubmit = () -> { };

    /** Which question's card is currently built, so a repaint does not rebuild it. */
    private long renderedQuestionVersionId = -1;
    private QuestionCardView renderedCard;

    /**
     * @param model     the state to render
     * @param countdown the timer chip, injected so a test can drive it from a fake clock
     */
    public ExamFormView(AttemptModel model, CountdownTimer countdown) {
        this.model = Objects.requireNonNull(model, "model");
        this.countdown = Objects.requireNonNull(countdown, "countdown");
        getStyleClass().add("exam-form");

        setTop(buildHeader());
        setCenter(buildBody());
        setBottom(buildFooter());

        previous.setOnAction(e -> model.goTo(model.currentIndex() - 1));
        next.setOnAction(e -> model.goTo(model.currentIndex() + 1));
        submit.setOnAction(e -> onSubmit.run());
    }

    /** Registers what happens when the student picks an option. */
    public void onSelect(java.util.function.BiConsumer<Long, Integer> handler) {
        this.onSelect = Objects.requireNonNull(handler, "handler");
    }

    /** Registers what happens when she presses Hand in. */
    public void onSubmit(Runnable handler) {
        this.onSubmit = Objects.requireNonNull(handler, "handler");
    }

    /** @return the countdown chip, so the screen can play the Time Extended moment on it. */
    public CountdownTimer countdown() {
        return countdown;
    }

    // ===================== Rendering =====================================

    /** Repaints everything from the model. Called on every model change. */
    public void refresh() {
        if (model.header() != null) {
            examName.setText(model.header().examName());
            courseLine.setText(model.header().courseLabel());
            generalText.setText(model.header().generalText());
            show(generalBlock, model.header().hasGeneralText());
        }
        progressLabel.setText(model.progressLabel());
        progressBar.setProgress(model.progress());
        renderSaveIndicator();
        renderNavigator();
        renderQuestion();
        renderNavigation();
        model.endsAt().ifPresent(endsAt -> countdown.resync(endsAt, model.totalDuration()));
    }

    /** Anchors the countdown and starts it ticking. Called once, when the paper arrives. */
    public void startCountdown() {
        model.endsAt().ifPresent(endsAt -> countdown.start(endsAt, model.totalDuration()));
    }

    private void renderSaveIndicator() {
        SaveState state = model.saveState();
        saveIndicator.setText(state.label());
        saveIndicator.getStyleClass().removeAll("saved", "saving", "unsaved");
        saveIndicator.getStyleClass().add(state.styleClass());
    }

    private void renderNavigator() {
        navigator.getChildren().clear();
        for (QuestionChip chip : model.chips()) {
            Button button = new Button(chip.label());
            button.getStyleClass().add("nav-chip");
            button.getStyleClass().addAll(chip.styleClass().split(" "));
            button.setTooltip(new Tooltip(chip.tooltip()));
            button.setOnAction(e -> model.goTo(chip.index()));
            navigator.getChildren().add(button);
        }
    }

    /**
     * Shows the current question, rebuilding its card only when it has actually changed.
     *
     * <p>{@link #refresh()} runs on every model change, which includes every autosave
     * response and every clock re-sync. Rebuilding the card each time would take keyboard
     * focus away from the option a student is in the middle of choosing, several times a
     * minute, in the one screen where that is least forgivable. So the card is built when
     * the question changes and only its state is written on the other repaints.
     */
    private void renderQuestion() {
        java.util.Optional<ExamQuestion> current = model.currentQuestion();
        if (current.isEmpty()) {
            Label empty = new Label(ExamCopy.NO_QUESTIONS);
            empty.getStyleClass().addAll("body", "muted");
            empty.setWrapText(true);
            questionHost.getChildren().setAll(empty);
            renderedQuestionVersionId = -1;
            renderedCard = null;
            return;
        }
        ExamQuestion question = current.get();
        if (question.questionVersionId() != renderedQuestionVersionId) {
            renderedCard = new QuestionCardView(question, model.questionCount())
                    .onSelect(option -> onSelect.accept(question.questionVersionId(), option));
            questionHost.getChildren().setAll(renderedCard);
            renderedQuestionVersionId = question.questionVersionId();
        }
        // The takeover locks the paper. The card owns that, so the same component behaves
        // identically here and in the coordinator's read-only preview (E8.4).
        renderedCard.applyState(model.answerFor(question.questionVersionId()).orElse(0),
                model.isLive());
    }

    private void renderNavigation() {
        previous.setDisable(model.currentIndex() == 0);
        next.setDisable(model.currentIndex() >= model.questionCount() - 1);
        submit.setDisable(!model.isLive());
        navigator.setDisable(model.questionCount() == 0);
    }

    // ===================== Layout ========================================

    private VBox buildHeader() {
        examName.getStyleClass().add("h1");
        courseLine.getStyleClass().addAll("small", "muted");

        VBox titles = new VBox(2, examName, courseLine);
        HBox top = new HBox(16, titles, Buttons.spacer(), countdown);
        top.setAlignment(Pos.CENTER_LEFT);

        generalText.getStyleClass().addAll("body");
        generalText.setWrapText(true);
        Label generalTitle = new Label("Instructions");
        generalTitle.getStyleClass().add("h3");
        generalBlock.getChildren().addAll(generalTitle, generalText);
        generalBlock.getStyleClass().addAll("hsts-card", "exam-instructions");

        progressLabel.getStyleClass().addAll("small", "muted");
        saveIndicator.getStyleClass().add("save-indicator");
        progressBar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        HBox progressRow = new HBox(12, progressLabel, progressBar, saveIndicator);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        Label stripTitle = new Label(ExamCopy.NAVIGATOR_TITLE);
        stripTitle.getStyleClass().addAll("small", "faint");
        navigator.setHgap(6);
        navigator.setVgap(6);
        navigator.getStyleClass().add("question-strip");

        VBox header = new VBox(14, top, generalBlock, progressRow,
                new VBox(6, stripTitle, navigator));
        header.setPadding(new Insets(24, 28, 12, 28));
        header.getStyleClass().add("exam-header");
        return header;
    }

    private ScrollPane buildBody() {
        questionHost.setPadding(new Insets(4, 28, 24, 28));
        ScrollPane scroller = new ScrollPane(questionHost);
        scroller.setFitToWidth(true);
        scroller.getStyleClass().add("hsts-page");
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroller;
    }

    private HBox buildFooter() {
        HBox footer = new HBox(10, previous, next, Buttons.spacer(), submit);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(12, 28, 20, 28));
        footer.getStyleClass().add("exam-footer");
        return footer;
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
