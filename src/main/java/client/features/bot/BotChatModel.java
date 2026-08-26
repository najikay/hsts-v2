package client.features.bot;

import common.dto.bot.BotAnswer;
import common.dto.bot.BotConversation;
import common.dto.bot.BotIntegrityNotice;
import common.dto.bot.BotTurn;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the chat screen knows (Presentation tier, E16.13).
 *
 * <p>FX-free on purpose, like {@code NotificationsModel} and {@code AttemptModel}:
 * the state and the transitions live here where a plain JUnit test can drive them,
 * and the view is a rendering of a snapshot. That is how this feature reaches the
 * coverage gate without a robot clicking through every branch, and it is why the
 * five {@link ChatState}s can each be asserted in a test that runs in a
 * millisecond.
 *
 * <p><b>The held question is the interesting part.</b> When the C-4 notice comes
 * back the question has already been typed, already been sent, and is already on
 * screen as a pending bubble. Dropping it and asking her to retype would be a
 * small cruelty; sending it without her answer would defeat the notice. So it is
 * held here, and exactly one of {@link #acknowledged()} or {@link #declined()}
 * disposes of it.
 *
 * <h2>The acknowledgement is remembered ⚑ (B-20)</h2>
 *
 * <p>ADR-018 and {@code BotMessages.integrityNotice}'s own javadoc both describe a
 * notice shown <b>once per attempt</b>, and the server keeps that promise for the
 * two things that matter — the integrity flag keeps its first timestamp and the
 * teacher is notified exactly once. It could not keep it for the prompt, because
 * the prompt is decided from one request field and nobody remembered the answer:
 * {@code BotChatSession.ask(String)} hardcoded {@code acknowledged=false} and
 * {@link #acknowledged()} cleared the held state without recording that she had
 * agreed. A student who confirmed once got the same dialog on every message for the
 * rest of the sitting.
 *
 * <p>So consent is recorded here, against the <b>notice she consented to</b> rather
 * than against a clock or a bare boolean, and {@link #hasAcknowledged()} is what the
 * session sends on later asks.
 *
 * <p><b>A notice is never swallowed.</b> {@link #needsAcknowledgement} discards any
 * consent it is holding before showing the new one: the server only asks when it has
 * decided this ask needs asking about, so an arriving notice is evidence that the
 * sitting it describes is not the one she already agreed to, and the honest response
 * is to put the question again rather than to answer it from memory.
 *
 * <p>Consent is also dropped by everything that ends the conversation it belongs to
 * — {@link #blocked}, {@link #load} and {@link #startFresh} — and it never outlives
 * the model, which the view rebuilds per course. <b>Its honest limit</b>, stated
 * rather than buried: the client has no attempt identity to key on, because
 * {@code BotIntegrityNotice} carries a course name and a sentence and nothing that
 * distinguishes one sitting from the next. Giving it one would be a server and wire
 * change, which B-20's ruling excludes — the server must keep deciding C-4 from its
 * own live registry and must not learn to trust a client's flag any further than it
 * already does. What the flag can and cannot buy is unchanged: it suppresses a
 * repeated <em>prompt</em>, and it has never been able to lift the same-course
 * lockout or to prevent the teacher being told.
 */
public final class BotChatModel {

    private final String courseCode;
    private final String courseName;
    private final List<ChatEntry> entries = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();

    private ChatState state = ChatState.IDLE;
    private long sessionId;
    private String banner = "";
    private String heldQuestion = "";

    /**
     * The C-4 notice she has confirmed, or {@code null} while she has confirmed none.
     *
     * <p>The notice rather than a boolean, so what she agreed to is recoverable — the view
     * can restate it, and a test can assert she consented to <em>this</em> and not merely
     * that some flag is set. See the class javadoc for why it is the only identity available.
     */
    private BotIntegrityNotice acknowledgedNotice;

    /** The notice currently on screen awaiting her answer, or {@code null}. */
    private BotIntegrityNotice pendingNotice;

    /**
     * @param courseCode the course this chat belongs to
     * @param courseName its display name, for the header
     */
    public BotChatModel(String courseCode, String courseName) {
        this.courseCode = Objects.requireNonNull(courseCode, "courseCode");
        this.courseName = courseName == null || courseName.isBlank() ? courseCode : courseName;
    }

    // ===================== Reads =========================================

    public String courseCode() {
        return courseCode;
    }

    public String courseName() {
        return courseName;
    }

    /** @return the conversation so far, oldest first; never modifiable by a caller. */
    public List<ChatEntry> entries() {
        return List.copyOf(entries);
    }

    public ChatState state() {
        return state;
    }

    /** @return the conversation this chat is continuing, or {@code 0} for a fresh one. */
    public long sessionId() {
        return sessionId;
    }

    /** @return the sentence the banner shows, or empty when there is no banner. */
    public String banner() {
        return banner;
    }

    /** @return the question waiting on the C-4 confirmation, or empty. */
    public String heldQuestion() {
        return heldQuestion;
    }

    /**
     * @return {@code true} once she has confirmed a C-4 notice and it has not been
     *         discarded. What {@code BotChatSession} sends as
     *         {@code BotAskRequest.integrityAcknowledged} on every later ask (B-20 ⚑)
     */
    public boolean hasAcknowledged() {
        return acknowledgedNotice != null;
    }

    /** @return the notice she confirmed, or empty when she has confirmed none. */
    public Optional<BotIntegrityNotice> acknowledgedNotice() {
        return Optional.ofNullable(acknowledgedNotice);
    }

    /** @return {@code true} when there is nothing to draw but the empty state. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    // ===================== Transitions ===================================

    /**
     * A question has been sent.
     *
     * <p>The bubble appears immediately and pending. The optimism is safe because
     * every path out of {@link ChatState#THINKING} either confirms it
     * ({@link #answered}) or takes it back ({@link #failed}, {@link #blocked},
     * {@link #needsAcknowledgement}).
     *
     * @param question what she typed
     * @param at       now
     */
    public void asking(String question, Instant at) {
        entries.add(ChatEntry.pendingQuestion(question, at));
        state = ChatState.THINKING;
        banner = "";
        changed();
    }

    /**
     * The answer arrived (F12.5, S-32).
     *
     * <p>The S-32 fallback is not treated specially here, and that is deliberate:
     * to the student it is the bot answering, in a bubble, with a sentence that
     * tells her what to do next. Marking it visually as a failure would tell her
     * about our provider chain, which is not her problem (ADR-009).
     *
     * @param answer the server's answer
     */
    public void answered(BotAnswer answer) {
        Objects.requireNonNull(answer, "answer");
        confirmPending();
        entries.add(ChatEntry.of(answer.answered()));
        if (answer.sessionId() > 0) {
            sessionId = answer.sessionId();
        }
        state = ChatState.IDLE;
        banner = "";
        heldQuestion = "";
        changed();
    }

    /**
     * The ask failed in a way worth retrying.
     *
     * <p>The pending bubble is removed and the question handed back, because a
     * question that was never asked must not sit in the transcript looking as
     * though it was.
     *
     * @param message what to tell her
     * @return the question, so the view can put it back in the box
     */
    public String failed(String message) {
        String question = removePending();
        state = ChatState.RETRYABLE_ERROR;
        banner = message == null ? BotCopy.ASK_FAILED : message;
        changed();
        return question;
    }

    /**
     * The bot cannot be used: not enrolled, no bot, switched off, or the C-4
     * same-course lockout.
     *
     * @param message the server's own sentence, which already says what to do next
     */
    public void blocked(String message) {
        removePending();
        state = ChatState.UNAVAILABLE;
        banner = message == null ? BotCopy.UNAVAILABLE_TITLE : message;
        heldQuestion = "";
        // The bot has become unusable — not enrolled, switched off, or the same-course
        // lockout, which means she has started sitting THIS course's exam. Whatever she
        // agreed to belonged to a situation that is over (B-20).
        acknowledgedNotice = null;
        changed();
    }

    /**
     * The C-4 cross-course notice came back instead of an answer (ADR-018).
     *
     * <p>The pending bubble goes away while she decides. Leaving it there would
     * show a question that has explicitly not been asked yet.
     *
     * <p>⚑ <b>B-20.</b> Any consent already held is discarded first. The server only
     * returns a notice when it has decided this ask needs asking about, so one arriving
     * after she has already confirmed says the sitting it describes is not the one she
     * agreed to — a new attempt notices again rather than being waved through on the
     * strength of an older confirmation.
     *
     * @param notice the notice to confirm; its sentence is what the dialog shows
     */
    public void needsAcknowledgement(BotIntegrityNotice notice) {
        heldQuestion = removePending();
        state = ChatState.NEEDS_ACKNOWLEDGEMENT;
        banner = notice == null || notice.message() == null ? "" : notice.message();
        pendingNotice = notice;
        acknowledgedNotice = null;
        changed();
    }

    /**
     * She confirmed the notice ⚑ (B-20).
     *
     * <p>Records the consent as well as disposing of the held question. That record is the
     * whole of B-20's fix: the next ask carries {@code integrityAcknowledged=true}, so the
     * server has no reason to ask again and she is not made to confirm every message for the
     * rest of the sitting.
     *
     * @return the held question, to be re-sent acknowledged; empty when there is none
     */
    public String acknowledged() {
        String question = heldQuestion;
        heldQuestion = "";
        banner = "";
        state = ChatState.IDLE;
        // Consent belongs to the notice she was actually shown. When there is none to point
        // at — a caller confirming out of turn — nothing is recorded, because a consent with
        // no question in front of it is not one.
        acknowledgedNotice = pendingNotice;
        pendingNotice = null;
        changed();
        return question;
    }

    /**
     * She declined the notice.
     *
     * @return the held question, to be put back in the box unsent
     */
    public String declined() {
        String question = heldQuestion;
        heldQuestion = "";
        banner = "";
        state = ChatState.IDLE;
        // Declining records nothing. The next ask goes out unacknowledged and is asked about
        // again, which is what "no" has to mean if it means anything.
        pendingNotice = null;
        acknowledgedNotice = null;
        changed();
        return question;
    }

    /**
     * Replaces the conversation with a reopened one (F12.10).
     *
     * @param conversation the stored transcript
     */
    public void load(BotConversation conversation) {
        Objects.requireNonNull(conversation, "conversation");
        entries.clear();
        for (BotTurn turn : conversation.turns()) {
            entries.add(ChatEntry.of(turn));
        }
        sessionId = conversation.sessionId();
        state = ChatState.IDLE;
        banner = "";
        heldQuestion = "";
        forgetAcknowledgement();
        changed();
    }

    /**
     * Abandons the current conversation and starts a fresh one.
     *
     * <p>The stored one is untouched: it stays in her history, and this only
     * forgets the id, so the next question starts a new session server-side.
     */
    public void startFresh() {
        entries.clear();
        sessionId = 0;
        state = ChatState.IDLE;
        banner = "";
        heldQuestion = "";
        forgetAcknowledgement();
        changed();
    }

    /**
     * Drops any C-4 consent (B-20).
     *
     * <p>Called by every transition that replaces the conversation the consent belonged to.
     * Erring towards asking again is deliberate: an extra confirmation costs one click, and
     * a stale one lets a report reach a teacher without the student having been told it
     * would.
     */
    private void forgetAcknowledgement() {
        pendingNotice = null;
        acknowledgedNotice = null;
    }

    // ===================== Change notification ===========================

    /** Subscribes a renderer; called on every state change. */
    public void onChange(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void changed() {
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }

    /** Marks the trailing pending bubble as confirmed. */
    private void confirmPending() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).pending()) {
                entries.set(i, entries.get(i).confirmed());
                return;
            }
        }
    }

    /** @return the removed pending question's text, or empty when there was none. */
    private String removePending() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).pending()) {
                return entries.remove(i).text();
            }
        }
        return "";
    }
}
