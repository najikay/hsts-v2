package server.db;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Proves the migrations run clean against a real MySQL (E2.1) — the Definition-of-Done
 * line "migrations run clean on an empty MySQL AND on top of the previous version".
 *
 * <p>Each test starts from a dropped-and-recreated throwaway schema
 * ({@link MySqlAvailability#TEST_SCHEMA}), never the developer's {@code hsts_db}.
 * The whole class is skipped where no MySQL answers — see {@link MySqlAvailability},
 * which is the single place that decision is made.
 *
 * <p>Beyond "it ran", the suite pins the things a later migration could silently break:
 * the exact table set, utf8mb4 everywhere (Hebrew must round-trip), and the constraints
 * that encode binding decisions — exactly one correct answer in 1..4 (C-8 / ADR-016),
 * pairwise-distinct answers, and one attempt per student per execution.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class FlywayCleanRunTest {

    /** Every table ARCHITECTURE §5 calls for. A new migration must extend this list. */
    private static final List<String> EXPECTED_TABLES = List.of(
            // V1 — core
            "subjects", "courses", "users", "course_teachers", "enrollments", "coordinators",
            // V2 — bank
            "questions", "question_versions",
            // V3 — exams
            "exams", "exam_versions", "exam_version_questions",
            // V4 — executions
            "exam_executions", "exam_attempts", "attempt_answers",
            // V5 — grading
            "grades",
            // V6 — bot
            "bots", "bot_sources", "bot_sessions", "bot_messages",
            // V7 — notifications
            "notifications");

    private static final int MIGRATION_COUNT = 7;
    private static final String LATEST_VERSION = "7";

    /** Hebrew, with an RTL mix, to prove utf8mb4 survives the whole round trip. */
    private static final String HEBREW_TEXT = "מהי תוצאת הביטוי 2+2 במערכת בינארית?";

    private HikariDataSource dataSource;

    @BeforeEach
    void recreateEmptySchema() throws SQLException {
        try (Connection connection = MySqlAvailability.openServerConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + MySqlAvailability.TEST_SCHEMA + "`");
            statement.execute("CREATE DATABASE `" + MySqlAvailability.TEST_SCHEMA + "`"
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        dataSource = DbBootstrap.dataSource(
                MySqlAvailability.schemaUrl(), MySqlAvailability.user(), MySqlAvailability.password());
    }

    @AfterEach
    void closePool() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @DisplayName("a clean run on an empty database applies all seven migrations")
    void cleanRunAppliesEveryMigration() {
        MigrateResult result = DbBootstrap.migrate(dataSource);

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(MIGRATION_COUNT);
        assertThat(result.targetSchemaVersion).isEqualTo(LATEST_VERSION);
        assertThat(DbBootstrap.flywayFor(dataSource).info().pending()).isEmpty();
    }

    @Test
    @DisplayName("every table in ARCHITECTURE §5 exists, and nothing else does")
    void schemaContainsExactlyTheExpectedTables() throws SQLException {
        DbBootstrap.migrate(dataSource);

        List<String> expected = new ArrayList<>(EXPECTED_TABLES);
        expected.add("flyway_schema_history");

        assertThat(tableNames()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("every table is utf8mb4 — Hebrew content must round-trip")
    void everyTableIsUtf8mb4() throws SQLException {
        DbBootstrap.migrate(dataSource);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT table_name, table_collation FROM information_schema.tables"
                             + " WHERE table_schema = ?")) {
            statement.setString(1, MySqlAvailability.TEST_SCHEMA);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    assertThat(rows.getString("table_collation"))
                            .as("collation of %s", rows.getString("table_name"))
                            .startsWith("utf8mb4");
                }
            }
        }
    }

    @Test
    @DisplayName("running migrate again on an up-to-date schema is a no-op")
    void reRunningMigrateChangesNothing() {
        DbBootstrap.migrate(dataSource);

        MigrateResult second = DbBootstrap.migrate(dataSource);

        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isZero();
        // Flyway reports no target version when it had nothing to apply, so the proof
        // that we are still at the head is the recorded current version.
        assertThat(DbBootstrap.flywayFor(dataSource).info().current().getVersion())
                .hasToString(LATEST_VERSION);
    }

    @Test
    @DisplayName("migrations apply on top of the previous version, not only from empty")
    void migratesOnTopOfThePreviousVersion() {
        MigrateResult upToSix = migrateTo("6");
        assertThat(upToSix.migrationsExecuted).isEqualTo(MIGRATION_COUNT - 1);
        assertThat(upToSix.targetSchemaVersion).isEqualTo("6");

        MigrateResult toLatest = DbBootstrap.migrate(dataSource);

        assertThat(toLatest.success).isTrue();
        assertThat(toLatest.migrationsExecuted).isOne();
        assertThat(toLatest.targetSchemaVersion).isEqualTo(LATEST_VERSION);
    }

    @Test
    @DisplayName("Hebrew text survives the round trip through question_versions")
    void hebrewRoundTrips() throws SQLException {
        DbBootstrap.migrate(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            seedMinimalGraph(connection);

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT text, a1 FROM question_versions WHERE id = 1");
                 ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("text")).isEqualTo(HEBREW_TEXT);
                assertThat(rows.getString("a1")).isEqualTo("ארבע");
            }
        }
    }

    @Test
    @DisplayName("correct_answer outside 1..4 is rejected (C-8 / ADR-016)")
    void correctAnswerMustBeOneToFour() throws SQLException {
        DbBootstrap.migrate(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            seedMinimalGraph(connection);

            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> insertQuestionVersion(connection, 2, "א", "ב", "ג", "ד", 5))
                    .withMessageContaining("ck_question_versions_correct");
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> insertQuestionVersion(connection, 3, "א", "ב", "ג", "ד", 0))
                    .withMessageContaining("ck_question_versions_correct");
        }
    }

    @Test
    @DisplayName("two identical answers are rejected (C-8 / ADR-016)")
    void answersMustBePairwiseDistinct() throws SQLException {
        DbBootstrap.migrate(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            seedMinimalGraph(connection);

            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> insertQuestionVersion(connection, 2, "אותה תשובה", "ב", "אותה תשובה", "ד", 1))
                    .withMessageContaining("ck_question_versions_distinct");
        }
    }

    @Test
    @DisplayName("a student cannot start two attempts on the same execution")
    void oneAttemptPerStudentPerExecution() throws SQLException {
        DbBootstrap.migrate(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            seedMinimalGraph(connection);

            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> execute(connection,
                            "INSERT INTO exam_attempts (id, execution_id, student_id, started_at, status)"
                                    + " VALUES (2, 1, 2, NOW(3), 'IN_PROGRESS')"))
                    .withMessageContaining("uq_exam_attempts_student");
        }
    }

    @Test
    @DisplayName("a question cannot reference a course that does not exist")
    void foreignKeysAreEnforced() throws SQLException {
        DbBootstrap.migrate(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            seedMinimalGraph(connection);

            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> execute(connection,
                            "INSERT INTO questions (id, course, serial3, display_id5)"
                                    + " VALUES (99, '99', 1, '99001')"))
                    .withMessageContaining("foreign key constraint fails");
        }
    }

    @Test
    @DisplayName("every entity PRD F10.4 names carries an optimistic-lock column")
    void editableEntitiesCarryALockVersion() throws SQLException {
        DbBootstrap.migrate(dataSource);

        // F10.4: "Applies to: questions, exams, bot sources, releases (editing
        // schedule), grading a student's submission" — plus exam_versions, where the
        // approve-vs-reject race lives.
        for (String table : List.of("questions", "exams", "exam_versions",
                "exam_executions", "bot_sources", "grades")) {
            assertThat(columnNames(table))
                    .as("optimistic-lock column on %s", table)
                    .contains("lock_version");
        }
    }

    @Test
    @DisplayName("deleting an execution a student has sat is blocked, not cascaded")
    void executionWithAttemptsCannotBeDeleted() throws SQLException {
        DbBootstrap.migrate(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            seedMinimalGraph(connection);
            execute(connection, "INSERT INTO grades (id, attempt_id, auto_score, final_score, status)"
                    + " VALUES (1, 1, 88, 88, 'APPROVED')");

            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> execute(connection, "DELETE FROM exam_executions WHERE id = 1"))
                    .withMessageContaining("foreign key constraint fails");

            // The grade — permanent student history — is still there.
            assertThat(countRows(connection, "grades")).isOne();
        }
    }

    // ===== helpers ========================================================

    /** Runs the migrations only as far as the given version. */
    private MigrateResult migrateTo(String version) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(DbBootstrap.MIGRATIONS_LOCATION)
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private List<String> columnNames(String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT column_name FROM information_schema.columns"
                             + " WHERE table_schema = ? AND table_name = ?")) {
            statement.setString(1, MySqlAvailability.TEST_SCHEMA);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            }
        }
        return names;
    }

    private long countRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private List<String> tableNames() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = ?")) {
            statement.setString(1, MySqlAvailability.TEST_SCHEMA);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            }
        }
        return names;
    }

    /**
     * One row per table along the chain a real attempt needs: subject → course → users →
     * question → version → exam → exam version → execution → attempt. Ids are explicit so
     * the assertions above can reference them.
     */
    private void seedMinimalGraph(Connection connection) throws SQLException {
        execute(connection, "INSERT INTO subjects (code2, name) VALUES ('10', 'מתמטיקה')");
        execute(connection, "INSERT INTO courses (code2, subject_code, name) VALUES ('11', '10', 'אלגברה')");
        execute(connection, "INSERT INTO users (id, username, password_hash, full_name, role, national_id)"
                + " VALUES (1, 'teacher1', '$2a$12$notarealhash', 'שרה כהן', 'TEACHER', '123456789')");
        execute(connection, "INSERT INTO users (id, username, password_hash, full_name, role, national_id)"
                + " VALUES (2, 'student1', '$2a$12$notarealhash', 'דוד לוי', 'STUDENT', '987654321')");
        execute(connection, "INSERT INTO questions (id, course, serial3, display_id5) VALUES (1, '11', 1, '11001')");
        insertQuestionVersion(connection, 1, "ארבע", "שלוש", "מאה", "אחת", 1);
        execute(connection, "INSERT INTO exams (id, course, serial2, display_id6, author)"
                + " VALUES (1, '11', 1, '101101', 1)");
        execute(connection, "INSERT INTO exam_versions (id, exam_id, version_no, name, duration_min, status, created_at)"
                + " VALUES (1, 1, 1, 'מבחן באלגברה', 60, 'APPROVED', NOW(3))");
        execute(connection, "INSERT INTO exam_version_questions (exam_version_id, question_version_id, points, ord)"
                + " VALUES (1, 1, 100, 1)");
        execute(connection, "INSERT INTO exam_executions (id, exam_version_id, code, open_at, close_at, status, created_by)"
                + " VALUES (1, 1, 'AB12', NOW(3), DATE_ADD(NOW(3), INTERVAL 2 HOUR), 'LIVE', 1)");
        execute(connection, "INSERT INTO exam_attempts (id, execution_id, student_id, started_at, status)"
                + " VALUES (1, 1, 2, NOW(3), 'IN_PROGRESS')");
    }

    private void insertQuestionVersion(Connection connection, long id,
                                       String a1, String a2, String a3, String a4,
                                       int correctAnswer) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO question_versions"
                        + " (id, question_id, version_no, text, a1, a2, a3, a4, correct_answer,"
                        + "  topic, difficulty, created_by, created_at)"
                        + " VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, 'משוואות', 'EASY', 1, NOW(3))")) {
            statement.setLong(1, id);
            statement.setLong(2, id);
            statement.setString(3, HEBREW_TEXT);
            statement.setString(4, a1);
            statement.setString(5, a2);
            statement.setString(6, a3);
            statement.setString(7, a4);
            statement.setInt(8, correctAnswer);
            statement.executeUpdate();
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
