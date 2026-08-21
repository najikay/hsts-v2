package server.console;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;

/**
 * The logback appender that feeds the console's log tail (Logic tier, E3.6 /
 * E19.4).
 *
 * <p>Registered in {@code logback.xml} rather than attached from Java, and that
 * placement is deliberate. An appender added programmatically starts collecting
 * only once the code that adds it has run, which on this server would be after
 * configuration, after Flyway and after the pool comes up. Those are precisely
 * the lines an operator most wants in the pane when a first boot goes wrong, so
 * the appender is configured with the framework and is capturing before the first
 * line of {@code ServerMain} executes.
 *
 * <h2>Finding the buffer from the console</h2>
 *
 * <p>Because logback constructs the appender, nobody holds a reference to it.
 * {@link #buffer()} is the way back: the appender publishes its buffer on
 * {@link #start()} and the console reads it. It is a static, which is a cost, and
 * it buys the console a live log without logback's configuration having to be
 * assembled in Java. When no such appender is configured, {@link #buffer()}
 * answers an empty buffer that nothing ever writes to, so a headless run and a
 * misconfigured logback both degrade to an empty pane rather than a null.
 *
 * <h2>What is kept</h2>
 *
 * <p>The message is formatted here, on the logging thread, and only the string is
 * retained (see {@link LogLine}). A throwable contributes its class and message
 * as one extra clause; the stack trace is not kept, because the pane is a tail
 * and the trace is in the file appender where somebody can read all of it.
 */
public class RingBufferAppender extends AppenderBase<ILoggingEvent> {

    /** Answered by {@link #buffer()} when no appender has started. */
    private static final LogRingBuffer NOT_CONFIGURED = new LogRingBuffer(1);

    private static volatile LogRingBuffer published;

    private int capacity = LogRingBuffer.DEFAULT_CAPACITY;
    private LogRingBuffer buffer;

    /**
     * The buffer the console reads.
     *
     * @return the buffer of the most recently started appender, or an empty
     *         buffer that never fills when logback has none configured
     */
    public static LogRingBuffer buffer() {
        LogRingBuffer current = published;
        return current == null ? NOT_CONFIGURED : current;
    }

    /** @return {@code true} when logback actually started one of these. */
    public static boolean isConfigured() {
        return published != null;
    }

    /**
     * Drops the published buffer. Test-only seam: one test's appender must not
     * still be the one a later test reads.
     */
    static void unpublish() {
        published = null;
    }

    /** Setter for {@code <capacity>} in {@code logback.xml}. */
    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public int getCapacity() {
        return capacity;
    }

    @Override
    public void start() {
        if (capacity <= 0) {
            // addError is logback's own channel for a bad configuration; throwing
            // here would take the whole logging system down over a typo in an
            // optional pane.
            addError("Ring buffer capacity must be positive, was " + capacity
                    + ". Fix the <capacity> element in logback.xml.");
            capacity = LogRingBuffer.DEFAULT_CAPACITY;
        }
        buffer = new LogRingBuffer(capacity);
        published = buffer;
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        if (published == buffer) {
            published = null;
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        LogRingBuffer target = buffer;
        if (target == null || event == null) {
            return;
        }
        target.add(toLine(event));
    }

    /** Visible for testing: the whole mapping from a logback event to a line. */
    static LogLine toLine(ILoggingEvent event) {
        LogLevel level = LogLevel.parse(String.valueOf(event.getLevel())).orElse(LogLevel.INFO);
        return new LogLine(Instant.ofEpochMilli(event.getTimeStamp()), level,
                shortLoggerName(event.getLoggerName()),
                event.getFormattedMessage() + throwableClause(event.getThrowableProxy()));
    }

    /**
     * @return the last two segments of a logger name
     *         ({@code "features.locks.EditLockService"}), which fits the pane and
     *         still says which feature spoke
     */
    static String shortLoggerName(String loggerName) {
        if (loggerName == null || loggerName.isEmpty()) {
            return "";
        }
        int lastDot = loggerName.lastIndexOf('.');
        if (lastDot < 0) {
            return loggerName;
        }
        int secondLastDot = loggerName.lastIndexOf('.', lastDot - 1);
        return loggerName.substring(secondLastDot + 1);
    }

    /** @return {@code " | java.io.IOException: broken pipe"}, or empty text. */
    static String throwableClause(IThrowableProxy throwable) {
        if (throwable == null) {
            return "";
        }
        String message = throwable.getMessage();
        return " | " + throwable.getClassName() + (message == null ? "" : ": " + message);
    }
}
