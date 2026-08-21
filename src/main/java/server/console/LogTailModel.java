package server.console;

import java.util.List;
import java.util.Objects;

/**
 * What the console's log pane is currently showing (Logic tier, E19.4).
 *
 * <p>All three controls of the pane live here, and none of them touches JavaFX:
 * the level filter, the free-text filter and pause/resume. The view asks for
 * {@link #visibleLines()} on a timer and renders whatever comes back.
 *
 * <h2>Pause freezes the view, never the capture</h2>
 *
 * <p>The obvious implementation of a pause button is to stop feeding the buffer.
 * It is also the wrong one, and the reason is exactly why an operator presses
 * pause: something interesting scrolled past and they want to read it. If pausing
 * stopped capture, everything that happened while they read would be gone, so the
 * button would destroy the evidence it was pressed to preserve.
 *
 * <p>So the buffer keeps filling and this class freezes the snapshot the pane
 * renders. {@link #pendingWhilePaused()} is how the view says "142 new lines" on
 * the resume button, and resuming shows them: nothing was lost, it was only held.
 */
public final class LogTailModel {

    private final LogRingBuffer buffer;

    private LogLevel minimum = LogLevel.DEFAULT_FILTER;
    private String search = "";
    private boolean paused;
    private List<LogLine> frozen = List.of();
    private long frozenAtCount;

    /** @param buffer the appender's buffer; see {@link RingBufferAppender#buffer()} */
    public LogTailModel(LogRingBuffer buffer) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
    }

    // ------------------------------------------------------------- filtering

    /** @return the level the pane is filtered to. */
    public LogLevel minimumLevel() {
        return minimum;
    }

    /**
     * Sets the level filter.
     *
     * <p>Applies to the frozen snapshot too: changing the filter while paused
     * re-filters what is on screen rather than requiring a resume, because
     * "show me only the errors in what I just saw" is the reason somebody
     * paused.
     */
    public void setMinimumLevel(LogLevel level) {
        this.minimum = Objects.requireNonNull(level, "level");
    }

    /** @return the free-text filter, never null. */
    public String search() {
        return search;
    }

    /** Sets the free-text filter; blank clears it. */
    public void setSearch(String text) {
        this.search = text == null ? "" : text;
    }

    // ------------------------------------------------------------- pausing

    public boolean isPaused() {
        return paused;
    }

    /**
     * Pauses or resumes the pane.
     *
     * <p>Pausing takes the snapshot that stays on screen; resuming discards it and
     * the pane catches up with everything that arrived meanwhile.
     */
    public void setPaused(boolean paused) {
        if (paused == this.paused) {
            return;
        }
        this.paused = paused;
        if (paused) {
            frozen = buffer.snapshot();
            frozenAtCount = buffer.totalAccepted();
        } else {
            frozen = List.of();
        }
    }

    /** Convenience for a toggle button. @return the state after the toggle */
    public boolean togglePaused() {
        setPaused(!paused);
        return paused;
    }

    /**
     * @return how many lines the server has logged since the pause began, zero
     *         when running. Lines dropped by the ring buffer still count: the
     *         number answers "how much have I not seen", which is the operator's
     *         question.
     */
    public long pendingWhilePaused() {
        return paused ? Math.max(0, buffer.totalAccepted() - frozenAtCount) : 0;
    }

    // ------------------------------------------------------------- rendering

    /** @return the lines the pane should show right now, oldest first. */
    public List<LogLine> visibleLines() {
        List<LogLine> source = paused ? frozen : buffer.snapshot();
        return source.stream()
                .filter(line -> minimum.includes(line.level()))
                .filter(line -> line.matches(search))
                .toList();
    }

    /**
     * @return the one-line status under the pane, saying what is being shown and,
     *         when paused, what is being held back
     */
    public String statusText() {
        int shown = visibleLines().size();
        StringBuilder status = new StringBuilder()
                .append(shown).append(shown == 1 ? " line" : " lines")
                .append(" at ").append(minimum.name()).append(" and above");
        if (!search.isBlank()) {
            status.append(" matching \"").append(search.trim()).append('"');
        }
        if (paused) {
            long pending = pendingWhilePaused();
            status.append(". Paused, ").append(pending)
                    .append(pending == 1 ? " new line" : " new lines")
                    .append(" waiting. Press Resume to catch up.");
        }
        return status.toString();
    }

    /** Empties the underlying buffer and any frozen snapshot. */
    public void clear() {
        buffer.clear();
        frozen = List.of();
        frozenAtCount = buffer.totalAccepted();
    }

    /** @return the buffer this model reads. */
    public LogRingBuffer buffer() {
        return buffer;
    }
}
