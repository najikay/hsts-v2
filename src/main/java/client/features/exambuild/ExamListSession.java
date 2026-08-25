package client.features.exambuild;

import client.events.ClientEventBus;
import client.events.FxThreadPoster;
import client.events.ServerPushEvent;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.authoring.ExamComposition;
import common.dto.authoring.ExamList;
import common.dto.authoring.ExamListRow;
import common.dto.authoring.ExamVersionAction;
import common.dto.authoring.ExamVersionRow;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.greenrobot.eventbus.Subscribe;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The logic behind the teacher's exam list (Presentation tier, E7.10 / E7.15 — F3.5, F3.6,
 * F4.2).
 *
 * <p>Everything the screen decides lives here and nothing else does: which exam is open, which
 * of its versions the panel is describing, whether a rejection reason is worth a panel, which
 * buttons a version's state permits, and what an action came back with. The FX view beside it
 * reads and renders. That split is what makes this screen's behaviour testable against
 * {@code FakeClientConnection} with no JavaFX toolkit (TEAM_SPLIT section 3.2).
 *
 * <h2>Three verbs, one read and two writes</h2>
 *
 * <p>{@code EXAM_LIST}, {@code EXAM_SUBMIT} and {@code EXAM_VERSION_REVISE}. The builder's own
 * writes ({@code EXAM_CREATE}, {@code EXAM_VERSION_SAVE}) are not here and are not this class's:
 * they belong to the builder session, which is E7.11 onward.
 *
 * <h2>This screen replaces E8's, and inherits two behaviours the DTO comparison does not cover
 * ⚑</h2>
 *
 * <p>Contract section 8 proves the <i>fields</i> of {@code MyApprovals} all cross over to
 * {@code ExamList}. Two things it does not mention are behaviours rather than fields, and losing
 * either would be a regression the contract's own table would call a clean swap:
 *
 * <ol>
 *   <li><b>The deep link.</b> {@code APPROVAL_APPROVED} and {@code APPROVAL_REJECTED} carry the
 *       version they are about in their {@code NavRef}, and F4.2 is only met if following one
 *       lands on that exam. {@link #selectedVersionId(long)} is this screen's half, and it drives
 *       the exam selection too, because a version she cannot see selected is not a landing.
 *       <b>The other half is not built and is not in this member's scope:</b>
 *       {@code NotificationsPanel.activate} calls {@code navigator.navigate(ref.route())}, the
 *       one-argument overload, which passes {@code NavParams.empty()} and drops
 *       {@code ref.entityId()} on the floor. So a notification click reaches this screen with no
 *       version and falls back. Found by a cold read of this file; raised with the lead, whose
 *       file it is. Stated here rather than left as a javadoc that describes a path nothing
 *       walks.</li>
 *   <li><b>The live re-read.</b> A decision arriving while she is on the screen re-reads the list
 *       without her pressing anything (NFR-18). {@link #subscribeTo(ClientEventBus)} wires it and
 *       {@link #onServerPush(ServerPushEvent)} is the entry point.
 *       <b>The old screen did not actually have this</b>, which the same cold read found:
 *       {@code MyApprovalsSession.onDecisionArrived} has no production caller and
 *       {@code MyApprovalsView.listensToEvents()} returns {@code false}, so the method was
 *       reachable only from its own test. Carrying it forward as written would have carried a
 *       claim rather than a behaviour, so it is wired here instead.</li>
 * </ol>
 *
 * <h2>An action carries the token of the row it was pressed on ⚑</h2>
 *
 * <p>{@link #submit(ExamListRow, ExamVersionRow)} and {@link #revise(ExamListRow,
 * ExamVersionRow)} take the version <b>as an object</b> rather than as an id, and read both
 * {@code examVersionId} and {@code lockVersion} off it. That is deliberate and it is the whole
 * defence: a signature taking two numbers can be handed the id of the row she clicked and the
 * token of the row that happens to be selected, which is a stale-token CONFLICT on a good day
 * and a write against the wrong version on a bad one. Taking the pair from one object makes the
 * mismatch unrepresentable rather than merely unlikely.
 *
 * <h2>Late answers are dropped, not applied</h2>
 *
 * <p>{@link #listGeneration} exists for the same reason {@code BankSession}'s does, though this
 * screen has no filters to race: an approval push, a manual retry and an action's own reload can
 * each put an {@code EXAM_LIST} in flight, and applying whichever landed last would put a
 * pre-submit list on screen after a post-submit one. The counter is bumped by anything that
 * changes what an answer would mean.
 *
 * <p>Action answers are <b>not</b> generation-filtered, and that asymmetry is the same one the
 * bank's delete makes: the write really did happen, so discarding its answer would leave the
 * screen claiming a state the server has already left.
 */
public final class ExamListSession {

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };

    // --- the list -------------------------------------------------------
    private AsyncViewState listState = AsyncViewState.IDLE;
    private String listError;
    private List<ExamListRow> rows = List.of();
    private int listGeneration;

    // --- selection ------------------------------------------------------
    private long selectedExamId;
    private long selectedVersionId;

    // --- an action in flight (E7.6, E7.5) -------------------------------
    private boolean acting;
    private String actionNotice;
    private String actionError;

    /**
     * @param dispatcher the request correlator
     * @param poster     the single FX-thread hop; {@code DirectFxThreadPoster} in tests
     */
    public ExamListSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public ExamListSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== Loading =======================================

    /**
     * Names the version a notification deep-linked to, so the screen can open on it.
     *
     * <p>Set before {@link #load()} and honoured once the rows arrive: it selects the exam that
     * owns the version and focuses that version inside it. A version that is not in the answer
     * is simply not selected, because notifications outlive what they point at and a dangling
     * reference should land her on the list rather than on an error.
     *
     * @param examVersionId the version to open on, or {@code 0} for none
     */
    public void selectedVersionId(long examVersionId) {
        this.selectedVersionId = examVersionId;
    }

    /** Requests the caller's own exams. */
    public void load() {
        if (listState == AsyncViewState.LOADING) {
            return;
        }
        listState = AsyncViewState.LOADING;
        listError = null;
        int generation = ++listGeneration;
        onChange.run();

        dispatcher.send(Verb.EXAM_LIST, null)
                .whenComplete((response, failure) ->
                        poster.run(() -> settleList(generation, response, failure)));
    }

    /**
     * Re-reads after an action or a push, without the in-flight guard {@link #load()} has.
     *
     * <p>{@link #load()} refuses to start a second read while one is running, which is right for
     * a retry button and wrong here: the read already in flight was issued before the write
     * landed, so its answer describes a world that no longer exists. Bumping the generation and
     * issuing a fresh one is what makes the older answer harmless.
     */
    public void reload() {
        listState = AsyncViewState.LOADING;
        listError = null;
        int generation = ++listGeneration;
        onChange.run();

        dispatcher.send(Verb.EXAM_LIST, null)
                .whenComplete((response, failure) ->
                        poster.run(() -> settleList(generation, response, failure)));
    }

    /**
     * Subscribes this session to the app bus, so a decision arriving repaints the list (E17).
     *
     * <p>Called from the view's {@code build()}, which is where {@code MyGradesSession} does the
     * same thing and for the stated reason: the live refresh is then wired somewhere a test can
     * reach, rather than in a {@code listensToEvents} override that only the shell can exercise.
     *
     * <p><b>{@link #onServerPush(ServerPushEvent)} must stay public on a public class.</b> The
     * bus invokes subscribers reflectively from its own package, so a package-private subscriber
     * registers without complaint and then throws {@code IllegalAccessException} on every push,
     * which the dispatcher catches and logs rather than rethrows. The screen simply never
     * updates and no test fails. That is not hypothetical: it is what happened while E6.14's
     * lock column was being built.
     *
     * @param eventBus the app bus; pushes arrive on it already on the FX thread
     * @return this, for chaining beside {@link #onChange(Runnable)}
     */
    public ExamListSession subscribeTo(ClientEventBus eventBus) {
        Objects.requireNonNull(eventBus, "eventBus").register(this);
        return this;
    }

    /**
     * A server push landed; re-read if it was a decision about one of her exams.
     *
     * <p>Filtered on the notification's own type rather than on the verb alone, because
     * {@code PUSH_NOTIFICATION} carries every kind of notification this app has and a grade
     * being published is not a reason to re-query an exam list.
     *
     * @param event the push, straight off the bus
     */
    @Subscribe
    public void onServerPush(ServerPushEvent event) {
        if (event == null || event.verb() != Verb.PUSH_NOTIFICATION) {
            return;
        }
        if (event.payload() instanceof NotificationDto item && isDecision(item.type())) {
            onDecisionArrived();
        }
    }

    /**
     * @param type the notification's type
     * @return {@code true} for the three that change what this list would say. {@code
     *         APPROVAL_SUPERSEDED} is included because E8's supersede is a rejection with a
     *         different cause, and it moves a version's state exactly as the other two do
     */
    private static boolean isDecision(NotificationType type) {
        return type == NotificationType.APPROVAL_APPROVED
                || type == NotificationType.APPROVAL_REJECTED
                || type == NotificationType.APPROVAL_SUPERSEDED;
    }

    /**
     * Re-reads after a coordinator's decision (E17).
     *
     * <p>A re-query rather than patching the pushed row in: the list is the server's answer to
     * "what became of my exams", and rebuilding from it is the only way the two cannot drift.
     * NFR-18 - the teacher pressed nothing.
     */
    public void onDecisionArrived() {
        reload();
    }

    private void settleList(int generation, Message response, Throwable failure) {
        if (generation != listGeneration) {
            return;
        }
        // An action holds `acting` across its own re-read and it is released HERE, not when the
        // write answered. See settleAction: between those two moments the pre-action list is
        // still on screen, and a second press against it is a second write.
        acting = false;
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof ExamList page)) {
            rows = List.of();
            listError = ExamListCopy.LOAD_FAILED;
            listState = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        rows = page.rows();
        listError = null;
        listState = AsyncViewState.forResult(rows);
        resolveSelection();
        onChange.run();
    }

    /**
     * Decides which exam is open once an answer has landed.
     *
     * <p>In order: the exam owning a deep-linked version, then the exam that was already open if
     * it is still in the list, then the first one. The middle case is what keeps a reload after a
     * submit from throwing her back to the top of her own list, which is the difference between a
     * screen that refreshes and a screen that resets.
     */
    private void resolveSelection() {
        if (rows.isEmpty()) {
            selectedExamId = 0;
            return;
        }
        if (selectedVersionId > 0) {
            Optional<ExamListRow> owner = rows.stream()
                    .filter(row -> row.versions().stream()
                            .anyMatch(version -> version.examVersionId() == selectedVersionId))
                    .findFirst();
            if (owner.isPresent()) {
                selectedExamId = owner.get().examId();
                return;
            }
        }
        boolean stillThere = rows.stream().anyMatch(row -> row.examId() == selectedExamId);
        if (!stillThere) {
            selectedExamId = rows.get(0).examId();
        }
    }

    // ===================== Selection =====================================

    /**
     * Opens one exam's versions.
     *
     * <p>Clears the deep link: once she has picked an exam herself, the version a notification
     * pointed at is no longer what the panel is about, and leaving it set would focus a version
     * belonging to an exam she is no longer looking at.
     *
     * @param examId the exam to open
     */
    public void select(long examId) {
        if (selectedExamId == examId) {
            return;
        }
        selectedExamId = examId;
        selectedVersionId = 0;
        onChange.run();
    }

    /** @return the exam whose versions the panel is showing, when there is one. */
    public Optional<ExamListRow> selectedExam() {
        return rows.stream().filter(row -> row.examId() == selectedExamId).findFirst();
    }

    /** @return the selected exam's versions, newest first; empty when nothing is selected. */
    public List<ExamVersionRow> versions() {
        return selectedExam().map(ExamListRow::versions).orElse(List.of());
    }

    /**
     * @return the version the panel describes: the deep-linked one when it is in the selected
     *         exam, otherwise the first sent-back one, because that is what a teacher opening
     *         this screen unprompted is most likely here for, otherwise the newest
     */
    public Optional<ExamVersionRow> focusedVersion() {
        List<ExamVersionRow> versions = versions();
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        if (selectedVersionId > 0) {
            Optional<ExamVersionRow> named = versions.stream()
                    .filter(version -> version.examVersionId() == selectedVersionId)
                    .findFirst();
            if (named.isPresent()) {
                return named;
            }
        }
        return versions.stream()
                .filter(version -> version.state().isRejected())
                .findFirst()
                .or(() -> Optional.of(versions.get(0)));
    }

    // ===================== What a state permits ==========================

    /**
     * @param version a version on screen
     * @return {@code true} when submitting it is a thing the server would accept, which is
     *         {@code DRAFT} and nothing else (contract §5.4). Reads {@code isEditable} rather
     *         than naming the state, so the client and the wire enum cannot disagree about what
     *         DRAFT means
     */
    public boolean canSubmit(ExamVersionRow version) {
        return version != null && version.isEditable();
    }

    /**
     * @param version a version on screen
     * @return {@code true} when revising it is a thing the server would accept, which is every
     *         state <b>except</b> {@code DRAFT} (contract §5.4, E7.5). A draft is already the
     *         thing revise would make, which the server answers {@code CONFLICT} rather than
     *         {@code VALIDATION}, and offering the button anyway would be the client inviting a
     *         refusal it could have predicted
     */
    public boolean canRevise(ExamVersionRow version) {
        return version != null && !version.isEditable();
    }

    // ===================== The two actions ===============================

    /**
     * Sends one draft to the coordinator (E7.6, F3.6).
     *
     * @param exam    the exam the version belongs to, kept so the notice can name it
     * @param version the version to submit, whose {@code lockVersion} travels with it
     */
    public void submit(ExamListRow exam, ExamVersionRow version) {
        act(Verb.EXAM_SUBMIT, exam, version, canSubmit(version));
    }

    /**
     * Opens a new draft from a version that is no longer editable (E7.5, C-2).
     *
     * @param exam    the exam the version belongs to, kept so the notice can name it
     * @param version the version to copy, whose {@code lockVersion} travels with it
     */
    public void revise(ExamListRow exam, ExamVersionRow version) {
        act(Verb.EXAM_VERSION_REVISE, exam, version, canRevise(version));
    }

    /**
     * The half both actions share.
     *
     * <p>The {@code permitted} argument is the client's own read of contract §5.4, and refusing
     * here is not a substitute for the server's check: it is what stops the screen from asking a
     * question it already knows the answer to. The server refuses the same case, in a
     * transaction, which is where the rule actually lives.
     */
    private void act(Verb verb, ExamListRow exam, ExamVersionRow version, boolean permitted) {
        if (acting || exam == null || version == null || !permitted) {
            return;
        }
        acting = true;
        actionNotice = null;
        actionError = null;
        onChange.run();

        // Both facts come off one object, so they cannot describe two different rows.
        ExamVersionAction action =
                new ExamVersionAction(version.examVersionId(), version.lockVersion());
        dispatcher.send(verb, action)
                .whenComplete((response, failure) ->
                        poster.run(() -> settleAction(verb, response, failure)));
    }

    /**
     * What a write came back with.
     *
     * <p><b>{@code acting} is cleared on the paths that stop here and held on the paths that
     * re-read</b>, which is not symmetry for its own sake. Clearing it before {@link #reload()}
     * re-enables both buttons while {@code rows} is still the <i>pre-action</i> list, so for one
     * network round trip the card for the version she just revised is on screen, unchanged, with
     * a live Revise on it. Revise has no idempotency anywhere: the server's guard only refuses a
     * version that is <i>itself</i> a draft, it does not touch the predecessor's row, so the
     * second press passes the same lock-token check and inserts again at
     * {@code findLatestVersionNo + 1}. One approved version, pressed twice inside that window,
     * becomes two drafts. Found by a cold read, not by the suite.
     */
    private void settleAction(Verb verb, Message response, Throwable failure) {
        if (failure != null || response == null) {
            acting = false;
            actionError = ExamListCopy.ACTION_FAILED;
            onChange.run();
            return;
        }
        if (response.isError()) {
            settleActionError(response);
            return;
        }
        if (!(response.getPayload() instanceof ExamComposition saved)) {
            acting = false;
            actionError = ExamListCopy.ACTION_FAILED;
            onChange.run();
            return;
        }
        // The revise notice names the version the SERVER made, never one predicted here: the
        // number is allocated against uq_exam_versions_no and a concurrent revise moves it.
        actionNotice = verb == Verb.EXAM_SUBMIT
                ? ExamListCopy.SUBMITTED_NOTICE
                : ExamListCopy.revisedNotice(saved.versionNo());
        if (verb == Verb.EXAM_VERSION_REVISE) {
            // Land her on what she just made rather than on what she copied.
            selectedVersionId = saved.examVersionId();
        }
        onChange.run();
        reload();
    }

    /**
     * Turns a refusal into a sentence, and reloads for the two that mean the list is stale.
     *
     * <p>{@code CONFLICT} and {@code NOT_FOUND} both say the row on screen may no longer describe
     * the server's row, so leaving the old list up would let her press the same button again
     * against the same stale token and read the same sentence.
     *
     * <h2>A CONFLICT keeps the server's sentence when it has one ⚑</h2>
     *
     * <p>Contract §6 gives CONFLICT three causes, and only two of them are staleness: a stale
     * token, the wrong state, <b>or the version is edit-locked by somebody else</b>. The third is
     * live on both of these verbs and is a different situation entirely. There the row on screen
     * is perfectly current, the re-read changes nothing, and the server's own sentence is the one
     * thing that helps because it <i>names the colleague holding it</i>. Replacing that with "the
     * list was reloaded, check the version before trying again" sends her to look at a version
     * with nothing wrong with it, and the next press says the same thing: the exact loop the
     * reload exists to break. So the sentence is kept when there is one, and the reload happens
     * anyway because staleness is still possible.
     *
     * <p>A {@code VALIDATION} refusal is different again: revise answers it when a question in
     * the copy has since been deleted from the bank, which is a fact about the bank rather than
     * about this list, so the list is left alone.
     */
    private void settleActionError(Message response) {
        ErrorCode code = response.getErrorCode();
        String message = response.errorMessage();
        boolean hasSentence = message != null && !message.isBlank();
        if (code == ErrorCode.CONFLICT) {
            actionError = hasSentence ? message : ExamListCopy.STALE_NOTICE;
            onChange.run();
            reload();
            return;
        }
        if (code == ErrorCode.NOT_FOUND) {
            // Held across the reload, same rule as the branch above: every path that re-reads
            // releases the buttons in settleList, and every path that stops here releases them
            // itself. One rule, so a future branch cannot be half of it.
            actionError = ExamListCopy.GONE_NOTICE;
            onChange.run();
            reload();
            return;
        }
        if (code == ErrorCode.VALIDATION) {
            acting = false;
            actionError = hasSentence ? message : ExamListCopy.ACTION_FAILED;
            onChange.run();
            return;
        }
        acting = false;
        actionError = ExamListCopy.ACTION_FAILED;
        onChange.run();
    }

    // ===================== What the screen reads =========================

    /** @return the current list state. */
    public AsyncViewState state() {
        return listState;
    }

    /** @return the loaded exams, newest first. */
    public List<ExamListRow> rows() {
        return rows;
    }

    /** @return the error sentence when the load failed. */
    public Optional<String> error() {
        return Optional.ofNullable(listError);
    }

    /** @return {@code true} while an {@code EXAM_LIST} is in flight. */
    public boolean isLoading() {
        return listState == AsyncViewState.LOADING;
    }

    /** @return {@code true} while a submit or a revise is in flight, which greys both buttons. */
    public boolean isActing() {
        return acting;
    }

    /** @return the sentence to show after a successful action, until it is dismissed. */
    public Optional<String> actionNotice() {
        return Optional.ofNullable(actionNotice);
    }

    /** @return the sentence to show after a refused action, until it is dismissed. */
    public Optional<String> actionError() {
        return Optional.ofNullable(actionError);
    }

    /**
     * Clears the success notice once its toast has been shown.
     *
     * <p>Both notices need a dismisser for the reason the bank's delete failure did:
     * {@link #onChange} fires on every settle and every selection, so a sentence the screen
     * never stops holding is a sentence the screen shows again on every one of them.
     */
    public void dismissNotice() {
        if (actionNotice == null) {
            return;
        }
        actionNotice = null;
        onChange.run();
    }

    /** Clears the failure sentence once its toast has been shown. */
    public void dismissActionError() {
        if (actionError == null) {
            return;
        }
        actionError = null;
        onChange.run();
    }
}
