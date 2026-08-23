package client.features.results;

import client.core.NavParams;
import client.core.Routes;
import client.ui.components.DataTable;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.screen.AbstractScreen;
import common.dto.grading.StudentGradeRow;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;

/**
 * The student's <b>My Grades</b> screen (Presentation tier, E13.3 — F9.1, T-9.1).
 *
 * <p>A renderer over {@link MyGradesSession} and nothing else. Every decision this screen
 * appears to make is made elsewhere and measured there: what state the list is in comes from
 * the session, and every string and formatted value comes from {@link MyGradesCopy}. That is
 * why this class is on the coverage exclusion list by name, on the same terms as every other
 * view in the product.
 *
 * <h2>No refresh control, anywhere</h2>
 *
 * <p>The list loads when the screen opens and re-reads itself when a grade is published
 * (NFR-18, E13.6). A refresh button here would be an admission that the push cannot be trusted,
 * and a student pressing it would be doing the application's job.
 *
 * <h2>What a student is shown, and what she is not</h2>
 *
 * <p>Exam, course, grade, approval date and the teacher's note. An adjusted grade carries a
 * marker saying a teacher reviewed it — and never the justification, which the wire strips
 * structurally before it reaches this tier (S-23). The distinction is the whole reason
 * {@link MyGradesCopy} is a separate file from {@link ResultsCopy}: the same row, two
 * audiences, two vocabularies.
 */
public final class MyGradesView extends AbstractScreen {

    /** Local zone for the approval dates; the wire is UTC (ADR-010). */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final VBox root = new VBox(16);
    private final DataTable<StudentGradeRow> table = new DataTable<>();
    private final Label error = new Label();

    private MyGradesSession session;

    @Override
    protected Parent build() {
        session = new MyGradesSession(dispatcher(), onFxThread())
                .onChange(this::render)
                .subscribeTo(eventBus());

        root.getStyleClass().add(MyGradesCopy.STYLE_CLASS);
        root.setPadding(new Insets(24));
        root.getChildren().addAll(buildHeader(), buildError(), buildTable());
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        session.load();
    }

    @Override
    public boolean listensToEvents() {
        // The session subscribes itself in build(), so the live refresh is wired where it can
        // be tested. This screen has no @Subscribe method of its own.
        return false;
    }

    // ===================== Layout ========================================

    private VBox buildHeader() {
        Label title = new Label(MyGradesCopy.TITLE);
        title.getStyleClass().add("h1");

        Label subtitle = new Label(MyGradesCopy.SUBTITLE);
        subtitle.getStyleClass().addAll("small", "muted");
        subtitle.setWrapText(true);

        VBox header = new VBox(4, title, subtitle);
        header.getStyleClass().add("my-grades-header");
        return header;
    }

    private Label buildError() {
        error.getStyleClass().addAll("small", "danger-text");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);
        return error;
    }

    private DataTable<StudentGradeRow> buildTable() {
        table.column(MyGradesCopy.COLUMN_EXAM, MyGradesCopy::examName)
                .column(MyGradesCopy.COLUMN_COURSE, MyGradesCopy::courseCode)
                .column(MyGradesCopy.COLUMN_SCORE, MyGradesCopy::score)
                .column(MyGradesCopy.COLUMN_APPROVED, row -> MyGradesCopy.approvedOn(row, ZONE))
                .column(MyGradesCopy.COLUMN_COMMENT, MyGradesCopy::comment)
                // Unheaded, like the teacher table's: the marker is about the row, not a
                // property of it, and a column heading would imply every row has a value.
                .column("", MyGradesCopy::adjustedMarker);

        // Double-click opens the marked paper (E13.4). The server re-checks all three of its
        // conditions, so a row that opens nothing is a refusal and not a broken link: the
        // checked form says so itself rather than this table trying to predict it.
        table.table().setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                StudentGradeRow row = table.table().getSelectionModel().getSelectedItem();
                if (row != null) {
                    navigator().navigate(Routes.CHECKED_FORM.id(),
                            NavParams.of("gradeId", row.gradeId()));
                }
            }
        });

        // Widths by content, not evenly. Left to itself the table divided the width equally and
        // the two-character Course column got the same share as a date, which truncated
        // "23 Aug 2026" to "23 Au…" — a date column that cannot show a date. Found on a real
        // screen; the copy tests format the string correctly and never see the column.
        sizeColumns(220, 90, 110, 150, 260, 170);

        table.emptyState(new EmptyState(Icons.RESULTS, "No grades yet", MyGradesSession.NOTHING_YET));
        table.getStyleClass().add("my-grades-table");
        VBox.setVgrow(table, Priority.ALWAYS);
        return table;
    }

    /**
     * Gives each column a width suited to what it holds.
     *
     * <p>Preferred widths rather than fixed ones, so the table still adapts to the window; the
     * point is only that a date is not allotted the same room as a two-character course code.
     *
     * @param widths one per column, in the order the columns were added
     */
    private void sizeColumns(double... widths) {
        var columns = table.table().getColumns();
        for (int i = 0; i < columns.size() && i < widths.length; i++) {
            columns.get(i).setPrefWidth(widths[i]);
        }
    }

    // ===================== Rendering =====================================

    private void render() {
        String message = session.error().orElse("");
        error.setText(message);
        error.setVisible(!message.isEmpty());
        error.setManaged(!message.isEmpty());

        switch (session.state()) {
            case LOADING -> table.showLoading();
            case ERROR -> table.showError();
            default -> table.setItems(session.grades());
        }
    }
}
