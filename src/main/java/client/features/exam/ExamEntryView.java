package client.features.exam;

import client.ui.components.Buttons;
import client.ui.components.FormField;
import client.ui.components.Icons;
import common.dto.exam.ExamHeader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * The code screen and the identity screen (Presentation tier, E10.9 — F6.1).
 *
 * <p>A renderer. Every rule, every error and both transitions live in
 * {@link ExamEntrySession}, which is FX-free and unit-tested; this class puts the session's
 * state on screen and sends the two buttons back to it.
 *
 * <p>The two steps share one node graph and swap which card is showing, so the transition
 * is a fade rather than a screen change. The exam summary between them is the point of the
 * split: a student confirms she is about to sit <i>Algebra Midterm, 45 minutes, 20
 * questions</i> before the sentence that starts her clock.
 */
public final class ExamEntryView extends StackPane {

    private final ExamEntrySession session;

    private final FormField codeField = FormField.text(ExamCopy.CODE_LABEL, "e.g. 4B7Q");
    private final FormField idField = FormField.text(ExamCopy.ID_LABEL, "Your ID number");
    private final Button continueButton = Buttons.primary(ExamCopy.CODE_BUTTON);
    private final Button startButton = Buttons.primary(ExamCopy.START_BUTTON);
    private final Label summaryTitle = new Label();
    private final Label summaryMeta = new Label();
    private final Label blockedText = new Label();

    private final VBox codeCard;
    private final VBox identityCard;
    private final VBox blockedCard;

    /** @param session the entry state machine this renders */
    public ExamEntryView(ExamEntrySession session) {
        this.session = Objects.requireNonNull(session, "session");
        getStyleClass().add("exam-entry");
        setPadding(new Insets(32));

        codeField.hint("Your teacher reads it out at the start of the exam.");
        codeField.textField().setPrefColumnCount(8);
        codeField.textField().textProperty().addListener((obs, old, value) -> session.setCode(value));
        codeField.textField().setOnAction(e -> session.submitCode());
        continueButton.setOnAction(e -> session.submitCode());

        idField.textField().setPrefColumnCount(12);
        idField.textField().textProperty().addListener((obs, old, value) -> session.setNationalId(value));
        idField.textField().setOnAction(e -> session.start());
        startButton.setOnAction(e -> session.start());

        codeCard = card(ExamCopy.CODE_TITLE, ExamCopy.CODE_SUBTITLE, codeField, continueButton);
        identityCard = identityCard();
        blockedCard = blockedCard();

        getChildren().addAll(codeCard, identityCard, blockedCard);
        session.onChange(this::refresh);
        refresh();
    }

    /** Mirrors the session onto the two cards. */
    public void refresh() {
        codeField.apply(session.codeState());
        idField.apply(session.idState());
        continueButton.setDisable(!session.canContinue());
        startButton.setDisable(!session.canStart());
        blockedText.setText(session.blockedMessage());

        session.header().ifPresent(this::showSummary);
        show(codeCard, session.phase() == EntryPhase.CODE);
        show(identityCard, session.phase() == EntryPhase.IDENTITY);
        show(blockedCard, session.phase() == EntryPhase.BLOCKED);
    }

    /**
     * Fills the code field in from a code the dashboard already validated.
     *
     * <p>Typing it into the control rather than into the session, so the session's own
     * listener runs and the button state, the validation and the field stay one thing.
     *
     * @param code the code to pre-fill; blank is ignored
     */
    public void prefillCode(String code) {
        if (code != null && !code.isBlank()) {
            codeField.textField().setText(code);
        }
    }

    /** Puts keyboard focus where the student is about to type. */
    public void focusCurrentField() {
        if (session.phase() == EntryPhase.IDENTITY) {
            idField.textField().requestFocus();
        } else {
            codeField.textField().requestFocus();
        }
    }

    private void showSummary(ExamHeader header) {
        summaryTitle.setText(header.examName());
        summaryMeta.setText(header.courseLabel()
                + " · " + ExamCopy.minutes(header.durationMinutes())
                + " · " + header.questionCount() + " questions");
    }

    private VBox identityCard() {
        summaryTitle.getStyleClass().add("h2");
        summaryMeta.getStyleClass().addAll("small", "muted");
        VBox summary = new VBox(2, summaryTitle, summaryMeta);
        summary.getStyleClass().add("exam-summary");

        VBox card = card(ExamCopy.ID_TITLE, ExamCopy.ID_SUBTITLE, idField, startButton);
        card.getChildren().add(1, summary);
        return card;
    }

    private VBox blockedCard() {
        Label title = new Label(ExamCopy.SUBMITTED_TITLE);
        title.getStyleClass().add("h2");
        blockedText.getStyleClass().addAll("body", "muted");
        blockedText.setWrapText(true);

        VBox card = new VBox(12, Icons.of(Icons.CHECK, Icons.SIZE_LARGE, "exam-entry-icon"),
                title, blockedText);
        card.getStyleClass().addAll("hsts-card", "exam-entry-card");
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(460);
        card.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return card;
    }

    private static VBox card(String title, String subtitle, FormField field, Button action) {
        Label heading = new Label(title);
        heading.getStyleClass().add("h1");

        Label explanation = new Label(subtitle);
        explanation.getStyleClass().addAll("body", "muted");
        explanation.setWrapText(true);

        HBox actions = new HBox(action);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox card = new VBox(16, heading, explanation, field, actions);
        card.getStyleClass().addAll("hsts-card", "exam-entry-card");
        card.setMaxWidth(460);
        card.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return card;
    }

    /** Keeps {@code managed} in step with {@code visible} so a hidden card takes no space. */
    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
