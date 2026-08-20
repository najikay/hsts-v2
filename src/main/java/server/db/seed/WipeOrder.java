package server.db.seed;

import org.hibernate.SessionFactory;
import org.hibernate.Session;
import server.db.Transactions;

import java.util.List;

/**
 * <b>The</b> canonical order in which HSTS tables may be emptied (E2.15).
 *
 * <p>This list is the single source of truth for that order, and it has exactly two consumers:
 * the seed loader's {@code --reseed} path in this package, and the test wipe in
 * {@code server.db.TestSchema}, which delegates here rather than keeping its own copy. It
 * lives in main code because production code cannot import test code, and because a second
 * copy would drift the first time a migration adds a table, in the direction that is hardest
 * to notice: a table missing from one list is not a compile error, it is a foreign key
 * violation on somebody else's machine.
 *
 * <h2>Reverse dependency order, and never {@code FOREIGN_KEY_CHECKS = 0}</h2>
 *
 * <p>ARCHITECTURE §5 permits switching foreign keys off around a wipe provided they are
 * switched back on before any insert, because the composite foreign key on
 * {@code exam_version_questions} is inert while they are off. Rather than guard that failure
 * mode, this class never creates it: children are deleted before the rows they point at, and
 * if the order below is ever wrong a foreign key refuses the delete and the caller fails
 * loudly instead of silently loading data no constraint ever checked.
 *
 * <p>That is not a theoretical preference. Putting {@code subjects} first was tried on
 * purpose during E2 PR 2b: H2 accepted it and passed every test, and MySQL refused it
 * instantly with {@code fk_courses_subject}. The order is only ever proven on the MySQL leaf.
 *
 * <h2>Adding a table</h2>
 *
 * <p>A new migration that adds a table adds it here too, positioned before every table it
 * references. {@code FlywayCleanRunTest.EXPECTED_TABLES} is the list that will notice the
 * table exists; this is the list that decides whether it can be emptied.
 */
public final class WipeOrder {

    /**
     * Every table in the schema, children before the rows they point at.
     *
     * <p>Twenty tables, matching {@code V1__core.sql} through {@code V7__notifications.sql}.
     * {@code flyway_schema_history} is deliberately absent: emptying it would strand the
     * database at an unknown version.
     */
    public static final List<String> TABLES = List.of(
            "notifications",
            "bot_messages", "bot_sessions", "bot_sources", "bots",
            "grades", "attempt_answers", "exam_attempts", "exam_executions",
            "exam_version_questions", "exam_versions", "exams",
            "question_versions", "questions",
            "coordinators", "enrollments", "course_teachers",
            "users", "courses", "subjects");

    private WipeOrder() {
        // static helper, no instances
    }

    /**
     * Deletes every row in every table, children first, in one transaction.
     *
     * @param factory the database to empty
     */
    public static void wipe(SessionFactory factory) {
        Transactions.runInTx(factory, WipeOrder::wipe);
    }

    /**
     * Deletes every row in every table, children first, inside the caller's transaction.
     *
     * <p>The session-taking form exists so a reseed can wipe and reload atomically: if the
     * load fails halfway, the delete rolls back with it and the operator is left with the
     * data they had rather than an empty database.
     *
     * @param session the session to delete through
     */
    public static void wipe(Session session) {
        TABLES.forEach(table ->
                session.createNativeMutationQuery("DELETE FROM " + table).executeUpdate());
    }
}
