package client.ui.components;

import client.ui.anim.Animations;
import client.ui.anim.Motion;
import client.ui.components.logic.AsyncViewState;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
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
 * <h2>The wave-2 treatment</h2>
 *
 * <p>Everything the remodel asked of "tables, globally" is here rather than
 * repeated on nine screens: kicker column headings, right-aligned numeric
 * columns, a full-row hover tint that reveals an "Open" affordance where the row
 * opens something, a selected row with an accent bar down its left edge, and a
 * first-load row stagger that never replays on a refresh or a resort. A screen
 * gets all of it by using this class, which is the only way a treatment applied
 * to nine tables stays applied to nine tables.
 *
 * <p>The width policy is part of that list: the table always fills the width it
 * is given, sharing it out in the ratio of {@link #columnWidths(double...)}.
 *
 * @param <T> the row type
 */
public final class DataTable<T> extends VBox {

    /** The affordance revealed at the end of a hovered row that opens something. */
    public static final String OPEN_AFFORDANCE = "Open →";

    /** How far the affordance sits from the wrapper's right edge, clear of a scrollbar. */
    private static final double AFFORDANCE_INSET = 20;

    private final TableView<T> table = new TableView<>();
    private final ObservableList<T> items = FXCollections.observableArrayList();
    private final FilteredList<T> filtered = new FilteredList<>(items, item -> true);
    private final StackPane body = new StackPane();
    private final HBox toolbar = new HBox(8);
    private final Label countLabel = new Label();
    private final Label affordance = new Label(OPEN_AFFORDANCE);

    private Node skeleton = Skeletons.list(6);
    private Node emptyState = EmptyState.noResults();
    private Node errorState;
    private TextField search;
    private BiPredicate<T, String> searchMatcher;
    private AsyncViewState state = AsyncViewState.IDLE;
    private Consumer<T> openAction;
    private Consumer<T> selectAction;

    /**
     * Whether the next batch of rows is the table's first.
     *
     * <p>The whole of the "first load only" rule, and it is a field rather than
     * a parameter because only this class knows which {@code setItems} is which.
     * Rows that re-animate every time a column header is clicked are the defect
     * the rule exists to prevent, and a screen cannot be relied on to remember
     * that its resort is not an arrival.
     */
    private boolean firstLoadPending = true;

    /** True only during the layout pass that follows the first load. */
    private boolean staggering;

    public DataTable() {
        getStyleClass().add("hsts-table-wrapper");
        setSpacing(0);

        SortedList<T> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.getStyleClass().add("hsts-table");
        // 2026-08-28, manual round 1: without a resize policy the columns sit at
        // their pref widths and the rest of every row is empty, which is the dead
        // space testers reported on the question bank. This policy hands the
        // table's whole width out in proportion to those pref widths, so
        // columnWidths still decides the shape and nothing is left over.
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        // The wrapper owns the empty state; TableView's own placeholder must not
        // flash "No content in table" underneath it.
        table.setPlaceholder(new Label(""));
        table.setRowFactory(view -> newRow());
        VBox.setVgrow(body, Priority.ALWAYS);

        toolbar.getStyleClass().add("hsts-table-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        countLabel.getStyleClass().add("table-count");

        affordance.getStyleClass().add("row-open-affordance");
        affordance.setVisible(false);
        affordance.setManaged(false);
        // The pointer must reach the row underneath: the affordance is a hint
        // about what a click will do, not a second control competing for it.
        affordance.setMouseTransparent(true);

        body.getChildren().addAll(table, skeleton, emptyState, affordance);
        getChildren().addAll(toolbar, body);
        setState(AsyncViewState.IDLE);
    }

    /** @return the underlying table, for column setup and selection listeners. */
    public TableView<T> table() {
        return table;
    }

    /** Adds a simple text column reading a property off each row. */
    public DataTable<T> column(String title, Function<T, String> reader) {
        TableColumn<T, String> column = new TableColumn<>();
        heading(column, title);
        column.setCellValueFactory(cell -> new SimpleStringProperty(reader.apply(cell.getValue())));
        table.getColumns().add(column);
        return this;
    }

    /** Adds a pre-built column (for chip cells, action buttons, custom sorting). */
    public DataTable<T> column(TableColumn<T, ?> column) {
        if (column.getText() != null && !column.getText().isEmpty()) {
            heading(column, column.getText());
        }
        table.getColumns().add(column);
        return this;
    }

    /**
     * Puts the wave-2 kicker treatment on a column heading.
     *
     * <p>A <b>graphic</b> rather than the column's own text, and the text is
     * cleared. Two reasons, both about not lying: JavaFX CSS has no
     * {@code text-transform} and no {@code letter-spacing}, so the uppercase and
     * the tracking have to be baked into a string — and a tracked string read
     * aloud is spelled out letter by letter. {@link Kicker} sets the accessible
     * text to the plain words, so the column heading a screen reader announces is
     * "APPROVED" and not "A P P R O V E D".
     */
    private static void heading(TableColumn<?, ?> column, String title) {
        column.setText("");
        column.setGraphic(Kicker.label(title));
    }

    /**
     * Marks columns as numeric: right-aligned, in the tabular figures the house
     * has (UI wave 2).
     *
     * <p>"Tabular figures" is a font feature, and JavaFX exposes no
     * {@code font-feature-settings}. The house's answer is the monospace stack
     * the {@code .mono} class already uses, applied through
     * {@code .numeric-cell}: it is the only way a column of three-digit scores
     * lines up on this toolkit, and it is what the design canvas's numeric
     * columns are drawn as.
     *
     * @param indexes column positions in the order the columns were added;
     *                out-of-range values are ignored rather than thrown, so a
     *                screen that later drops a column does not crash on the next
     *                visit
     */
    public DataTable<T> numericColumns(int... indexes) {
        for (int index : indexes) {
            if (index < 0 || index >= table.getColumns().size()) {
                continue;
            }
            markNumeric(table.getColumns().get(index));
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    private void markNumeric(TableColumn<T, ?> column) {
        TableColumn<T, Object> typed = (TableColumn<T, Object>) column;
        typed.getStyleClass().add("numeric");
        if (typed.getGraphic() != null) {
            typed.getGraphic().getStyleClass().add("numeric");
        }
        var base = typed.getCellFactory();
        // Wrapped rather than replaced: a screen that gave the column a chip cell
        // or a custom renderer keeps it and simply gains the alignment.
        typed.setCellFactory(col -> {
            TableCell<T, Object> cell = base.call(col);
            cell.getStyleClass().add("numeric-cell");
            return cell;
        });
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
     * <p>2026-08-28, manual round 1: these are now <b>proportions</b> rather than
     * starting widths. The table runs
     * {@code CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS}, which shares the full table
     * width out in the ratio of the pref widths, so a table wider than their sum
     * grows every column instead of leaving a gap and a narrower one shrinks them
     * all together. The numbers a screen already passes keep meaning what they
     * meant: it is their ratio that was carrying the intent.
     *
     * <p>A table that leaves this alone gets equal columns, which is right for
     * four columns of similar content and wrong the moment one of them holds a
     * question stem. Pass widths when the columns are not alike.
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
        this.openAction = Objects.requireNonNull(open, "open");
        this.selectAction = null;
        // The row factory is installed once, in the constructor, and reads this
        // field. Wave 1 set a factory here instead, which meant a table gained
        // the open gesture and lost whatever else a factory was doing — and
        // wave 2 gives every row a first-load entrance and a hover affordance,
        // both of which a second factory would have silently replaced.
        table.refresh();
        return this;
    }

    /**
     * The same click and Enter gesture as {@link #openOnClick}, without the hover
     * hint (M-6).
     *
     * <p>The affordance is a promise: "Open →" says the click navigates somewhere.
     * A master-detail screen where the click drives the detail panel keeps the
     * gesture but must not make that promise, or the control says one thing and
     * does another. A table is given one of these, never both; a second call
     * replaces the first either way.
     *
     * @param select what to do with the row that was clicked
     */
    public DataTable<T> selectOnClick(Consumer<T> select) {
        this.selectAction = Objects.requireNonNull(select, "select");
        this.openAction = null;
        table.refresh();
        return this;
    }

    /**
     * The one row factory: the open gesture, the hover affordance and the
     * first-load entrance (UI wave 1 F-8, UI wave 2).
     */
    private TableRow<T> newRow() {
        TableRow<T> row = new TableRow<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                boolean arriving = staggering && !empty && item != null && getItem() == null;
                super.updateItem(item, empty);
                if (arriving) {
                    Animations.staggerRows(List.of(this));
                }
            }
        };
        row.setOnMouseClicked(event -> {
            Consumer<T> action = rowAction();
            if (action != null && event.getButton() == MouseButton.PRIMARY
                    && event.getClickCount() == 1 && !row.isEmpty()) {
                action.accept(row.getItem());
            }
        });
        row.setOnKeyPressed(event -> {
            Consumer<T> action = rowAction();
            if (action != null && event.getCode() == KeyCode.ENTER && !row.isEmpty()) {
                action.accept(row.getItem());
            }
        });
        // The hover hint stays tied to openAction alone: a select gesture makes no
        // navigation promise, so it must not advertise one (M-6).
        row.hoverProperty().addListener((obs, was, hovered) -> {
            if (hovered && openAction != null && !row.isEmpty()) {
                showAffordance(row);
            } else {
                hideAffordance();
            }
        });
        return row;
    }

    /** The gesture a row click or Enter fires; the two setters keep it single-valued. */
    private Consumer<T> rowAction() {
        return openAction != null ? openAction : selectAction;
    }

    /**
     * Puts the "Open" hint at the end of the hovered row.
     *
     * <p>An overlay in the wrapper's own {@code StackPane} rather than an extra
     * column or an extra child of the row. A column would shift every screen's
     * {@code columnWidths} by one and appear in the sort order; a child of a
     * {@code TableRow} is at the mercy of {@code TableRowSkin}, which sets its
     * children from the cells and would drop it. The overlay is a node this
     * class owns outright.
     */
    private void showAffordance(TableRow<T> row) {
        Bounds rowBounds = row.localToScene(row.getLayoutBounds());
        Bounds hostBounds = body.localToScene(body.getLayoutBounds());
        if (rowBounds == null || hostBounds == null) {
            return;
        }
        affordance.setVisible(true);
        affordance.applyCss();
        affordance.autosize();
        double top = rowBounds.getMinY() - hostBounds.getMinY();
        affordance.setLayoutY(top + (rowBounds.getHeight() - affordance.getHeight()) / 2);
        affordance.setLayoutX(body.getWidth() - affordance.getWidth() - AFFORDANCE_INSET);
        Animations.fadeIn(affordance, Motion.ROW_HOVER_MS);
    }

    private void hideAffordance() {
        Animations.stop(affordance);
        affordance.setVisible(false);
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
        boolean firstContent = firstLoadPending && !rows.isEmpty();
        if (firstContent) {
            firstLoadPending = false;
            staggering = true;
            // Cleared after the layout pass that this setAll provokes, so only
            // the rows realised for the first load animate. A refresh, a resort
            // and a filter all arrive with this already false.
            Platform.runLater(() -> staggering = false);
        }
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
            // The table itself still fades; its rows carry the first-load
            // stagger separately, and only once.
            Animations.fadeIn(table, Motion.ROUTE_MS);
        }
        if (!next.showsContent()) {
            hideAffordance();
        }
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
