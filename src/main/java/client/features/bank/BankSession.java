package client.features.bank;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.auth.CourseRef;
import common.dto.bank.BankListRequest;
import common.dto.bank.BankPage;
import common.dto.bank.BankQuestionRow;
import common.dto.bank.BlockingExam;
import common.dto.bank.Difficulty;
import common.dto.bank.DeleteOutcome;
import common.dto.bank.QuestionDeleteRequest;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionImage;
import common.dto.bank.QuestionImageRequest;
import common.dto.bank.QuestionRequest;
import common.dto.bank.VersionHistory;
import common.protocol.Message;
import common.protocol.Verb;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The logic behind the question bank screen (Presentation tier, E6.9 / E6.12 / E6.13 — F2.4,
 * F2.5, T-2).
 *
 * <p>Everything the screen decides lives here and nothing else does: what the filters are
 * hiding, which page is showing, which question is open, whether its illustration has arrived,
 * whether the history panel is out, and what a delete came back with. The FX view beside it
 * reads and renders. That split is what makes the screen's behaviour testable against
 * {@code FakeClientConnection} with no JavaFX toolkit (TEAM_SPLIT section 3.2).
 *
 * <h2>Five verbs, four of them reads</h2>
 *
 * <p>{@code BANK_LIST}, {@code QUESTION_GET}, {@code QUESTION_VERSIONS},
 * {@code QUESTION_IMAGE_GET} and {@code QUESTION_DELETE}. The editor's two writes
 * ({@code QUESTION_CREATE}, {@code QUESTION_UPDATE}) are not here and are not this class's:
 * they belong to the editor session, which is E6.10.
 *
 * <h2>The filters are the server's, not this class's</h2>
 *
 * <p>Unlike the principal's Data screen, which pulls the whole bank and narrows it in the
 * client, a teacher's browse is <b>paginated by design</b> (E6.5) and the contract clamps the
 * page size. So every filter travels, the server intersects the course filter with the caller's
 * reachable set, and this class never sees a row it was not meant to. Narrowing a page in the
 * client would narrow one page and silently hide the matches on the others.
 *
 * <h2>Late answers are dropped, not applied ⚑</h2>
 *
 * <p>This is the defect this class is most exposed to and the reason for {@link #listGeneration}
 * and {@link #detailGeneration}. A teacher who types into the search box, or clicks question
 * after question down the list, has several requests in flight whose answers can arrive in any
 * order. Applying whichever landed last puts rows on screen that do not match the filters above
 * them, or a question in the detail pane that is not the one highlighted in the list.
 *
 * <p>Every request carries the generation it was issued under and an answer from an older
 * generation is discarded. The counter is bumped by <b>anything that changes what an answer
 * would mean</b>, which is every filter, the page, and the selection. That is a property of how
 * the class is built rather than a rule about the order the network happens to deliver in.
 */
public final class BankSession {

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    /** The courses the signed-in user may filter by, straight from the sign-in payload. */
    private final List<CourseRef> courses;

    private Runnable onChange = () -> { };

    // --- the list -------------------------------------------------------
    private String courseFilter;
    private String topicFilter;
    private Difficulty difficultyFilter;
    private String search = "";
    private int page;

    private AsyncViewState listState = AsyncViewState.IDLE;
    private String listError;
    private List<BankQuestionRow> rows = List.of();
    private long totalRows;
    private int totalPages;
    private boolean hasNextPage;
    private int listGeneration;

    // --- the selected question -----------------------------------------
    private String selectedId;
    private AsyncViewState detailState = AsyncViewState.IDLE;
    private String detailError;
    private QuestionDetail detail;
    private int detailGeneration;

    // --- its illustration (E6.6) ---------------------------------------
    private AsyncViewState imageState = AsyncViewState.IDLE;
    private String imageError;
    private byte[] image;

    // --- its history (E6.12) -------------------------------------------
    private boolean historyOpen;
    private AsyncViewState historyState = AsyncViewState.IDLE;
    private String historyError;
    private VersionHistory history;

    // --- delete (E6.13) -------------------------------------------------
    private boolean deleting;
    private String blocked;
    private String deleteError;
    private String deleted;
    private List<BlockingExam> blockingExams = List.of();

    /**
     * @param dispatcher the request correlator; the screen never touches a socket
     * @param poster     the single FX-thread hop; {@code DirectFxThreadPoster} in tests
     * @param courses    the signed-in user's courses, from {@code LoginResult.courses()}. Not
     *                   fetched: E1.4 already puts them in the sign-in payload, and a verb to
     *                   re-read what the session is holding would be a round trip that can
     *                   disagree with the session
     */
    public BankSession(RequestDispatcher dispatcher, FxThreadPoster poster,
                       List<CourseRef> courses) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
        this.courses = courses == null ? List.of() : List.copyOf(courses);
    }

    /** Registers the "re-read me and re-render" callback. */
    public BankSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== Loading the list ==============================

    /** Loads the first page. What {@code onShow} calls. */
    public void load() {
        requestPage(0);
    }

    /**
     * Re-asks for the page currently showing.
     *
     * <p>Called after a delete, and after the editor saves. <b>Not</b> a refresh button: NFR-18
     * forbids asking the teacher to press one, and nothing on this screen does.
     */
    public void reload() {
        requestPage(page);
    }

    private void requestPage(int wanted) {
        int generation = ++listGeneration;
        page = Math.max(wanted, 0);
        listState = AsyncViewState.LOADING;
        listError = null;
        onChange.run();

        BankListRequest request = new BankListRequest(courseFilter, topicFilter,
                difficultyFilter, search, page, BankListRequest.DEFAULT_PAGE_SIZE);
        dispatcher.send(Verb.BANK_LIST, request)
                .whenComplete((response, failure) ->
                        poster.run(() -> settleList(generation, response, failure)));
    }

    private void settleList(int generation, Message response, Throwable failure) {
        if (generation != listGeneration) {
            // A filter or a page moved while this was in flight. Its rows describe a screen
            // that is no longer on screen.
            return;
        }
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof BankPage payload)) {
            rows = List.of();
            totalRows = 0;
            totalPages = 0;
            hasNextPage = false;
            listError = BankCopy.LIST_FAILED;
            listState = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        // The page comes from the ANSWER, never from what was asked for. BankBrowseService
        // clamps a negative page and nothing else, so asking for page 2 of a bank that has one
        // page answers an empty page 2 rather than an error. Trusting the request here is how a
        // full bank renders the "there are no questions yet" panel: see stepBackFromTheEnd.
        page = Math.max(payload.page(), 0);
        rows = List.copyOf(payload.rows());
        totalRows = payload.totalRows();
        totalPages = payload.totalPages();
        // Delegated to BankPage rather than recomputed here. Not a guard and no test can prove
        // it: BankPage.hasNextPage IS page + 1 < totalPages, so recomputing it would be an
        // equivalent mutation. It is here so the rule has one home, which is worth doing even
        // where it is not worth checking.
        hasNextPage = payload.hasNextPage();
        listError = null;
        listState = AsyncViewState.forResult(rows);

        if (stepBackFromTheEnd()) {
            return;
        }

        // A selection that is no longer on the page stops being the selection, so the detail
        // pane can never describe a question the list is not showing.
        if (selectedId != null && rows.stream().noneMatch(r -> r.displayId5().equals(selectedId))) {
            clearSelection();
        }
        onChange.run();
    }

    /**
     * Walks back to the last real page when the page showing has run off the end.
     *
     * <p>Deleting the only row on the last page is the way there: the reload asks for the page
     * she was on, the server answers it empty because it clamps only negatives, and without this
     * the screen tells a teacher with forty questions that she has none. That sentence exists to
     * describe an empty bank and must never describe a full one.
     *
     * @return whether a new request was issued, in which case the caller must not render yet
     */
    private boolean stepBackFromTheEnd() {
        if (totalPages <= 0 || page < totalPages) {
            return false;
        }
        requestPage(totalPages - 1);
        return true;
    }

    // ===================== Filters ========================================

    /**
     * Applies the free-text search.
     *
     * <p>The <b>view</b> decides when to call this, and calls it on submit rather than on every
     * keystroke. Debouncing would need a timer, and a timer inside an FX-free class is a seam
     * that exists only for its own sake: the trigger is a presentation decision and it is made
     * where the keyboard is.
     *
     * @param text what she typed; {@code null} and blank both mean "do not filter"
     */
    public void setSearch(String text) {
        String next = text == null ? "" : text.strip();
        if (next.equals(search)) {
            return;
        }
        search = next;
        requestPage(0);
    }

    /** @param courseCode the course to show, or {@code null} for every course she can reach */
    public void selectCourse(String courseCode) {
        String next = blankToNull(courseCode);
        if (Objects.equals(next, courseFilter)) {
            return;
        }
        courseFilter = next;
        requestPage(0);
    }

    /** @param topic exact-equality lookup per the contract's ruling 7.6, or {@code null} */
    public void selectTopic(String topic) {
        String next = blankToNull(topic);
        if (Objects.equals(next, topicFilter)) {
            return;
        }
        topicFilter = next;
        requestPage(0);
    }

    /** @param difficulty the one to show, or {@code null} for any */
    public void selectDifficulty(Difficulty difficulty) {
        if (difficultyFilter == difficulty) {
            return;
        }
        difficultyFilter = difficulty;
        requestPage(0);
    }

    /** Puts every filter back and returns to the first page. */
    public void clearFilters() {
        if (!isFiltered()) {
            return;
        }
        courseFilter = null;
        topicFilter = null;
        difficultyFilter = null;
        search = "";
        requestPage(0);
    }

    /** @return whether anything is narrowing the list right now */
    public boolean isFiltered() {
        return courseFilter != null || topicFilter != null || difficultyFilter != null
                || !search.isEmpty();
    }

    // ===================== Paging =========================================

    /** @return whether there is a page after this one, as the last answer reported it */
    public boolean hasNextPage() {
        return hasNextPage;
    }

    /** @return whether there is a page before this one */
    public boolean hasPreviousPage() {
        return page > 0;
    }

    /** Moves forward one page, or does nothing at the end. */
    public void nextPage() {
        if (hasNextPage()) {
            requestPage(page + 1);
        }
    }

    /** Moves back one page, or does nothing at the start. */
    public void previousPage() {
        if (hasPreviousPage()) {
            requestPage(page - 1);
        }
    }

    // ===================== Selecting a question ===========================

    /**
     * Opens one question in the detail pane.
     *
     * <p>Sends {@code QUESTION_GET}, and then {@code QUESTION_IMAGE_GET} only if the answer says
     * the question has an illustration. Two round trips rather than one, which is the shape the
     * contract chose: an image never travels in a list or a detail, so a bank of illustrated
     * questions does not cost a megabyte per row.
     *
     * @param displayId5 the five-digit id, or {@code null} to clear the pane
     */
    public void select(String displayId5) {
        if (displayId5 == null) {
            clearSelection();
            onChange.run();
            return;
        }
        if (displayId5.equals(selectedId) && detailState == AsyncViewState.READY) {
            return;
        }
        int generation = ++detailGeneration;
        selectedId = displayId5;
        detail = null;
        detailError = null;
        detailState = AsyncViewState.LOADING;
        resetImage();
        resetHistory();
        onChange.run();

        dispatcher.send(Verb.QUESTION_GET, new QuestionRequest(displayId5))
                .whenComplete((response, failure) ->
                        poster.run(() -> settleDetail(generation, response, failure)));
    }

    private void settleDetail(int generation, Message response, Throwable failure) {
        if (generation != detailGeneration) {
            return;
        }
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof QuestionDetail payload)) {
            detail = null;
            detailError = BankCopy.DETAIL_FAILED;
            detailState = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        detail = payload;
        detailError = null;
        detailState = AsyncViewState.READY;
        onChange.run();

        if (payload.hasImage()) {
            requestImage(generation, payload);
        }
        if (historyOpen) {
            requestHistory(generation, payload.displayId5());
        }
    }

    /**
     * Asks again for the question that failed to open.
     *
     * <p>An explicit entry point because the list cannot provide one: the row is still selected
     * after a failure, so clicking it again fires no selection change and the screen would look
     * like it was ignoring her. NFR-18 is about not making a teacher refresh a screen that
     * worked; it does not ask a screen that failed to leave her no way back.
     */
    public void retrySelected() {
        if (selectedId == null || detailState != AsyncViewState.ERROR) {
            return;
        }
        String id = selectedId;
        selectedId = null;
        select(id);
    }

    private void clearSelection() {
        selectedId = null;
        detail = null;
        detailError = null;
        detailState = AsyncViewState.IDLE;
        detailGeneration++;
        resetImage();
        resetHistory();
    }

    // ===================== The illustration (E6.6) ========================

    private void requestImage(int generation, QuestionDetail forDetail) {
        imageState = AsyncViewState.LOADING;
        imageError = null;
        onChange.run();

        QuestionImageRequest request =
                new QuestionImageRequest(forDetail.displayId5(), forDetail.versionNo());
        dispatcher.send(Verb.QUESTION_IMAGE_GET, request)
                .whenComplete((response, failure) ->
                        poster.run(() -> settleImage(generation, response, failure)));
    }

    private void settleImage(int generation, Message response, Throwable failure) {
        if (generation != detailGeneration) {
            return;
        }
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof QuestionImage payload)) {
            image = null;
            imageError = BankCopy.IMAGE_FAILED;
            imageState = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        image = payload.bytes();
        imageError = null;
        imageState = AsyncViewState.READY;
        onChange.run();
    }

    private void resetImage() {
        image = null;
        imageError = null;
        imageState = AsyncViewState.IDLE;
    }

    // ===================== Version history (E6.12) ========================

    /**
     * Opens or closes the history panel, fetching it the first time it is opened for a question.
     *
     * <p>Not fetched with the detail: most openings of a question never open its history, and
     * {@code QUESTION_VERSIONS} carries every version's full text and answers.
     */
    public void toggleHistory() {
        historyOpen = !historyOpen;
        if (!historyOpen || detail == null) {
            onChange.run();
            return;
        }
        if (historyState == AsyncViewState.READY) {
            onChange.run();
            return;
        }
        requestHistory(detailGeneration, detail.displayId5());
    }

    private void requestHistory(int generation, String displayId5) {
        historyState = AsyncViewState.LOADING;
        historyError = null;
        onChange.run();

        dispatcher.send(Verb.QUESTION_VERSIONS, new QuestionRequest(displayId5))
                .whenComplete((response, failure) ->
                        poster.run(() -> settleHistory(generation, response, failure)));
    }

    private void settleHistory(int generation, Message response, Throwable failure) {
        if (generation != detailGeneration) {
            return;
        }
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof VersionHistory payload)) {
            history = null;
            historyError = BankCopy.VERSIONS_FAILED;
            historyState = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        history = payload;
        historyError = null;
        historyState = AsyncViewState.forResult(payload.versions());
        onChange.run();
    }

    private void resetHistory() {
        history = null;
        historyError = null;
        historyState = AsyncViewState.IDLE;
    }

    // ===================== Delete (E6.13, F2.5, T-2.7) ====================

    /**
     * Asks the server to delete the question in the detail pane.
     *
     * <p>Convenience over {@link #delete(String, int)} for a caller that has not already read
     * the detail. A screen that showed a confirmation dialog first should use the explicit form
     * and pass what the dialog described: {@code WarnConfirm.show} runs a nested event loop, so
     * an answer can land and change the selection while the teacher is reading the question she
     * is being asked about.
     */
    public void deleteSelected() {
        if (detail == null) {
            return;
        }
        delete(detail.displayId5(), detail.versionNo());
    }

    /**
     * Asks the server to delete one question.
     *
     * <p>Carries {@code baseVersionNo}, which is what the contract asks for and what makes a
     * delete racing an edit a {@code CONFLICT} rather than a coin toss. Two outcomes are both
     * successes at the protocol level: it was soft-deleted, or it is referenced and the server
     * named the exams (T-2.7). Only a transport or server failure is an error.
     *
     * <p><b>The outcome is applied whatever she is looking at when it lands, and it says which
     * question it is about.</b> Dropping a late delete answer the way a late read is dropped
     * would be wrong: the row really was deleted, and a list still showing it would be a lie.
     * What must not happen is the outcome acting on the <i>wrong</i> question, so the id travels
     * with it and the selection is only cleared when the question deleted is the one open.
     *
     * @param displayId5    the question to delete
     * @param baseVersionNo the version the teacher was shown, as the staleness token
     */
    public void delete(String displayId5, int baseVersionNo) {
        if (displayId5 == null || deleting) {
            return;
        }
        deleting = true;
        deleteError = null;
        deleted = null;
        blocked = null;
        blockingExams = List.of();
        onChange.run();

        QuestionDeleteRequest request = new QuestionDeleteRequest(displayId5, baseVersionNo);
        dispatcher.send(Verb.QUESTION_DELETE, request)
                .whenComplete((response, failure) ->
                        poster.run(() -> settleDelete(displayId5, response, failure)));
    }

    private void settleDelete(String id, Message response, Throwable failure) {
        deleting = false;
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof DeleteOutcome outcome)) {
            deleteError = BankCopy.DELETE_FAILED;
            onChange.run();
            return;
        }
        if (!outcome.deleted()) {
            blocked = id;
            blockingExams = outcome.blockingExams();
            onChange.run();
            return;
        }
        deleted = id;
        if (id.equals(selectedId)) {
            clearSelection();
        }
        onChange.run();
        // The row has to leave the list, and the count and the page bounds move with it.
        reload();
    }

    /** Clears the blocked-exams refusal once its dialog has been read. */
    public void dismissBlocked() {
        if (blockingExams.isEmpty() && blocked == null) {
            return;
        }
        blocked = null;
        blockingExams = List.of();
        onChange.run();
    }

    /** Clears the "it was deleted" flag once its toast has been shown. */
    public void dismissDeleted() {
        if (deleted == null) {
            return;
        }
        deleted = null;
        onChange.run();
    }

    /**
     * Clears the delete failure once its toast has been shown.
     *
     * <p>Its absence was a defect rather than an omission: {@link #onChange} fires on every
     * settle, every filter and every selection, so a sentence the screen never stops holding is
     * a sentence the screen shows again on every one of them. The other two notices have had a
     * dismisser from the start and this one now matches them.
     */
    public void dismissDeleteError() {
        if (deleteError == null) {
            return;
        }
        deleteError = null;
        onChange.run();
    }

    // ===================== What the view reads ============================

    /** @return the rows on the page showing */
    public List<BankQuestionRow> rows() {
        return rows;
    }

    /** @return the list's load state */
    public AsyncViewState state() {
        return listState;
    }

    /** @return the list's failure sentence, or {@code null} */
    public String error() {
        return listError;
    }

    /** @return how many rows the filters match across every page */
    public long totalRows() {
        return totalRows;
    }

    /** @return how many pages the filters match */
    public int totalPages() {
        return totalPages;
    }

    /** @return the zero-based page showing, as the wire counts */
    public int page() {
        return page;
    }

    /** @return the panel to show where the list would be, or empty when there are rows */
    public Optional<BankCopy.EmptyPanel> emptyPanel() {
        if (listState != AsyncViewState.EMPTY) {
            return Optional.empty();
        }
        return Optional.of(isFiltered() ? BankCopy.NO_MATCHES : BankCopy.NO_QUESTIONS);
    }

    /** @return the signed-in user's own courses, exactly as the sign-in payload gave them */
    public List<CourseRef> courses() {
        return courses;
    }

    /**
     * Whether the caller may write into this course, which is narrower than seeing it.
     *
     * <p>Contract section 2 keeps two scopes apart: a coordinator <b>reads</b> every course of
     * her subject and <b>writes</b> only in the ones she also teaches. The read half is why the
     * list can show her a course at all; this is the write half, and without it the screen
     * offers her Delete and Edit on rows the server will refuse.
     *
     * <p>The set is {@code LoginResult.courses()}, which is
     * {@code CourseRepository.findForUser}: teaching <b>union enrolment</b>. For staff the
     * enrolment half is empty, so it is exactly the taught set. Said out loud because the union
     * is not obvious from the name, and it would be the wrong set for a role that has both.
     *
     * <p>Offering, not permission: the server re-checks with {@code requireTeachesCourse} on
     * create and {@code teachesCourse} on edit and delete, and it is the one that decides. This
     * only stops the client proposing a trip whose single possible outcome is a refusal.
     *
     * @param courseCode the course a row or a detail belongs to
     * @return whether the caller teaches it
     */
    public boolean canWriteIn(String courseCode) {
        String wanted = blankToNull(courseCode);
        if (wanted == null) {
            return false;
        }
        for (CourseRef course : courses) {
            // strip(), never trim(), for the reason the contract's section 5 gives: course codes
            // are CHAR(2) under a PAD SPACE collation, so a code carrying a Unicode space
            // matches the row in SQL while failing Java equality.
            if (wanted.equals(blankToNull(course.code()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The courses the picker offers: the caller's own, plus every course the bank has actually
     * shown her.
     *
     * <p><b>The union is not tidiness, it is the only thing that makes the picker work for the
     * coordinator.</b> {@code LoginResult.courses()} is {@code CourseRepository.findForUser},
     * which is taught union enrolled and touches neither {@code coordinators} nor
     * {@code subjects}. The bank's read scope is wider on purpose (contract section 2): a
     * coordinator reaches every course of her subject, whether or not she teaches it.
     *
     * <p>The principal reaches wider still and is not a caller here: this screen is registered
     * for the two authoring roles only, because it carries Delete. Her browse is the E15.2 Data
     * screen (the lead's ruling on #41).
     *
     * <p>So for {@code rina.barak}, who holds a {@code coordinators} row and <b>zero</b>
     * {@code course_teachers} rows deliberately, the sign-in payload alone would offer a picker
     * with nothing in it beside the bank she can plainly see. She is a starred demo account and
     * the approver in acceptance scenario 4, so that is a defect with a stage in front of it.
     *
     * <p>This is a repair rather than the answer. The rows in hand are one page, so the union is
     * complete only when her bank fits on a page. <b>The real fix is a lookup verb</b>, and it is
     * the natural companion to the {@code BANK_TOPICS} verb ruling 7.6 already schedules: both
     * are "what may I filter by", both are answered by the reachable set the service already
     * computes once per request. Flagged for the lead in this PR's report.
     *
     * @return the options, the caller's own first, each course once, never null
     */
    public List<CourseRef> courseOptions() {
        java.util.LinkedHashMap<String, CourseRef> options = new java.util.LinkedHashMap<>();
        for (CourseRef course : courses) {
            options.putIfAbsent(course.code(), course);
        }
        for (BankQuestionRow row : rows) {
            options.putIfAbsent(row.courseCode(),
                    new CourseRef(row.courseCode(), row.courseName()));
        }
        return List.copyOf(options.values());
    }

    /**
     * The topics the picker offers.
     *
     * <p><b>The one place the topic lookup lands.</b> The contract's ruling 7.6 replaced the
     * typed topic filter with a picker fed by {@code BANK_TOPICS} over
     * {@code QuestionRepository.findDistinctTopics}, and neither the verb nor its payload exists
     * yet. Until they do this answers empty, the view hides the picker rather than offering an
     * empty one, and {@link #selectTopic(String)} already works: the filter itself has been on
     * the wire since the read-verbs PR.
     *
     * <p>When the lookup lands, this method is the only thing that changes.
     *
     * @return the distinct topics of the filtered course, newest lookup first; empty for now
     */
    public List<String> availableTopics() {
        return List.of();
    }

    /** @return the course filter, or {@code null} */
    public String selectedCourse() {
        return courseFilter;
    }

    /** @return the topic filter, or {@code null} */
    public String selectedTopic() {
        return topicFilter;
    }

    /** @return the difficulty filter, or {@code null} */
    public Difficulty selectedDifficulty() {
        return difficultyFilter;
    }

    /** @return the search text, never {@code null} */
    public String search() {
        return search;
    }

    /** @return the id of the question in the detail pane, or {@code null} */
    public String selectedId() {
        return selectedId;
    }

    /** @return the open question, or {@code null} */
    public QuestionDetail detail() {
        return detail;
    }

    /** @return the detail pane's load state */
    public AsyncViewState detailState() {
        return detailState;
    }

    /** @return the detail pane's failure sentence, or {@code null} */
    public String detailError() {
        return detailError;
    }

    /** @return the illustration's bytes, or {@code null} */
    public byte[] image() {
        return image;
    }

    /** @return the illustration's load state */
    public AsyncViewState imageState() {
        return imageState;
    }

    /** @return the illustration's failure sentence, or {@code null} */
    public String imageError() {
        return imageError;
    }

    /** @return whether the history panel is out */
    public boolean isHistoryOpen() {
        return historyOpen;
    }

    /** @return the versions, newest first, or {@code null} */
    public VersionHistory history() {
        return history;
    }

    /** @return the history panel's load state */
    public AsyncViewState historyState() {
        return historyState;
    }

    /** @return the history panel's failure sentence, or {@code null} */
    public String historyError() {
        return historyError;
    }

    /** @return the question the last refusal was about, or {@code null}; the dialog names it */
    public String blockedQuestion() {
        return blocked;
    }

    /**
     * The exams that refused the last delete (T-2.7).
     *
     * @return the blocking exams, or empty when nothing is being refused
     */
    public List<BlockingExam> blockingExams() {
        return blockingExams;
    }

    /** @return whether a delete is in flight */
    public boolean isDeleting() {
        return deleting;
    }

    /** @return the delete's failure sentence, or {@code null} */
    public String deleteError() {
        return deleteError;
    }

    /** @return the id of the question just deleted, for the toast, or {@code null} */
    public String justDeleted() {
        return deleted;
    }

    /**
     * The history, newest first, paired with what changed since the version before it.
     *
     * <p>Computed here rather than in the view so the diff E6.12 asks for is testable. The
     * pairing walks the list as the server ordered it, so the oldest entry is paired with
     * {@code null} and reads as the first version.
     *
     * @return one entry per version, or empty when no history is loaded
     */
    public List<HistoryEntry> historyEntries() {
        if (history == null || history.versions().isEmpty()) {
            return List.of();
        }
        var versions = history.versions();
        int latest = versions.get(0).versionNo();
        List<HistoryEntry> entries = new ArrayList<>(versions.size());
        for (int i = 0; i < versions.size(); i++) {
            var version = versions.get(i);
            var older = i + 1 < versions.size() ? versions.get(i + 1) : null;
            entries.add(new HistoryEntry(version, BankCopy.historyEntry(version, latest),
                    BankCopy.changeSummary(version, older), version.versionNo() == latest));
        }
        return List.copyOf(entries);
    }

    /**
     * One line of the version timeline.
     *
     * @param version   the version itself, so the panel can render it read-only
     * @param headline  what the timeline row says
     * @param changes   what moved since the version before it
     * @param isCurrent whether this is the newest version
     */
    public record HistoryEntry(common.dto.bank.QuestionVersionDetail version, String headline,
                               String changes, boolean isCurrent) {
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
