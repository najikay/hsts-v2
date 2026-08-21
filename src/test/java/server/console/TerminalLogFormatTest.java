package server.console;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The terminal log stream the defence is run from (E20.3).
 *
 * <p>Asserted against the shipped {@code logback.xml} through the live logging
 * context, not against a copy of the pattern: the value of this test is that the
 * configuration the JARs carry produces a readable, coloured line, and a test that
 * declared its own pattern would keep passing after somebody edited the real one.
 *
 * <p>Colour is checked as ANSI escapes rather than as "looks nice", and the last
 * two tests are the two ways this pattern can quietly break: escape codes leaking
 * into the console window's log pane (which formats events itself and must never
 * see this pattern), and padding applied outside a colour converter, which counts
 * escape characters as letters and makes the message column drift.
 */
class TerminalLogFormatTest {

    private static final Instant T0 = Instant.parse("2026-08-21T08:41:36Z");

    /** Every ANSI colour sequence, so a rendered line can be measured as text. */
    private static final String ANSI = "\u001B\\[[;\\d]*m";

    private static LoggerContext liveContext() {
        assertThat(LoggerFactory.getILoggerFactory())
                .as("the build's own logging context")
                .isInstanceOf(LoggerContext.class);
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    private static Appender<ch.qos.logback.classic.spi.ILoggingEvent> rootAppender(String name) {
        return liveContext().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender(name);
    }

    /** The pattern the shipped configuration actually writes to the terminal with. */
    private static String shippedPattern() {
        Appender<?> console = rootAppender("CONSOLE");
        assertThat(console)
                .as("logback.xml declares a console appender on the root logger")
                .isInstanceOf(ConsoleAppender.class);
        Object encoder = ((ConsoleAppender<?>) console).getEncoder();
        assertThat(encoder)
                .as("the console appender formats with a pattern")
                .isInstanceOf(PatternLayoutEncoder.class);
        return ((PatternLayoutEncoder) encoder).getPattern();
    }

    private static String render(Level level, String logger, String message) {
        PatternLayout layout = new PatternLayout();
        layout.setContext(liveContext());
        layout.setPattern(shippedPattern());
        layout.start();
        try {
            return layout.doLayout(event(level, logger, message));
        } finally {
            layout.stop();
        }
    }

    private static LoggingEvent event(Level level, String logger, String message) {
        LoggingEvent logged = new LoggingEvent();
        logged.setLevel(level);
        logged.setLoggerName(logger);
        logged.setMessage(message);
        logged.setTimeStamp(T0.toEpochMilli());
        return logged;
    }

    /** The colour sequence a rendered line opens with, for comparing severities. */
    private static String colourOf(String rendered, String around) {
        int at = rendered.indexOf(around);
        assertThat(at).as("'%s' appears in the rendered line", around).isGreaterThan(0);
        String before = rendered.substring(0, at);
        int lastEscape = before.lastIndexOf('\u001B');
        assertThat(lastEscape).as("'%s' is preceded by a colour", around).isNotNegative();
        return before.substring(lastEscape);
    }

    @Test
    @DisplayName("the line carries the time, the level, the logger and the message")
    void everyFieldIsPresent() {
        String rendered = render(Level.INFO, "server.features.exams.ExamService", "Exam 12 started");
        String plain = rendered.replaceAll(ANSI, "");

        assertThat(plain)
                .contains("INFO")
                .contains("ExamService")
                .contains("Exam 12 started")
                .endsWith(System.lineSeparator());
        assertThat(plain).as("the line opens with a wall-clock time")
                .matches("(?s)^\\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d .*");
        assertThat(plain).as("every converter in the pattern was understood").doesNotContain("%");
    }

    @Test
    @DisplayName("severity is colour-coded, and the three that matter are told apart")
    void severitiesAreDistinctColours() {
        String error = colourOf(render(Level.ERROR, "server.core.HSTSServer", "boom"), "ERROR");
        String warn = colourOf(render(Level.WARN, "server.core.HSTSServer", "hmm"), "WARN");
        String info = colourOf(render(Level.INFO, "server.core.HSTSServer", "fine"), "INFO");

        assertThat(error).startsWith("\u001B[");
        assertThat(List.of(error, warn, info)).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the terminal gets colour; the console window's log pane never sees it (E19.4)")
    void theRingBufferIsUnaffected() {
        assertThat(rootAppender("RING"))
                .as("the ring buffer appender is still attached beside the console one")
                .isInstanceOf(RingBufferAppender.class);
        assertThat(((RingBufferAppender) rootAppender("RING")).getCapacity())
                .as("and still 2000 lines deep")
                .isEqualTo(2000);

        LogLine line = RingBufferAppender.toLine(
                event(Level.ERROR, "server.features.exams.ExamService", "Exam 12 failed"));

        assertThat(line.message()).doesNotContain("\u001B");
        assertThat(line.logger()).doesNotContain("\u001B");
    }

    @Test
    @DisplayName("messages line up: padding is inside the colours, not around them")
    void theMessageColumnDoesNotDrift() {
        String shortLogger = render(Level.INFO, "server.db.DbBootstrap", "ready").replaceAll(ANSI, "");
        String longLogger = render(Level.INFO,
                "server.features.questions.QuestionCatalogueService", "ready").replaceAll(ANSI, "");

        assertThat(longLogger.indexOf("ready"))
                .as("a long logger name must not push the message out of its column")
                .isEqualTo(shortLogger.indexOf("ready"));
    }
}
