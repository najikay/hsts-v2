package client.features.home;

import client.core.NavParams;
import client.core.Routes;
import client.ui.components.Buttons;
import client.ui.components.FormField;
import client.ui.components.logic.ValidationState;
import client.ui.screen.AbstractScreen;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;

/**
 * The student dashboard (Presentation tier, E5.6 — T-1).
 *
 * <p>The exam-code card is the shortcut into E10: the field, the validation and the Enter
 * button behave exactly as the take-exam screen's own code step does (rules in
 * {@link StudentHomeSession}, unit-tested), and a well-formed code navigates there with
 * the code already filled in. Nothing is skipped by that: the screen still joins, still
 * asks for an ID, and the clock still starts there (S-18).
 *
 * <p>UI wave 1 (F-10) replaced the three placeholder stats with two real cards from
 * {@link StudentDashboardSession}: the latest published grade, and the study bot.
 * A third was asked for and dropped, with the reason recorded on that class: no
 * verb on the wire answers "which exam is next for me", and a dashboard card is
 * not where a protocol change gets decided. The code entry above is, and remains,
 * the real way into a sitting.
 */
public final class StudentHomeView extends AbstractScreen {

    private final VBox headerHost = new VBox();
    private final StudentHomeSession session = new StudentHomeSession();
    private final GridPane cards = new GridPane();

    private StudentDashboardSession dashboard;
    private FormField codeField;
    private Button enterButton;

    @Override
    protected Parent build() {
        session.onChange(this::refreshCodeCard);
        dashboard = new StudentDashboardSession(dispatcher(), onFxThread())
                .onChange(this::renderCards);

        codeField = FormField.text("Execution code", "e.g. 4B7Q");
        codeField.hint("Your teacher reads the code out at the start of the exam.");
        codeField.textField().setPrefColumnCount(6);
        codeField.textField().textProperty().addListener((obs, old, value) -> session.setCode(value));
        codeField.textField().setOnAction(e -> submitCode());

        enterButton = Buttons.primary("Enter");
        enterButton.setOnAction(e -> submitCode());
        enterButton.setDisable(true);

        HBox entry = new HBox(12, codeField, enterButton);
        // 2026-08-30, live session, U-48: TOP_LEFT, not CENTER_LEFT. The form field grows
        // when its hint or its validation sentence shows, and a centre-aligned row then
        // re-centres the button against the taller field, so it jumped by half a line every
        // time the sentence came and went. Anchored to the top, the button sits beside the
        // input (the margin below is the label's height) whatever appears under the field.
        entry.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(codeField, Priority.NEVER);
        // Align the button with the control, not with the field's label row.
        HBox.setMargin(enterButton, new javafx.geometry.Insets(22, 0, 0, 0));

        return DashboardPage.page(
                headerHost,
                DashboardPage.card("Take an exam",
                        "Enter the 4-character code your teacher gives you.",
                        entry),
                cards,
                DashboardPage.coursesCard("Your courses",
                        "You are enrolled in these. Each course has a study bot.",
                        DashboardPage.currentCourses(),
                        "You are not enrolled in any course yet."));
    }

    @Override
    public void onShow(NavParams params) {
        renderHeader();
        session.clear();
        codeField.textField().clear();
        renderCards();
        dashboard.load();
    }

    private void renderCards() {
        DashboardPage.fillCardGrid(cards, dashboard.cards(), navigator()::navigate);
        renderHeader();
    }

    /**
     * Rebuilt on every settle, not only on show: the summary sentence is
     * composed from what the grades read returned, so it is provisional until
     * that lands.
     */
    private void renderHeader() {
        headerHost.getChildren().setAll(DashboardPage.header(
                DashboardPage.currentDisplayName(), LocalDateTime.now(), dashboard.summary()));
    }

    private void submitCode() {
        if (!session.submit()) {
            codeField.apply(ValidationState.invalid(StudentHomeSession.INVALID_CODE));
            return;
        }
        // The code travels as a hint; the take-exam screen still joins, still asks for an
        // ID and still starts the clock there (S-18). Nothing is skipped by arriving early.
        navigator().navigate(Routes.TAKE_EXAM.id(),
                NavParams.of(StudentHomeSession.CODE_PARAM, session.normalizedCode()));
    }

    /** Mirrors the session's state onto the field and the button. */
    private void refreshCodeCard() {
        enterButton.setDisable(!session.canSubmit());
        codeField.apply(session.validationError()
                .map(ValidationState::invalid)
                .orElseGet(ValidationState::pristine));
    }
}
