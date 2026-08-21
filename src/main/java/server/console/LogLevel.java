package server.console;

import java.util.Locale;
import java.util.Optional;

/**
 * The five severities the console's log tail filters on (Logic tier, E19.4).
 *
 * <p>A small enum of our own rather than {@code ch.qos.logback.classic.Level},
 * for one reason worth stating: the console's filtering logic is unit-tested
 * without a logging framework attached, and a model that imported a logback type
 * would drag the whole framework into tests whose subject is "does the WARN
 * filter hide INFO lines". The mapping happens once, in
 * {@link RingBufferAppender}, at the boundary.
 *
 * <p>Declared in ascending severity, so {@link #ordinal()} <em>is</em> the
 * comparison and {@link #includes} needs no table.
 */
public enum LogLevel {

    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR;

    /** The filter the console starts on: everything the server routinely says. */
    public static final LogLevel DEFAULT_FILTER = INFO;

    /**
     * @param other the level of a line
     * @return {@code true} when a filter set to this level should show that line
     */
    public boolean includes(LogLevel other) {
        return other != null && other.ordinal() >= ordinal();
    }

    /**
     * Parses a level name from a logging framework.
     *
     * @param name the framework's name for the level, any case
     * @return the matching level, empty when unrecognised
     */
    public static Optional<LogLevel> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String trimmed = name.trim().toUpperCase(Locale.ROOT);
        for (LogLevel level : values()) {
            if (level.name().equals(trimmed)) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }

    /**
     * The style class the console's log pane paints this level with.
     *
     * <p>Named to match {@code hsts.css}'s existing severity helpers rather than
     * inventing a fourth vocabulary for the same four colours.
     *
     * @return a style class from the design system's token layer
     */
    public String styleClass() {
        return switch (this) {
            case ERROR -> "danger-text";
            case WARN -> "warn-text";
            case INFO -> "body";
            case DEBUG, TRACE -> "faint";
        };
    }
}
