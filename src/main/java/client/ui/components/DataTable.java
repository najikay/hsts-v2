package client.ui.components;

import client.ui.anim.Animations;
import client.ui.components.logic.AsyncViewState;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The app's list surface: a {@link TableView} plus the states around it
 * (Presentation tier, E4.11).
 *
 * <p>Raw {@code TableView} gives sorting and nothing else; every screen then
 * reinvents "what if it is loading", "what if it is empty" and "what if it
 * failed", and one of them forgets. This wrapper makes those states part of the
 * component: it swaps between a skeleton, the table, an empty state and an error
 * state driven by a single {@link AsyncViewState} — the exhaustive enum being
 * exactly why nothing can be forgotten.
 *
 * <p>Sorting and filtering ride on JavaFX's own {@link SortedList}/
 * {@link FilteredList} over the backing items, so a sort survives a data refresh
 * and a filter does not mutate the source list.
 *
 * @param <T> the row type
 */
public final class DataTable<T> extends VBox {

    private final TableView<T> table = new TableView<>();
    private final ObservableList<T> items = FXCollections.observableArrayList();
    private final FilteredList<T> filtered = new FilteredList<>(items, item -> true);
    private final StackPane body = new StackPane();
    private final HBox toolbar = new HBox(8);
    private final Label countLabel = new Label();

    private Node skeleton = Skeletons.list(6);
    private Node emptyState = EmptyState.noResults();
    private Node errorState;
    private TextField search;
    private BiPredicate<T, String> searchMatcher;
    private AsyncViewState state = AsyncViewState.IDLE;

    public DataTable() {
        getStyleClass().add("hsts-table-wrapper");
        setSpacing(0);

        SortedList<T> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.getStyleClass().add("hsts-table");
        // The wrapper owns the empty state; TableView's own placeholder must not
        // flash "No content in table" underneath it.
        table.setPlaceholder(new Label(""));
        VBox.setVgrow(body, Priority.ALWAYS);

        toolbar.getStyleClass().add("hsts-table-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        countLabel.getStyleClass().add("table-count");

        body.getChildren().addAll(table, skeleton, emptyState);
        getChildren().addAll(toolbar, body);
        setState(AsyncViewState.IDLE);
    }

    /** @return the underlying table, for column setup and selection listeners. */
    public TableView<T> table() {
        return table;
    }

    /** Adds a simple text column reading a property off each row. */
    public DataTable<T> column(String title, Function<T, String> reader) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new SimpleStringProperty(reader.apply(cell.getValue())));
        table.getColumns().add(column);
        return this;
    }

    /** Adds a pre-built column (for chip cells, action buttons, custom sorting). */
    public DataTable<T> column(TableColumn<T, ?> column) {
        table.getColumns().add(column);
        return this;
    }

    /**
     * Gives each column a preferred width suited to what it holds (UI wave 1 —
     * F-9, the B-5 treatment made general).
     *
     * <p>Left alone, {@code TableView} divides its width evenly, so a
     * two-character course code is allotted exactly as much room as "23 Aug
     * 2026" and the date is the one that loses: it renders as "23 Au…". That is
     * B-5, found on My Grades, and it was never a My Grades bug — every table in
     * the app divides its width the same way. The copy tests never see it,
     * because they check the string and a column is not a string.
     *
     * <p><b>Preferred</b>, not fixed: the table still stretches and the user can
     * still drag a divider. All this does is set the starting proportions from
     * what the column actually contains, so nothing is truncated at the default
     * window size.
     *
     * @param widths one per column, in the order the columns were added; extra
     *               values are ignored, missing ones leave that column alone
     */
    public DataTable<T> columnWidths(double... widths) {
        List<TableColumn<T, ?>> columns = table.getColumns();
        for (int i = 0; i < columns.size() && i < widths.length; i++) {
            columns.get(i).setPrefWidth(widths[i]);
        }
        return this;
    }

    /**
     * Opens a row on a single primary click, and on Enter (UI wave 1 — F-8).
     *
     * <p>Double click was the house pattern and it was the wrong one. A queue of
     * exams is a list of links, not a file manager: the row has one obvious
     * thing it does, there is no second action competing for the gesture, and a
     * user who single-clicks and gets nothing concludes the row is not
     * clickable. Selection still happens — it is what the click does first — so
     * a screen that highlights the selected row keeps doing so.
     *
     * <p>Enter is wired for the same reason it always was: a coordinator working
     * through six submissions should not have to reach for the trackpad.
     *
     * @param open what to do with the row that was clicked
     */
    public DataTable<T> openOnClick(Consumer<T> open) {
        Objects.requireNonNull(open, "open");
        table.setRowFactory(view -> {
            TableRow<T> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY
                        && event.getClickCount() == 1 && !row.isEmpty()) {
                    open.accept(row.getItem());
                }
            });
            row.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ENTER && !row.isEmpty()) {
                    open.accept(row.getItem());
                }
            });
            return row;
        });
        return this;
    }

    /** Puts a title on the toolbar strip above the table. */
    public DataTable<T> title(String text) {
        Label title = new Label(text);
        title.getStyleClass().add("table-title");
        toolbar.getChildren().addAll(title, countLabel, Buttons.spacer());
        return this;
    }

    /**
     * Adds a search box filtering rows live.
     *
     * @param matcher decides whether a row matches the (lowercased, trimmed) query
     */
    public DataTable<T> searchable(String prompt, BiPredicate<T, String> matcher) {
        this.searchMatcher = Objects.requireNonNull(matcher, "matcher");
        search = new TextField();
        search.setPromptText(prompt);
        search.setPrefWidth(240);
        search.textProperty().addListener((obs, old, query) -> applyFilter(query));
        if (toolbar.getChildren().isEmpty()) {
            toolbar.getChildren().add(Buttons.spacer());
        }
        toolbar.getChildren().add(search);
        return this;
    }

    /** Replaces the default "no matches" panel. */
    public DataTable<T> emptyState(Node node) {
        Objects.requireNonNull(node, "node");
        body.getChildren().remove(emptyState);
        emptyState = node;
        body.getChildren().add(emptyState);
        setState(state);
        return this;
    }

    /** Replaces the default skeleton with a shape matching this table's rows. */
    public DataTable<T> skeleton(Node node) {
        Objects.requireNonNull(node, "node");
        body.getChildren().remove(skeleton);
        skeleton = node;
        body.getChildren().add(skeleton);
        setState(state);
        return this;
    }

    /** Registers the error panel and its retry action. */
    public DataTable<T> onRetry(String hint, Runnable retry) {
        if (errorState != null) {
            body.getChildren().remove(errorState);
        }
        errorState = EmptyState.error(hint, retry);
        body.getChildren().add(errorState);
        setState(state);
        return this;
    }

    /** Switches to the loading skeleton. */
    public void showLoading() {
        setState(AsyncViewState.LOADING);
    }

    /** Switches to the error panel. */
    public void showError() {
        setState(AsyncViewState.ERROR);
    }

    /**
     * Replaces the rows and picks the right state automatically — empty results
     * land on the empty state rather than on a blank table.
     */
    public void setItems(List<T> rows) {
        Objects.requireNonNull(rows, "rows");
        items.setAll(rows);
        countLabel.setText(rows.isEmpty() ? "" : rows.size() + " items");
        setState(AsyncViewState.forResult(rows));
    }

    /** @return the current display state. */
    public AsyncViewState state() {
        return state;
    }

    /** @return the rows currently passing the filter. */
    public List<T> visibleItems() {
        return List.copyOf(filtered);
    }

    private void applyFilter(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        filtered.setPredicate(needle.isEmpty() ? item -> true : item -> searchMatcher.test(item, needle));
        // A filter emptying the table is a different empty state from no data at
        // all, but both must show one — never a blank rectangle.
        if (state == AsyncViewState.READY || state == AsyncViewState.EMPTY) {
            setState(AsyncViewState.forResultSize(filtered.size()));
        }
    }

    private void setState(AsyncViewState next) {
        this.state = next;
        setShown(table, next.showsContent());
        setShown(skeleton, next.showsSkeleton());
        setShown(emptyState, next.showsEmptyState());
        if (errorState != null) {
            setShown(errorState, next.showsError());
        }
        if (!next.showsSkeleton()) {
            Skeletons.stopShimmer(skeleton);
        }
        if (next.showsContent()) {
            Animations.fadeIn(table);
        }
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
