package client.features.home;

import client.core.NavParams;
import client.ui.components.Buttons;
import client.ui.components.EmptyState;
import client.ui.components.FormField;
import client.ui.components.Icons;
import client.ui.components.ToastStack;
import client.ui.components.logic.ValidationState;
import client.ui.screen.AbstractScreen;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;

/**
 * The student dashboard (Presentation tier, E5.6 — T-1).
 *
 * <p>The exam-code card is real: the field, the validation and the Enter button
 * behave exactly as they will in E10 (rules in {@link StudentHomeSession},
 * unit-tested). Only the last step is honest about itself — submitting a valid
 * code answers with an info toast naming the epic that will start the attempt,
 * rather than a spinner that goes nowhere.
 */
public final class StudentHomeView extends AbstractScreen {

    private final VBox headerHost = new VBox();
    private final StudentHomeSession session = new StudentHomeSession();

    private FormField codeField;
    private Button enterButton;

    @Override
    protected Parent build() {
        session.onChange(this::refreshCodeCard);

        codeField = FormField.text("Execution code", "e.g. 4B7Q");
        codeField.hint("Your teacher reads the code out at the start of the exam.");
        codeField.textField().setPrefColumnCount(6);
        codeField.textField().textProperty().addListener((obs, old, value) -> session.setCode(value));
        codeField.textField().setOnAction(e -> submitCode());

        enterButton = Buttons.primary("Enter");
        enterButton.setOnAction(e -> submitCode());
        enterButton.setDisable(true);

        HBox entry = new HBox(12, codeField, enterButton);
        entry.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(codeField, Priority.NEVER);
        // Align the button with the control, not with the field's label row.
        HBox.setMargin(enterButton, new javafx.geometry.Insets(22, 0, 0, 0));

        return DashboardPage.page(
                headerHost,
                DashboardPage.card("Take an exam",
                        "Enter the 4-character code your teacher gives you.",
                        entry),
                DashboardPage.statGrid(
                        DashboardPage.statCard("Exams taken", "Arrives with E10"),
                        DashboardPage.statCard("Average grade", "Arrives with E13"),
                        DashboardPage.statCard("Courses",
                                "From your enrolments",
                                Integer.toString(DashboardPage.currentCourses().size()))),
                DashboardPage.coursesCard("Your courses",
                        "You are enrolled in these. Each course has a study bot.",
                        DashboardPage.currentCourses(),
                        "You are not enrolled in any course yet."),
                DashboardPage.card("Recent grades",
                        "A grade appears here once your teacher approves it.",
                        new EmptyState(Icons.RESULTS, "No grades yet",
                                "Grades show up with the checked exam form, after teacher approval.")));
    }

    @Override
    public void onShow(NavParams params) {
        headerHost.getChildren().setAll(
                DashboardPage.header(DashboardPage.currentDisplayName(), LocalDateTime.now()));
        session.clear();
        codeField.textField().clear();
    }

    private void submitCode() {
        String message = session.submit();
        if (!session.canSubmit()) {
            codeField.apply(ValidationState.invalid(message));
            return;
        }
        ToastStack toasts = toasts();
        if (toasts != null) {
            toasts.info("Not built yet", message);
        }
    }

    /** Mirrors the session's state onto the field and the button. */
    private void refreshCodeCard() {
        enterButton.setDisable(!session.canSubmit());
        codeField.apply(session.validationError()
                .map(ValidationState::invalid)
                .orElseGet(ValidationState::pristine));
    }
}
