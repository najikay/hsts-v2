package client.ui.components;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DataTable} fills the width it is given ⚑ (2026-08-28, manual round 1).
 *
 * <p>The wrapper set no column resize policy, so JavaFX left every column at its
 * pref width and painted whatever was left of the row as empty background. On
 * the question bank — eight columns that never passed widths at all — that was
 * more than half the row, and it is what the manual round reported. The defect
 * is invisible to every other test in the suite, because a column width is not a
 * string and the copy tests only ever read strings.
 *
 * <p>Asserted on the wrapper rather than on a screen, because the policy is the
 * wrapper's to set: nine tables get it from here, and a test per screen would be
 * nine chances to check eight of them.
 *
 * <p>Same escape hatch as the other UI tests:
 * {@code ./mvnw verify -Dhsts.uitests=false}.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class DataTableTest extends ApplicationTest {

    private record Row(String id, String text) {
    }

    @BeforeAll
    static void headless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("glass.platform", "Monocle");
        System.setProperty("monocle.platform", "Headless");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
    }

    @Override
    public void start(Stage stage) {
        // Nothing to show: these assertions are about the table's own settings.
    }

    /**
     * ⚑ U-61's promise, proven. setItems answered with identical rows deliberately skips
     * the clear-then-add (which yanks selection and realised cells), and refreshes instead;
     * the refresh must still repaint a column that draws from state OUTSIDE the row object,
     * or a monitor or bank list whose derived facts moved would quietly stop updating.
     */
    @Test
    @DisplayName("⚑ U-61: identical rows still repaint cells that draw from outside the row")
    void equalRowsRefreshDerivedCells() {
        java.util.concurrent.atomic.AtomicReference<String> external =
                new java.util.concurrent.atomic.AtomicReference<>("first");
        DataTable<Row> table = new DataTable<>();
        table.column("Id", Row::id)
                .column("Derived", row -> external.get());

        Stage[] stage = new Stage[1];
        interact(() -> {
            stage[0] = new Stage();
            stage[0].setScene(new javafx.scene.Scene(table, 420, 320));
            stage[0].show();
            table.setItems(List.of(new Row("q1", ""), new Row("q2", "")));
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(cellTexts(table)).contains("first");

        external.set("second");
        interact(() -> table.setItems(List.of(new Row("q1", ""), new Row("q2", ""))));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(cellTexts(table))
                .as("a push that changes only derived state must still repaint")
                .contains("second")
                .doesNotContain("first");
        interact(() -> stage[0].hide());
    }

    private static List<String> cellTexts(DataTable<?> table) {
        return table.table().lookupAll(".table-cell").stream()
                .filter(javafx.scene.control.TableCell.class::isInstance)
                .map(cell -> ((javafx.scene.control.TableCell<?, ?>) cell).getText())
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Test
    @DisplayName("⚑ every table shares its full width out across the columns")
    void theTableFillsItsWidth() {
        DataTable<Row> table = build();

        assertThat(table.table().getColumnResizePolicy())
                .as("an unconstrained table leaves the rest of the row empty, "
                        + "which is the dead space reported on the question bank")
                .isSameAs(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        assertThat(table.table().getColumnResizePolicy())
                .isNotSameAs(TableView.UNCONSTRAINED_RESIZE_POLICY);
    }

    @Test
    @DisplayName("columnWidths still sets the pref widths the policy shares out in proportion")
    void columnWidthsStillSetTheProportions() {
        DataTable<Row> table = build();
        interact(() -> table.columnWidths(90, 380));

        assertThat(table.table().getColumns().get(0).getPrefWidth()).isEqualTo(90);
        assertThat(table.table().getColumns().get(1).getPrefWidth()).isEqualTo(380);
    }

    @Test
    @DisplayName("no column carries a min or max width that would fight the policy")
    void nothingBlocksTheShareOut() {
        // A column pinned to a fixed width takes its share off the top and hands
        // the squeeze to its neighbours. The wrapper sets neither, so a screen
        // that wants a narrow column asks for it through columnWidths.
        DataTable<Row> table = build();
        interact(() -> table.columnWidths(90, 380));

        assertThat(table.table().getColumns())
                .allSatisfy(column -> {
                    assertThat(column.getMinWidth()).isLessThanOrEqualTo(column.getPrefWidth());
                    assertThat(column.getMaxWidth()).isGreaterThanOrEqualTo(column.getPrefWidth());
                });
    }

    @Test
    @DisplayName("⚑ S3: setItems with identical rows does not replay the entrance fade")
    void identicalRowsDoNotReplayTheFade() {
        DataTable<Row> table = build();
        // Let the first-content fade finish (ROUTE_MS, bounded).
        sleep(400);
        WaitForAsyncUtils.waitForFxEvents();

        double[] opacity = new double[1];
        interact(() -> {
            // The U-61 refresh path: every push re-read lands here with the same rows.
            table.setItems(List.of(new Row("Q-1", "What is a monad?")));
            opacity[0] = table.table().getOpacity();
        });

        assertThat(opacity[0])
                .as("fadeIn drops the node to opacity 0 before animating, so a replayed "
                        + "fade blinks the whole table on a re-read that changed nothing")
                .isEqualTo(1.0);
    }

    // ===================== The column chooser (U-36) =====================

    @Test
    @DisplayName("⚑ U-36: a hidden column is hidden, not gone")
    void hideColumnsHidesByTitle() {
        DataTable<Row> table = build();
        interact(() -> table.hideColumns("Question"));

        assertThat(table.table().getColumns().get(0).isVisible())
                .as("a column nobody hid")
                .isTrue();
        assertThat(table.table().getColumns().get(1).isVisible()).isFalse();
        assertThat(table.table().getColumns())
                .as("hidden, so the chooser can put it back; dropping it would take the data, "
                        + "the sort order and the tick box with it")
                .hasSize(2);
    }

    @Test
    @DisplayName("a title no column has is ignored rather than thrown")
    void hidingAColumnThatIsNotThereIsSafe() {
        DataTable<Row> table = build();
        interact(() -> table.hideColumns("Difficulty"));

        assertThat(table.table().getColumns()).allMatch(column -> column.isVisible());
    }

    @Test
    @DisplayName("⚑ U-36: the chooser offers every column, ticked to match what is shown")
    void theChooserListsTheColumns() {
        DataTable<Row> table = build();
        interact(() -> table.hideColumns("Question").columnChooser());

        List<MenuItem> items = chooserItems(table);
        assertThat(items).extracting(MenuItem::getText).containsExactly("Id", "Question");
        assertThat(items).allMatch(CheckMenuItem.class::isInstance);
        assertThat(((CheckMenuItem) items.get(0)).isSelected())
                .as("a shown column opens the chooser already ticked")
                .isTrue();
        assertThat(((CheckMenuItem) items.get(1)).isSelected())
                .as("and a column hidden before the chooser was built opens clear")
                .isFalse();
    }

    @Test
    @DisplayName("ticking a box in the chooser brings the column back")
    void tickingTheBoxShowsTheColumn() {
        DataTable<Row> table = build();
        interact(() -> table.hideColumns("Question").columnChooser());

        interact(() -> ((CheckMenuItem) chooserItems(table).get(1)).setSelected(true));

        assertThat(table.table().getColumns().get(1).isVisible()).isTrue();
    }

    @Test
    @DisplayName("the chooser is asked for once, however many times a screen asks")
    void theChooserIsMountedOnce() {
        DataTable<Row> table = build();
        interact(() -> table.columnChooser().columnChooser());

        assertThat(table.getChildren().get(0).lookupAll(".table-column-chooser")).hasSize(1);
    }

    /** @return the chooser's menu items, read off the button the toolbar mounts. */
    private List<MenuItem> chooserItems(DataTable<Row> table) {
        Node button = table.getChildren().get(0).lookup(".table-column-chooser");
        assertThat(button).as("the chooser sits on the toolbar strip").isInstanceOf(Button.class);
        ContextMenu menu = ((Button) button).getContextMenu();
        assertThat(menu).as("the chooser's own menu").isNotNull();
        return menu.getItems();
    }

    /**
     * Built on the FX thread: the wrapper starts a fade the moment it has rows,
     * and an animation played from anywhere else is an exception rather than an
     * assertion failure.
     */
    private DataTable<Row> build() {
        @SuppressWarnings("unchecked")
        DataTable<Row>[] built = new DataTable[1];
        interact(() -> {
            DataTable<Row> table = new DataTable<>();
            table.column("Id", Row::id);
            table.column("Question", Row::text);
            table.setItems(List.of(new Row("Q-1", "What is a monad?")));
            built[0] = table;
        });
        WaitForAsyncUtils.waitForFxEvents();
        return built[0];
    }
}
