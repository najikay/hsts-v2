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
 *
 * <p>The block marked "amendments from the PR 1 review" carries one test per schema
 * decision the lead made in review, so each is provably in force rather than merely
 * written down.
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
                    // Named, not the generic message: were this FK CASCADE, the delete would
                    // reach the attempt and then be stopped by fk_grades_attempt instead —
                    // still an exception, still "foreign key constraint fails", still green.
                    .isThrownBy(() -> execute(connection, "DELETE FROM exam_executions WHERE id = 1"))
                    .withMessageContaining("fk_exam_attempts_execution");

            // The grade — permanent student history — is still there.
            assertThat(countRows(connection, "grades")).isOne();
        }
    }

    // ===== amendments from the PR 1 review ================================

    @Test
    @DisplayName("two students cannot share a national id (S-18 must stay unambiguous)")
    void nationalIdIsUnique() throws SQLException {
        DbBootstrap.migrate(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            seedMinimalGraph(connection);

            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> execute(connection,
                            "INSERT INTO users (id, username, password_hash, full_name, role, national_id)"
                                    + " VALUES (3, 'student2', '$2a$12$notarealhash', 'רות מזרחי', 'STUDENT', '987654321')"))
                    .withMessageContaining("uq_users_national_id");
        }
    }

    @Test
    @DisplayName("questions carry a soft-delete stamp rather than being removed (F2.5)")
    void questionsAreSoftDeleted() throws SQLException {
        DbBootstrap.migrate(dataSource);

        assertThat(columnNames("questions")).contains("deleted_at");

        try (Connection connection = dataSource.getConnection()) {
            seedMinimalGraph(connection);
            // A second question that NO exam version references — the case F2.5 hands to
            // soft delete, and the one a CASCADE would have quietly hard-deleted.
            execute(connection, "INSERT INTO questions (id, course, serial3, display_id5) VALUES (2, '11', 2, '11002')");
            insertQuestionVersion(connection, 2, 2, 1, "ארבע", "חמש", "שש", "שבע", 1);

            assertThatExceptionOfType(SQLException.class)
                    .as("a question an exam references")
                    .isThrownBy(() -> execute(connection, "DELETE FROM questions WHERE id = 1"))
                    .withMessageContaining("fk_question_versions_question");

            // The one that used to slip through: nothing points at question 2, so under
            // CASCADE this delete succeeded and took its version history with it.
            assertThatExceptionOfType(SQLException.class)
                    .as("a question nothing references")
                    .isThrownBy(() -> execute(connection, "DELETE FROM questions WHERE id = 2"))
                    .withMessageContaining("fk_question_versions_question");

            assertThatExceptionOfType(SQLException.class)
                    .as("the version an exam was built from")
                    .isThrownBy(() -> execute(connection, "DELETE FROM question_versions WHERE id = 1"))
                    .withMessageContaining("fk_evq_question_version");

            // Soft delete is the only route, and it leaves the history intact.
            execute(connection, "UPDATE questions SET deleted_at = NOW(3) WHERE id = 2");
            assertThat(countRows(connection, "question_versions")).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("one question cannot appear twice in an exam version through two of its versions")
    void sameQuestionCannotAppearTwiceViaDifferentVersions() throws SQLException {
        DbBootstrap.migrate(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            seedMinimalGraph(connection);
            // A second version of the SAME question — the exact hole PRD §6 names, which
            // a link table keyed only on question_version_id could not see.
            insertQuestionVersion(connection, 2, 1, 2, "ארבע", "חמש", "שש", "שבע", 1);

            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> execute(connection, "INSERT INTO exam_version_questions"
                            + " (exam_version_id, question_id, question_version_id, points, ord)"
                            + " VALUES (1, 1, 2, 50, 2)"))
                    .withMessageContaining("uq_exam_version_questions_question");
        }
    }

    @Test
    @DisplayName("the denormalised question_id cannot drift from the version it names")
    void denormalisedQuestionIdCannotDrift() throws SQLException {
        DbBootstrap.migrate(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            seedMinimalGraph(connection);
            execute(connection, "INSERT INTO questions (id, course, serial3, display_id5) VALUES (2, '11', 2, '11002')");
            execute(connection, "INSERT INTO questions (id, course, serial3, display_id5) VALUES (3, '11', 3, '11003')");
            insertQuestionVersion(connection, 2, 2, 1, "ארבע", "חמש", "שש", "שבע", 1);
            insertQuestionVersion(connection, 3, 3, 1, "אחת", "שתיים", "שלוש", "עשר", 2);

            // Every part of this row is individually legal: question 3 exists, version 2
            // exists, (exam_version 1, question 3) is free and ord 2 is free — so neither
            // unique constraint can fire. The lie is only in the PAIRING: version 2 belongs
            // to question 2, not question 3. The composite foreign key is the sole thing
            // standing between that row and a corrupt uniqueness guarantee.
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> execute(connection, "INSERT INTO exam_version_questions"
                            + " (exam_version_id, question_id, question_version_id, points, ord)"
                            + " VALUES (1, 3, 2, 50, 2)"))
                    .withMessageContaining("foreign key constraint fails");
        }
    }

    @Test
    @DisplayName("a scheduled execution can be CANCELLED rather than deleted (F5.5)")
    void executionsCanBeCancelled() throws SQLException {
        DbBootstrap.migrate(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            seedMinimalGraph(connection);
            execute(connection, "INSERT INTO exam_executions"
                    + " (id, exam_version_id, code, open_at, close_at, status, created_by)"
                    + " VALUES (2, 1, 'CD34', NOW(3), DATE_ADD(NOW(3), INTERVAL 2 HOUR), 'SCHEDULED', 1)");

            execute(connection, "UPDATE exam_executions SET status = 'CANCELLED' WHERE id = 2");

            assertThat(singleString(connection, "SELECT status FROM exam_executions WHERE id = 2"))
                    .isEqualTo("CANCELLED");
        }
    }

    @Test
    @DisplayName("a bot source with no content cannot exist — neither NULL nor empty (F12.2)")
    void botSourcesAlwaysCarryContent() throws SQLException {
        DbBootstrap.migrate(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            seedMinimalGraph(connection);
            seedBot(connection);

            assertThatExceptionOfType(SQLException.class)
                    .as("NULL extracted_text")
                    .isThrownBy(() -> execute(connection, "INSERT INTO bot_sources"
                            + " (id, bot_id, type, title, raw, extracted_text, added_by, updated_at)"
                            + " VALUES (2, 1, 'PDF', 'ריק', 'bytes', NULL, 1, NOW(3))"));

            assertThatExceptionOfType(SQLException.class)
                    .as("NULL raw")
                    .isThrownBy(() -> execute(connection, "INSERT INTO bot_sources"
                            + " (id, bot_id, type, title, raw, extracted_text, added_by, updated_at)"
                            + " VALUES (5, 1, 'PDF', 'ריק', NULL, 'טקסט', 1, NOW(3))"));

            // NOT NULL alone would let this through, and a zero-length source is exactly
            // the silently-useless row the rule exists to prevent.
            assertThatExceptionOfType(SQLException.class)
                    .as("empty extracted_text")
                    .isThrownBy(() -> execute(connection, "INSERT INTO bot_sources"
                            + " (id, bot_id, type, title, raw, extracted_text, added_by, updated_at)"
                            + " VALUES (3, 1, 'PDF', 'ריק', 'bytes', '', 1, NOW(3))"))
                    .withMessageContaining("ck_bot_sources_text_present");

            assertThatExceptionOfType(SQLException.class)
                    .as("empty raw")
                    .isThrownBy(() -> execute(connection, "INSERT INTO bot_sources"
                            + " (id, bot_id, type, title, raw, extracted_text, added_by, updated_at)"
                            + " VALUES (4, 1, 'PDF', 'ריק', '', 'טקסט', 1, NOW(3))"))
                    .withMessageContaining("ck_bot_sources_raw_present");
        }
    }

    @Test
    @DisplayName("deleting a bot, or a session, cannot wipe the analytics corpus (F12.4 / S-34)")
    void botHistorySurvivesDeletion() throws SQLException {
        DbBootstrap.migrate(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            seedMinimalGraph(connection);
            seedBot(connection);

            // A second bot whose ONLY history is a session, no messages. Without it this
            // test cannot see fk_bot_sessions_bot at all: where messages exist, the
            // bot_messages keys are reported first and the assertions below stay green
            // even if fk_bot_sessions_bot were reverted to CASCADE.
            execute(connection, "INSERT INTO courses (code2, subject_code, name) VALUES ('12', '10', 'גאומטריה')");
            execute(connection, "INSERT INTO bots (id, course, name, active) VALUES (2, '12', 'עוזר גאומטריה', TRUE)");
            execute(connection, "INSERT INTO bot_sessions"
                    + " (id, bot_id, student_id, started_at, updated_at, transcript)"
                    + " VALUES (2, 2, 2, NOW(3), NOW(3), '[]')");

            assertThatExceptionOfType(SQLException.class)
                    .as("a bot whose only history is a session")
                    .isThrownBy(() -> execute(connection, "DELETE FROM bots WHERE id = 2"))
                    .withMessageContaining("fk_bot_sessions_bot");

            assertThatExceptionOfType(SQLException.class)
                    .as("a bot with messages")
                    .isThrownBy(() -> execute(connection, "DELETE FROM bots WHERE id = 1"))
                    .withMessageContaining("fk_bot_messages_bot");

            assertThatExceptionOfType(SQLException.class)
                    .as("deleting the session out from under the messages")
                    .isThrownBy(() -> execute(connection, "DELETE FROM bot_sessions WHERE id = 1"))
                    .withMessageContaining("fk_bot_messages_session");

            assertThat(countRows(connection, "bot_messages")).isOne();
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

    private String singleString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
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
        execute(connection, "INSERT INTO exam_version_questions"
                + " (exam_version_id, question_id, question_version_id, points, ord)"
                + " VALUES (1, 1, 1, 100, 1)");
        execute(connection, "INSERT INTO exam_executions (id, exam_version_id, code, open_at, close_at, status, created_by)"
                + " VALUES (1, 1, 'AB12', NOW(3), DATE_ADD(NOW(3), INTERVAL 2 HOUR), 'LIVE', 1)");
        execute(connection, "INSERT INTO exam_attempts (id, execution_id, student_id, started_at, status)"
                + " VALUES (1, 1, 2, NOW(3), 'IN_PROGRESS')");
    }

    private void insertQuestionVersion(Connection connection, long id,
                                       String a1, String a2, String a3, String a4,
                                       int correctAnswer) throws SQLException {
        insertQuestionVersion(connection, id, 1, id, a1, a2, a3, a4, correctAnswer);
    }

    /** Full form — the version chain of a chosen question, for the duplicate/drift tests. */
    private void insertQuestionVersion(Connection connection, long id, long questionId, long versionNo,
                                       String a1, String a2, String a3, String a4,
                                       int correctAnswer) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO question_versions"
                        + " (id, question_id, version_no, text, a1, a2, a3, a4, correct_answer,"
                        + "  topic, difficulty, created_by, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'משוואות', 'EASY', 1, NOW(3))")) {
            statement.setLong(1, id);
            statement.setLong(2, questionId);
            statement.setLong(3, versionNo);
            statement.setString(4, HEBREW_TEXT);
            statement.setString(5, a1);
            statement.setString(6, a2);
            statement.setString(7, a3);
            statement.setString(8, a4);
            statement.setInt(9, correctAnswer);
            statement.executeUpdate();
        }
    }

    /**
     * A bot with one source, one session and one message — the analytics corpus whose
     * survival the RESTRICT foreign keys exist to guarantee.
     */
    private void seedBot(Connection connection) throws SQLException {
        execute(connection, "INSERT INTO bots (id, course, name, active) VALUES (1, '11', 'עוזר אלגברה', TRUE)");
        execute(connection, "INSERT INTO bot_sources"
                + " (id, bot_id, type, title, raw, extracted_text, added_by, updated_at)"
                + " VALUES (1, 1, 'TEXT', 'סיכום שיעור', 'raw bytes', 'טקסט שחולץ', 1, NOW(3))");
        execute(connection, "INSERT INTO bot_sessions"
                + " (id, bot_id, student_id, started_at, updated_at, transcript)"
                + " VALUES (1, 1, 2, NOW(3), NOW(3), '[]')");
        execute(connection, "INSERT INTO bot_messages"
                + " (id, bot_id, session_id, student_id, question, answer, provider, asked_at)"
                + " VALUES (1, 1, 1, 2, 'מה זה משתנה?', 'תא בזיכרון', 'deepseek-chat', NOW(3))");
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
