package client.features.bank;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import client.ui.components.logic.ImagePickerLogic;
import common.dto.bank.Difficulty;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionDraft;
import common.dto.bank.QuestionEdit;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import server.features.bank.BankMessages;
import server.features.bank.QuestionValidator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The logic behind the question editor (Presentation tier, E6.10 / E6.11 — F2.1, F2.2, C-8,
 * T-2.2).
 *
 * <p>FX-free, so every rule it enforces and every refusal it maps is tested without a toolkit.
 *
 * <h2>One rule, one home: this class imports the server's validator ⚑</h2>
 *
 * <p>E6.11 has to reach <b>the same verdict as the server</b> while the teacher is typing. There
 * are two ways to get that and only one of them survives contact with time: copy the rule and
 * write a test that the copies agree, or call the rule. This calls it.
 * {@link QuestionValidator#sameAnswer} is public for exactly this consumer and says so in its
 * own javadoc, and the four length constants come from the same class, so the numbers the editor
 * refuses at cannot drift from the columns they stand in for.
 *
 * <p>The refusal <i>sentences</i> come from {@code BankMessages} for the same reason. A teacher
 * who is told while typing that two answers are too similar, and then told something differently
 * worded when she saves anyway, has been given two accounts of one rule.
 *
 * <p><b>Why importing across the tier is safe here, measured rather than assumed.</b> The client
 * jar's {@code artifactSet} includes the whole project artifact, so server classes ship in it;
 * and the transitive closure of these two imports is the JDK plus
 * {@code server.db.entities.Difficulty}, an annotation-free enum. Nothing here pulls Hibernate,
 * which the client jar does not carry. The alternative, a duplicated fold plus an agreement test,
 * is written up in this PR's report with its cost, because it is the reviewer's call and not
 * mine.
 *
 * <h2>The trap this class is built to make unreachable ⚑</h2>
 *
 * <p>The components report names one thing an editor screen can still get wrong:
 * {@code loadExisting} must run before the picker is visible, or a question with a diagram opens
 * saying "No illustration" and a teacher who presses Save writes {@code KEEP}-of-nothing over her
 * own picture.
 *
 * <p>So {@link #forEdit} <b>refuses</b> a question whose {@code hasImage} is true and whose bytes
 * are absent, and the {@link ImagePickerLogic} it hands the view has already been loaded. There
 * is no ordering for a screen to get right, because the wrong order throws.
 *
 * <p><b>Taking the bytes as a parameter was not enough, and this javadoc said it was.</b> A
 * required argument that accepts null is a required argument in name only; a cold read of this
 * class found the claim before a teacher did, and the check in {@code forEdit} is what the
 * paragraph above had been describing rather than enforcing.
 *
 * <p>What the failure actually costs, corrected in the same pass: {@code KEEP} copies the stored
 * blob forward (`QuestionService.imageFor`), so a blind save does <b>not</b> lose her picture.
 * The harms are that the editor says "No illustration" about a question that has one, and that a
 * Remove she presses is silently discarded.
 */
public final class QuestionEditorSession {

    /** How many answers a question has, from the rule rather than from a literal (C-7). */
    public static final int ANSWER_COUNT = QuestionValidator.ANSWER_COUNT;

    /** What the editor is doing. */
    public enum Mode {

        /** Writing a question that does not exist yet (F2.2). */
        CREATE,

        /** Writing version n+1 of one that does (F2.3, C-2). */
        EDIT
    }

    /** How a save ended, for the screen to act on. */
    public enum Outcome {

        /** Nothing has been attempted yet, or the last attempt was superseded. */
        NONE,

        /** The server wrote it. */
        SAVED,

        /** The server refused it; {@link #refusals()} says where each sentence goes. */
        REFUSED,

        /** Somebody else wrote a version while this editor was open. */
        STALE,

        /** The question stopped being reachable. */
        GONE,

        /** It never reached a decision. */
        FAILED
    }

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;
    private final Mode mode;
    private final String courseCode;
    private final String displayId5;
    private int baseVersionNo;
    private final ImagePickerLogic image;

    private Runnable onChange = () -> { };

    private String text = "";
    private final String[] answers = {"", "", "", ""};
    private Integer correctAnswer;
    private String topic = "";
    private Difficulty difficulty;

    private String baselineText = "";
    private String[] baselineAnswers = {"", "", "", ""};
    private Integer baselineCorrect;
    private String baselineTopic = "";
    private Difficulty baselineDifficulty;

    private AsyncViewState saveState = AsyncViewState.IDLE;
    private Outcome outcome = Outcome.NONE;
    private List<QuestionEditorCopy.Refusal> refusals = List.of();
    private QuestionDetail saved;

    /** Somebody else holds the edit lock; the form renders and refuses to send (E6.14). */
    private boolean readOnly;

    private QuestionEditorSession(RequestDispatcher dispatcher, FxThreadPoster poster, Mode mode,
                                  String courseCode, String displayId5, int baseVersionNo,
                                  ImagePickerLogic image) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
        this.mode = mode;
        this.courseCode = courseCode;
        this.displayId5 = displayId5;
        this.baseVersionNo = baseVersionNo;
        this.image = image;
    }

    /**
     * An editor for a question that does not exist yet.
     *
     * @param courseCode the course it will belong to; the server re-checks that she teaches it
     */
    public static QuestionEditorSession forCreate(RequestDispatcher dispatcher,
                                                  FxThreadPoster poster, String courseCode) {
        Objects.requireNonNull(courseCode, "courseCode");
        return new QuestionEditorSession(dispatcher, poster, Mode.CREATE, courseCode, null, 0,
                new ImagePickerLogic());
    }

    /**
     * An editor for an existing question, opened on the version she was reading.
     *
     * <p><b>{@code imageBytes} is a required argument, and that is the whole point.</b> Pass the
     * answer to {@code QUESTION_IMAGE_GET}, or {@code null} when {@code detail.hasImage()} is
     * false. A caller that has not fetched them yet cannot build this object, which is what makes
     * the components report's one remaining screen-level trap unreachable rather than merely
     * documented.
     *
     * @param detail     the version on screen, which becomes the edit's staleness token
     * @param imageBytes that version's illustration, or {@code null} when it has none
     */
    public static QuestionEditorSession forEdit(RequestDispatcher dispatcher,
                                                FxThreadPoster poster, QuestionDetail detail,
                                                byte[] imageBytes) {
        Objects.requireNonNull(detail, "detail");
        // Requiring the PARAMETER is not requiring the BYTES, and the javadoc above used to
        // claim otherwise. Passing null for a question that has a picture was accepted in
        // silence: the picker then reports "no illustration" about a question that has one, and
        // a Remove she presses maps to KEEP and is discarded with no error. The check is what
        // the surrounding prose was describing.
        if (detail.hasImage() && (imageBytes == null || imageBytes.length == 0)) {
            throw new IllegalArgumentException("question " + detail.displayId5() + " version "
                    + detail.versionNo() + " has an illustration, so its bytes are required "
                    + "here. Answer QUESTION_IMAGE_GET before opening the editor.");
        }
        QuestionEditorSession session = new QuestionEditorSession(dispatcher, poster, Mode.EDIT,
                detail.courseCode(), detail.displayId5(), detail.versionNo(),
                ImagePickerLogic.of(imageBytes));
        session.adopt(detail);
        return session;
    }

    private void adopt(QuestionDetail detail) {
        // Including the staleness token. Leaving it at the version the editor opened on makes a
        // second save from the same editor report "somebody else edited this" about her own
        // first save. Latent only while the screen navigates away on success.
        baseVersionNo = detail.versionNo();
        text = nullToEmpty(detail.text());
        List<String> given = detail.answers();
        for (int i = 0; i < ANSWER_COUNT; i++) {
            answers[i] = i < given.size() ? nullToEmpty(given.get(i)) : "";
        }
        correctAnswer = detail.correctAnswer();
        topic = nullToEmpty(detail.topic());
        difficulty = detail.difficulty();
        rememberBaseline();
    }

    private void rememberBaseline() {
        baselineText = text;
        baselineAnswers = answers.clone();
        baselineCorrect = correctAnswer;
        baselineTopic = topic;
        baselineDifficulty = difficulty;
    }

    /** Registers the "re-read me and re-render" callback. */
    public QuestionEditorSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== What the teacher types =========================

    /** @param value the stem */
    public void setText(String value) {
        String next = nullToEmpty(value);
        if (next.equals(text)) {
            return;
        }
        text = next;
        changed();
    }

    /**
     * @param oneBased 1..4, the same numbering as the wire (C-8)
     * @param value    what is in that box
     */
    public void setAnswer(int oneBased, String value) {
        if (oneBased < 1 || oneBased > ANSWER_COUNT) {
            return;
        }
        String next = nullToEmpty(value);
        if (next.equals(answers[oneBased - 1])) {
            return;
        }
        answers[oneBased - 1] = next;
        changed();
    }

    /** @param oneBased which answer the key names, 1..4, or {@code null} for none chosen yet */
    public void setCorrectAnswer(Integer oneBased) {
        if (Objects.equals(oneBased, correctAnswer)) {
            return;
        }
        correctAnswer = oneBased;
        changed();
    }

    /** @param value the topic */
    public void setTopic(String value) {
        String next = nullToEmpty(value);
        if (next.equals(topic)) {
            return;
        }
        topic = next;
        changed();
    }

    /** @param value the difficulty */
    public void setDifficulty(Difficulty value) {
        if (value == difficulty) {
            return;
        }
        difficulty = value;
        changed();
    }

    /**
     * Tells the session the illustration moved.
     *
     * <p>The bytes themselves live in {@link #imageLogic()}, which the picker owns and mutates.
     * This exists so a change there marks the form dirty like any other edit.
     */
    public void imageChanged() {
        changed();
    }

    private void changed() {
        // A refusal describes the text it was computed against. Once she edits, it is about a
        // question that no longer exists, and leaving it under the box is how a teacher fixes
        // something and is still told it is wrong.
        if (outcome == Outcome.REFUSED) {
            outcome = Outcome.NONE;
            refusals = List.of();
        }
        onChange.run();
    }

    // ===================== E6.11, live while typing =======================

    /**
     * Every rule this form can check without the server, in the server's own words.
     *
     * <p>Ordered so the list reads down the form. Each entry names the control it belongs to, and
     * {@link QuestionEditorCopy.Refusal#message()} is a {@code BankMessages} sentence wherever
     * the server has one, so the live hint and the eventual refusal cannot be worded differently.
     *
     * <p><b>Blank boxes are not reported while she is still filling them in.</b> A form that
     * turns red the moment it opens has told her nothing; the required-ness is shown by the
     * fields' own markers and enforced by {@link #canSave()}. What IS reported live is the pair
     * of rules she cannot see for herself: an answer that duplicates another, and a value that is
     * over its column's length.
     *
     * @return the refusals, empty when nothing is wrong
     */
    public List<QuestionEditorCopy.Refusal> liveProblems() {
        List<QuestionEditorCopy.Refusal> found = new ArrayList<>();

        if (text.length() > QuestionValidator.MAX_TEXT_LENGTH) {
            found.add(QuestionEditorCopy.Refusal.of(QuestionEditorCopy.Field.TEXT,
                    BankMessages.textTooLong(QuestionValidator.MAX_TEXT_LENGTH)));
        }
        for (int i = 0; i < ANSWER_COUNT; i++) {
            if (answers[i].length() > QuestionValidator.MAX_ANSWER_LENGTH) {
                found.add(QuestionEditorCopy.Refusal.answer(i + 1,
                        BankMessages.answerTooLong(i + 1, QuestionValidator.MAX_ANSWER_LENGTH)));
            }
        }
        if (topic.length() > QuestionValidator.MAX_TOPIC_LENGTH) {
            found.add(QuestionEditorCopy.Refusal.of(QuestionEditorCopy.Field.TOPIC,
                    BankMessages.topicTooLong(QuestionValidator.MAX_TOPIC_LENGTH)));
        }
        found.addAll(duplicatePairs());
        return List.copyOf(found);
    }

    /**
     * The duplicate-answer rule (C-8, ADR-016), run through the server's own comparison.
     *
     * <p>Both positions of a colliding pair are marked, not just the second, because a teacher
     * told that answer 3 is a duplicate has to hunt for which other one it duplicates.
     *
     * <p>Blank boxes are skipped: two empty boxes are identical under any comparison, and saying
     * so while she is still typing would flood a half-filled form with duplicate warnings. The
     * server refuses blanks by their own rule, which is a different and better sentence.
     */
    private List<QuestionEditorCopy.Refusal> duplicatePairs() {
        List<QuestionEditorCopy.Refusal> found = new ArrayList<>();
        for (int i = 0; i < ANSWER_COUNT; i++) {
            for (int j = i + 1; j < ANSWER_COUNT; j++) {
                if (answers[i].isBlank() || answers[j].isBlank()) {
                    continue;
                }
                if (QuestionValidator.sameAnswer(answers[i], answers[j])) {
                    String message = BankMessages.answersDuplicated(i + 1, j + 1);
                    found.add(QuestionEditorCopy.Refusal.answer(i + 1, message));
                    found.add(QuestionEditorCopy.Refusal.answer(j + 1, message));
                }
            }
        }
        return found;
    }

    /**
     * Whether the form is complete and internally consistent.
     *
     * <p>This is the Save button's enabled state, and it is deliberately stricter than
     * {@link #liveProblems()}: it also requires the boxes that are merely empty. F3.1's
     * "blocked, not warned" shape, applied to a question rather than to an exam.
     */
    public boolean canSave() {
        if (saveState == AsyncViewState.LOADING || readOnly) {
            return false;
        }
        if (text.isBlank() || topic.isBlank() || difficulty == null || correctAnswer == null) {
            return false;
        }
        for (String answer : answers) {
            if (answer.isBlank()) {
                return false;
            }
        }
        return liveProblems().isEmpty();
    }

    /** @return whether anything has been typed since the editor opened */
    public boolean isDirty() {
        return !text.equals(baselineText)
                || !Arrays.equals(answers, baselineAnswers)
                || !Objects.equals(correctAnswer, baselineCorrect)
                || !topic.equals(baselineTopic)
                || difficulty != baselineDifficulty
                || image.action() != common.dto.bank.ImageAction.KEEP;
    }

    // ===================== Saving =========================================

    /**
     * Sends the question.
     *
     * <p>{@code QUESTION_CREATE} in create mode, {@code QUESTION_UPDATE} in edit mode, and the
     * edit carries {@code baseVersionNo} so two teachers who both opened v3 get a
     * {@code CONFLICT} rather than a coin toss.
     *
     * <p>The image pair is taken from the picker's own logic and never assembled by hand: the
     * component guarantees {@code action()} and {@code chosenBytes()} are consistent, and that
     * guarantee is the reason a cancelled file chooser cannot become a {@code REMOVE}.
     */
    public void save() {
        if (!canSave()) {
            return;
        }
        saveState = AsyncViewState.LOADING;
        outcome = Outcome.NONE;
        refusals = List.of();
        onChange.run();

        List<String> four = List.of(answers[0], answers[1], answers[2], answers[3]);
        if (mode == Mode.CREATE) {
            QuestionDraft draft = new QuestionDraft(courseCode, text, four, correctAnswer, topic,
                    difficulty, image.chosenBytes());
            dispatcher.send(Verb.QUESTION_CREATE, draft)
                    .whenComplete((response, failure) ->
                            poster.run(() -> settle(response, failure)));
            return;
        }
        QuestionEdit edit = new QuestionEdit(displayId5, baseVersionNo, text, four, correctAnswer,
                topic, difficulty, image.action(), image.chosenBytes());
        dispatcher.send(Verb.QUESTION_UPDATE, edit)
                .whenComplete((response, failure) -> poster.run(() -> settle(response, failure)));
    }

    private void settle(Message response, Throwable failure) {
        saveState = AsyncViewState.READY;
        if (failure != null || response == null) {
            outcome = Outcome.FAILED;
            onChange.run();
            return;
        }
        if (response.isError()) {
            settleError(response);
            return;
        }
        if (!(response.getPayload() instanceof QuestionDetail detail)) {
            outcome = Outcome.FAILED;
            onChange.run();
            return;
        }
        saved = detail;
        outcome = Outcome.SAVED;
        adopt(detail);
        onChange.run();
    }

    private void settleError(Message response) {
        ErrorCode code = response.getErrorCode();
        String message = messageOf(response);
        if (code == ErrorCode.CONFLICT) {
            outcome = Outcome.STALE;
            onChange.run();
            return;
        }
        if (code == ErrorCode.NOT_FOUND) {
            outcome = Outcome.GONE;
            onChange.run();
            return;
        }
        if (code == ErrorCode.VALIDATION) {
            outcome = Outcome.REFUSED;
            refusals = List.of(locate(message));
            onChange.run();
            return;
        }
        outcome = Outcome.FAILED;
        onChange.run();
    }

    /**
     * Puts a server refusal under the box it is about (T-2.2).
     *
     * <p>The contract says a {@code VALIDATION} message names its field, and {@code BankMessages}
     * is where those sentences are written. So the mapping is <b>equality against that
     * catalogue</b> rather than substring sniffing: an exact match cannot put the topic's refusal
     * under the stem, and a sentence the catalogue does not contain falls through to a dialog
     * instead of being silently swallowed.
     *
     * <p>The three parameterised sentences are matched by regenerating them for every position
     * they can be about, which is a small closed set.
     */
    private QuestionEditorCopy.Refusal locate(String message) {
        if (message == null || message.isBlank()) {
            return QuestionEditorCopy.Refusal.form(QuestionEditorCopy.SAVE_FAILED);
        }
        if (message.equals(BankMessages.TEXT_REQUIRED)
                || message.equals(BankMessages.textTooLong(QuestionValidator.MAX_TEXT_LENGTH))) {
            return QuestionEditorCopy.Refusal.of(QuestionEditorCopy.Field.TEXT, message);
        }
        if (message.equals(BankMessages.TOPIC_REQUIRED)
                || message.equals(BankMessages.topicTooLong(QuestionValidator.MAX_TOPIC_LENGTH))) {
            return QuestionEditorCopy.Refusal.of(QuestionEditorCopy.Field.TOPIC, message);
        }
        if (message.equals(BankMessages.CORRECT_ANSWER_RANGE)) {
            return QuestionEditorCopy.Refusal.of(QuestionEditorCopy.Field.CORRECT_ANSWER, message);
        }
        if (message.equals(BankMessages.DIFFICULTY_REQUIRED)) {
            return QuestionEditorCopy.Refusal.of(QuestionEditorCopy.Field.DIFFICULTY, message);
        }
        if (message.equals(BankMessages.IMAGE_TOO_LARGE)
                || message.equals(BankMessages.IMAGE_WRONG_TYPE)
                || message.equals(BankMessages.IMAGE_REPLACE_WITHOUT_FILE)) {
            return QuestionEditorCopy.Refusal.of(QuestionEditorCopy.Field.IMAGE, message);
        }
        for (int i = 1; i <= ANSWER_COUNT; i++) {
            if (message.equals(BankMessages.answerBlank(i))
                    || message.equals(BankMessages.answerTooLong(i,
                            QuestionValidator.MAX_ANSWER_LENGTH))) {
                return QuestionEditorCopy.Refusal.answer(i, message);
            }
            for (int j = i + 1; j <= ANSWER_COUNT; j++) {
                if (message.equals(BankMessages.answersDuplicated(i, j))) {
                    // The LATER box, matching QuestionValidator.answersDistinct, which reports
                    // answerField(j). The live check marks both positions; a server refusal
                    // carries one field and this is the one the server chose. Unreachable in
                    // practice, because the live rule refuses the pair before a save is sent.
                    return QuestionEditorCopy.Refusal.answer(j, message);
                }
            }
        }
        return QuestionEditorCopy.Refusal.form(message);
    }

    private static String messageOf(Message response) {
        Object payload = response.getPayload();
        if (payload instanceof common.dto.ErrorPayload error) {
            return error.message();
        }
        return payload instanceof String text ? text : null;
    }

    /**
     * Puts the form into read-only, because somebody else holds the edit lock (E6.14, E18).
     *
     * <p>Told to the session rather than only painted on the view, so "a question somebody else
     * is editing cannot be saved" is a property the FX-free tests can prove. A screen that only
     * greyed its controls would still have a session that would happily send
     * {@code QUESTION_UPDATE} if anything else called {@code save()}.
     *
     * <p><b>The server gates this too, as of 2026-08-24.</b> This javadoc has now said all three
     * things in turn - that the server checked, that it did not, and that it does - so the
     * mechanism rather than the claim: {@code QuestionService.update} and {@code delete} consult
     * {@code EditLockGuard} between the scope check and the version check, and answer
     * {@code CONFLICT} carrying {@code BankMessages.lockedBy}. Section 6's second
     * {@code CONFLICT} finally has an issuer.
     *
     * <p>So this method is a courtesy in front of a server check rather than the only thing
     * standing between two teachers. It still earns its place: greying the form tells her before
     * she types a paragraph she cannot save, which a refusal at save time cannot do.
     *
     * @param locked whether another editor holds it
     */
    public void setReadOnly(boolean locked) {
        if (readOnly == locked) {
            return;
        }
        readOnly = locked;
        onChange.run();
    }

    /** @return whether somebody else's lock is holding this editor open read-only */
    public boolean isReadOnly() {
        return readOnly;
    }

    /** Clears the last outcome once the screen has acted on it. */
    public void dismissOutcome() {
        if (outcome == Outcome.NONE) {
            return;
        }
        outcome = Outcome.NONE;
        refusals = List.of();
        onChange.run();
    }

    // ===================== What the view reads ============================

    /** @return whether this editor is writing a new question or a new version */
    public Mode mode() {
        return mode;
    }

    /** @return the course the question belongs to */
    public String courseCode() {
        return courseCode;
    }

    /** @return the five-digit id, or {@code null} in create mode */
    public String displayId5() {
        return displayId5;
    }

    /** @return the version this edit is based on, 0 in create mode */
    public int baseVersionNo() {
        return baseVersionNo;
    }

    /**
     * The picker's logic, already loaded with the version's illustration.
     *
     * <p>Handed to {@code new ImagePicker(label, logic)} so the component and this class share
     * one state rather than two that must be kept in step.
     */
    public ImagePickerLogic imageLogic() {
        return image;
    }

    /** @return the stem */
    public String text() {
        return text;
    }

    /** @param oneBased 1..4 */
    public String answer(int oneBased) {
        return oneBased < 1 || oneBased > ANSWER_COUNT ? "" : answers[oneBased - 1];
    }

    /** @return the four answers in order, for filling the form in */
    public List<String> answers() {
        return List.of(answers[0], answers[1], answers[2], answers[3]);
    }

    /** @return which answer the key names, or {@code null} */
    public Integer correctAnswer() {
        return correctAnswer;
    }

    /** @return the topic */
    public String topic() {
        return topic;
    }

    /** @return the difficulty, or {@code null} */
    public Difficulty difficulty() {
        return difficulty;
    }

    /** @return whether a save is in flight */
    public boolean isSaving() {
        return saveState == AsyncViewState.LOADING;
    }

    /** @return how the last save ended */
    public Outcome outcome() {
        return outcome;
    }

    /** @return where each server refusal goes; empty unless the outcome is REFUSED */
    public List<QuestionEditorCopy.Refusal> refusals() {
        return refusals;
    }

    /** @return the version the server wrote, once it has, else empty */
    public Optional<QuestionDetail> saved() {
        return Optional.ofNullable(saved);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
