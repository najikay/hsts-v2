package client.ui.components;

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
