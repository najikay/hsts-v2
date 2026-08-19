package client.ui.components.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decides which toasts are on screen and which are waiting (Presentation tier,
 * E4.14).
 *
 * <p>Auto-save fires on every answer change (F6.3) and a flaky link can produce a
 * burst of failures; without a queue the top-right corner becomes a wall of
 * cards covering the exam. So:
 * <ul>
 *   <li>at most {@link #maxVisible()} toasts are shown at once;</li>
 *   <li>the overflow waits in FIFO order and is promoted as slots free up;</li>
 *   <li><b>identical consecutive toasts collapse</b> — ten "Answer saved" in a
 *       row is one toast, not ten, which is the single most valuable rule here.</li>
 * </ul>
 *
 * <p>Pure state machine: it emits "show this" / "hide that" callbacks and never
 * touches a node, so every ordering, overflow and dedup case is unit-tested. The
 * FX component wires {@link #onShow}/{@link #onHide} to the animated stack.
 */
public final class ToastQueue {

    /** Three cards is the most that fits under the navbar without dominating the screen. */
    public static final int DEFAULT_MAX_VISIBLE = 3;

    private final int maxVisible;
    private final List<ToastSpec> visible = new ArrayList<>();
    private final Deque<ToastSpec> waiting = new ArrayDeque<>();
    private final List<Consumer<ToastSpec>> showListeners = new ArrayList<>();
    private final List<Consumer<ToastSpec>> hideListeners = new ArrayList<>();

    private ToastSpec lastEnqueued;
    private int suppressedDuplicates;

    public ToastQueue() {
        this(DEFAULT_MAX_VISIBLE);
    }

    public ToastQueue(int maxVisible) {
        if (maxVisible < 1) {
            throw new IllegalArgumentException("maxVisible must be >= 1, was " + maxVisible);
        }
        this.maxVisible = maxVisible;
    }

    /** @return how many toasts may be on screen simultaneously. */
    public int maxVisible() {
        return maxVisible;
    }

    /**
     * Submits a toast.
     *
     * @return {@code true} when it was accepted, {@code false} when it collapsed
     *         into the identical toast submitted immediately before it
     */
    public boolean enqueue(ToastSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (spec.equals(lastEnqueued) && isStillPresent(spec)) {
            suppressedDuplicates++;
            return false;
        }
        lastEnqueued = spec;
        if (visible.size() < maxVisible) {
            visible.add(spec);
            fire(showListeners, spec);
        } else {
            waiting.addLast(spec);
        }
        return true;
    }

    /**
     * Removes a toast that has finished its dwell or was clicked away, promoting
     * the next waiting one into the freed slot.
     *
     * @return {@code true} when the toast was on screen and got removed
     */
    public boolean dismiss(ToastSpec spec) {
        if (!visible.remove(spec)) {
            return false;
        }
        fire(hideListeners, spec);
        promote();
        return true;
    }

    /** Dismisses the oldest visible toast; a no-op when nothing is showing. */
    public boolean dismissOldest() {
        return !visible.isEmpty() && dismiss(visible.get(0));
    }

    /** Clears everything — shown and waiting. Called on logout and on screen reset. */
    public void clear() {
        for (ToastSpec spec : List.copyOf(visible)) {
            fire(hideListeners, spec);
        }
        visible.clear();
        waiting.clear();
        lastEnqueued = null;
    }

    /** @return the toasts currently on screen, oldest first. */
    public List<ToastSpec> visible() {
        return List.copyOf(visible);
    }

    /** @return the toasts waiting for a slot, next first. */
    public List<ToastSpec> waiting() {
        return List.copyOf(waiting);
    }

    public int visibleCount() {
        return visible.size();
    }

    public int waitingCount() {
        return waiting.size();
    }

    /** @return how many submissions were collapsed as consecutive duplicates. */
    public int suppressedDuplicates() {
        return suppressedDuplicates;
    }

    /** Subscribes to "this toast should appear now". */
    public void onShow(Consumer<ToastSpec> listener) {
        showListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Subscribes to "this toast should disappear now". */
    public void onHide(Consumer<ToastSpec> listener) {
        hideListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void promote() {
        while (visible.size() < maxVisible && !waiting.isEmpty()) {
            ToastSpec next = waiting.pollFirst();
            visible.add(next);
            fire(showListeners, next);
        }
    }

    /**
     * Dedup only collapses a repeat of something the user can still see (or is
     * about to). Once a toast has left the screen, the same message again is new
     * information — the save failed <i>again</i>.
     */
    private boolean isStillPresent(ToastSpec spec) {
        return visible.contains(spec) || waiting.contains(spec);
    }

    private static void fire(List<Consumer<ToastSpec>> listeners, ToastSpec spec) {
        for (Consumer<ToastSpec> listener : List.copyOf(listeners)) {
            listener.accept(spec);
        }
    }
}
