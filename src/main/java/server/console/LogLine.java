package server.console;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * One line in the console's log tail (Logic tier, E19.4).
 *
 * <p>Already rendered: the appender formats the event's message with its
 * arguments once, on the logging thread, and this record carries the result. The
 * alternative, keeping the logback event and formatting on demand, would mean
 * holding two thousand live event objects, each with a reference to the argument
 * array it was logged with, for the lifetime of the process. A server console
 * that pins two thousand DTOs in memory because somebody logged them is a leak
 * with a nice window on it.
 *
 * @param at      when it was logged
 * @param level   its severity
 * @param logger  the short logger name (last segment), which is all the pane has
 *                room for
 * @param message the formatted message, plus the throwable's first line when
 *                there was one
 */
public record LogLine(Instant at, LogLevel level, String logger, String message) {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public LogLine {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(level, "level");
        logger = logger == null ? "" : logger;
        message = message == null ? "" : message;
    }

    /**
     * @param zone the operator's zone; the console renders local time because the
     *             operator is comparing it to the clock on the wall, not to a
     *             database row
     * @return {@code "22:14:07.213 INFO  server.core.HSTSServer  Client connected"}
     */
    public String display(ZoneId zone) {
        return CLOCK.format(at.atZone(zone)) + ' '
                + String.format("%-5s", level.name()) + ' '
                + logger + "  " + message;
    }

    /** @return {@link #display(ZoneId)} in the machine's own zone. */
    public String display() {
        return display(ZoneId.systemDefault());
    }

    /**
     * @param needle case-insensitive text to look for
     * @return {@code true} when the logger or the message contains it; a blank
     *         needle matches everything, so an empty search box is not a filter
     */
    public boolean matches(String needle) {
        if (needle == null || needle.isBlank()) {
            return true;
        }
        String lower = needle.trim().toLowerCase(java.util.Locale.ROOT);
        return message.toLowerCase(java.util.Locale.ROOT).contains(lower)
                || logger.toLowerCase(java.util.Locale.ROOT).contains(lower);
    }
}
