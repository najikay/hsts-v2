package client.features.bot;

import common.dto.bot.BotAnswer;
import common.dto.bot.BotConversation;
import common.dto.bot.BotSpeaker;
import common.dto.bot.BotTurn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chat screen's state machine (E16.13).
 *
 * <p>FX-free, so every state and every transition is a plain assertion. The one
 * worth reading twice is the optimistic bubble: it appears the instant she presses
 * send and every exit from {@code THINKING} either confirms it or takes it back,
 * which is what stops a question that was never asked from sitting in the
 * transcript looking as though it was.
 */
class BotChatModelTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    private BotChatModel model() {
        return new BotChatModel("22", "Databases 22");
    }

    @Test
    @DisplayName("a new chat is idle and empty, and knows its course")
    void startsIdle() {
        BotChatModel model = model();

        assertThat(model.state()).isEqualTo(ChatState.IDLE);
        assertThat(model.isEmpty()).isTrue();
        assertThat(model.courseCode()).isEqualTo("22");
        assertThat(model.courseName()).isEqualTo("Databases 22");
        assertThat(model.sessionId()).isZero();
        assertThat(model.banner()).isEmpty();
    }

    @Test
    @DisplayName("a blank course name falls back to the code rather than an empty header")
    void courseNameFallsBack() {
        assertThat(new BotChatModel("22", "  ").courseName()).isEqualTo("22");
    }

    @Test
    @DisplayName("asking shows the question immediately, muted, and starts thinking")
    void askingIsOptimistic() {
        BotChatModel model = model();

        model.asking("what is a foreign key", NOW);

        assertThat(model.state()).isEqualTo(ChatState.THINKING);
        assertThat(model.state().isThinking()).isTrue();
        assertThat(model.state().acceptsInput()).isFalse();
        assertThat(model.entries()).hasSize(1);
        assertThat(model.entries().get(0).pending()).isTrue();
        assertThat(model.entries().get(0).isFromStudent()).isTrue();
    }

    @Test
    @DisplayName("an answer confirms the question and appends the reply")
    void answering() {
        BotChatModel model = model();
        model.asking("what is a foreign key", NOW);

        model.answered(new BotAnswer(7L, "what is a foreign key", "It points at a primary key.",
                NOW));

        assertThat(model.state()).isEqualTo(ChatState.IDLE);
        assertThat(model.entries()).hasSize(2);
        assertThat(model.entries().get(0).pending())
                .as("the question is no longer provisional")
                .isFalse();
        assertThat(model.entries().get(1).speaker()).isEqualTo(BotSpeaker.BOT);
        assertThat(model.sessionId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("the S-32 sentence is shown as an answer, not as a failure")
    void fallbackIsAnAnswer() {
        BotChatModel model = model();
        model.asking("q", NOW);

        model.answered(BotAnswer.unanswered(7L, "q", NOW));

        assertThat(model.state()).isEqualTo(ChatState.IDLE);
        assertThat(model.entries().get(1).text()).isEqualTo(BotAnswer.S32_FALLBACK);
        assertThat(model.banner()).isEmpty();
    }

    @Test
    @DisplayName("a session id of zero does not overwrite the one she was already in")
    void keepsTheSessionWhenTheServerCannotStoreOne() {
        BotChatModel model = model();
        model.asking("first", NOW);
        model.answered(new BotAnswer(7L, "first", "a", NOW));
        model.asking("second", NOW);

        model.answered(new BotAnswer(0L, "second", "a", NOW));

        assertThat(model.sessionId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("a retryable failure takes the question back and hands it to the caller")
    void failing() {
        BotChatModel model = model();
        model.asking("what is a foreign key", NOW);

        String returned = model.failed(BotCopy.ASK_FAILED);

        assertThat(returned).isEqualTo("what is a foreign key");
        assertThat(model.entries())
                .as("a question that was never asked must not sit in the transcript")
                .isEmpty();
        assertThat(model.state()).isEqualTo(ChatState.RETRYABLE_ERROR);
        assertThat(model.state().acceptsInput()).isTrue();
        assertThat(model.banner()).isEqualTo(BotCopy.ASK_FAILED);
    }

    @Test
    @DisplayName("a failure with no message falls back to one that says what to do next")
    void failingWithoutAMessage() {
        BotChatModel model = model();
        model.asking("q", NOW);

        model.failed(null);

        assertThat(model.banner()).isEqualTo(BotCopy.ASK_FAILED);
    }

    @Test
    @DisplayName("being blocked disables the composer and shows the server's own sentence")
    void blocked() {
        BotChatModel model = model();
        model.asking("q", NOW);

        model.blocked("This study bot is switched off right now. Ask your teacher.");

        assertThat(model.state()).isEqualTo(ChatState.UNAVAILABLE);
        assertThat(model.state().acceptsInput()).isFalse();
        assertThat(model.state().isBlocked()).isTrue();
        assertThat(model.entries()).isEmpty();
        assertThat(model.banner()).startsWith("This study bot is switched off");
    }

    @Test
    @DisplayName("being blocked with no message still says something")
    void blockedWithoutAMessage() {
        BotChatModel model = model();

        model.blocked(null);

        assertThat(model.banner()).isEqualTo(BotCopy.UNAVAILABLE_TITLE);
    }

    @Test
    @DisplayName("the C-4 notice holds the question while she decides (ADR-018)")
    void needsAcknowledgement() {
        BotChatModel model = model();
        model.asking("what is a foreign key", NOW);

        model.needsAcknowledgement("You are taking an exam right now.");

        assertThat(model.state()).isEqualTo(ChatState.NEEDS_ACKNOWLEDGEMENT);
        assertThat(model.state().acceptsInput()).isFalse();
        assertThat(model.heldQuestion()).isEqualTo("what is a foreign key");
        assertThat(model.entries())
                .as("the bubble goes away while she decides: it has not been asked yet")
                .isEmpty();
        assertThat(model.banner()).isEqualTo("You are taking an exam right now.");
    }

    @Test
    @DisplayName("confirming hands the held question back to be re-sent")
    void acknowledging() {
        BotChatModel model = model();
        model.asking("what is a foreign key", NOW);
        model.needsAcknowledgement("notice");

        String held = model.acknowledged();

        assertThat(held).isEqualTo("what is a foreign key");
        assertThat(model.state()).isEqualTo(ChatState.IDLE);
        assertThat(model.heldQuestion()).isEmpty();
        assertThat(model.banner()).isEmpty();
    }

    @Test
    @DisplayName("declining hands it back too, so nothing she typed is lost")
    void declining() {
        BotChatModel model = model();
        model.asking("what is a foreign key", NOW);
        model.needsAcknowledgement("notice");

        String held = model.declined();

        assertThat(held).isEqualTo("what is a foreign key");
        assertThat(model.state()).isEqualTo(ChatState.IDLE);
        assertThat(model.entries()).isEmpty();
    }

    @Test
    @DisplayName("reopening replaces the conversation with the stored one (F12.10)")
    void loadingAConversation() {
        BotChatModel model = model();
        model.asking("something else", NOW);

        model.load(new BotConversation(9L, "22", "Databases 22", NOW, NOW,
                List.of(BotTurn.asked("stored question", NOW),
                        BotTurn.answered("stored answer", NOW))));

        assertThat(model.entries()).hasSize(2);
        assertThat(model.entries().get(0).text()).isEqualTo("stored question");
        assertThat(model.entries()).allSatisfy(entry -> assertThat(entry.pending()).isFalse());
        assertThat(model.sessionId()).isEqualTo(9L);
        assertThat(model.state()).isEqualTo(ChatState.IDLE);
    }

    @Test
    @DisplayName("starting fresh forgets the conversation without touching what is stored")
    void startingFresh() {
        BotChatModel model = model();
        model.asking("q", NOW);
        model.answered(new BotAnswer(7L, "q", "a", NOW));

        model.startFresh();

        assertThat(model.entries()).isEmpty();
        assertThat(model.sessionId()).isZero();
        assertThat(model.state()).isEqualTo(ChatState.IDLE);
    }

    @Test
    @DisplayName("every transition notifies the renderer exactly once")
    void notifiesListeners() {
        BotChatModel model = model();
        AtomicInteger changes = new AtomicInteger();
        model.onChange(changes::incrementAndGet);

        model.asking("q", NOW);
        model.answered(new BotAnswer(7L, "q", "a", NOW));
        model.startFresh();

        assertThat(changes).hasValue(3);
    }

    @Test
    @DisplayName("the entries list a caller gets cannot be used to change the model")
    void entriesAreACopy() {
        BotChatModel model = model();
        model.asking("q", NOW);

        List<ChatEntry> entries = model.entries();

        assertThat(entries).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> entries.add(
                        ChatEntry.pendingQuestion("injected", NOW)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a chat entry knows who spoke, and confirming it is idempotent")
    void chatEntry() {
        ChatEntry pending = ChatEntry.pendingQuestion("q", NOW);

        assertThat(pending.isFromStudent()).isTrue();
        assertThat(pending.pending()).isTrue();
        assertThat(pending.confirmed().pending()).isFalse();
        assertThat(pending.confirmed().confirmed()).isEqualTo(pending.confirmed());
        assertThat(ChatEntry.of(BotTurn.answered("a", NOW)).isFromStudent()).isFalse();
        assertThat(new ChatEntry(BotSpeaker.BOT, null, NOW, false).text()).isEmpty();
    }

    @Test
    @DisplayName("every chat state has an opinion about the composer, and none of them is a mystery")
    void everyStateIsRenderable() {
        for (ChatState state : ChatState.values()) {
            assertThat(state.acceptsInput() || state.isThinking() || state.isBlocked()
                    || state == ChatState.NEEDS_ACKNOWLEDGEMENT)
                    .as("%s must have a rendering", state)
                    .isTrue();
        }
    }
}
