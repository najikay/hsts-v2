package client.features.bot;

import common.dto.bot.BotAnswer;
import common.dto.bot.BotConversation;
import common.dto.bot.BotTurn;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        changed();
    }

    /**
     * The C-4 cross-course notice came back instead of an answer (ADR-018).
     *
     * <p>The pending bubble goes away while she decides. Leaving it there would
     * show a question that has explicitly not been asked yet.
     *
     * @param notice the sentence to show in the confirmation
     */
    public void needsAcknowledgement(String notice) {
        heldQuestion = removePending();
        state = ChatState.NEEDS_ACKNOWLEDGEMENT;
        banner = notice == null ? "" : notice;
        changed();
    }

    /**
     * She confirmed the notice.
     *
     * @return the held question, to be re-sent acknowledged; empty when there is none
     */
    public String acknowledged() {
        String question = heldQuestion;
        heldQuestion = "";
        banner = "";
        state = ChatState.IDLE;
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
        changed();
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
