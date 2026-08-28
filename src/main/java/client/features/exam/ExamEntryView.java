package client.features.exam;

import client.ui.components.Buttons;
import client.ui.components.FormField;
import client.ui.components.Icons;
import common.dto.exam.ExamHeader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
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

    /** Style hook for the confirming variant's field; the look is in {@code hsts.css}. */
    private static final String READ_ONLY_CLASS = "read-only";

    private final ExamEntrySession session;

    private final FormField codeField = FormField.text(ExamCopy.CODE_LABEL, "e.g. 4B7Q");
    private final FormField idField = FormField.text(ExamCopy.ID_LABEL, "Your ID number");
    private final Button continueButton = Buttons.primary(ExamCopy.CODE_BUTTON);
    private final Label codeTitle = new Label(ExamCopy.CODE_TITLE);
    private final Label codeSubtitle = new Label(ExamCopy.CODE_SUBTITLE);
    /** The way out of the confirming variant; hidden on the ordinary code step. */
    private final Hyperlink differentCode = new Hyperlink(ExamCopy.DIFFERENT_CODE);
    private final Button startButton = Buttons.primary(ExamCopy.START_BUTTON);
    private final Label summaryTitle = new Label();
    private final Label summaryMeta = new Label();
    /** The B-14 disclosure: shown only when the window cuts the sitting short. */
    private final Label summaryWindow = new Label();
    private final Label blockedText = new Label();

    private final VBox codeCard;
    private final VBox identityCard;
    private final VBox blockedCard;

    /** @param session the entry state machine this renders */
    public ExamEntryView(ExamEntrySession session) {
        this.session = Objects.requireNonNull(session, "session");
        getStyleClass().add("exam-entry");
        setPadding(new Insets(32));

        codeField.hint(ExamCopy.CODE_HINT);
        codeField.textField().setPrefColumnCount(8);
        codeField.textField().textProperty().addListener((obs, old, value) -> session.setCode(value));
        codeField.textField().setOnAction(e -> observed(session.submitCode()));
        continueButton.setOnAction(e -> observed(session.submitCode()));

        idField.textField().setPrefColumnCount(12);
        idField.textField().textProperty().addListener((obs, old, value) -> session.setNationalId(value));
        idField.textField().setOnAction(e -> observed(session.start()));
        startButton.setOnAction(e -> observed(session.start()));

        differentCode.getStyleClass().add("small");
        differentCode.setOnAction(e -> session.useDifferentCode());

        codeCard = codeCard();
        identityCard = identityCard();
        blockedCard = blockedCard();

        getChildren().addAll(codeCard, identityCard, blockedCard);
        session.onChange(this::refresh);
        refresh();
    }

    /**
     * Observes a button's future instead of dropping it (M-4).
     *
     * <p>The session's futures never complete exceptionally by contract, but the
     * {@code onStarted} listener runs downstream of them and renders the whole paper;
     * a throw there used to vanish into a discarded future, which is how a student
     * met a blank screen with no evidence anywhere. This is the evidence.
     */
    private static void observed(java.util.concurrent.CompletableFuture<Void> future) {
        future.whenComplete((ignored, failure) -> {
            if (failure != null) {
                org.slf4j.LoggerFactory.getLogger(ExamEntryView.class)
                        .error("Exam entry failed after the server answered", failure);
            }
        });
    }

    /** Mirrors the session onto the two cards. */
    public void refresh() {
        renderCodeStep();
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
     * The code step in whichever of its two moods the session is in ⚑.
     *
     * <p>2026-08-28, manual round 1, lead's ruling: arriving from the dashboard card renders
     * this step as a confirmation. Same card, same field and same button, because it is the
     * same request underneath and a second card would be a second thing to keep in step; what
     * changes is that the code is stated rather than asked for, the field is read-only, and
     * the button already means yes.
     *
     * <p>The control's text is written from the session rather than the other way round, and
     * only when the two differ, so the text listener that feeds the session cannot loop.
     */
    private void renderCodeStep() {
        boolean confirming = session.isConfirming();
        String code = session.code();
        if (!code.equals(codeField.textField().getText())) {
            codeField.textField().setText(code);
        }
        codeTitle.setText(confirming ? ExamCopy.CONFIRM_TITLE : ExamCopy.CODE_TITLE);
        codeSubtitle.setText(confirming
                ? ExamCopy.confirmSubtitle(code) : ExamCopy.CODE_SUBTITLE);
        continueButton.setText(confirming ? ExamCopy.CONFIRM_BUTTON : ExamCopy.CODE_BUTTON);
        codeField.textField().setEditable(!confirming);
        codeField.textField().getStyleClass().remove(READ_ONLY_CLASS);
        if (confirming) {
            codeField.textField().getStyleClass().add(READ_ONLY_CLASS);
        }
        codeField.hint(confirming ? "" : ExamCopy.CODE_HINT);
        show(differentCode, confirming);
    }

    /** Puts keyboard focus where the student is about to act. */
    public void focusCurrentField() {
        if (session.phase() == EntryPhase.IDENTITY) {
            idField.textField().requestFocus();
        } else if (session.isConfirming()) {
            // Nothing to type: the one thing left to do is the thing the button does.
            continueButton.requestFocus();
        } else {
            codeField.textField().requestFocus();
        }
    }

    /**
     * The exam summary above the ID field, and the one sentence B-14 added to it ⚑.
     *
     * <p>The meta line still names the paper's own length, because that is what the exam is
     * worth and she may well be comparing it with what her teacher said. When the execution's
     * window shuts before that length is up, a second line states plainly when this sitting
     * ends and how long that actually leaves her — said here, on the screen before the one
     * that starts her clock, because afterwards it is not information any more.
     *
     * <p>The line is hidden entirely rather than blanked when the window is wide enough,
     * which is the normal case: a reassuring "you have all 75 minutes" on every entry would
     * make the sentence that matters just another line she has learned to skip.
     */
    private void showSummary(ExamHeader header) {
        summaryTitle.setText(header.examName());
        summaryMeta.setText(header.courseLabel()
                + " · " + ExamCopy.minutes(header.durationMinutes())
                + " · " + header.questionCount() + " questions");
        boolean shortened = header.isSittingShortened();
        if (shortened) {
            summaryWindow.setText(
                    ExamCopy.sittingShortened(header.windowClosesAt(), header.sittingMinutes()));
        }
        show(summaryWindow, shortened);
    }

    /**
     * The code card, built once and re-titled by {@link #renderCodeStep()}.
     *
     * <p>Not the shared {@link #card} helper, because this card's title, explanation and
     * button all change with the session and it carries one node the identity card does not:
     * the link out of the confirming variant.
     */
    private VBox codeCard() {
        codeTitle.getStyleClass().add("h1");
        codeSubtitle.getStyleClass().addAll("body", "muted");
        codeSubtitle.setWrapText(true);

        HBox actions = new HBox(12, differentCode, Buttons.spacer(), continueButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox card = new VBox(16, codeTitle, codeSubtitle, codeField, actions);
        card.getStyleClass().addAll("hsts-card", "exam-entry-card");
        card.setMaxWidth(460);
        card.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return card;
    }

    private VBox identityCard() {
        summaryTitle.getStyleClass().add("h2");
        summaryMeta.getStyleClass().addAll("small", "muted");
        summaryWindow.getStyleClass().addAll("small", "exam-summary-window");
        summaryWindow.setWrapText(true);
        show(summaryWindow, false);
        VBox summary = new VBox(2, summaryTitle, summaryMeta, summaryWindow);
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
