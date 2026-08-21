package server.console;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * The last N log lines, and no more (Logic tier, E19.4).
 *
 * <p>A server that runs through a two-hour defence produces a great many log
 * lines and the console shows the recent ones. Bounding the buffer is therefore
 * not an optimisation, it is the feature: an unbounded list would grow for the
 * lifetime of the process to hold text nobody will scroll back to, and the one
 * machine where that matters is the demo laptop.
 *
 * <p>{@link #DEFAULT_CAPACITY} is two thousand lines. That is far more than fits
 * on a screen and comfortably more than one demo scenario produces, so "scroll
 * up to see what happened when the student's timer expired" always works, while
 * the memory it costs is a few hundred kilobytes of already-formatted strings.
 *
 * <p><b>Thread safety.</b> Writes arrive on whichever thread logged, reads happen
 * on the FX thread, and both are serialised on one monitor. Reads copy, so a
 * caller iterating a snapshot can never see it change underneath, which is the
 * usual way a log viewer acquires a
 * {@link java.util.ConcurrentModificationException} at the worst moment.
 */
public final class LogRingBuffer {

    /** Lines kept before the oldest is dropped. */
    public static final int DEFAULT_CAPACITY = 2000;

    private final int capacity;
    private final Deque<LogLine> lines;
    private long totalAccepted;

    /** A buffer of {@link #DEFAULT_CAPACITY} lines. */
    public LogRingBuffer() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * @param capacity how many lines to keep; must be positive
     * @throws IllegalArgumentException on a capacity of zero or less, which would
     *         be a silently empty log pane
     */
    public LogRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("A log buffer needs room for at least one line");
        }
        this.capacity = capacity;
        this.lines = new ArrayDeque<>(Math.min(capacity, 256));
    }

    /** Appends a line, dropping the oldest when full. */
    public void add(LogLine line) {
        Objects.requireNonNull(line, "line");
        synchronized (this) {
            if (lines.size() == capacity) {
                lines.removeFirst();
            }
            lines.addLast(line);
            totalAccepted++;
        }
    }

    /** @return every retained line, oldest first, as an immutable snapshot. */
    public List<LogLine> snapshot() {
        synchronized (this) {
            return List.copyOf(lines);
        }
    }

    /**
     * @param minimum the filter level
     * @param search  free text to match against logger and message; blank means
     *                no text filter
     * @return the retained lines that pass both filters, oldest first
     */
    public List<LogLine> snapshot(LogLevel minimum, String search) {
        Objects.requireNonNull(minimum, "minimum");
        return snapshot().stream()
                .filter(line -> minimum.includes(line.level()))
                .filter(line -> line.matches(search))
                .toList();
    }

    /** Empties the buffer. The console's "clear" button; the counter survives. */
    public void clear() {
        synchronized (this) {
            lines.clear();
        }
    }

    /** @return how many lines are retained right now. */
    public int size() {
        synchronized (this) {
            return lines.size();
        }
    }

    /** @return the buffer's fixed capacity. */
    public int capacity() {
        return capacity;
    }

    /**
     * @return how many lines have ever been offered, including the ones since
     *         dropped. The difference from {@link #size()} is what tells an
     *         operator that the pane is a window and not the whole story.
     */
    public long totalAccepted() {
        synchronized (this) {
            return totalAccepted;
        }
    }
}
