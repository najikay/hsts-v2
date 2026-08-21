package server.db.repos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;
import server.db.entities.Difficulty;
import server.db.projections.BankQuestionSummary;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bank browse contract on the real Flyway schema, plus the cases only MySQL can answer.
 *
 * <p>Search behaviour belongs here rather than in the contract because H2 in MySQL mode does
 * not reproduce {@code utf8mb4_unicode_ci}. A Hebrew or case test passing on H2 says nothing
 * about production and reads as coverage, which is the drift the two-engine pair exists to
 * catch.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class BankBrowseMySqlTest extends BankBrowseContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }

    @Test
    @DisplayName("Hebrew search round-trips through the real collation (§5, NFR utf8mb4)")
    void hebrewSearchWorksOnTheRealSchema() {
        long id = question(COURSE_ALGEBRA, (short) 1);
        version(id, 1, "מצא את השורש של המשוואה", "משוואות", Difficulty.EASY, null);

        List<BankQuestionSummary> hit = browse(BankQuery.scopedTo(
                List.of(COURSE_ALGEBRA), null, null, null, "השורש"));

        assertThat(hit).hasSize(1);
        assertThat(hit.get(0).text()).isEqualTo("מצא את השורש של המשוואה");
    }

    @Test
    @DisplayName("an exact topic filter is exact, and Hebrew topics survive the round trip")
    void hebrewTopicFilterIsExact() {
        version(question(COURSE_ALGEBRA, (short) 1), 1, "one", "משוואות ליניאריות",
                Difficulty.EASY, null);
        version(question(COURSE_ALGEBRA, (short) 2), 1, "two", "משוואות ריבועיות",
                Difficulty.EASY, null);

        // Topic is an equality filter, not a prefix. Two topics sharing a first word is the
        // shape the seed actually has, so a filter that matched loosely would return both and
        // nothing in an engine-agnostic test would notice.
        List<BankQuestionSummary> linear = browse(BankQuery.scopedTo(
                List.of(COURSE_ALGEBRA), null, "משוואות ליניאריות", null, null));

        assertThat(linear).extracting(BankQuestionSummary::text).containsExactly("one");
    }

    @Test
    @DisplayName("search is case-insensitive on the real collation, not only in Java")
    void searchIsCaseInsensitiveOnMySql() {
        version(question(COURSE_ALGEBRA, (short) 1), 1, "Find The ROOT Here", "משוואות",
                Difficulty.EASY, null);

        // The query lowercases both sides rather than leaning on the collation, deliberately,
        // so the two engines agree. This asserts the result on the engine that ships.
        for (String term : List.of("root", "ROOT", "RoOt")) {
            assertThat(browse(BankQuery.scopedTo(List.of(COURSE_ALGEBRA), null, null, null, term)))
                    .as("searching %s", term)
                    .hasSize(1);
        }
    }
}
