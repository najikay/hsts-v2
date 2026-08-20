package server.db;

import org.hibernate.SessionFactory;

import java.util.List;

/**
 * Emptying a test database, in the one order that works (E2.13).
 *
 * <p>Lifted out of {@link RepositoryTestBase} when a second family of database tests needed
 * the same thing: {@code server.features.notify.JpaNotificationStoreContract} runs against
 * the shared MySQL schema too, and {@code notifications} has a foreign key to {@code users},
 * so it cannot simply delete its own table and hope whatever the previous test class left
 * behind is compatible. Two copies of {@link #WIPE_ORDER} would have drifted the first time a
 * migration added a table.
 *
 * <h2>Never {@code FOREIGN_KEY_CHECKS = 0}</h2>
 *
 * <p>§5 permits switching foreign keys off around a wipe provided they are switched back on
 * before any insert, because the composite foreign key on {@code exam_version_questions} is
 * inert while they are off. Rather than guard that failure mode, this helper never creates
 * it: if the order below is ever wrong, a foreign key refuses the delete and the suite fails
 * loudly instead of silently loading data no constraint ever checked.
 */
public final class TestSchema {

    /** Reverse dependency order: children before the rows they point at. */
    public static final List<String> WIPE_ORDER = List.of(
            "notifications",
            "bot_messages", "bot_sessions", "bot_sources", "bots",
            "grades", "attempt_answers", "exam_attempts", "exam_executions",
            "exam_version_questions", "exam_versions", "exams",
            "question_versions", "questions",
            "coordinators", "enrollments", "course_teachers",
            "users", "courses", "subjects");

    private TestSchema() {
        // static helper - no instances
    }

    /**
     * Deletes every row in every table, children first.
     *
     * @param factory the database to empty
     */
    public static void wipe(SessionFactory factory) {
        Transactions.runInTx(factory, session -> WIPE_ORDER.forEach(
                table -> session.createNativeMutationQuery("DELETE FROM " + table).executeUpdate()));
    }
}
