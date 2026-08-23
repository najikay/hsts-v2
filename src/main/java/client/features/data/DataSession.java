package client.features.data;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.bank.BankListRequest;
import common.dto.bank.BankPage;
import common.dto.bank.BankQuestionRow;
import common.dto.report.DataExamRow;
import common.dto.report.DataExams;
import common.dto.report.DataResults;
import common.dto.report.ReportRow;
import common.protocol.Message;
import common.protocol.Verb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The logic behind the principal's <b>Data</b> screen (Presentation tier, E15.2 — F9.3, T-11).
 *
 * <p>Everything the screen decides lives here and nothing else does: which tab is showing, what
 * each tab has loaded, what each tab's filters are hiding, and which explanation belongs where
 * a table would be. The FX view beside it reads {@link #tab()}, the three row lists and
 * {@link #emptyPanel()} and renders. That split is what makes the screen's behaviour testable
 * against {@code FakeClientConnection} with no JavaFX toolkit (TEAM_SPLIT section 3.2).
 *
 * <h2>Read-only by construction (S-7) ⚑</h2>
 *
 * <p>This class sends exactly three verbs — {@code BANK_LIST}, {@code DATA_EXAMS_GET} and
 * {@code DATA_RESULTS_GET} — and every one of them is a read. There is no method here that takes
 * an edit, and the screen it drives holds no editable control. The real guarantee is the
 * server's role gate, as it always is; this is the client end of the same rule, and it is one
 * short file so that "does her screen offer anything that writes" is answered by reading it.
 *
 * <h2>Three tabs, one shape</h2>
 *
 * <p>Each tab loads once, on the first visit, and keeps what it loaded. Switching back to a tab
 * therefore costs nothing and shows what it showed, which is what a browser should do and what
 * NFR-18 asks for: nothing on this screen is a manual refresh button.
 *
 * <p>The filters are per tab and are applied <b>here</b>, over the rows already in hand, rather
 * than by asking the server again. Two reasons, in order: the lists are school-sized (PRD
 * section 6), so a round trip per keystroke would buy nothing; and a filter that travelled would
 * be a field a client could set, which is the one thing the reports contract's section 4 refuses
 * to put on this role's wire. The one filter that <em>could</em> have been a server filter is the
 * bank's course code, and it is not one, so that all three tabs narrow the same way.
 *
 * <h2>The bank arrives a page at a time and is shown as one list</h2>
 *
 * <p>{@code BANK_LIST} is paginated because a teacher's bank browse is (E6.5); this screen is not
 * (PRD section 6). So the session asks for page after page and appends, and the screen sees one
 * list. The loop is bounded by {@link #MAX_BANK_PAGES}, because a loop that asks a server for
 * pages until it says stop is a loop, and an unbounded one is a client that hangs on a server
 * answering nonsense. The bound is far above any real school and
 * {@link DataCopy#TOO_MANY_QUESTIONS} says so if it is ever reached.
 */
public final class DataSession {

    /**
     * How many pages of the bank this screen will pull before it stops and says so.
     *
     * <p>Twenty pages of {@link BankListRequest#MAX_PAGE_SIZE} is two thousand questions, which
     * is far more than a school has and far less than a runaway loop.
     */
    public static final int MAX_BANK_PAGES = 20;

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };

    private DataTab tab = DataTab.defaultTab();

    private final Map<DataTab, AsyncViewState> states = new EnumMap<>(DataTab.class);
    private final Map<DataTab, String> filters = new EnumMap<>(DataTab.class);
    private final Map<DataTab, String> courseFilters = new EnumMap<>(DataTab.class);
    private final Map<DataTab, String> errors = new EnumMap<>(DataTab.class);

    private List<BankQuestionRow> questions = List.of();
    private boolean bankTruncated;
    private List<DataExamRow> exams = List.of();
    private List<ReportRow> sittings = List.of();

    /**
     * @param dispatcher the request correlator; the screen never touches a socket
     * @param poster     the single FX-thread hop; {@code DirectFxThreadPoster} in tests
     */
    public DataSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
        for (DataTab each : DataTab.values()) {
            states.put(each, AsyncViewState.IDLE);
            filters.put(each, "");
        }
    }

    /** Registers the "re-read me and re-render" callback. */
    public DataSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== Loading =======================================

    /** Loads the current tab if it has never been loaded. What {@code onShow} calls. */
    public void load() {
        loadIfNeeded(tab);
    }

    /**
     * Switches tabs, loading the new one the first time it is opened.
     *
     * <p>A tab that has already loaded keeps its rows <b>and its filters</b>. Coming back to a
     * list to find the text you typed still narrowing it is what a browser does; clearing it
     * would silently undo a decision she made.
     *
     * @param next which tab to show; ignored when it is already showing
     */
    public void selectTab(DataTab next) {
        Objects.requireNonNull(next, "tab");
        if (tab == next) {
            return;
        }
        tab = next;
        onChange.run();
        loadIfNeeded(next);
    }

    /**
     * Loads a tab unless it is already loaded or loading.
     *
     * <p><b>A tab that failed is asked again</b> when it is next opened, and that is not the
     * manual refresh NFR-18 forbids: it is the only way back from a dropped connection on a
     * screen with no reload button. A tab that succeeded is never asked twice, however many
     * times she visits it.
     */
    private void loadIfNeeded(DataTab asked) {
        AsyncViewState state = states.get(asked);
        if (state != AsyncViewState.IDLE && state != AsyncViewState.ERROR) {
            return;
        }
        states.put(asked, AsyncViewState.LOADING);
        errors.remove(asked);
        onChange.run();
        switch (asked) {
            case QUESTIONS -> requestBankPage(0, new ArrayList<>());
            case EXAMS -> dispatcher.send(Verb.DATA_EXAMS_GET, null)
                    .whenComplete((response, failure) -> poster.run(() ->
                            settle(DataTab.EXAMS, response, failure, DataExams.class, payload -> {
                                exams = payload.exams();
                                return exams;
                            })));
            case RESULTS -> dispatcher.send(Verb.DATA_RESULTS_GET, null)
                    .whenComplete((response, failure) -> poster.run(() ->
                            settle(DataTab.RESULTS, response, failure, DataResults.class,
                                    payload -> {
                                        sittings = payload.sittings();
                                        return sittings;
                                    })));
        }
    }

    /**
     * The one settle path the two whole-list verbs share.
     *
     * @param asked  which tab this answer belongs to
     * @param type   the payload type the verb answers with
     * @param adopt  what to do with a well-formed payload; returns the rows now held
     * @param <T>    the payload type
     */
    private <T> void settle(DataTab asked, Message response, Throwable failure, Class<T> type,
                            Function<T, List<?>> adopt) {
        if (failure != null || response == null || response.isError()
                || !type.isInstance(response.getPayload())) {
            // A well-formed OK carrying the wrong type is a protocol bug rather than a user
            // error; she still gets a sentence rather than a stack trace.
            fail(asked);
            return;
        }
        List<?> rows = adopt.apply(type.cast(response.getPayload()));
        errors.remove(asked);
        states.put(asked, AsyncViewState.forResult(rows));
        onChange.run();
    }

    private void fail(DataTab asked) {
        errors.put(asked, DataCopy.loadFailed(asked));
        states.put(asked, AsyncViewState.ERROR);
        onChange.run();
    }

    // ===================== The bank, page by page ========================

    private void requestBankPage(int page, List<BankQuestionRow> gathered) {
        dispatcher.send(Verb.BANK_LIST, new BankListRequest(null, null, null, null, page,
                        BankListRequest.MAX_PAGE_SIZE))
                .whenComplete((response, failure) ->
                        poster.run(() -> settleBankPage(page, gathered, response, failure)));
    }

    private void settleBankPage(int page, List<BankQuestionRow> gathered, Message response,
                                Throwable failure) {
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof BankPage payload)) {
            fail(DataTab.QUESTIONS);
            return;
        }
        gathered.addAll(payload.rows());
        if (payload.hasNextPage() && page + 1 < MAX_BANK_PAGES) {
            requestBankPage(page + 1, gathered);
            return;
        }
        // Truncated only when the server says there is more and the bound stopped us asking.
        bankTruncated = payload.hasNextPage();
        questions = List.copyOf(gathered);
        errors.remove(DataTab.QUESTIONS);
        states.put(DataTab.QUESTIONS, AsyncViewState.forResult(questions));
        onChange.run();
    }

    // ===================== Filtering =====================================

    /**
     * Narrows the current tab by free text.
     *
     * <p>Case-insensitive and {@code strip}ped, matched with {@code contains} against everything
     * on the row a person would recognise it by. Not a prefix match: a principal looking for the
     * Algebra midterm types "midterm", which is in the middle of its name.
     *
     * @param text what she typed; {@code null} and blank both mean "do not filter"
     */
    public void setFilter(String text) {
        String next = text == null ? "" : text.strip();
        if (next.equals(filters.get(tab))) {
            return;
        }
        filters.put(tab, next);
        onChange.run();
    }

    /**
     * Narrows the current tab to one course.
     *
     * @param courseCode the course to show, or {@code null} for every course
     */
    public void selectCourse(String courseCode) {
        String next = courseCode == null || courseCode.isBlank() ? null : courseCode.strip();
        if (Objects.equals(next, courseFilters.get(tab))) {
            return;
        }
        if (next == null) {
            courseFilters.remove(tab);
        } else {
            courseFilters.put(tab, next);
        }
        onChange.run();
    }

    /** Clears both of the current tab's filters. What the empty panel's hint describes. */
    public void clearFilters() {
        if (filters.get(tab).isEmpty() && courseFilters.get(tab) == null) {
            return;
        }
        filters.put(tab, "");
        courseFilters.remove(tab);
        onChange.run();
    }

    // ===================== What the screen reads =========================

    /** @return the tab showing; never null. */
    public DataTab tab() {
        return tab;
    }

    /** @return the current tab's view state: skeleton, content, empty or error. */
    public AsyncViewState state() {
        return states.get(tab);
    }

    /** @return the error sentence when the current tab failed to load. */
    public Optional<String> error() {
        return Optional.ofNullable(errors.get(tab));
    }

    /** @return true while the current tab's request is in flight, for the skeleton. */
    public boolean isLoading() {
        return state().showsSkeleton();
    }

    /** @return the current tab's text filter, as typed; empty when there is none. */
    public String filter() {
        return filters.get(tab);
    }

    /** @return the current tab's course filter, or empty when every course is showing. */
    public Optional<String> selectedCourse() {
        return Optional.ofNullable(courseFilters.get(tab));
    }

    /** @return {@code true} when either of the current tab's filters is narrowing the list. */
    public boolean isFiltered() {
        return !filters.get(tab).isEmpty() || courseFilters.get(tab) != null;
    }

    /**
     * @return the questions passing the Questions tab's filters, in the server's order. Empty
     *         unless that tab has loaded
     */
    public List<BankQuestionRow> questions() {
        // Arrays.asList and not List.of: a question's topic is nullable, and a haystack that
        // threw on the one row without a topic would be a filter that crashed on real data.
        return filtered(questions, DataTab.QUESTIONS, BankQuestionRow::courseCode,
                row -> Arrays.asList(row.displayId5(), row.text(), row.topic(), row.courseCode(),
                        row.courseName()));
    }

    /** @return the exams passing the Exams tab's filters, ordered by display id. */
    public List<DataExamRow> exams() {
        return filtered(exams, DataTab.EXAMS, DataExamRow::courseCode,
                row -> Arrays.asList(row.displayId6(), row.examName(), row.courseCode(),
                        row.courseName(), row.authorName()));
    }

    /** @return the closed sittings passing the Results tab's filters, newest first. */
    public List<ReportRow> sittings() {
        return filtered(sittings, DataTab.RESULTS, ReportRow::courseCode,
                row -> Arrays.asList(row.code4(), row.examName(), row.courseCode(),
                        row.courseName()));
    }

    /** @return how many rows the current tab holds before its filters are applied. */
    public int loadedCount() {
        return switch (tab) {
            case QUESTIONS -> questions.size();
            case EXAMS -> exams.size();
            case RESULTS -> sittings.size();
        };
    }

    /** @return how many rows the current tab is showing. */
    public int shownCount() {
        return switch (tab) {
            case QUESTIONS -> questions().size();
            case EXAMS -> exams().size();
            case RESULTS -> sittings().size();
        };
    }

    /** @return "40 questions", or "12 of 40 questions" while a filter narrows them. */
    public String countLine() {
        return DataCopy.countLine(tab, shownCount(), loadedCount());
    }

    /**
     * @return {@code true} when the bank had more pages than {@link #MAX_BANK_PAGES} allowed.
     *         Unreachable in a school of a realistic size, and said out loud rather than hidden
     */
    public boolean isBankTruncated() {
        return bankTruncated;
    }

    /**
     * The courses the current tab's rows actually belong to, for its dropdown.
     *
     * <p>Derived from the rows rather than fetched, and that is deliberate on two counts. There
     * is no course-list verb for this role to call, and inventing one would be a second answer
     * to "what courses exist" for a screen that already holds every row it could filter. And a
     * dropdown built from the rows cannot offer a course that would filter to nothing, which is
     * the dead end PRD section 4.1 forbids.
     *
     * @return code and name, ordered by code; empty before the tab has loaded
     */
    public List<CourseOption> courseOptions() {
        Map<String, CourseOption> byCode = new LinkedHashMap<>();
        switch (tab) {
            case QUESTIONS -> questions.forEach(row ->
                    put(byCode, row.courseCode(), row.courseName()));
            case EXAMS -> exams.forEach(row -> put(byCode, row.courseCode(), row.courseName()));
            case RESULTS -> sittings.forEach(row ->
                    put(byCode, row.courseCode(), row.courseName()));
        }
        List<CourseOption> options = new ArrayList<>(byCode.values());
        options.sort(Comparator.comparing(CourseOption::code));
        return List.copyOf(options);
    }

    /**
     * Which explanation belongs where the table would be, when there is no table.
     *
     * <p>Two different facts, and the screen has to say which one it is: this tab has nothing in
     * it at all, or it has rows and the filters are hiding every one of them. The second is the
     * one worth getting right, because a principal who has typed something and sees "the
     * question bank is empty" will believe it.
     *
     * @return the panel to show; meaningful only when there are no rows to draw
     */
    public DataCopy.EmptyPanel emptyPanel() {
        if (loadedCount() > 0) {
            return DataCopy.NO_MATCHES;
        }
        return DataCopy.nothingHere(tab);
    }

    // ===================== The one filter =================================

    /**
     * The filter every tab shares, written once.
     *
     * @param rows     the rows the tab holds
     * @param which    the tab, for its filters
     * @param courseOf where the row's course code is
     * @param haystack the row's searchable text, in the order a reader would scan it
     * @param <T>      the row type
     * @return the rows passing both filters, in the order they arrived
     */
    private <T> List<T> filtered(List<T> rows, DataTab which, Function<T, String> courseOf,
                                 Function<T, List<String>> haystack) {
        String needle = filters.get(which).toLowerCase(Locale.ROOT);
        String course = courseFilters.get(which);
        if (needle.isEmpty() && course == null) {
            return rows;
        }
        List<T> kept = new ArrayList<>(rows.size());
        for (T row : rows) {
            if (course != null && !course.equals(strip(courseOf.apply(row)))) {
                continue;
            }
            if (!needle.isEmpty() && !matches(haystack.apply(row), needle)) {
                continue;
            }
            kept.add(row);
        }
        return List.copyOf(kept);
    }

    private static boolean matches(List<String> haystack, String needle) {
        for (String field : haystack) {
            if (field != null && field.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Course codes are {@code CHAR(2)} under a PAD SPACE collation, so a code read back from
     * MySQL can carry trailing space that the code beside it does not. Stripping both ends is
     * the same defence {@code ByCourseStrategy} carries server-side.
     */
    private static String strip(String courseCode) {
        return courseCode == null ? "" : courseCode.strip();
    }

    private static void put(Map<String, CourseOption> byCode, String code, String name) {
        String key = strip(code);
        if (!key.isEmpty()) {
            byCode.putIfAbsent(key, new CourseOption(key, name == null ? "" : name));
        }
    }

    /**
     * One entry in a tab's course dropdown.
     *
     * @param code the two-character course code, which is what the filter compares
     * @param name the course's display name, which is what the principal reads
     */
    public record CourseOption(String code, String name) {

        public CourseOption {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(name, "name");
        }

        /** @return "Algebra (11)", so two similarly named courses are distinguishable. */
        public String label() {
            return DataCopy.course(code, name);
        }
    }
}
