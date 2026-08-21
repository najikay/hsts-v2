package client.features.exam;

import client.ui.components.Buttons;
import common.dto.exam.ExamQuestion;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * One question card, as a student sees it (Presentation tier, E10.10/E8.4 ⚑ — F6.2, F4.1).
 *
 * <p>Position and worth, the stem, an optional illustration and four single-select options.
 * It renders an {@link ExamQuestion} and holds no rules: which option is chosen, whether the
 * paper is still live and what happens on a click are all the caller's.
 *
 * <h2>Why this is a component and not two screens' worth of layout code</h2>
 *
 * <p>F4.1 requires a coordinator to see the exam <b>exactly as a student will</b>, and v1
 * failed that requirement by not showing her the exam at all. The obvious repair is a preview
 * screen; the honest one is this class. A second card written to the same specification looks
 * identical on the day it is written and drifts the first time either screen changes a
 * padding, a wrap or an image size — and the drift is invisible until somebody puts the two
 * on a projector side by side.
 *
 * <p>So {@link ExamFormView} draws its live paper with this, and
 * {@code client.features.approval.ExamPreviewView} draws the coordinator's preview with the
 * same class in {@linkplain #readOnly() read-only} mode over the same
 * {@link ExamQuestion} type. "She sees what the student sees" is then a fact about the node
 * graph rather than a promise about two files.
 *
 * <p>The card carries no correctness and cannot: {@link ExamQuestion} has no field for one.
 * The coordinator's answer key is drawn in a side panel beside the paper, never on it, which
 * is what keeps the preview an honest rendering of the student's screen instead of a marked
 * copy of it.
 */
public final class QuestionCardView extends VBox {

    private final ExamQuestion question;
    private final List<RadioButton> options = new ArrayList<>(ExamQuestion.OPTION_COUNT);

    private IntConsumer onSelect = option -> { };

    /**
     * True while the card is writing state onto the radio buttons.
     *
     * <p>{@code setSelected} fires the same listener a click does, so without this a repaint
     * would look exactly like the student picking the option she already had, and every clock
     * re-sync would send a redundant write.
     */
    private boolean applying;

    /**
     * @param question   the question to draw
     * @param totalCount how many questions the paper has, for the "Question 3 of 20" line;
     *                   pass {@code 0} to omit the total
     */
    public QuestionCardView(ExamQuestion question, int totalCount) {
        this.question = Objects.requireNonNull(question, "question");
        setSpacing(12);
        getStyleClass().addAll("hsts-card", "question-card");

        getChildren().addAll(meta(totalCount), stem());
        if (question.hasImage()) {
            getChildren().add(illustration());
        }
        getChildren().add(optionsBox());
    }

    /** @return the question this card is drawing. */
    public ExamQuestion question() {
        return question;
    }

    /** Registers what happens when an option is picked; the argument is 1..4. */
    public QuestionCardView onSelect(IntConsumer handler) {
        this.onSelect = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /**
     * Locks the card: options are still shown and still legible, and nothing responds.
     *
     * <p>What the approval preview uses for every card, and what the Time Up takeover uses
     * for a paper that is over. Disabled rather than removed, because "read-only" has to
     * look like the same exam, not like a different one (F4.1).
     */
    public QuestionCardView readOnly() {
        setLive(false);
        return this;
    }

    /**
     * Writes the chosen option and the paper's liveness onto the radio buttons.
     *
     * @param chosen the option 1..4, or {@code 0} for none
     * @param live   whether picking is still allowed
     */
    public void applyState(int chosen, boolean live) {
        applying = true;
        try {
            for (int index = 0; index < options.size(); index++) {
                options.get(index).setSelected(index + 1 == chosen);
            }
        } finally {
            applying = false;
        }
        setLive(live);
    }

    /**
     * Marks one option as the correct one (E8.4).
     *
     * <p>Used only by the coordinator's preview, and never by a student's paper: the method
     * takes the key as an argument rather than reading it off the question, because
     * {@link ExamQuestion} has nowhere to hold one. A card that could find the answer by
     * itself would be a card that could leak it.
     *
     * @param correctOption the right option 1..4; anything outside that marks nothing
     */
    public void markCorrect(int correctOption) {
        for (int index = 0; index < options.size(); index++) {
            RadioButton option = options.get(index);
            option.getStyleClass().remove("answer-key");
            if (index + 1 == correctOption) {
                option.getStyleClass().add("answer-key");
                option.setAccessibleHelp("This is the correct answer.");
            }
        }
    }

    /** @return the four option controls, for tests and for the preview's marking. */
    public List<RadioButton> options() {
        return List.copyOf(options);
    }

    private void setLive(boolean live) {
        for (RadioButton option : options) {
            // The server would refuse a late answer anyway; a form that still responds to
            // clicks after the paper is closed is the v1 screen.
            option.setDisable(!live);
        }
    }

    private HBox meta(int totalCount) {
        Label position = new Label(totalCount > 0
                ? "Question " + question.ordinal() + " of " + totalCount
                : "Question " + question.ordinal());
        position.getStyleClass().addAll("small", "muted");

        Label worth = new Label(question.points() + " points");
        worth.getStyleClass().addAll("small", "faint");

        HBox meta = new HBox(10, position, Buttons.spacer(), worth);
        meta.setAlignment(Pos.CENTER_LEFT);
        return meta;
    }

    private Label stem() {
        Label stem = new Label(question.text());
        stem.getStyleClass().add("question-text");
        stem.setWrapText(true);
        return stem;
    }

    /**
     * Renders the optional illustration (F2.1).
     *
     * <p>Decoded defensively: a corrupt or truncated image in the bank must not blank the
     * question a student is trying to answer. If it will not decode she still sees the stem
     * and the options, which is the part she is being marked on.
     */
    private ImageView illustration() {
        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.setFitWidth(520);
        view.getStyleClass().add("question-image");
        try {
            view.setImage(new Image(new ByteArrayInputStream(question.image())));
        } catch (RuntimeException e) {
            view.setVisible(false);
            view.setManaged(false);
        }
        return view;
    }

    private VBox optionsBox() {
        ToggleGroup group = new ToggleGroup();
        VBox box = new VBox(8);
        box.getStyleClass().add("question-options");

        for (int index = 1; index <= ExamQuestion.OPTION_COUNT; index++) {
            RadioButton option = new RadioButton(question.option(index));
            option.getStyleClass().add("question-option");
            option.setWrapText(true);
            option.setToggleGroup(group);
            option.setUserData(index);
            options.add(option);
            box.getChildren().add(option);
        }
        group.selectedToggleProperty().addListener((obs, old, picked) -> {
            if (picked != null && !applying) {
                onSelect.accept((int) picked.getUserData());
            }
        });
        return box;
    }
}
