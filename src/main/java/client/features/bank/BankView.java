package client.features.bank;

import client.core.NavParams;
import client.core.ScreenManager;
import client.ui.components.Buttons;
import client.ui.components.DataTable;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.components.StatusChip;
import client.ui.components.WarnConfirm;
import client.ui.screen.AbstractScreen;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.bank.BankQuestionRow;
import common.dto.bank.Difficulty;
import common.dto.bank.QuestionDetail;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The question bank (Presentation tier, E6.9 / E6.12 / E6.13 — F2.4, F2.5, T-2).
 *
 * <p>A renderer over {@link BankSession}. Filters across the top, the master list on the left,
 * the detail pane on the right, and the version history unfolding under the detail when it is
 * asked for.
 *
 * <p>Thin, on the {@code DataView} pattern: every decision — what the filters hide, which page
 * is showing, whether a late answer still applies, what a delete came back with, what each
 * sentence says — is made in {@link BankSession} and {@link BankCopy}, both of which are
 * measured and tested. This class owns nodes and nothing else, which is why it is on the
 * coverage exclusion list by name.
 *
 * <h2>The two ways into the editor, and the one that can be closed</h2>
 *
 * <p><b>Edit question</b> is disabled until an illustrated question's bytes have arrived, because
 * {@link QuestionEditorSession#forEdit} takes them as a required argument. This button is the
 * only route from a question into the editor, so the components report's remaining screen-level
 * trap is closed here rather than remembered: there is no state from which the editor can be
 * opened before its picture exists.
 *
 * <p><b>New question</b> needs a course, because {@code QUESTION_CREATE} carries one and the
 * server refuses a course she does not teach. It uses the course filter rather than guessing, and
 * says so when nothing is filtered.
 *
 * <h2>The topic picker is absent until it can be populated</h2>
 *
 * <p>The contract's ruling 7.6 made the topic filter an exact-equality lookup fed by a picker,
 * and the lookup verb does not exist yet. So this screen shows no topic picker rather than an
 * empty one or, worse, a text box whose exact match misses on any spacing difference. The
 * single place that changes is {@link BankSession#availableTopics()}.
 */
public final class BankView extends AbstractScreen {

    /** How wide the detail column is. The list takes everything else. */
    private static final double DETAIL_WIDTH = 420;

    private final VBox root = new VBox(14);
    private final DataTable<BankQuestionRow> list = new DataTable<>();

    private final TextField search = new TextField();
    private final ComboBox<CourseOption> coursePicker = new ComboBox<>();
    private final ComboBox<DifficultyOption> difficultyPicker = new ComboBox<>();
    private final ComboBox<String> topicPicker = new ComboBox<>();
    private final Button clearFilters = Buttons.styled(BankCopy.CLEAR_FILTERS, Buttons.GHOST);

    private final Label error = new Label();
    private final Label count = new Label();
    private final Label pageLine = new Label();
    private final Button previousPage = Buttons.outline("Previous");
    private final Button nextPage = Buttons.outline("Next");

    private final VBox detailBody = new VBox(12);
    private final EmptyState detailEmpty = new EmptyState(Icons.BANK,
            BankCopy.NOTHING_SELECTED.title(), BankCopy.NOTHING_SELECTED.hint());
    private final VBox historyBody = new VBox(10);
    private final Button historyToggle = Buttons.outline(BankCopy.HISTORY_OPEN);
    private final Button delete = Buttons.danger(BankCopy.DELETE);
    private final Button retry = Buttons.outline(BankCopy.RETRY);
    private final Button edit = Buttons.secondary(BankCopy.EDIT);
    private final Button newQuestion = Buttons.primary(QuestionEditorCopy.NEW_QUESTION);

    private BankSession session;

    /** Guards the render loop against a dialog that would re-enter it. */
    private boolean showingDialog;

    /** Guards the pickers against firing their listeners while being re-selected. */
    private boolean selecting;

    @Override
    protected Parent build() {
        session = new BankSession(dispatcher(), onFxThread(), coursesOfSignedInUser(),
                eventBus(), signedInUserId())
                .onChange(this::render);

        root.getStyleClass().addAll("hsts-page", "bank-screen");
        root.setPadding(new Insets(24, 28, 24, 24));

        Label title = new Label(BankCopy.TITLE);
        title.getStyleClass().add("h1");
        Label subtitle = new Label(BankCopy.SUBTITLE);
        subtitle.getStyleClass().addAll("small", "muted");
        subtitle.setWrapText(true);

        error.getStyleClass().addAll("small", "danger-text");
        error.setWrapText(true);
        count.getStyleClass().addAll("small", "muted", "bank-count");
        pageLine.getStyleClass().addAll("small", "muted", "bank-page-line");

        buildList();
        buildDetail();

        HBox body = new HBox(18, buildListColumn(), buildDetailColumn());
        body.getStyleClass().add("bank-body");
        VBox.setVgrow(body, Priority.ALWAYS);

        root.getChildren().addAll(new VBox(2, title, subtitle), buildFilterRow(), error, body);
        return root;
    }

    @Override
    public void onShow(NavParams params) {
        session.load();
    }

    /**
     * Stops listening for lock pushes on the way out (E6.14).
     *
     * <p>It withdraws no watch, and that is deliberate: the only verb that could withdraw one
     * also releases a held lock, and the screen this navigates to is usually the editor holding
     * the very question the list was watching. {@code BankRowLocks} carries the full reasoning.
     */
    @Override
    public void onHide() {
        session.stop();
    }

    // ===================== Building =======================================

    private void buildList() {
        list.column("Id", BankCopy::questionId);
        list.column("Question", BankQuestionRow::text);
        list.column("Topic", row -> BankCopy.topic(row.topic()));
        list.column("Difficulty", row -> BankCopy.difficulty(row.difficulty()));
        list.column("Course", BankQuestionRow::courseName);
        list.column("Version", row -> "v" + row.latestVersionNo());
        list.column("Written", row -> BankCopy.rowDate(row.lastVersionAt()));
        // E6.14. Reads from the session per render rather than from the row, because the row is
        // a wire DTO of what the question IS and this is who happens to be holding it: putting
        // it on BankQuestionRow would have made a live fact arrive on a paginated snapshot.
        list.column(BankCopy.EDITING_COLUMN, row -> row == null ? ""
                : session.editorOf(row.displayId5())
                        .map(holder -> BankCopy.editing(holder, session.isSelf(holder)))
                        .orElse(""));
        // 2026-08-28, manual round 1. The bank was the screen the dead space was
        // reported on, and it is the one table that never passed widths: eight
        // columns at the default meant an even split, which gives a question stem
        // the same room as "v3". Now that the table shares its width out in the
        // ratio of these numbers, the stem gets the room and the id and the
        // version get almost none.
        list.columnWidths(80, 340, 150, 120, 160, 80, 150, 160);
        list.getStyleClass().add("bank-list");
        list.emptyState(new EmptyState(Icons.BANK, BankCopy.NO_QUESTIONS.title(),
                BankCopy.NO_QUESTIONS.hint()));

        list.table().getSelectionModel().selectedItemProperty()
                .addListener((observable, old, row) -> {
                    if (!selecting) {
                        session.select(row == null ? null : row.displayId5());
                    }
                });
    }

    private Node buildListColumn() {
        previousPage.setOnAction(event -> session.previousPage());
        nextPage.setOnAction(event -> session.nextPage());

        HBox pager = new HBox(10, previousPage, nextPage, Buttons.spacer(), pageLine);
        pager.setAlignment(Pos.CENTER_LEFT);
        pager.getStyleClass().add("bank-pager");

        VBox column = new VBox(10, list, count, pager);
        column.getStyleClass().add("bank-list-column");
        HBox.setHgrow(column, Priority.ALWAYS);
        VBox.setVgrow(list, Priority.ALWAYS);
        return column;
    }

    private Node buildFilterRow() {
        search.setPromptText(BankCopy.SEARCH_PROMPT);
        search.setPrefWidth(280);
        search.getStyleClass().add("bank-search");
        // On submit, not on every keystroke: every filter here travels to the server, and a
        // round trip per character would be a request storm on a paginated list.
        search.setOnAction(event -> session.setSearch(search.getText()));
        search.focusedProperty().addListener((observable, was, focused) -> {
            if (!focused) {
                session.setSearch(search.getText());
            }
        });

        coursePicker.setCellFactory(view -> new CourseCell());
        coursePicker.setButtonCell(new CourseCell());
        coursePicker.setPrefWidth(200);
        coursePicker.getStyleClass().add("bank-course-picker");
        coursePicker.getSelectionModel().selectedItemProperty()
                .addListener((observable, old, option) -> {
                    if (!selecting) {
                        session.selectCourse(option == null ? null : option.code());
                    }
                });

        difficultyPicker.setCellFactory(view -> new DifficultyCell());
        difficultyPicker.setButtonCell(new DifficultyCell());
        difficultyPicker.setPrefWidth(170);
        difficultyPicker.getStyleClass().add("bank-difficulty-picker");
        difficultyPicker.getSelectionModel().selectedItemProperty()
                .addListener((observable, old, option) -> {
                    if (!selecting) {
                        session.selectDifficulty(option == null ? null : option.difficulty());
                    }
                });

        topicPicker.setPrefWidth(190);
        topicPicker.getStyleClass().add("bank-topic-picker");
        topicPicker.getSelectionModel().selectedItemProperty()
                .addListener((observable, old, topic) -> {
                    if (!selecting) {
                        session.selectTopic(BankCopy.ALL_TOPICS.equals(topic) ? null : topic);
                    }
                });
        topicPicker.setVisible(false);
        topicPicker.setManaged(false);

        clearFilters.setOnAction(event -> session.clearFilters());
        newQuestion.setOnAction(event -> openEditorForNewQuestion());

        List<DifficultyOption> difficultyOptions = new ArrayList<>();
        difficultyOptions.add(new DifficultyOption(null));
        for (Difficulty difficulty : Difficulty.values()) {
            difficultyOptions.add(new DifficultyOption(difficulty));
        }
        difficultyPicker.getItems().setAll(difficultyOptions);

        HBox row = new HBox(12, search, coursePicker, difficultyPicker, topicPicker,
                Buttons.spacer(), clearFilters, newQuestion);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("bank-filter-row");
        return row;
    }

    private void buildDetail() {
        historyToggle.setOnAction(event -> session.toggleHistory());
        retry.setOnAction(event -> session.retrySelected());
        edit.setOnAction(event -> openEditorForSelected());
        retry.getStyleClass().add("bank-retry");
        retry.setVisible(false);
        retry.setManaged(false);
        delete.setOnAction(event -> confirmDelete());
        detailBody.getStyleClass().add("bank-detail-body");
        historyBody.getStyleClass().add("bank-history");
    }

    private Node buildDetailColumn() {
        VBox pane = new VBox(14, detailEmpty, retry, detailBody, historyBody);
        pane.getStyleClass().addAll("hsts-card", "bank-detail");
        pane.setPadding(new Insets(18));
        pane.setPrefWidth(DETAIL_WIDTH);
        pane.setMinWidth(DETAIL_WIDTH);

        ScrollPane scroll = new ScrollPane(pane);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("bank-detail-scroll");
        scroll.setPrefWidth(DETAIL_WIDTH);
        scroll.setMinWidth(DETAIL_WIDTH);
        return scroll;
    }

    // ===================== Rendering ======================================

    private void render() {
        renderList();
        renderDetail();
        renderDialogs();
    }

    private void renderList() {
        selecting = true;
        try {
            switch (session.state()) {
                case IDLE, LOADING -> list.showLoading();
                case ERROR -> list.showError();
                default -> list.setItems(session.rows());
            }
            session.emptyPanel().ifPresent(panel ->
                    list.emptyState(new EmptyState(Icons.BANK, panel.title(), panel.hint())));

            String message = session.error();
            error.setText(message == null ? "" : message);
            error.setVisible(message != null);
            error.setManaged(message != null);

            count.setText(BankCopy.countLine(session.rows().size(), session.totalRows(),
                    session.isFiltered()));
            pageLine.setText(BankCopy.pageLine(session.page(), session.totalPages()));
            previousPage.setDisable(!session.hasPreviousPage());
            nextPage.setDisable(!session.hasNextPage());
            clearFilters.setDisable(!session.isFiltered());

            // Rebuilt on every render rather than once at build time: the options are the
            // caller's own courses UNION the ones the bank has shown her, and the second half
            // only exists after a page has arrived. See BankSession.courseOptions for the two
            // roles this is the difference between a working picker and an empty one.
            List<CourseOption> courseOptions = new ArrayList<>();
            courseOptions.add(new CourseOption(null, BankCopy.ALL_COURSES));
            for (CourseRef course : session.courseOptions()) {
                courseOptions.add(new CourseOption(course.code(), course.name()));
            }
            if (!courseOptions.equals(coursePicker.getItems())) {
                coursePicker.getItems().setAll(courseOptions);
            }
            String course = session.selectedCourse();
            coursePicker.getSelectionModel().select(courseOptions.stream()
                    .filter(option -> java.util.Objects.equals(option.code(), course))
                    .findFirst().orElse(courseOptions.get(0)));

            List<String> topics = session.availableTopics();
            boolean hasTopics = !topics.isEmpty();
            topicPicker.setVisible(hasTopics);
            topicPicker.setManaged(hasTopics);
            if (hasTopics) {
                List<String> options = new ArrayList<>();
                options.add(BankCopy.ALL_TOPICS);
                options.addAll(topics);
                topicPicker.getItems().setAll(options);
                topicPicker.getSelectionModel().select(session.selectedTopic() == null
                        ? BankCopy.ALL_TOPICS : session.selectedTopic());
            }

            String selected = session.selectedId();
            if (selected == null) {
                list.table().getSelectionModel().clearSelection();
            } else {
                for (BankQuestionRow row : session.rows()) {
                    if (row.displayId5().equals(selected)) {
                        list.table().getSelectionModel().select(row);
                        break;
                    }
                }
            }
        } finally {
            selecting = false;
        }
    }

    private void renderDetail() {
        QuestionDetail detail = session.detail();
        boolean hasDetail = detail != null;

        detailEmpty.setVisible(!hasDetail);
        detailEmpty.setManaged(!hasDetail);
        if (!hasDetail) {
            String failure = session.detailError();
            if (failure != null) {
                // Its own heading, because the row stays highlighted after a failure and
                // "No question selected" beside a selected row is the screen contradicting
                // itself. The action is the way back: clicking the same row again fires no
                // selection change, so the list cannot offer a retry.
                detailEmpty.set(BankCopy.DETAIL_FAILED_TITLE, failure);
                retry.setVisible(true);
                retry.setManaged(true);
            } else {
                detailEmpty.set(BankCopy.NOTHING_SELECTED.title(),
                        BankCopy.NOTHING_SELECTED.hint());
                retry.setVisible(false);
                retry.setManaged(false);
            }
            detailBody.getChildren().clear();
            historyBody.getChildren().clear();
            return;
        }
        retry.setVisible(false);
        retry.setManaged(false);

        detailBody.getChildren().setAll(detailNodes(detail));
        renderHistory();
    }

    private List<Node> detailNodes(QuestionDetail detail) {
        List<Node> nodes = new ArrayList<>();

        Label id = new Label("#" + detail.displayId5());
        id.getStyleClass().addAll("h2", "bank-detail-id");
        Label course = new Label(detail.courseName());
        course.getStyleClass().addAll("small", "muted");
        Label version = new Label(BankCopy.versionLine(detail));
        version.getStyleClass().addAll("small", "muted", "bank-version-line");
        nodes.add(new VBox(2, id, course, version));

        Label text = new Label(detail.text());
        text.setWrapText(true);
        text.getStyleClass().add("bank-question-text");
        nodes.add(text);

        Label answersHeading = new Label(BankCopy.ANSWERS_HEADING);
        answersHeading.getStyleClass().addAll("small", "muted");
        nodes.add(answersHeading);

        VBox answers = new VBox(6);
        answers.getStyleClass().add("bank-answers");
        List<String> options = detail.answers();
        for (int i = 0; i < options.size(); i++) {
            int oneBased = i + 1;
            Label label = new Label(BankCopy.answerLabel(oneBased));
            label.getStyleClass().addAll("small", "muted");
            Label value = new Label(options.get(i));
            value.setWrapText(true);
            HBox line = new HBox(8, label, value);
            line.getStyleClass().add("bank-answer");
            if (oneBased == detail.correctAnswer()) {
                Label mark = new Label(BankCopy.CORRECT_MARK);
                mark.getStyleClass().addAll("small", "bank-answer-correct");
                line.getChildren().add(mark);
                line.getStyleClass().add("correct");
            }
            answers.getChildren().add(line);
        }
        nodes.add(answers);

        HBox facts = new HBox(10, new Label(BankCopy.topic(detail.topic())),
                StatusChip.difficulty(detail.difficulty().name()));
        facts.setAlignment(Pos.CENTER_LEFT);
        facts.getStyleClass().add("bank-facts");
        nodes.add(facts);

        nodes.add(imageNode(detail));

        Label author = new Label(BankCopy.writtenBy(detail));
        author.getStyleClass().addAll("small", "muted");
        author.setWrapText(true);
        nodes.add(author);

        historyToggle.setText(session.isHistoryOpen()
                ? BankCopy.HISTORY_CLOSE : BankCopy.HISTORY_OPEN);
        // Writing is narrower than seeing: a coordinator reads her whole subject and authors
        // only where she teaches. Both write controls go together, and both say why, because a
        // greyed button with no reason is a defect of its own on a screen she reached legitimately.
        boolean mayWrite = session.canWriteIn(detail.courseCode());
        delete.setDisable(session.isDeleting() || !mayWrite);
        String why = mayWrite ? null : BankCopy.readOnlyCourse(detail.courseName());
        setReason(delete, why);
        setReason(edit, why);
        // Editing is offered only once the illustration has settled, which is what makes the
        // components report's trap unreachable from this screen: the editor takes the bytes as a
        // required argument, so a button that could open it early would be the only way in.
        edit.setDisable(!canEdit(detail));
        HBox actions = new HBox(10, historyToggle, edit, Buttons.spacer(), delete);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("bank-actions");
        nodes.add(actions);

        return nodes;
    }

    /**
     * The illustration, or the honest sentence about why it is not there.
     *
     * <p>Three different absences, three different sentences: the question has no picture, the
     * picture is on its way, and the picture could not be fetched. One "no image" for all three
     * would tell a teacher whose diagram failed to load that she never attached one.
     */
    private Node imageNode(QuestionDetail detail) {
        if (!detail.hasImage()) {
            Label none = new Label(BankCopy.NO_IMAGE);
            none.getStyleClass().addAll("small", "muted", "bank-no-image");
            return none;
        }
        switch (session.imageState()) {
            case READY -> {
                byte[] bytes = session.image();
                if (bytes != null && bytes.length > 0) {
                    ImageView view = new ImageView(new Image(new ByteArrayInputStream(bytes)));
                    view.setPreserveRatio(true);
                    view.setFitWidth(DETAIL_WIDTH - 60);
                    view.getStyleClass().add("bank-image");
                    return view;
                }
                Label broken = new Label(BankCopy.IMAGE_FAILED);
                broken.getStyleClass().addAll("small", "danger-text");
                broken.setWrapText(true);
                return broken;
            }
            case ERROR -> {
                Label failed = new Label(session.imageError());
                failed.getStyleClass().addAll("small", "danger-text", "bank-image-error");
                failed.setWrapText(true);
                return failed;
            }
            default -> {
                Label loading = new Label(BankCopy.IMAGE_LOADING);
                loading.getStyleClass().addAll("small", "muted", "bank-image-loading");
                return loading;
            }
        }
    }

    private void renderHistory() {
        historyBody.getChildren().clear();
        if (!session.isHistoryOpen()) {
            historyBody.setVisible(false);
            historyBody.setManaged(false);
            return;
        }
        historyBody.setVisible(true);
        historyBody.setManaged(true);

        Label heading = new Label(BankCopy.HISTORY_TITLE);
        heading.getStyleClass().add("h3");
        historyBody.getChildren().add(heading);

        String failure = session.historyError();
        if (failure != null) {
            Label label = new Label(failure);
            label.getStyleClass().addAll("small", "danger-text");
            label.setWrapText(true);
            historyBody.getChildren().add(label);
            return;
        }
        for (BankSession.HistoryEntry entry : session.historyEntries()) {
            Label headline = new Label(entry.headline());
            headline.getStyleClass().add(entry.isCurrent() ? "bank-history-current"
                    : "bank-history-past");
            Label changes = new Label(entry.changes());
            changes.getStyleClass().addAll("small", "muted");
            changes.setWrapText(true);
            VBox row = new VBox(2, headline, changes);
            row.getStyleClass().add("bank-history-entry");
            historyBody.getChildren().add(row);
        }
    }

    // ===================== Delete (E6.13) =================================

    private void confirmDelete() {
        QuestionDetail detail = session.detail();
        if (detail == null || showingDialog) {
            return;
        }
        showingDialog = true;
        try {
            boolean confirmed = WarnConfirm.show(window(),
                    WarnConfirm.spec(BankCopy.DELETE_CONFIRM_TITLE)
                            .explanation(BankCopy.deleteConfirmBody(detail))
                            .confirmText(BankCopy.DELETE_CONFIRM_BUTTON)
                            .cancelText(BankCopy.DELETE_CANCEL_BUTTON)
                            .danger());
            if (confirmed) {
                session.deleteSelected();
            }
        } finally {
            showingDialog = false;
        }
    }

    private void renderDialogs() {
        if (showingDialog) {
            return;
        }
        if (!session.blockingExams().isEmpty()) {
            showingDialog = true;
            try {
                WarnConfirm.show(window(), WarnConfirm.spec(BankCopy.DELETE_BLOCKED_TITLE)
                        .explanation(BankCopy.deleteBlockedBody(session.blockedQuestion(),
                                session.blockingExams()))
                        .confirmText(BankCopy.DELETE_BLOCKED_BUTTON)
                        .info());
            } finally {
                showingDialog = false;
            }
            session.dismissBlocked();
            return;
        }
        String justDeleted = session.justDeleted();
        if (justDeleted != null) {
            if (toasts() != null) {
                toasts().success(BankCopy.DELETE, BankCopy.deleted(justDeleted));
            }
            session.dismissDeleted();
            return;
        }
        String failure = session.deleteError();
        if (failure != null) {
            if (toasts() != null) {
                toasts().error(BankCopy.DELETE, failure);
            }
            // Dismissed like the other two notices. Without this, render() shows it again on
            // every settle, filter and selection for the rest of the session.
            session.dismissDeleteError();
        }
    }

    // ===================== Odds and ends ==================================

    /**
     * Whether the editor can be opened on what is on screen.
     *
     * <p>An illustrated question is editable only once its bytes have arrived, because
     * {@code QuestionEditorSession.forEdit} takes them as a required argument. That is the
     * components report's trap made unreachable rather than remembered: this button is the only
     * route into the editor from a question, and it cannot fire early.
     */
    private boolean canEdit(QuestionDetail detail) {
        if (detail == null || session.isDeleting()) {
            return false;
        }
        // The bytes, and enough of them. Three states have to agree here and two of them are
        // easy to miss: QuestionImage normalises a null blob to an EMPTY array, so a non-null
        // check passes for a picture that is not there; and this view already renders that
        // state as a failure in imageNode. forEdit refuses it, so a laxer test here would
        // enable a button whose only outcome is an IllegalArgumentException.
        if (!session.canWriteIn(detail.courseCode())) {
            return false;
        }
        byte[] bytes = session.image();
        return !detail.hasImage() || (bytes != null && bytes.length > 0);
    }

    private void openEditorForSelected() {
        QuestionDetail detail = session.detail();
        if (!canEdit(detail)) {
            return;
        }
        NavParams params = NavParams.of(QuestionEditorView.PARAM_DETAIL, detail);
        byte[] image = session.image();
        if (image != null) {
            params = params.with(QuestionEditorView.PARAM_IMAGE, image);
        }
        navigator().navigate(BankRoutes.EDITOR, params);
    }

    /**
     * Opens the editor on a new question in the course the filter is set to.
     *
     * <p>The course has to be known before the editor opens, because {@code QUESTION_CREATE}
     * carries it and the server refuses a course she does not teach. With no course filter set
     * there is nothing to guess at, so the button asks her to pick one rather than choosing on
     * her behalf.
     */
    private void openEditorForNewQuestion() {
        String course = session.selectedCourse();
        if (course == null) {
            if (toasts() != null) {
                toasts().info(QuestionEditorCopy.NEW_QUESTION, BankCopy.PICK_A_COURSE_FIRST);
            }
            return;
        }
        // The filter can name a course she reads and does not teach, because the two scopes are
        // different sets. QUESTION_CREATE would be refused with COURSE_NOT_TAUGHT, so the trip
        // is not offered.
        if (!session.canWriteIn(course)) {
            if (toasts() != null) {
                toasts().info(QuestionEditorCopy.NEW_QUESTION,
                        BankCopy.readOnlyCourse(courseNameOf(course)));
            }
            return;
        }
        navigator().navigate(BankRoutes.EDITOR,
                NavParams.of(QuestionEditorView.PARAM_COURSE, course));
    }

    /** A tooltip that explains a disabled control, or clears one that no longer applies. */
    private static void setReason(Button button, String reason) {
        button.setTooltip(reason == null ? null : new javafx.scene.control.Tooltip(reason));
    }

    /** The course's display name from the options in hand, or the code when it is not there. */
    private String courseNameOf(String courseCode) {
        return session.courseOptions().stream()
                .filter(course -> courseCode.equals(course.code()))
                .map(CourseRef::name)
                .findFirst()
                .orElse(courseCode);
    }

    private static List<CourseRef> coursesOfSignedInUser() {
        LoginResult user = ScreenManager.getInstance().signedInUser();
        return user == null ? List.of() : user.courses();
    }

    /**
     * @return the signed-in user's id, or {@code 0} when there is no session. Zero is safe here
     *         rather than a guard: it is only ever compared against a lock holder's id to decide
     *         whether a row says "you", and no holder can carry it, so the column falls back to
     *         naming the holder instead of claiming the row is this user's
     */
    private static long signedInUserId() {
        LoginResult user = ScreenManager.getInstance().signedInUser();
        return user == null ? 0L : user.userId();
    }

    private javafx.stage.Window window() {
        return view().getScene() == null ? null : view().getScene().getWindow();
    }

    /** The course picker's entry; {@code code} is null on the "all courses" row. */
    private record CourseOption(String code, String name) {
    }

    private static final class CourseCell extends ListCell<CourseOption> {
        @Override
        protected void updateItem(CourseOption option, boolean empty) {
            super.updateItem(option, empty);
            setText(empty || option == null ? null : option.name());
        }
    }

    /** The difficulty picker's entry; {@code difficulty} is null on the "any" row. */
    private record DifficultyOption(Difficulty difficulty) {
    }

    private static final class DifficultyCell extends ListCell<DifficultyOption> {
        @Override
        protected void updateItem(DifficultyOption option, boolean empty) {
            super.updateItem(option, empty);
            setText(empty || option == null ? null : BankCopy.difficulty(option.difficulty()));
        }
    }
}
