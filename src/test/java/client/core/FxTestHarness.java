package client.core;

import javafx.application.Platform;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The shared teardown for every test that boots {@code ClientApp} (UI wave 1,
 * item 0).
 *
 * <h2>The race this exists to close</h2>
 *
 * <p>Fourteen interaction tests share one shape: boot the real app, drive it
 * with the robot, then discard the {@code ScreenManager} singleton in
 * {@code @AfterEach} so the next test starts from nothing. Each of them copied
 * the same six reflective lines, and each of them carried the same bug.
 *
 * <p>Booting the app lands on {@code ConnectView}, which sweeps the LAN for two
 * seconds <b>on a daemon thread</b> and then posts its decision back with
 * {@code Platform.runLater}. A test that finishes in under two seconds — most of
 * them — tears the singleton down while that sweep is still in flight. The
 * posted runnable then runs against a freshly created, never-initialised
 * manager, asks it for the event bus, gets {@code null}, and
 * {@code ConnectWiring.forEndpoint} throws. Because the throw happens inside a
 * {@code runLater} on the FX thread and not inside any test's call stack, JUnit
 * attributes it to <b>whichever test runs next</b>: a failure that moves around
 * between runs and never points at the code that caused it.
 *
 * <h2>The three-part fix</h2>
 *
 * <ol>
 *   <li><b>Drain, then tear down.</b> {@link #drainFxEvents()} posts a runnable
 *       of its own and waits on a latch. {@code Platform.runLater} is FIFO, so
 *       when that runnable executes, every runnable queued before it has already
 *       finished. Work the sweep had <i>already</i> posted therefore runs while
 *       the world still exists, which is the case it was written for.</li>
 *   <li><b>Hide before discarding.</b> {@link #resetGlobalState()} runs
 *       {@code ScreenManager.resetForTests()} on the FX thread, and that method
 *       now hides the current screen before dropping the singleton. Hiding
 *       clears {@code ConnectView}'s "still showing" flag, so a sweep that lands
 *       <i>after</i> teardown returns without touching anything.</li>
 *   <li><b>A backstop in the wiring.</b> {@code ConnectWiring.forEndpoint}
 *       logs and degrades instead of throwing when the event bus is gone, so
 *       even an unforeseen route into it cannot fire an exception at an
 *       unrelated test.</li>
 * </ol>
 *
 * <p>The drain runs a second time after the reset: hiding a screen can itself
 * post work (animation stops, bus unregistration), and none of that should be
 * left sitting in the queue when the next test boots a new app.
 *
 * <h2>2026-08-30, wave 6, U-45: one generation was not enough</h2>
 *
 * <p>The shape above still failed about one full build in three, in
 * {@code BotInteractionTest} and once in {@code TakeExamInteractionTest}, always as
 * {@code NullPointerException ... AbstractScreen.eventBus() is null} while a screen built.
 * The gap is that a single latch only proves the queue as it stood <i>when the latch was
 * posted</i> is empty. A runnable ahead of the latch may post another - a navigation that
 * builds a screen, a build that posts its own follow-up - and that second generation lands
 * <b>behind</b> the latch, so the drain returns with work still queued and the reset runs on
 * top of it. {@link #drainFxEvents()} therefore drains two generations, and each generation
 * also hands control to TestFX's {@code WaitForAsyncUtils.waitForFxEvents()}, which pumps the
 * queue and is what the interaction tests themselves wait on.
 *
 * <p>Two generations is a window, not a proof: no drain can wait for a background thread that
 * has not posted yet. The other half of the fix is that a screen built into the emptied world
 * no longer throws - {@code AbstractScreen}'s bus, dispatcher and connection accessors hand
 * back inert detached collaborators when the manager has no event bus, so the late build
 * paints into a scene nobody will show and is discarded with it.
 *
 * <p>Lives in {@code client.core} on purpose: {@code resetForTests()} is
 * package-private, so a harness in this package calls it directly and the
 * copied {@code setAccessible} reflection disappears from all fourteen tests.
 */
public final class FxTestHarness {

    /**
     * How long a drain waits before giving up. Generous: a drain that times out
     * is a bug report, not a reason to hang a build for minutes.
     */
    private static final long DRAIN_TIMEOUT_SECONDS = 10;

    private FxTestHarness() {
    }

    /**
     * The whole {@code @AfterEach} body for an app-booting interaction test:
     * drain, hide-and-discard the singleton, drain again, clear the flags that
     * are read from system properties.
     *
     * <p>Safe to call when the toolkit was never started or the app never
     * booted — both degrade to doing nothing.
     */
    public static void resetGlobalState() {
        drainFxEvents();
        runOnFxThreadAndWait(ScreenManager::resetForTests);
        drainFxEvents();
        System.clearProperty(AppArgs.PROP_GALLERY);
    }

    /**
     * Blocks until every {@code Platform.runLater} runnable queued before this
     * call has run, and then until everything <i>those</i> runnables queued has
     * run too (U-45).
     *
     * <p>The mechanism is the FIFO ordering of the FX event queue and nothing
     * else: put a latch-releasing runnable at the back of the queue, wait for
     * it, and everything ahead of it is necessarily done. One pass is not
     * enough, because a runnable ahead of the latch can post work behind it —
     * which is precisely how a screen came to be built after its world was
     * discarded. It does <b>not</b> wait for background threads that have not
     * posted yet — that is what step 2 of the class contract is for.
     */
    public static void drainFxEvents() {
        drainOneGeneration();
        drainOneGeneration();
    }

    /** One latch to the back of the queue, then TestFX's own pump. */
    private static void drainOneGeneration() {
        runOnFxThreadAndWait(() -> {
        });
        waitForTestFxEvents();
    }

    /**
     * Hands control to {@code WaitForAsyncUtils.waitForFxEvents()}, which pumps the FX queue
     * the same way the interaction tests do while they wait for a screen to appear.
     *
     * <p>Swallows the two conditions that are normal in a teardown, exactly as
     * {@link #runOnFxThreadAndWait(Runnable)} does: no toolkit was ever started, and a call
     * made from the FX thread itself, where waiting for the queue would be waiting for the
     * caller.
     */
    private static void waitForTestFxEvents() {
        if (Platform.isFxApplicationThread()) {
            return;
        }
        try {
            WaitForAsyncUtils.waitForFxEvents();
        } catch (RuntimeException noToolkit) {
            // Nothing was booted, or the pump gave up; either way there is nothing to drain.
        }
    }

    /**
     * Runs {@code work} on the FX thread and waits for it, swallowing the two
     * conditions that are normal in a test teardown: the toolkit was never
     * started, and this call is already on the FX thread (in which case the
     * work simply runs inline, since queueing it would deadlock on our own
     * latch).
     */
    private static void runOnFxThreadAndWait(Runnable work) {
        if (Platform.isFxApplicationThread()) {
            work.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        try {
            Platform.runLater(() -> {
                try {
                    work.run();
                } finally {
                    done.countDown();
                }
            });
        } catch (IllegalStateException toolkitNotStarted) {
            // Nothing was ever booted, so there is nothing to drain or reset.
            return;
        }
        try {
            if (!done.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("FX teardown did not complete within "
                        + DRAIN_TIMEOUT_SECONDS + "s; the FX thread is blocked");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while draining the FX queue", e);
        }
    }
}
