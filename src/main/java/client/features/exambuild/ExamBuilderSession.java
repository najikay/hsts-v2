package client.features.exambuild;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.approval.ApprovalState;
import common.dto.authoring.AutoComposeRequest;
import common.dto.authoring.AutoComposeResult;
import common.dto.authoring.ComposedQuestion;
import common.dto.authoring.ExamComposition;
import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.ExamVersionRequest;
import common.dto.authoring.ExamVersionSave;
import common.dto.authoring.QuestionPin;
import common.dto.authoring.Shortfall;
import common.dto.authoring.TopicQuota;
import common.dto.bank.BankListRequest;
import common.dto.bank.BankPage;
import common.dto.bank.BankQuestionRow;
import common.dto.bank.Difficulty;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import server.features.exambuild.ExamValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The logic behind the exam builder (Presentation tier, E7.11 to E7.14 — F3.1, F3.3, F3.5, S-11).
 *
 * <p>Everything the builder decides lives here: which of three things it is doing, what the
 * metadata currently says, what is on the paper and in what order, what the points add up to, and
 * what a save came back with. The FX view beside it reads and renders, which is what makes all of
 * it testable against {@code FakeClientConnection} with no toolkit (TEAM_SPLIT §3.2).
 *
 * <h2>Three modes, decided in one place ⚑</h2>
 *
 * <p>{@link Mode} is a <b>function of two facts</b> and is computed by {@link #modeFor} alone:
 * whether the screen was opened with a version id, and what state that version came back in. It
 * is not a field anybody sets, and that is deliberate. The expensive defect available on this
 * screen is a save that goes to the wrong verb - {@code EXAM_CREATE} making a second exam when
 * she meant to update her draft, or {@code EXAM_VERSION_SAVE} against a version that is no longer
 * a draft - and a mode somebody assigns is a mode somebody can assign wrongly. Derived from the
 * answer, it cannot disagree with the answer.
 *
 * <p>{@link Mode#READ_ONLY} is not decoration either. Contract §8's read path, ruled 2026-08-25,
 * moved the "open a finished version and read it" case here from the retired approval screen, so
 * a version that is not a {@code DRAFT} renders and refuses every edit.
 *
 * <h2>The live points total is the server's rule, not a second copy of it ⚑</h2>
 *
 * <p>{@link #pointsProblem()} calls {@code ExamValidator.pointsProblem}, the same method
 * {@code ExamService} refuses a save with. E7.3 made it public for exactly this. So the indicator
 * that turns green and the rule that lets the save through are one piece of code and cannot drift:
 * there is no client-side arithmetic here to get wrong, and no second sentence to disagree with
 * the server's. {@code QuestionEditorSession} set the precedent of a screen importing a server
 * validator, and for the same reason.
 *
 * <h2>The picker, and the one field it waited on</h2>
 *
 * <p>{@link #addFromBank} was <b>unbuildable until 2026-08-26</b> and this javadoc said so: it
 * needs a {@code questionVersionId} to pin, the contract's §3 names {@code BANK_LIST} as the
 * picker, and nothing on the frozen bank wire carried that id. Found by reading the two contracts
 * against each other before building on either, and raised with the lead, whose tree
 * {@code common/dto/bank} is. BANK amendment A1 added {@code BankQuestionRow.latestVersionId} and
 * the whole path binds to it.
 *
 * <p>It was <b>one method</b> on purpose (method rule 3), and adopting the field cost exactly the
 * one edit that was predicted rather than a sweep through everything that wanted to add a
 * question.
 *
 * <h2>The picker refuses a duplicate before the server has to ⚑</h2>
 *
 * <p>§5.2 and T-3.9: a question cannot appear twice in one exam version, <em>even through two
 * different versions of it</em>. So the comparison is on {@code displayId5}, the question's own
 * identity, and never on {@code questionVersionId}, which is the identity of one version of it.
 * Comparing version ids would let 11005 v1 and 11005 v2 both onto the paper and hand her a
 * refusal from the server on save, about a click she made ten minutes earlier.
 *
 * <p>The server still checks, and is still the guarantee: {@code ExamValidator.compositionProblem}
 * and {@code uq_exam_version_questions_question} both stand behind this. What this adds is that
 * the refusal arrives on the click that caused it.
 */
public final class ExamBuilderSession {

    /** What this screen is doing, derived and never assigned. */
    public enum Mode {

        /** No version id: a new exam, which {@code EXAM_CREATE} writes whole. */
        CREATE,

        /** A version id that came back a {@code DRAFT}: {@code EXAM_VERSION_SAVE} updates it. */
        EDIT,

        /** A version id that came back anything else: rendered, and every edit refused. */
        READ_ONLY
    }

    /**
     * One line of the paper as the builder holds it.
     *
     * <p>Deliberately not {@link ComposedQuestion}: that record carries {@code ord}, and holding
     * an ordinal <em>inside</em> each row beside a list that also has an order is two expressions
     * of one fact. Here the list index <b>is</b> the order, and {@code ord} is written once, on
     * the way out, in {@link #pins()}. A reorder is then a list operation that cannot leave a row
     * disagreeing with its position.
     *
     * @param questionVersionId the exact bank version pinned onto the paper
     * @param displayId5        the id staff quote (S-8)
     * @param text              the stem, already truncated server-side
     * @param topic             the pinned version's topic
     * @param difficulty        the pinned version's difficulty
     * @param hasImage          whether it carries an illustration
     * @param pinnedVersionNo   the bank version this paper is pinned to
     * @param latestVersionNo   the newest the bank now holds; greater means E7.7's badge
     * @param latestVersionId   the id of that newest version, which is what E7.14 re-pins to.
     *                          The number draws the badge; only the id can press it
     * @param points            what it is worth, 1..100
     */
    public record Line(long questionVersionId, String displayId5, String text, String topic,
                       Difficulty difficulty, boolean hasImage, int pinnedVersionNo,
                       int latestVersionNo, long latestVersionId, int points,
                       boolean showsSupersededDetails) {

        /**
         * A line as it arrives from the server, which by definition shows what it pins.
         *
         * <p>Every construction site except {@link #updatedToLatest()} goes through here, so the
         * stale marker is false unless something deliberately made it true.
         */
        @SuppressWarnings("checkstyle:ParameterNumber")
        public static Line fromServer(long questionVersionId, String displayId5, String text,
                                      String topic, Difficulty difficulty, boolean hasImage,
                                      int pinnedVersionNo, int latestVersionNo,
                                      long latestVersionId, int points) {
            return new Line(questionVersionId, displayId5, text, topic, difficulty, hasImage,
                    pinnedVersionNo, latestVersionNo, latestVersionId, points, false);
        }

        /** @return the same line worth a different number of points. */
        public Line withPoints(int newPoints) {
            return new Line(questionVersionId, displayId5, text, topic, difficulty, hasImage,
                    pinnedVersionNo, latestVersionNo, latestVersionId, newPoints,
                    showsSupersededDetails);
        }

        /**
         * The same question, re-pinned to the bank's newest version of it (E7.14).
         *
         * <p><b>The stem, topic, difficulty and image marker are deliberately carried over
         * unchanged</b>, and they belong to the version being left behind. This screen has never
         * read the new version's content: {@code ComposedQuestion} carries the newer version's id
         * and number and none of its text, which is the whole shape of E7.7. Re-reading it here
         * would cost a round trip and would still not be authoritative, because the save is a
         * full replace whose answer is the server's own re-read.
         *
         * <p>So the row tells the truth about <em>which</em> version it now pins, and shows the
         * old wording until {@code settleSave} adopts the server's answer. The window is between
         * her click and her save, on her own screen, and {@code ExamBuildCopy.REPINNED_NOTICE}
         * says so rather than leaving her to notice.
         *
         * @return the re-pinned line, or {@code this} when the bank has not moved on
         */
        public Line updatedToLatest() {
            if (!hasNewerVersion()) {
                return this;
            }
            return new Line(latestVersionId, displayId5, text, topic, difficulty, hasImage,
                    latestVersionNo, latestVersionNo, latestVersionId, points, true);
        }

        /** @return {@code true} when the bank has moved on from what this paper pins (E7.7). */
        public boolean hasNewerVersion() {
            return latestVersionNo > pinnedVersionNo;
        }
    }

    /**
     * The course-wide row, which is always index 0 and is never removed.
     *
     * <p>A blank topic is what makes a quota course-wide on the wire
     * ({@code TopicQuota.isCourseWide}), so the grid's first row is that quota and every other
     * row is a topic. Keeping it at a fixed index means "which row is the course-wide one" has
     * one answer rather than a search, and §7.3a's rule is about exactly that row.
     */
    private static final Criterion COURSE_WIDE_ROW = new Criterion(null, 0, 0, 0, 0);

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };

    // --- what it was opened with ----------------------------------------
    private long openedVersionId;
    private String courseCode;

    // --- the load --------------------------------------------------------
    private AsyncViewState loadState = AsyncViewState.IDLE;
    private int loadGeneration;
    private String loadError;
    private ApprovalState loadedState;
    private long examVersionId;
    private int lockVersion;
    private String displayId6 = "";
    private String courseName = "";

    // --- the form --------------------------------------------------------
    private String name = "";
    private int durationMinutes = 60;
    private String studentText = "";
    private String teacherText = "";
    private final List<Line> lines = new ArrayList<>();

    // --- the bank picker (E7.12) ------------------------------------------
    private boolean pickerOpen;
    private AsyncViewState pickerState = AsyncViewState.IDLE;
    private int pickerGeneration;
    private String pickerError;
    private String pickerSearch = "";
    private final List<BankQuestionRow> bank = new ArrayList<>();

    // --- the save --------------------------------------------------------
    // --- the auto tab (E7.13) ---------------------------------------------
    private Tab tab = Tab.MANUAL;
    private final List<Criterion> criteria = new ArrayList<>(List.of(COURSE_WIDE_ROW));
    private boolean composing;
    private int composeGeneration;
    private String composeError;
    private String composeNotice;
    private List<Shortfall> shortfalls = List.of();

    private boolean saving;
    private String saveError;
    private String saveNotice;
    private boolean saved;

    /** Set while another teacher holds this version's edit lock (E18.5). */
    private boolean lockedOut;

    /**
     * @param dispatcher the request correlator
     * @param poster     the single FX-thread hop; {@code DirectFxThreadPoster} in tests
     */
    public ExamBuilderSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public ExamBuilderSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== Opening ========================================

    /**
     * Opens the builder on a new exam (E7.11).
     *
     * <p>Nothing is fetched: a new exam has nothing to read back, and the whole thing is written
     * by one {@code EXAM_CREATE} carrying the composition. The course is fixed from here on and
     * is what §5.2 scopes every question to.
     *
     * @param course the course to file the exam under, from the caller's taught set
     */
    public void openNew(String course) {
        resetLoaded();
        this.openedVersionId = 0;
        this.courseCode = course;
        this.loadState = AsyncViewState.READY;
        this.loadError = null;
        this.durationMinutes = ExamBuildCopy.DEFAULT_DURATION_MINUTES;
        onChange.run();
    }

    /**
     * Wipes everything an earlier open left behind, and retires its answer.
     *
     * <p>Both entry points call it, and both halves matter. <b>Bumping the generation</b> is what
     * makes an answer already in flight land on nothing; <b>clearing the fields</b> is what stops
     * the previous exam's id, token, name, texts and composition being visible under a new
     * heading. A cold read found both: opening B while A was still loading committed B's id and
     * then dropped the request, so A's answer arrived and was adopted under B's identity, and
     * {@code openNew} left {@code examVersionId}, {@code lockVersion}, {@code displayId6},
     * {@code courseName} and {@code saved} from whatever was open before.
     *
     * <p>{@link ExamListSession} in this same package has carried a generation counter from the
     * day it was written and this class shipped without one, which is the more useful half of
     * that finding.
     *
     * <h2>ALL THREE counters, and every field an earlier exam touched ⚑</h2>
     *
     * <p><b>The paragraph above was true of the load and false of everything added after it</b>,
     * which a second cold read found. This class now has three in-flight answers - the version
     * load, the picker's bank pages and the compose - and a reset that bumps two of three is a
     * reset that reads as complete. The compose was the dangerous one: {@link #settleCompose}
     * guards on its counter and nothing else, so a proposal for the exam she left arrived, called
     * {@code lines.clear()}, filled the paper of the exam she had just opened, and announced it
     * with a success toast. The screen is cached and reused across navigations, so it was two
     * clicks away.
     *
     * <p>Whatever is added next: if it can be in flight, its counter belongs here, and if it is a
     * field the previous exam wrote, it belongs in the clearing half. {@link #tab} and
     * {@link #criteria} are here for the second reason - the auto tab survived into a read-only
     * version and hid the paper she had opened to read, with the tab switch withheld because the
     * version is not editable, and criteria built for one course named a topic in a report about
     * another.
     */
    private void resetLoaded() {
        loadGeneration++;
        composeGeneration++;
        // The picker belongs to the version that was open: its rows are that exam's course, and
        // isOnPaper() reads a paper that is about to be replaced. Leaving it up across an open()
        // would offer her another exam's bank against this exam's duplicate rule.
        closePicker();
        tab = Tab.MANUAL;
        criteria.clear();
        criteria.add(COURSE_WIDE_ROW);
        composing = false;
        composeError = null;
        composeNotice = null;
        shortfalls = List.of();
        examVersionId = 0;
        lockVersion = 0;
        // Belongs to the version being left, exactly like examVersionId. Carrying it across an
        // open() would render the next exam read-only under a banner naming a teacher who holds
        // a different row, and the view's syncLock only opens the new lock after this returns.
        lockedOut = false;
        loadedState = null;
        displayId6 = "";
        courseName = "";
        name = "";
        studentText = "";
        teacherText = "";
        lines.clear();
        saved = false;
        saveError = null;
        saveNotice = null;
    }

    /**
     * Opens the builder on a stored version, for editing or for reading (E7.11, §8's read path).
     *
     * <p>Which of the two it turns out to be is not decided here and is not the caller's to say:
     * {@code EXAM_VERSION_GET} answers with the version's {@code state} and {@link #modeFor}
     * reads it. A screen that took "open this read-only" as a parameter could be told the wrong
     * thing by a stale list.
     *
     * @param examVersionId the version to open
     */
    public void open(long examVersionId) {
        resetLoaded();
        this.openedVersionId = examVersionId;
        this.courseCode = null;
        loadState = AsyncViewState.LOADING;
        loadError = null;
        int generation = loadGeneration;
        onChange.run();

        dispatcher.send(Verb.EXAM_VERSION_GET, new ExamVersionRequest(examVersionId))
                .whenComplete((response, failure) ->
                        poster.run(() -> settleLoad(generation, response, failure)));
    }

    /**
     * Takes an answer only if it is still the answer to the question being asked.
     *
     * <p>Without the generation check, opening B while A is in flight adopts A's whole
     * composition under B's id: the form is editable if A was a draft, and Save writes to A with
     * A's token, while she believes she is editing B. There is no error anywhere on that path,
     * which is what makes it worth a counter rather than an ordering rule.
     */
    private void settleLoad(int generation, Message response, Throwable failure) {
        if (generation != loadGeneration) {
            return;
        }
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof ExamComposition version)) {
            loadError = ExamBuildCopy.LOAD_FAILED;
            loadState = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        adopt(version);
        loadError = null;
        loadState = AsyncViewState.READY;
        onChange.run();
    }

    /** Takes the server's answer as the new truth, including the token the next write sends. */
    private void adopt(ExamComposition version) {
        examVersionId = version.examVersionId();
        lockVersion = version.lockVersion();
        loadedState = version.state();
        courseCode = version.courseCode();
        courseName = version.courseName();
        displayId6 = version.displayId6();
        name = version.name();
        durationMinutes = version.durationMinutes();
        studentText = version.studentText() == null ? "" : version.studentText();
        teacherText = version.teacherText() == null ? "" : version.teacherText();
        // The server's re-read IS the refresh the notice promised: every row now carries the
        // wording of the version it actually pins, so the promise has been kept and the notice
        // has nothing left to say.
        lines.clear();
        for (ComposedQuestion question : version.questions()) {
            lines.add(Line.fromServer(question.questionVersionId(), question.questionDisplayId5(),
                    question.text(), question.topic(), question.difficulty(), question.hasImage(),
                    question.pinnedVersionNo(), question.latestVersionNo(),
                    question.latestVersionId(), question.points()));
        }
    }

    /**
     * Asks for the open version again (E7.11).
     *
     * <p>Two callers and one behaviour. It is the retry beside a failed load, and it is the way
     * out of a stale-token refusal: after a {@code CONFLICT} the token this screen holds is dead,
     * so pressing Save again re-sends it and reads the same sentence forever. The server's own
     * message says "open it again"; without this there was nothing that did.
     *
     * <p>Does nothing on a new exam, which has no stored version to re-read.
     */
    public void reopen() {
        if (openedVersionId <= 0) {
            return;
        }
        open(openedVersionId);
    }

    /**
     * The whole mode rule, in one expression.
     *
     * @param openedWith the version id the screen was opened with, or {@code 0}
     * @param state      the state the server answered with, or {@code null} before it has
     * @return which of the three things this screen is doing
     */
    static Mode modeFor(long openedWith, ApprovalState state) {
        if (openedWith <= 0) {
            return Mode.CREATE;
        }
        return state == ApprovalState.DRAFT ? Mode.EDIT : Mode.READ_ONLY;
    }

    /** @return what this screen is doing, derived from the answer and never assigned. */
    public Mode mode() {
        return modeFor(openedVersionId, loadedState);
    }

    /** @return {@code true} when the form accepts changes at all. */
    public boolean isEditable() {
        return mode() != Mode.READ_ONLY && !lockedOut;
    }

    /**
     * Records that another teacher holds this version's edit lock (E18.5).
     *
     * <p>Routed through {@link #isEditable()} rather than through the Save button alone, because
     * this is the gate every mutator already asks: {@code name}, {@code duration}, the points
     * and the paper edits all refuse when it answers false. Disabling Save on its own would
     * leave the form writable underneath a banner saying it is not, and the refusal would arrive
     * only at the end, which is the whole defect E18.5 exists to close.
     *
     * <p>The server refuses a locked write regardless ({@code CONFLICT}, contract §624), so this
     * is the courtesy and that is the rule. It is not the guard.
     *
     * <p>Guards on an unchanged value before notifying: {@code onChange} re-renders, the render
     * re-reads the lock, and a setter that fired unconditionally would turn every lock heartbeat
     * into a repaint.
     *
     * @param locked whether somebody else is holding it
     */
    public void setLockedOut(boolean locked) {
        if (lockedOut == locked) {
            return;
        }
        lockedOut = locked;
        onChange.run();
    }

    /** @return whether another teacher's lock is holding this builder read-only */
    public boolean isLockedOut() {
        return lockedOut;
    }

    // ===================== The metadata form (E7.11) ======================

    /** Sets the exam's name; refused outright in {@link Mode#READ_ONLY}. */
    public void name(String value) {
        if (!isEditable()) {
            return;
        }
        this.name = value == null ? "" : value;
        onChange.run();
    }

    /** Sets the sitting length in minutes; refused in {@link Mode#READ_ONLY}. */
    public void durationMinutes(int value) {
        if (!isEditable()) {
            return;
        }
        this.durationMinutes = value;
        onChange.run();
    }

    /** Sets the student-facing instructions; refused in {@link Mode#READ_ONLY}. */
    public void studentText(String value) {
        if (!isEditable()) {
            return;
        }
        this.studentText = value == null ? "" : value;
        onChange.run();
    }

    /** Sets the teacher-only notes; refused in {@link Mode#READ_ONLY}. */
    public void teacherText(String value) {
        if (!isEditable()) {
            return;
        }
        this.teacherText = value == null ? "" : value;
        onChange.run();
    }

    public String name() {
        return name;
    }

    public int durationMinutes() {
        return durationMinutes;
    }

    public String studentText() {
        return studentText;
    }

    public String teacherText() {
        return teacherText;
    }

    /** @return the course this exam is filed under, which §5.2 scopes every question to. */
    public String courseCode() {
        return courseCode;
    }

    public String courseName() {
        return courseName;
    }

    /** @return the 6-digit id staff quote, or {@code ""} before the exam exists (S-10). */
    public String displayId6() {
        return displayId6;
    }

    // ===================== The paper (E7.12) ==============================

    /** @return the paper in order; the list index is the position and the only one there is. */
    public List<Line> lines() {
        return List.copyOf(lines);
    }

    /**
     * Changes what one question is worth.
     *
     * @param index  its position on the paper
     * @param points the new value; stored as typed and judged by {@link #pointsProblem()}, so
     *               the sentence she reads is the server's rather than a second opinion
     */
    public void points(int index, int points) {
        if (!isEditable() || index < 0 || index >= lines.size()) {
            return;
        }
        lines.set(index, lines.get(index).withPoints(points));
        onChange.run();
    }

    /** Moves one question one place towards the front of the paper. */
    public void moveUp(int index) {
        swap(index, index - 1);
    }

    /** Moves one question one place towards the back of the paper. */
    public void moveDown(int index) {
        swap(index, index + 1);
    }

    private void swap(int from, int to) {
        if (!isEditable() || from < 0 || to < 0 || from >= lines.size() || to >= lines.size()) {
            return;
        }
        Line moved = lines.get(from);
        lines.set(from, lines.get(to));
        lines.set(to, moved);
        onChange.run();
    }

    /** Takes one question off the paper. */
    public void remove(int index) {
        if (!isEditable() || index < 0 || index >= lines.size()) {
            return;
        }
        lines.remove(index);
        onChange.run();
    }

    // ===================== The bank picker (E7.12) ========================

    /**
     * How many bank pages the picker asks for before it gives up.
     *
     * <p>{@code BANK_LIST} is paginated because a teacher's own browse is (E6.5); the picker is
     * not, so it asks page after page and shows one list. {@code DataSession} set that precedent
     * and set this bound with it: a loop that asks a server for pages until the server says stop
     * is a loop, and an unbounded one is a client that hangs on a server answering nonsense. Far
     * above any real course bank, and the picker says so rather than silently truncating if it is
     * ever reached.
     */
    private static final int MAX_BANK_PAGES = 50;

    /**
     * Opens the picker and loads this exam's course bank (E7.12, §3).
     *
     * <p>Scoped to {@link #courseCode} and never to the whole bank: §5.2 refuses a question from
     * another course on save, so offering one here would be offering a click that cannot work.
     * Re-opening reloads, because the bank is another screen away and may have moved.
     */
    public void openPicker() {
        if (!isEditable()) {
            return;
        }
        pickerOpen = true;
        pickerSearch = "";
        loadBank();
    }

    /** Retries a failed bank load without closing the picker. */
    public void retryPicker() {
        if (pickerOpen) {
            loadBank();
        }
    }

    private void loadBank() {
        bank.clear();
        pickerError = null;
        pickerState = AsyncViewState.LOADING;
        pickerGeneration++;
        onChange.run();
        requestBankPage(pickerGeneration, 0);
    }

    /** Closes the picker and forgets what it loaded. */
    public void closePicker() {
        pickerOpen = false;
        pickerState = AsyncViewState.IDLE;
        pickerError = null;
        pickerSearch = "";
        bank.clear();
        // Bumped so a page still in flight cannot land in a picker she has closed and reopened.
        pickerGeneration++;
        onChange.run();
    }

    private void requestBankPage(int generation, int page) {
        dispatcher.send(Verb.BANK_LIST, new BankListRequest(courseCode, null, null, null,
                        page, BankListRequest.DEFAULT_PAGE_SIZE))
                .whenComplete((response, failure) ->
                        poster.run(() -> settleBankPage(generation, page, response, failure)));
    }

    /**
     * Takes a page only if it is still a page of the load being run.
     *
     * <p>The same generation guard {@link #settleLoad} carries and for the same reason: closing
     * the picker and opening it again while a page is in flight would otherwise append that page
     * to the new load's rows, so the list would hold a course she is no longer looking at.
     */
    private void settleBankPage(int generation, int page, Message response, Throwable failure) {
        if (generation != pickerGeneration || !pickerOpen) {
            return;
        }
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof BankPage bankPage)
                || bankPage.page() != page) {
            // The page number is checked, not assumed. Appending a page the server did not
            // send is how the same rows arrive twice and a whole page goes missing, with a
            // full-looking list and nothing failing. Found by a fixture that answered every
            // request with page 0 while claiming two pages existed.
            bank.clear();
            pickerError = ExamBuildCopy.PICKER_LOAD_FAILED;
            pickerState = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        bank.addAll(bankPage.rows());

        boolean more = page + 1 < bankPage.totalPages();
        if (more && page + 1 < MAX_BANK_PAGES) {
            requestBankPage(generation, page + 1);
            return;
        }
        pickerError = more ? ExamBuildCopy.PICKER_TOO_MANY : null;
        pickerState = AsyncViewState.READY;
        onChange.run();
    }

    /** @return whether the picker is showing. */
    public boolean isPickerOpen() {
        return pickerOpen;
    }

    /** @return where the picker's load has got to. */
    public AsyncViewState pickerState() {
        return pickerState;
    }

    /** @return what went wrong loading the bank, when something did. */
    public Optional<String> pickerError() {
        return Optional.ofNullable(pickerError);
    }

    /** @param typed the free text the picker is filtered by. */
    public void pickerSearch(String typed) {
        pickerSearch = typed == null ? "" : typed;
        onChange.run();
    }

    /** @return the current picker filter. */
    public String pickerSearch() {
        return pickerSearch;
    }

    /**
     * The rows the picker is offering, filtered.
     *
     * <p>Filtered here rather than by {@code BankListRequest.search} because the whole course bank
     * is already in hand: a round trip per keystroke would buy nothing and would make the list
     * flicker between answers. The match is over the three things visible on the row.
     *
     * @return the matching rows, in the order the bank gave them
     */
    public List<BankQuestionRow> pickerRows() {
        String needle = pickerSearch.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.copyOf(bank);
        }
        List<BankQuestionRow> matching = new ArrayList<>();
        for (BankQuestionRow row : bank) {
            if (matches(row, needle)) {
                matching.add(row);
            }
        }
        return List.copyOf(matching);
    }

    private static boolean matches(BankQuestionRow row, String needle) {
        return contains(row.displayId5(), needle)
                || contains(row.text(), needle)
                || contains(row.topic(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * Whether this question is already on the paper (§5.2, T-3.9).
     *
     * <p>On {@code displayId5}, the question's identity, and never on a version id. See the class
     * javadoc: two versions of one question are one question as far as this rule is concerned.
     *
     * @param row a row the picker is offering
     * @return {@code true} when adding it would be adding it twice
     */
    public boolean isOnPaper(BankQuestionRow row) {
        if (row == null) {
            return false;
        }
        for (Line line : lines) {
            if (Objects.equals(line.displayId5(), row.displayId5())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts a bank question on the paper (E7.12).
     *
     * <p>The row is pinned at {@link BankQuestionRow#latestVersionId()}, so the paper carries the
     * exact version she was looking at when she clicked. That is what makes E7.7's badge a drift
     * detector afterwards: the pin does not move, the bank does, and the two version numbers are
     * then a comparison rather than a coincidence. Pinned and latest are equal on the way in,
     * which is why a freshly added question never carries a badge.
     *
     * <p>Points start at {@link QuestionPin#MIN_POINTS} rather than a share of 100. An even split
     * would silently rewrite numbers she had already set on the other questions, and §5.1's
     * indicator plus its sentence are what tell her how far off the total is. Cheap to reverse: a
     * constant on this line.
     *
     * @param row the row she clicked, or {@code null}
     * @return {@code true} when the paper grew, {@code false} when the click was refused
     */
    public boolean addFromBank(BankQuestionRow row) {
        if (row == null || !isEditable() || isOnPaper(row)) {
            return false;
        }
        lines.add(Line.fromServer(row.latestVersionId(), row.displayId5(), row.text(), row.topic(),
                row.difficulty(), row.hasImage(), row.latestVersionNo(), row.latestVersionNo(),
                row.latestVersionId(), QuestionPin.MIN_POINTS));
        onChange.run();
        return true;
    }

    // ===================== The auto tab (E7.13, F3.3) =====================

    /**
     * Which half of the builder is showing.
     *
     * <p>In the session rather than the view because the auto tab is not a panel, it is a way of
     * filling the same paper: {@link #generate()} writes into {@link #lines}, which is the list
     * the manual tab edits. F3.3's "auto-result is editable before save" is that sharing and
     * nothing else - there is no second composition anywhere in this class to keep in step.
     */
    public enum Tab {

        /** The bank picker and the paper (E7.12). */
        MANUAL,

        /** The criteria form (E7.13). */
        AUTO
    }

    /** One row of the criteria grid, as she is typing it. */
    public record Criterion(String topic, int easy, int medium, int hard, int any) {

        /**
         * This row as the wire's quota, told by its position which kind of row it is.
         *
         * <p><b>An unnamed row cannot be expressed on this wire, and that is the whole reason
         * {@link #isUnnamed()} exists.</b> {@code TopicQuota}'s own compact constructor folds a
         * blank topic to {@code null}, and a null topic <em>is</em> the course-wide bucket, so a
         * half-typed topic row becomes a second course-wide quota no matter what this method
         * passes. That record is the lead's and is not Member A's to change.
         *
         * <p>So the defence is upstream: {@link #criteriaProblem()} refuses while any row asks for
         * questions without naming a topic, and such a row is never sent. The parameter here does
         * not add a guarantee - it makes the call site say which row it thinks it is holding,
         * which is worth one boolean and is not a substitute for the refusal.
         *
         * <p><b>An earlier version of this javadoc claimed the parameter made the bad state
         * unreachable. It does not, and a test written against that claim failed the moment it
         * was run</b>, which is the only reason the claim is not still standing here.
         *
         * @param courseWide whether this is the grid's first row
         * @return the quota; a blank topic still arrives course-wide, hence the guard upstream
         */
        public TopicQuota toQuota(boolean courseWide) {
            if (courseWide) {
                return new TopicQuota(null, easy, medium, hard, any);
            }
            return new TopicQuota(topic == null ? "" : topic, easy, medium, hard, any);
        }

        /** @return {@code true} when this row asks for nothing at all. */
        public boolean isEmpty() {
            return easy == 0 && medium == 0 && hard == 0 && any == 0;
        }

        /** @return {@code true} when she has asked for questions without naming the topic. */
        public boolean isUnnamed() {
            return (topic == null || topic.isBlank()) && !isEmpty();
        }

        /** @return the same row with one bucket changed, named by {@link Bucket}. */
        public Criterion with(Bucket bucket, int value) {
            return switch (bucket) {
                case EASY -> new Criterion(topic, value, medium, hard, any);
                case MEDIUM -> new Criterion(topic, easy, value, hard, any);
                case HARD -> new Criterion(topic, easy, medium, value, any);
                case ANY -> new Criterion(topic, easy, medium, hard, value);
            };
        }

        /** @return the same row under a different topic. */
        public Criterion withTopic(String newTopic) {
            return new Criterion(newTopic, easy, medium, hard, any);
        }
    }

    /** The four numbers on a criteria row, so the view names a bucket rather than a position. */
    public enum Bucket { EASY, MEDIUM, HARD, ANY }

    /**
     * Moves between the two tabs.
     *
     * <p>Refused on a read-only version, which has no criteria form: §8's read path renders a
     * finished paper and offers no way to compose one.
     *
     * @param wanted the tab she clicked
     */
    public void tab(Tab wanted) {
        if (wanted == null || (wanted == Tab.AUTO && !isEditable())) {
            return;
        }
        tab = wanted;
        onChange.run();
    }

    /** @return which tab is showing. */
    public Tab tab() {
        return tab;
    }

    /** @return the criteria grid as she has it, course-wide row first. */
    public List<Criterion> criteria() {
        return List.copyOf(criteria);
    }

    /**
     * Retires the last answer, because it was about criteria that no longer exist ⚑.
     *
     * <p>Called by every criteria mutator. {@code INFEASIBLE_TITLE} reads "the bank cannot satisfy
     * <em>these</em> criteria" and points at the grid directly above it, so a report left standing
     * while she edits the grid is a sentence about numbers that are no longer on screen. The demo
     * path is exactly that: T-3.5 asks for three, is refused, and T-3.6 edits it down - and the
     * red block went on naming three.
     *
     * <p>Same shape as {@code hasRepinned}, fixed one round earlier in this same PR: a stored
     * artefact outliving the state it describes. That one became derived; this one cannot, since
     * only the server can say what is short, so it is retired at the moment its question changes.
     */
    private void retireLastAnswer() {
        shortfalls = List.of();
        composeError = null;
    }

    /** @param index the row; @param topic what she typed into its topic box. */
    public void criterionTopic(int index, String topic) {
        if (index <= 0 || index >= criteria.size()) {
            // Index 0 is the course-wide row and has no topic to set. Guarding here rather than
            // hiding the box is what keeps "the first row is the course-wide one" a single fact.
            return;
        }
        criteria.set(index, criteria.get(index).withTopic(topic));
        retireLastAnswer();
        onChange.run();
    }

    /**
     * @param index  the row
     * @param bucket which of its four numbers
     * @param value  what she typed; stored as typed and judged by {@link #criteriaProblem()},
     *               so a negative reaches the same rule the server would refuse it with
     */
    public void criterionCount(int index, Bucket bucket, int value) {
        if (index < 0 || index >= criteria.size() || bucket == null) {
            return;
        }
        criteria.set(index, criteria.get(index).with(bucket, value));
        retireLastAnswer();
        onChange.run();
    }

    /** Adds a blank topic row. */
    public void addCriterion() {
        if (!isEditable()) {
            return;
        }
        criteria.add(new Criterion("", 0, 0, 0, 0));
        retireLastAnswer();
        onChange.run();
    }

    /** Removes a topic row. The course-wide row at index 0 cannot be removed. */
    public void removeCriterion(int index) {
        if (index <= 0 || index >= criteria.size()) {
            return;
        }
        criteria.remove(index);
        retireLastAnswer();
        onChange.run();
    }

    /**
     * The criteria as the wire wants them, which is also what the live rule is asked about.
     *
     * @return the request {@code EXAM_AUTO_COMPOSE} would carry right now
     */
    private AutoComposeRequest request() {
        List<TopicQuota> quotas = new ArrayList<>(criteria.size());
        for (int index = 0; index < criteria.size(); index++) {
            Criterion each = criteria.get(index);
            // A topic row asking for nothing is not sent at all. The grid always carries blank
            // rows and the server has a rule for ignoring them, but not sending one is simpler
            // than relying on that rule, and it keeps the request equal to what she asked for.
            if (index > 0 && each.isEmpty()) {
                continue;
            }
            quotas.add(each.toQuota(index == 0));
        }
        // No seed. §7.5 keeps it for tests; a client that sent one would be asking the server to
        // be predictable in front of a class, which is the opposite of what it is for.
        return new AutoComposeRequest(courseCode, quotas, null);
    }

    /**
     * The server's own sentence about the criteria, live (§7.3a).
     *
     * <p>{@code ExamValidator.quotaProblem} is the very method the handler refuses with, called
     * here for the reason {@link #pointsProblem()} calls {@code pointsProblem}: one rule, one
     * sentence, and no client-side second opinion that can drift from it. §7.3a's refusal has to
     * name both legal shapes, and it does - the wording is {@code ExamBuildMessages}' and this
     * class composes none of it (ruling 4).
     *
     * @return what is wrong with the grid, or empty when it is a request the server would take
     */
    public Optional<String> criteriaProblem() {
        // One rule this client owns, and it owns it because the server cannot see the state:
        // a row with counts and no topic is never SENT, so no server sentence exists for it, and
        // ruling 4's "the client composes nothing" is about rules ExamBuildMessages states. This
        // is a half-filled form rather than a §7 rule, and saying so beats sending her a request
        // that would be answered about a course she did not mean to ask about.
        for (int index = 1; index < criteria.size(); index++) {
            if (criteria.get(index).isUnnamed()) {
                return Optional.of(ExamBuildCopy.TOPIC_REQUIRED);
            }
        }
        return ExamValidator.quotaProblem(request()).map(ExamValidator.Violation::message);
    }

    /** @return {@code true} when Generate is worth offering. */
    public boolean canGenerate() {
        return isEditable() && !composing && criteriaProblem().isEmpty();
    }

    /**
     * Asks the server to compose a paper from the criteria (E7.13, F3.3).
     *
     * <p>A feasible answer <b>replaces the paper</b> and drops her on the manual tab, because
     * F3.3's "editable before save" is only a claim if she can see the thing she may edit. It is
     * destructive of an unsaved composition and {@code ExamBuildCopy.GENERATE_REPLACES} says so
     * beside the button rather than after the fact; the draft on the server is untouched until
     * she saves, so reopening is the way back.
     *
     * <p>An infeasible answer changes nothing at all. §7.2's whole point is that the report is
     * the useful outcome, so it is rendered and the paper she had is still there.
     */
    public void generate() {
        if (!canGenerate()) {
            return;
        }
        composing = true;
        composeError = null;
        shortfalls = List.of();
        composeGeneration++;
        int generation = composeGeneration;
        onChange.run();

        dispatcher.send(Verb.EXAM_AUTO_COMPOSE, request())
                .whenComplete((response, failure) ->
                        poster.run(() -> settleCompose(generation, response, failure)));
    }

    /** Takes an answer only if it is still the answer to the criteria she last sent. */
    private void settleCompose(int generation, Message response, Throwable failure) {
        if (generation != composeGeneration) {
            return;
        }
        composing = false;
        if (failure != null || response == null) {
            composeError = ExamBuildCopy.COMPOSE_FAILED;
            onChange.run();
            return;
        }
        if (response.isError()) {
            String sentence = response.errorMessage();
            composeError = sentence == null || sentence.isBlank()
                    ? ExamBuildCopy.COMPOSE_FAILED : sentence;
            onChange.run();
            return;
        }
        if (!(response.getPayload() instanceof AutoComposeResult result)) {
            composeError = ExamBuildCopy.COMPOSE_FAILED;
            onChange.run();
            return;
        }
        if (!result.feasible()) {
            shortfalls = List.copyOf(result.shortfalls());
            onChange.run();
            return;
        }
        adoptProposal(result.questions());
        onChange.run();
    }

    private void adoptProposal(List<ComposedQuestion> proposed) {
        shortfalls = List.of();
        composeNotice = ExamBuildCopy.composedNotice(proposed.size());
        lines.clear();
        for (ComposedQuestion question : proposed) {
            lines.add(Line.fromServer(question.questionVersionId(), question.questionDisplayId5(),
                    question.text(), question.topic(), question.difficulty(), question.hasImage(),
                    question.pinnedVersionNo(), question.latestVersionNo(),
                    question.latestVersionId(), question.points()));
        }
        tab = Tab.MANUAL;
    }

    /** @return {@code true} while the server is composing. */
    public boolean isComposing() {
        return composing;
    }

    /** @return why a compose failed outright, as opposed to being infeasible. */
    public Optional<String> composeError() {
        return Optional.ofNullable(composeError);
    }

    /**
     * @return the shortfalls from the last infeasible answer, empty otherwise. Rendered into
     *         sentences by {@code ExamBuildCopy.shortfallLine}, which is where §7.1's four shapes
     *         and ruling 4's "the sentence stays on the client" both live
     */
    public List<Shortfall> shortfalls() {
        return shortfalls;
    }

    /** @return what a successful compose did, once, until dismissed. */
    public Optional<String> composeNotice() {
        return Optional.ofNullable(composeNotice);
    }

    /** Clears the composed notice, the way {@link #dismissNotice()} clears the saved one. */
    public void dismissComposeNotice() {
        composeNotice = null;
        onChange.run();
    }

    // ===================== The update action (E7.14) ======================

    /**
     * Re-pins one question to the bank's newest version of it (E7.14, F3.5).
     *
     * <p>The other half of E7.7. The badge says the bank has moved on; this is the button beside
     * it, and it was unbuildable until {@code ComposedQuestion.latestVersionId} landed on
     * 2026-08-26: a version <em>number</em> says that something newer exists and not what to pin.
     *
     * <p>No verb of its own. The composition is saved by full replace (§5.2), so re-pinning is a
     * change to this list like a reorder or a repoint, and it reaches the database through the
     * same {@code EXAM_VERSION_SAVE} she was going to press anyway. A dedicated verb would be a
     * second write path into one composition, and §5.6's one-transaction-per-write rule exists to
     * stop exactly that.
     *
     * <p>Refuses silently on a question the bank has not moved past, rather than writing the pin
     * it already holds: see {@link Line#updatedToLatest()} for what the row does and does not
     * know about the version it moves to.
     *
     * @param index the row's position on the paper
     * @return {@code true} when a pin actually moved
     */
    public boolean updateToLatest(int index) {
        if (!isEditable() || index < 0 || index >= lines.size()) {
            return false;
        }
        Line line = lines.get(index);
        if (!line.hasNewerVersion()) {
            return false;
        }
        lines.set(index, line.updatedToLatest());
        onChange.run();
        return true;
    }

    /**
     * Whether any row on this paper is showing a superseded version's details ⚑.
     *
     * <p><b>Derived from the rows, never a flag anybody sets.</b> It was a flag until a cold read
     * showed a flag cannot be right: composing replaces every line with server-proposed rows and
     * removing the re-pinned row removes the only thing the notice was about, and in both cases a
     * stored boolean went on claiming the paper held stale wording. Same argument as {@link Mode},
     * one field over: a fact derived from the rows cannot disagree with the rows.
     *
     * @return {@code true} while at least one line was re-pinned and not yet replaced by a
     *         server re-read, which is what licenses the notice
     */
    public boolean hasRepinned() {
        return lines.stream().anyMatch(Line::showsSupersededDetails);
    }

    /** @return {@code true} when this version can have questions added to it at all. */
    public boolean canAddFromBank() {
        return isEditable();
    }

    // ===================== The live points rule (E7.3, S-11) ==============

    /** @return what the paper currently adds up to. */
    public int pointsTotal() {
        return lines.stream().mapToInt(Line::points).sum();
    }

    /**
     * @return the server's own sentence about the points, when there is one
     * @see ExamValidator#pointsProblem(List) the very method a save is refused by
     */
    public Optional<String> pointsProblem() {
        return ExamValidator.pointsProblem(pins()).map(ExamValidator.Violation::message);
    }

    /** @return {@code true} when the points rule is satisfied, which is what turns it green. */
    public boolean pointsAreRight() {
        return pointsProblem().isEmpty();
    }

    /**
     * The paper as the wire wants it.
     *
     * <p><b>{@code ord} is written here and nowhere else</b>: it is the list index plus one, so a
     * question's position and its ordinal are one fact with one home. §4 says as much about the
     * wire ("{@code ord} is THE LIST INDEX and is not a field").
     *
     * @return one pin per line, in paper order
     */
    private List<QuestionPin> pins() {
        List<QuestionPin> pins = new ArrayList<>(lines.size());
        for (Line line : lines) {
            pins.add(new QuestionPin(line.questionVersionId(), line.points()));
        }
        return pins;
    }

    // ===================== Saving =========================================

    /**
     * Writes the exam, through whichever verb the mode calls for.
     *
     * <p>{@link Mode#CREATE} sends {@code EXAM_CREATE} with the whole composition;
     * {@link Mode#EDIT} sends {@code EXAM_VERSION_SAVE} with the version and the token it was
     * loaded against. {@link Mode#READ_ONLY} sends nothing at all, which is §5.4's rule predicted
     * rather than invited: the server answers {@code CONFLICT} for a save against a non-draft,
     * and a screen that offered the button anyway would be asking a question it knows the answer
     * to.
     */
    public void save() {
        if (saving || !isEditable()) {
            return;
        }
        saving = true;
        saveError = null;
        saveNotice = null;
        saved = false;
        onChange.run();

        Mode mode = mode();
        Verb verb = mode == Mode.CREATE ? Verb.EXAM_CREATE : Verb.EXAM_VERSION_SAVE;
        // The two texts are handed over exactly as typed. Both records normalise blank to null
        // in their own compact constructors (§4's inbound rule), so folding them here as well
        // would be a second expression of one rule - and a mutation round proved it: breaking
        // the local copy changed no behaviour and failed no test, because the record was doing
        // the work either way. One rule, one home.
        Object payload = mode == Mode.CREATE
                ? new ExamCreateRequest(courseCode, name, durationMinutes,
                        studentText, teacherText, pins())
                : new ExamVersionSave(examVersionId, lockVersion, name, durationMinutes,
                        studentText, teacherText, pins());

        dispatcher.send(verb, payload)
                .whenComplete((response, failure) ->
                        poster.run(() -> settleSave(response, failure)));
    }

    private void settleSave(Message response, Throwable failure) {
        saving = false;
        if (failure != null || response == null) {
            saveError = ExamBuildCopy.SAVE_FAILED;
            onChange.run();
            return;
        }
        if (response.isError()) {
            settleSaveError(response);
            return;
        }
        if (!(response.getPayload() instanceof ExamComposition stored)) {
            saveError = ExamBuildCopy.SAVE_FAILED;
            onChange.run();
            return;
        }
        // The answer is the server's re-read, token included, so the next save sends the token
        // the database actually holds rather than the one this screen started with.
        openedVersionId = stored.examVersionId();
        adopt(stored);
        saved = true;
        saveNotice = ExamBuildCopy.SAVED_NOTICE;
        onChange.run();
    }

    /**
     * Turns a refusal into a sentence.
     *
     * <p><b>The server's own sentence is kept whenever there is one</b>, on every code. Every
     * refusal this screen can provoke names a field or a question - the points shortfall by how
     * much, the duplicate by its display id, the deleted question by its display id, the stale
     * token by what to do next - and replacing any of those with a generic line throws away the
     * only part a teacher can act on. That lesson is E7.10's: a cold read found this screen's
     * sibling collapsing a CONFLICT that named the colleague holding the lock.
     */
    private void settleSaveError(Message response) {
        ErrorCode code = response.getErrorCode();
        String message = response.errorMessage();
        boolean hasSentence = message != null && !message.isBlank();
        if (hasSentence) {
            saveError = message;
            onChange.run();
            return;
        }
        saveError = code == ErrorCode.CONFLICT
                ? ExamBuildCopy.STALE_NOTICE
                : ExamBuildCopy.SAVE_FAILED;
        onChange.run();
    }

    // ===================== What the screen reads ==========================

    public AsyncViewState state() {
        return loadState;
    }

    public Optional<String> loadError() {
        return Optional.ofNullable(loadError);
    }

    public boolean isSaving() {
        return saving;
    }

    public Optional<String> saveError() {
        return Optional.ofNullable(saveError);
    }

    public Optional<String> saveNotice() {
        return Optional.ofNullable(saveNotice);
    }

    /** @return {@code true} once a save has landed, which is what lets the screen navigate away. */
    public boolean isSaved() {
        return saved;
    }

    /** @return the version this screen is editing, or {@code 0} before a create has landed. */
    public long examVersionId() {
        return examVersionId;
    }

    /** Clears the success notice once its toast has been shown. */
    public void dismissNotice() {
        if (saveNotice == null) {
            return;
        }
        saveNotice = null;
        onChange.run();
    }

    /** Clears the failure sentence once its toast has been shown. */
    public void dismissSaveError() {
        if (saveError == null) {
            return;
        }
        saveError = null;
        onChange.run();
    }
}
