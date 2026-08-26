package client.features.exambuild;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.approval.ApprovalState;
import common.dto.authoring.ComposedQuestion;
import common.dto.authoring.ExamComposition;
import common.dto.authoring.ExamCreateRequest;
import common.dto.authoring.ExamVersionRequest;
import common.dto.authoring.ExamVersionSave;
import common.dto.authoring.QuestionPin;
import common.dto.bank.Difficulty;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import server.features.exambuild.ExamValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The logic behind the exam builder (Presentation tier, E7.11 / E7.12 — F3.1, F3.5, S-11).
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
 * <h2>What is NOT here yet, and why it is one method ⚑</h2>
 *
 * <p>{@link #addFromBank} is the picker's half of E7.12 and it cannot be written yet.
 * {@code QuestionPin} needs a {@code questionVersionId}; the contract's §3 names {@code BANK_LIST}
 * as the picker; and nothing on the frozen bank wire carries that id - not
 * {@code BankQuestionRow}, not {@code QuestionDetail}, not {@code QuestionVersionDetail}. Found by
 * reading the two contracts against each other before building on them, and raised with the lead,
 * whose tree {@code common/dto/bank} is.
 *
 * <p>It is <b>one method</b> on purpose (method rule 3): the pending decision binds at a single
 * line, so adopting the field when it lands is one edit rather than a sweep through everything
 * that wanted to add a question. Editing an existing composition needs none of it, because
 * {@link ComposedQuestion} carries the id already.
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
     * @param points            what it is worth, 1..100
     */
    public record Line(long questionVersionId, String displayId5, String text, String topic,
                       Difficulty difficulty, boolean hasImage, int pinnedVersionNo,
                       int latestVersionNo, int points) {

        /** @return the same line worth a different number of points. */
        public Line withPoints(int newPoints) {
            return new Line(questionVersionId, displayId5, text, topic, difficulty, hasImage,
                    pinnedVersionNo, latestVersionNo, newPoints);
        }

        /** @return {@code true} when the bank has moved on from what this paper pins (E7.7). */
        public boolean hasNewerVersion() {
            return latestVersionNo > pinnedVersionNo;
        }
    }

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

    // --- the save --------------------------------------------------------
    private boolean saving;
    private String saveError;
    private String saveNotice;
    private boolean saved;

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
     */
    private void resetLoaded() {
        loadGeneration++;
        examVersionId = 0;
        lockVersion = 0;
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
        lines.clear();
        for (ComposedQuestion question : version.questions()) {
            lines.add(new Line(question.questionVersionId(), question.questionDisplayId5(),
                    question.text(), question.topic(), question.difficulty(), question.hasImage(),
                    question.pinnedVersionNo(), question.latestVersionNo(), question.points()));
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
        return mode() != Mode.READ_ONLY;
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

    /**
     * Puts a bank question on the paper (E7.12) — <b>not yet buildable, see the class javadoc</b>.
     *
     * <p>The single line the picker's whole "add" path binds at. It needs a
     * {@code questionVersionId} and the frozen bank wire carries none, so it refuses rather than
     * guessing: pinning "the latest version of question 11005" resolved at save time would break
     * the exact-version pin that E7.7's newer-version badge is a comparison against.
     *
     * <p>When {@code BankQuestionRow} grows the field, this method takes a row and appends a
     * {@link Line}, and nothing else in this class changes.
     *
     * @return {@code false}, always, until the bank wire can identify a version
     */
    public boolean addFromBank() {
        return false;
    }

    /** @return {@code true} when the picker's add path can work, which it cannot yet. */
    public boolean canAddFromBank() {
        return false;
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
