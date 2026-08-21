package server.console;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The log tail: buffer, appender and view model (E3.6 / E19.4).
 *
 * <p>Tested without the console, which is the point of the split. The appender is
 * driven with real logback events, the buffer with a loop, and the pause semantics
 * with a counter rather than with a wall clock.
 */
class LogTailTest {

    private static final Instant T0 = Instant.parse("2026-08-20T09:00:00Z");

    private static LogLine line(LogLevel level, String message) {
        return new LogLine(T0, level, "server.core.HSTSServer", message);
    }

    @Nested
    @DisplayName("the ring buffer")
    class Buffer {

        @Test
        @DisplayName("keeps the newest lines and drops the oldest when full")
        void bounded() {
            LogRingBuffer buffer = new LogRingBuffer(3);

            for (int i = 1; i <= 5; i++) {
                buffer.add(line(LogLevel.INFO, "line " + i));
            }

            assertThat(buffer.size()).isEqualTo(3);
            assertThat(buffer.snapshot()).extracting(LogLine::message)
                    .as("a tail shows the end, oldest first among what it kept")
                    .containsExactly("line 3", "line 4", "line 5");
            assertThat(buffer.capacity()).isEqualTo(3);
        }

        @Test
        @DisplayName("counts every line ever offered, including the dropped ones")
        void totalAccepted() {
            LogRingBuffer buffer = new LogRingBuffer(2);
            for (int i = 0; i < 7; i++) {
                buffer.add(line(LogLevel.INFO, "x"));
            }

            assertThat(buffer.totalAccepted())
                    .as("the difference from size() is what says the pane is a window")
                    .isEqualTo(7);
            assertThat(buffer.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("filters by level and by text")
        void filtering() {
            LogRingBuffer buffer = new LogRingBuffer(10);
            buffer.add(line(LogLevel.DEBUG, "pool opened"));
            buffer.add(line(LogLevel.INFO, "client connected"));
            buffer.add(line(LogLevel.ERROR, "client exploded"));

            assertThat(buffer.snapshot(LogLevel.INFO, "")).hasSize(2);
            assertThat(buffer.snapshot(LogLevel.ERROR, "")).hasSize(1);
            assertThat(buffer.snapshot(LogLevel.TRACE, "client")).hasSize(2);
            assertThat(buffer.snapshot(LogLevel.TRACE, "  ")).hasSize(3);
        }

        @Test
        @DisplayName("clearing empties the lines and keeps the running total")
        void clearing() {
            LogRingBuffer buffer = new LogRingBuffer(4);
            buffer.add(line(LogLevel.INFO, "a"));

            buffer.clear();

            assertThat(buffer.size()).isZero();
            assertThat(buffer.totalAccepted()).isEqualTo(1);
        }

        @Test
        @DisplayName("a buffer with no room is a configuration error, not a silent empty pane")
        void capacityMustBePositive() {
            assertThatIllegalArgumentException().isThrownBy(() -> new LogRingBuffer(0));
            assertThatIllegalArgumentException().isThrownBy(() -> new LogRingBuffer(-1));
            assertThatNullPointerException().isThrownBy(() -> new LogRingBuffer(1).add(null));
            assertThat(new LogRingBuffer().capacity()).isEqualTo(LogRingBuffer.DEFAULT_CAPACITY);
        }

        @Test
        @DisplayName("concurrent writers never corrupt it")
        void threadSafe() throws Exception {
            LogRingBuffer buffer = new LogRingBuffer(500);
            List<Thread> writers = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(id -> new Thread(() -> {
                        for (int i = 0; i < 250; i++) {
                            buffer.add(line(LogLevel.INFO, id + ":" + i));
                        }
                    })).toList();

            writers.forEach(Thread::start);
            for (Thread writer : writers) {
                writer.join();
            }

            assertThat(buffer.totalAccepted()).isEqualTo(1000);
            assertThat(buffer.size()).isEqualTo(500);
        }
    }

    @Nested
    @DisplayName("the view model")
    class Model {

        private LogRingBuffer buffer;
        private LogTailModel model;

        @BeforeEach
        void setUp() {
            buffer = new LogRingBuffer(100);
            model = new LogTailModel(buffer);
        }

        @Test
        @DisplayName("starts at INFO and hides debug noise")
        void defaultFilter() {
            buffer.add(line(LogLevel.DEBUG, "hidden"));
            buffer.add(line(LogLevel.INFO, "shown"));

            assertThat(model.minimumLevel()).isEqualTo(LogLevel.INFO);
            assertThat(model.visibleLines()).extracting(LogLine::message).containsExactly("shown");
        }

        @Test
        @DisplayName("pausing freezes the view and never the capture")
        void pauseFreezesTheViewOnly() {
            buffer.add(line(LogLevel.INFO, "before"));

            model.setPaused(true);
            buffer.add(line(LogLevel.INFO, "during"));
            buffer.add(line(LogLevel.INFO, "also during"));

            assertThat(model.visibleLines()).extracting(LogLine::message)
                    .as("what the operator is reading stays on screen")
                    .containsExactly("before");
            assertThat(model.pendingWhilePaused())
                    .as("and the resume button can say how much is waiting")
                    .isEqualTo(2);

            model.setPaused(false);

            assertThat(model.visibleLines()).extracting(LogLine::message)
                    .as("nothing was lost, it was held")
                    .containsExactly("before", "during", "also during");
            assertThat(model.pendingWhilePaused()).isZero();
        }

        @Test
        @DisplayName("the level filter applies to a frozen snapshot too")
        void filteringWhilePaused() {
            buffer.add(line(LogLevel.INFO, "ordinary"));
            buffer.add(line(LogLevel.ERROR, "the interesting one"));
            model.setPaused(true);

            model.setMinimumLevel(LogLevel.ERROR);

            assertThat(model.visibleLines()).extracting(LogLine::message)
                    .as("\"show me only the errors in what I just saw\" is why people pause")
                    .containsExactly("the interesting one");
        }

        @Test
        @DisplayName("pausing twice is not a double freeze, and toggling reports the new state")
        void pauseIsIdempotent() {
            buffer.add(line(LogLevel.INFO, "one"));
            model.setPaused(true);
            buffer.add(line(LogLevel.INFO, "two"));
            model.setPaused(true);

            assertThat(model.visibleLines()).hasSize(1);
            assertThat(model.togglePaused()).isFalse();
            assertThat(model.togglePaused()).isTrue();
            assertThat(model.isPaused()).isTrue();
        }

        @Test
        @DisplayName("free text narrows by message and by logger")
        void search() {
            buffer.add(line(LogLevel.INFO, "client connected"));
            buffer.add(new LogLine(T0, LogLevel.INFO, "features.locks.EditLockService", "held"));

            model.setSearch("locks");
            assertThat(model.visibleLines()).hasSize(1);

            model.setSearch("CONNECTED");
            assertThat(model.visibleLines())
                    .as("matching is case-insensitive; nobody types the log's casing")
                    .hasSize(1);

            model.setSearch(null);
            assertThat(model.search()).isEmpty();
            assertThat(model.visibleLines()).hasSize(2);
        }

        @Test
        @DisplayName("the status line says what is shown and what is held back")
        void statusText() {
            buffer.add(line(LogLevel.INFO, "one"));

            assertThat(model.statusText()).isEqualTo("1 line at INFO and above");

            buffer.add(line(LogLevel.INFO, "two"));
            model.setSearch("two");
            assertThat(model.statusText())
                    .isEqualTo("1 line at INFO and above matching \"two\"");

            model.setSearch("");
            model.setPaused(true);
            buffer.add(line(LogLevel.INFO, "three"));
            assertThat(model.statusText())
                    .contains("2 lines at INFO and above")
                    .contains("Paused, 1 new line waiting")
                    .contains("Press Resume to catch up");
        }

        @Test
        @DisplayName("clearing empties the pane and resets the pending count")
        void clearing() {
            buffer.add(line(LogLevel.INFO, "one"));
            model.setPaused(true);
            buffer.add(line(LogLevel.INFO, "two"));

            model.clear();

            assertThat(model.visibleLines()).isEmpty();
            assertThat(model.pendingWhilePaused()).isZero();
            assertThat(model.buffer()).isSameAs(buffer);
        }

        @Test
        @DisplayName("collaborators are required")
        void required() {
            assertThatNullPointerException().isThrownBy(() -> new LogTailModel(null));
            assertThatNullPointerException().isThrownBy(() -> model.setMinimumLevel(null));
        }
    }

    @Nested
    @DisplayName("the appender")
    class Appender {

        private LoggerContext context;

        @BeforeEach
        void setUp() {
            context = new LoggerContext();
            RingBufferAppender.unpublish();
        }

        @AfterEach
        void tearDown() {
            RingBufferAppender.unpublish();
        }

        private LoggingEvent event(Level level, String logger, String message, Throwable failure) {
            LoggingEvent logged = new LoggingEvent();
            logged.setLevel(level);
            logged.setLoggerName(logger);
            logged.setMessage(message);
            logged.setTimeStamp(T0.toEpochMilli());
            if (failure != null) {
                logged.setThrowableProxy(new ThrowableProxy(failure));
            }
            return logged;
        }

        @Test
        @DisplayName("publishes its buffer on start and withdraws it on stop")
        void publishing() {
            RingBufferAppender appender = new RingBufferAppender();
            appender.setContext(context);
            appender.setCapacity(5);

            assertThat(RingBufferAppender.isConfigured()).isFalse();
            assertThat(RingBufferAppender.buffer().size())
                    .as("an unconfigured logback yields an empty pane, never a null")
                    .isZero();

            appender.start();

            assertThat(RingBufferAppender.isConfigured()).isTrue();
            assertThat(RingBufferAppender.buffer().capacity()).isEqualTo(5);
            assertThat(appender.getCapacity()).isEqualTo(5);

            appender.stop();
            assertThat(RingBufferAppender.isConfigured()).isFalse();
        }

        @Test
        @DisplayName("captures events as already-formatted lines")
        void captures() {
            RingBufferAppender appender = new RingBufferAppender();
            appender.setContext(context);
            appender.start();

            appender.doAppend(event(Level.WARN, "server.core.HSTSServer", "socket died", null));

            List<LogLine> lines = RingBufferAppender.buffer().snapshot();
            assertThat(lines).hasSize(1);
            assertThat(lines.get(0).level()).isEqualTo(LogLevel.WARN);
            assertThat(lines.get(0).logger()).isEqualTo("core.HSTSServer");
            assertThat(lines.get(0).message()).isEqualTo("socket died");
            assertThat(lines.get(0).at()).isEqualTo(T0);
        }

        @Test
        @DisplayName("a throwable contributes one clause, never a stack trace")
        void throwables() {
            LogLine line = RingBufferAppender.toLine(
                    event(Level.ERROR, "a.b.C", "write failed",
                            new java.io.IOException("broken pipe")));

            assertThat(line.message())
                    .as("the trace is in the log file; the pane is a tail")
                    .isEqualTo("write failed | java.io.IOException: broken pipe");
        }

        @Test
        @DisplayName("a throwable with no message still names its class")
        void throwableWithoutMessage() {
            assertThat(RingBufferAppender.throwableClause(
                    new ThrowableProxy(new IllegalStateException())))
                    .isEqualTo(" | java.lang.IllegalStateException");
            assertThat(RingBufferAppender.throwableClause(null)).isEmpty();
        }

        @Test
        @DisplayName("logger names are shortened to their last two segments")
        void shortLoggerNames() {
            assertThat(RingBufferAppender.shortLoggerName("server.features.locks.EditLockService"))
                    .isEqualTo("locks.EditLockService");
            assertThat(RingBufferAppender.shortLoggerName("Bare")).isEqualTo("Bare");
            assertThat(RingBufferAppender.shortLoggerName("one.Two")).isEqualTo("one.Two");
            assertThat(RingBufferAppender.shortLoggerName(null)).isEmpty();
            assertThat(RingBufferAppender.shortLoggerName("")).isEmpty();
        }

        @Test
        @DisplayName("an unrecognised level is kept as INFO rather than dropped")
        void unknownLevel() {
            LoggingEvent logged = event(Level.OFF, "a.b.C", "odd", null);

            assertThat(RingBufferAppender.toLine(logged).level()).isEqualTo(LogLevel.INFO);
        }

        @Test
        @DisplayName("a bad capacity is corrected rather than allowed to kill logging")
        void badCapacity() {
            RingBufferAppender appender = new RingBufferAppender();
            appender.setContext(context);
            appender.setCapacity(0);

            appender.start();

            assertThat(appender.getCapacity()).isEqualTo(LogRingBuffer.DEFAULT_CAPACITY);
            assertThat(context.getStatusManager().getCopyOfStatusList())
                    .as("logback's own channel carries the configuration complaint")
                    .isNotEmpty();
            appender.stop();
        }

        @Test
        @DisplayName("appending before start, or a null event, is ignored")
        void defensive() {
            RingBufferAppender appender = new RingBufferAppender();
            appender.setContext(context);

            appender.append(event(Level.INFO, "a.b.C", "too early", null));
            appender.start();
            appender.append(null);

            assertThat(RingBufferAppender.buffer().size()).isZero();
            appender.stop();
        }

        @Test
        @DisplayName("the shipped logback.xml really registers one, so the console has a log")
        void configuredInProduction() {
            // The whole reason the appender is in logback.xml rather than attached
            // from Java: it must already be capturing before ServerMain runs.
            assertThat(LoggerFactory.getILoggerFactory())
                    .as("the build's own logging context")
                    .isInstanceOf(LoggerContext.class);
            LoggerContext live = (LoggerContext) LoggerFactory.getILoggerFactory();
            assertThat(live.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("RING"))
                    .as("logback.xml declares the ring appender on the root logger")
                    .isInstanceOf(RingBufferAppender.class);
        }
    }

    @Nested
    @DisplayName("levels and lines")
    class Values {

        @Test
        @DisplayName("a filter includes its own level and everything above it")
        void includes() {
            assertThat(LogLevel.INFO.includes(LogLevel.INFO)).isTrue();
            assertThat(LogLevel.INFO.includes(LogLevel.ERROR)).isTrue();
            assertThat(LogLevel.INFO.includes(LogLevel.DEBUG)).isFalse();
            assertThat(LogLevel.TRACE.includes(LogLevel.TRACE)).isTrue();
            assertThat(LogLevel.INFO.includes(null)).isFalse();
            assertThat(LogLevel.DEFAULT_FILTER).isEqualTo(LogLevel.INFO);
        }

        @Test
        @DisplayName("parsing is tolerant of case and refuses what it does not know")
        void parsing() {
            assertThat(LogLevel.parse("warn")).contains(LogLevel.WARN);
            assertThat(LogLevel.parse(" ERROR ")).contains(LogLevel.ERROR);
            assertThat(LogLevel.parse("OFF")).isEmpty();
            assertThat(LogLevel.parse(null)).isEmpty();
        }

        @Test
        @DisplayName("each level paints with an existing design-system class")
        void styleClasses() {
            assertThat(LogLevel.ERROR.styleClass()).isEqualTo("danger-text");
            assertThat(LogLevel.WARN.styleClass()).isEqualTo("warn-text");
            assertThat(LogLevel.INFO.styleClass()).isEqualTo("body");
            assertThat(LogLevel.DEBUG.styleClass()).isEqualTo("faint");
            assertThat(LogLevel.TRACE.styleClass()).isEqualTo("faint");
        }

        @Test
        @DisplayName("a line renders local wall-clock time, which is what the operator compares to")
        void display() {
            LogLine logLine = new LogLine(Instant.parse("2026-08-20T09:00:07.213Z"),
                    LogLevel.INFO, "core.HSTSServer", "Client connected");

            assertThat(logLine.display(ZoneId.of("UTC")))
                    .isEqualTo("09:00:07.213 INFO  core.HSTSServer  Client connected");
            assertThat(logLine.display()).contains("core.HSTSServer  Client connected");
        }

        @Test
        @DisplayName("a line tolerates a missing logger or message")
        void defensiveLine() {
            LogLine logLine = new LogLine(T0, LogLevel.INFO, null, null);

            assertThat(logLine.logger()).isEmpty();
            assertThat(logLine.message()).isEmpty();
            assertThatNullPointerException()
                    .isThrownBy(() -> new LogLine(null, LogLevel.INFO, "a", "b"));
            assertThatNullPointerException()
                    .isThrownBy(() -> new LogLine(T0, null, "a", "b"));
        }

        @Test
        @DisplayName("an empty search matches everything, so an empty box is not a filter")
        void matching() {
            LogLine logLine = line(LogLevel.INFO, "Client connected");

            assertThat(logLine.matches(null)).isTrue();
            assertThat(logLine.matches("   ")).isTrue();
            assertThat(logLine.matches("client")).isTrue();
            assertThat(logLine.matches("HSTSServer")).isTrue();
            assertThat(logLine.matches("nothing here")).isFalse();
        }
    }
}
