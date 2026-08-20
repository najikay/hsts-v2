package server.db.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties of the canonical wipe order that need no database (E2.15).
 *
 * <p>Whether the order actually <em>works</em> is proven by deleting from a populated MySQL
 * database, which {@code RepositoryFixtureContract} and {@code SeedLoaderContract} do. What is
 * proven here is the part that would otherwise only be caught there: that the list is complete
 * and has no duplicates. Those checks run everywhere, including on a machine with no MySQL,
 * which is exactly where a new table gets forgotten.
 */
class WipeOrderTest {

    private static final Path MIGRATIONS =
            Path.of("src", "main", "resources", "db", "migration");

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?",
                    Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("the wipe list covers exactly the tables the migrations create")
    void coversEveryMigratedTable() {
        // Read off the migration SQL rather than compared to a second hand-written list,
        // because the SQL is the only thing that actually creates tables. The failure this
        // prevents: a V8 adds a table, FlywayCleanRunTest is updated because it fails loudly
        // without it, and WipeOrder is not because nothing forces it. The seed then reloads
        // on top of rows it never deleted, and the first symptom is a unique-constraint
        // violation in somebody else's test.
        //
        // No database needed, so this runs on a machine with no MySQL, which is exactly where
        // the forgotten table gets written.
        Set<String> created = tablesCreatedByMigrations();

        assertThat(created).as("the scan must actually find the CREATE TABLE statements")
                .hasSizeGreaterThanOrEqualTo(20);
        assertThat(WipeOrder.TABLES)
                .as("every table the migrations create must be wipeable, and WipeOrder must "
                        + "not name a table that does not exist")
                .containsExactlyInAnyOrderElementsOf(created);
    }

    private static Set<String> tablesCreatedByMigrations() {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            Set<String> tables = new HashSet<>();
            files.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .forEach(path -> collectTables(path, tables));
            return tables;
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + MIGRATIONS, e);
        }
    }

    private static void collectTables(Path migration, Set<String> into) {
        try {
            Matcher matcher = CREATE_TABLE.matcher(
                    Files.readString(migration, StandardCharsets.UTF_8));
            while (matcher.find()) {
                into.add(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + migration, e);
        }
    }

    @Test
    @DisplayName("no table is listed twice")
    void hasNoDuplicates() {
        Set<String> seen = new HashSet<>(WipeOrder.TABLES);

        assertThat(seen).hasSameSizeAs(WipeOrder.TABLES);
    }

    @Test
    @DisplayName("flyway_schema_history is deliberately absent")
    void doesNotWipeTheMigrationHistory() {
        // Deleting it would leave the database schema-current but history-empty, so the next
        // migrate would try to re-apply V1 against tables that already exist.
        assertThat(WipeOrder.TABLES).doesNotContain("flyway_schema_history");
    }

    @Test
    @DisplayName("children come before the tables they point at")
    void childrenPrecedeTheirParents() {
        // Every pair below is a real foreign key in V1..V7, checked against the migrations
        // rather than inferred from the list's current shape. A pair that is not an actual
        // relationship would pass here for the wrong reason and prove nothing.
        assertParentComesAfter("subjects", "courses");
        assertParentComesAfter("courses", "course_teachers");
        assertParentComesAfter("users", "course_teachers");
        assertParentComesAfter("courses", "enrollments");
        assertParentComesAfter("users", "enrollments");
        assertParentComesAfter("subjects", "coordinators");
        assertParentComesAfter("users", "coordinators");
        assertParentComesAfter("courses", "questions");
        assertParentComesAfter("questions", "question_versions");
        assertParentComesAfter("exams", "exam_versions");
        assertParentComesAfter("exam_versions", "exam_version_questions");
        assertParentComesAfter("question_versions", "exam_version_questions");
        assertParentComesAfter("exam_versions", "exam_executions");
        assertParentComesAfter("exam_executions", "exam_attempts");
        assertParentComesAfter("exam_attempts", "attempt_answers");
        assertParentComesAfter("question_versions", "attempt_answers");
        assertParentComesAfter("exam_attempts", "grades");
        assertParentComesAfter("courses", "bots");
        assertParentComesAfter("bots", "bot_sources");
        assertParentComesAfter("bots", "bot_sessions");
        assertParentComesAfter("bots", "bot_messages");
        assertParentComesAfter("bot_sessions", "bot_messages");
    }

    @Test
    @DisplayName("everything that points at users is deleted before users")
    void usersAreDeletedLast() {
        // users is the most-referenced table in the schema: eleven foreign keys land on it,
        // and most are RESTRICT, so getting it early in the order fails the whole wipe. Read
        // off the migrations rather than off the list this asserts against.
        List<String> referencesUsers = List.of(
                "course_teachers", "enrollments", "coordinators", "question_versions",
                "exams", "exam_executions", "exam_attempts", "grades",
                "bot_sources", "bot_sessions", "bot_messages", "notifications");

        referencesUsers.forEach(child -> assertParentComesAfter("users", child));
    }

    private static void assertParentComesAfter(String parent, String child) {
        List<String> order = WipeOrder.TABLES;

        assertThat(order.indexOf(child))
                .as("%s references %s, so it must be deleted first", child, parent)
                .isLessThan(order.indexOf(parent));
    }
}
