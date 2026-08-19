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
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
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
