package server.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the server says when it cannot start (E19.2).
 *
 * <p>Each branch is asserted against the message text a real driver or Flyway
 * produces, because the sentence exists for one moment: a first boot on a machine
 * nobody has run MySQL on, minutes before somebody needs it working.
 */
class StartupMessagesTest {

    @Test
    @DisplayName("a rejected login points at server.properties")
    void badCredentials() {
        RuntimeException failure = new IllegalStateException("Unable to obtain connection",
                new SQLException("Access denied for user 'root'@'localhost' (using password: YES)"));

        assertThat(StartupMessages.migrationFailed(failure))
                .startsWith(StartupMessages.BAD_CREDENTIALS)
                .contains("Access denied for user");
    }

    @Test
    @DisplayName("a dead MySQL says to start the service")
    void unreachable() {
        RuntimeException failure = new IllegalStateException("boot failed",
                new SQLException("Communications link failure"));

        assertThat(StartupMessages.migrationFailed(failure))
                .startsWith(StartupMessages.DATABASE_UNREACHABLE);
        assertThat(StartupMessages.migrationFailed(
                new IllegalStateException(new SQLException("Connection refused"))))
                .startsWith(StartupMessages.DATABASE_UNREACHABLE);
        assertThat(StartupMessages.migrationFailed(
                new IllegalStateException("Could not create connection to database server")))
                .startsWith(StartupMessages.DATABASE_UNREACHABLE);
    }

    @Test
    @DisplayName("a Flyway checksum mismatch says to drop and rebuild")
    void schemaMismatch() {
        assertThat(StartupMessages.migrationFailed(new IllegalStateException(
                "Validate failed: Migration checksum mismatch for migration version 3")))
                .startsWith(StartupMessages.SCHEMA_MISMATCH);
    }

    @Test
    @DisplayName("anything unrecognised still gets a complete sentence and the detail")
    void fallback() {
        assertThat(StartupMessages.migrationFailed(new IllegalStateException("something odd")))
                .isEqualTo(StartupMessages.GENERIC_DATABASE_PROBLEM + " Details: something odd");
    }

    @Test
    @DisplayName("the innermost cause is the one quoted, because it is the one that explains")
    void rootMessage() {
        Throwable deep = new IllegalStateException("outer",
                new RuntimeException("middle", new SQLException("the actual problem")));

        assertThat(StartupMessages.rootMessage(deep)).isEqualTo("the actual problem");
    }

    @Test
    @DisplayName("a cause with no message falls back to its class name")
    void messagelessCause() {
        assertThat(StartupMessages.rootMessage(new IllegalStateException(new NullPointerException())))
                .isEqualTo("NullPointerException");
        assertThat(StartupMessages.rootMessage(new IllegalStateException("   ")))
                .isEqualTo("IllegalStateException");
        assertThat(StartupMessages.rootMessage(null)).isEqualTo("unknown failure");
    }

    @Test
    @DisplayName("a cause cycle is bounded rather than a hang while reporting a failure")
    void causeCycle() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(StartupMessages.rootMessage(first)).isNotNull();
    }

    @Test
    @DisplayName("no sentence uses an em dash, and every one names a next step")
    void houseCopyRule() {
        assertThat(java.util.List.of(StartupMessages.GENERIC_DATABASE_PROBLEM,
                        StartupMessages.BAD_CREDENTIALS, StartupMessages.DATABASE_UNREACHABLE,
                        StartupMessages.SCHEMA_MISMATCH))
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain("—")
                        .matches(text -> text.contains("Check") || text.contains("Start")
                                || text.contains("Drop")));
    }
}
