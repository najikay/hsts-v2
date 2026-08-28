package client.features.results;

import client.core.NavParams;
import client.ui.components.BackLink;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.screen.AbstractScreen;
import common.dto.grading.AnswerReviewRow;
import common.dto.grading.CheckedForm;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * The student's checked exam (Presentation tier, E13.4 ⚑ — T-9.2, S-36).
 *
 * <p>A renderer over {@link CheckedFormSession}. Reached from a My Grades row rather than from
 * the rail, because it is a view of one paper and a rail item that needed a grade chosen first
 * would be a dead end — the same reasoning as E8's exam preview.
 *
 * <p>Thin by the usual rule, and on the coverage exclusion list by name: every marking decision
 * is in {@link CheckedFormCopy} and every load decision is in the session, both measured.
 *
 * <h2>Three outcomes per question, not two</h2>
 *
 * <p>Right, wrong, and never answered. The third scored zero exactly as the second did, but
 * they are different facts and a student reviewing a timed-out paper needs the difference — see
 * {@link CheckedFormCopy#UNANSWERED}.
 *
 * <h2>Print layout (S-36, acceptance 9.3)</h2>
 *
 * <p>One toggle that adds a style class, on the {@code TeacherResultsView} pattern: it drops the
 * application chrome and leaves the paper in a single column. Modest on purpose — what a student
 * needs from "print" is her marked paper without the app around it.
 */
public final class CheckedFormView extends AbstractScreen {

    private final VBox root = new VBox(16);
    private final Label heading = new Label(CheckedFormCopy.TITLE);
    private final Label header = new Label();
    private final Label teacherLine = new Label();
    private final Label attemptLine = new Label();
    private final Label teacherNote = new Label();
    private final VBox noteBox = new VBox(4);
    private final VBox questions = new VBox(12);
    private final ToggleButton printToggle = new ToggleButton("Print layout");
    private final EmptyState unavailable =
            new EmptyState(Icons.RESULTS, "Not available", CheckedFormSession.NOT_AVAILABLE);

    private CheckedFormSession session;
    private Node printExit;

    @Override
    protected Parent build() {
        session = new CheckedFormSession(dispatcher(), onFxThread()).onChange(this::render);

        heading.getStyleClass().add("h1");
        header.getStyleClass().add("h3");
        teacherLine.getStyleClass().addAll("small", "muted");
        attemptLine.getStyleClass().addAll("small", "muted");

        Label noteHeading = new Label(CheckedFormCopy.TEACHER_NOTE);
        noteHeading.getStyleClass().addAll("small", "muted");
        teacherNote.setWrapText(true);
        noteBox.getStyleClass().add("checked-form-note");
        noteBox.getChildren().addAll(noteHeading, teacherNote);

        printToggle.selectedProperty().addListener((obs, was, now) -> applyPrintLayout(now));

        // The way off the screen is the shell's navbar Back now: this route is not on
        // the rail, so the shell carries one for every screen in this position rather
        // than each of them remembering to build its own.
        // The way out of the print layout is a different thing and still belongs here,
        // beside the toggle whose place it takes: print mode hides that toggle, which is
        // how this screen came to have no exit from its own print view.
        printExit = BackLink.exit(ResultsCopy.PRINT_EXIT, ResultsCopy.PRINT_EXIT_TARGET,
                () -> printToggle.setSelected(false));
        show(printExit, false);

        HBox titleRow = new HBox(12, heading, spacer(), printToggle, printExit);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroller = new ScrollPane(questions);
        scroller.setFitToWidth(true);
        scroller.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroller, Priority.ALWAYS);

        root.getStyleClass().add(CheckedFormCopy.STYLE_CLASS);
        root.setPadding(new Insets(24));
        root.getChildren().addAll(titleRow, header, teacherLine, attemptLine, noteBox,
                unavailable, scroller);
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        long gradeId = params.getLong("gradeId", 0);
        if (gradeId > 0) {
            session.open(gradeId);
        }
    }

    @Override
    public boolean listensToEvents() {
        // A marked paper is frozen history. Nothing is pushed here.
        return false;
    }

    // ===================== Rendering =====================================

    private void render() {
        session.error().ifPresentOrElse(
                message -> {
                    unavailable.set("Not available", message);
                    show(unavailable, true);
                    show(header, false);
                    show(teacherLine, false);
                    show(attemptLine, false);
                    show(noteBox, false);
                    questions.getChildren().clear();
                },
                () -> show(unavailable, false));

        session.form().ifPresent(this::renderForm);
    }

    private void renderForm(CheckedForm form) {
        header.setText(CheckedFormCopy.header(form));
        attemptLine.setText(CheckedFormCopy.attemptLine(form));
        show(header, true);
        show(attemptLine, true);

        // A6: whose exam this was, under its name. Hidden rather than blank when the server
        // could not resolve a name, on the same rule the teacher's note follows.
        String teacher = CheckedFormCopy.teacherLine(form);
        teacherLine.setText(teacher == null ? "" : teacher);
        show(teacherLine, teacher != null);

        String note = CheckedFormCopy.teacherNote(form);
        teacherNote.setText(note == null ? "" : note);
        show(noteBox, note != null);

        questions.getChildren().clear();
        for (AnswerReviewRow row : form.answers()) {
            questions.getChildren().add(questionCard(row));
        }
    }

    /** One marked question: its text, its four options, and how it was marked. */
    private static VBox questionCard(AnswerReviewRow row) {
        Label number = new Label(row.ordinal() + ". " + row.questionText());
        number.setWrapText(true);
        number.getStyleClass().add("checked-form-question");

        Label outcome = new Label(CheckedFormCopy.outcome(row));
        outcome.getStyleClass().addAll("hsts-chip", CheckedFormCopy.outcomeStyle(row));

        Label points = new Label(CheckedFormCopy.points(row));
        points.getStyleClass().addAll("small", "muted");

        Label displayId = new Label(row.displayId());
        displayId.getStyleClass().addAll("small", "muted");

        HBox marks = new HBox(8, outcome, points, spacer(), displayId);
        marks.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(6, number, marks);
        card.getStyleClass().addAll("hsts-card", "checked-form-card",
                CheckedFormCopy.outcomeStyle(row));

        List<String> options = List.of(row.answer1(), row.answer2(), row.answer3(), row.answer4());
        int chosen = CheckedFormCopy.chosenOption(row);
        for (int i = 0; i < options.size(); i++) {
            card.getChildren().add(optionRow(i + 1, options.get(i), chosen, row.correct()));
        }
        return card;
    }

    /**
     * One option, tagged when it was chosen and when it was right.
     *
     * <p>Both tags are shown even when they are the same option: "your answer" and "correct
     * answer" together is how a student sees she got it right, and dropping one of them on a
     * correct question would make the two cases render inconsistently.
     */
    private static HBox optionRow(int index, String text, int chosen, byte correct) {
        Label label = new Label(index + ") " + text);
        label.setWrapText(true);
        HBox.setHgrow(label, Priority.ALWAYS);

        HBox line = new HBox(8, label);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("checked-form-option");

        if (index == chosen) {
            line.getChildren().add(tag(CheckedFormCopy.YOUR_ANSWER, "neutral"));
            line.getStyleClass().add("chosen");
        }
        if (index == correct) {
            line.getChildren().add(tag(CheckedFormCopy.CORRECT_ANSWER, "success"));
            line.getStyleClass().add("correct");
        }
        return line;
    }

    private static Label tag(String text, String variant) {
        Label chip = new Label(text);
        chip.getStyleClass().addAll("hsts-chip", variant);
        return chip;
    }

    private void applyPrintLayout(boolean printing) {
        root.getStyleClass().remove(ResultsCopy.PRINT_STYLE_CLASS);
        if (printing) {
            root.getStyleClass().add(ResultsCopy.PRINT_STYLE_CLASS);
        }
        // The exit appears exactly when the layout that needs one is on. It is the only
        // control print mode does not take away: `.results-print` hides the toggle that
        // switched print on, which is what left this screen with no way back at all.
        show(printExit, printing);
    }

    private static HBox spacer() {
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
