package client.core;

import client.events.ClientEventBus;
import client.net.IClientConnection;
import client.net.RequestDispatcher;
import client.ui.screen.AbstractScreen;
import common.protocol.Message;
import common.protocol.Verb;
import javafx.scene.Parent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A screen built after its world was torn down asks for its collaborators and gets inert ones
 * rather than {@code null} ⚑ (2026-08-30, wave 6, U-45).
 *
 * <p><b>The bug this exists to stop.</b> About one full build in three failed in
 * {@code BotInteractionTest} — once in {@code TakeExamInteractionTest} — with
 * {@code NullPointerException ... AbstractScreen.eventBus() is null} while a screen built, and
 * the failure was attributed to whichever test happened to run next, because the throw was
 * inside a {@code Platform.runLater} and not inside any test's call stack. The cause is the
 * teardown race {@link FxTestHarness} documents: a runnable queued by one test builds a screen
 * against the manager the harness has already discarded.
 *
 * <p>{@code FxTestHarness.drainFxEvents} now drains two generations of the queue, which closes
 * the window. This test covers the door behind it, and it is the half that can actually be
 * asserted: no drain can prove that no background thread is about to post one more runnable, so
 * what has to be true is that a build landing in the emptied world <em>completes</em>. No
 * toolkit is booted here — the probe never builds a node, it only asks the four accessors the
 * torn-down state used to break.
 */
class FxTestHarnessDetachedScreenTest {

    /**
     * A screen that is nothing but its accessors. {@code build()} is never called: the point
     * is what a subclass reaches for while building, not what it draws.
     */
    private static final class ProbeScreen extends AbstractScreen {

        @Override
        protected Parent build() {
            throw new UnsupportedOperationException("the probe never builds a node graph");
        }

        ClientEventBus bus() {
            return eventBus();
        }

        RequestDispatcher requests() {
            return dispatcher();
        }

        IClientConnection connection() {
            return client();
        }

        Navigator routes() {
            return navigator();
        }

        void postOnFxThread(Runnable work) {
            onFxThread().run(work);
        }
    }

    private final ProbeScreen screen = new ProbeScreen();

    @BeforeEach
    void tearTheWorldDown() {
        // The state under test is the one every interaction test's @AfterEach leaves behind.
        FxTestHarness.resetGlobalState();
    }

    @Test
    @DisplayName("⚑ U-45: the accessors answer a discarded manager without throwing")
    void accessorsAreNeverNullAfterTeardown() {
        assertThatCode(() -> {
            assertThat(screen.bus()).isNotNull();
            assertThat(screen.requests()).isNotNull();
            assertThat(screen.connection()).isNotNull();
            assertThat(screen.routes()).isNotNull();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the detached bus swallows a post, and its poster drops the work")
    void theDetachedBusGoesNowhere() {
        AtomicBoolean ran = new AtomicBoolean(false);

        assertThatCode(() -> screen.bus().post("an event nobody is left to hear"))
                .doesNotThrowAnyException();
        screen.postOnFxThread(() -> ran.set(true));

        assertThat(ran)
                .as("work posted by a screen that is about to be discarded must not run: it "
                        + "would touch a scene the test has finished with")
                .isFalse();
    }

    @Test
    @DisplayName("the detached dispatcher fails a send at once rather than arming a timeout")
    void theDetachedDispatcherRefusesTheSend() {
        CompletableFuture<Message> answer = screen.requests().send(Verb.BANK_LIST, null);

        assertThat(answer).isCompletedExceptionally();
        assertThat(screen.requests().pendingCount())
                .as("a refused send leaves nothing waiting for an answer that cannot come")
                .isZero();
        assertThat(screen.connection().isConnectionOpen()).isFalse();
    }

    @Test
    @DisplayName("draining a queue that was never started is a no-op, not a failure")
    void drainingWithoutAToolkitIsSafe() {
        assertThatCode(FxTestHarness::drainFxEvents).doesNotThrowAnyException();
    }
}
