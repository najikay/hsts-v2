package server.db.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the console is handed after a seed run (E2.15). */
class SeedSummaryTest {

    @Test
    @DisplayName("the text names every table with its count, in load order")
    void textListsTablesInOrder() {
        SeedSummary summary = new SeedSummary(SeedOutcome.LOADED, ordered());

        String text = summary.toText();

        assertThat(text).contains("18 rows inserted");
        assertThat(text).containsSubsequence("subjects", "courses", "users");
        assertThat(text).contains("users").contains("12");
    }

    @Test
    @DisplayName("totals add up across tables")
    void totalsRows() {
        assertThat(new SeedSummary(SeedOutcome.LOADED, ordered()).totalRows()).isEqualTo(18);
    }

    @Test
    @DisplayName("an empty LOAD_IF_MISSING run says so instead of reporting zero rows")
    void unchangedReadsDifferentlyFromLoaded() {
        // "Seed loaded: 0 rows" and "already present" are different answers for an operator,
        // and collapsing them would make a loader silently inserting nothing because of a bug
        // look exactly like one correctly finding the data there.
        String unchanged = SeedSummary.nothing(SeedOutcome.UNCHANGED).toText();

        assertThat(unchanged).isEqualTo("Seed already present, nothing inserted.");
        assertThat(unchanged).doesNotContain("0 rows");
    }

    @Test
    @DisplayName("a cancelled reseed states plainly that nothing was destroyed")
    void cancelledSaysNothingWasDeleted() {
        // The operator declined a destructive action. The one thing they need to read is
        // that declining worked.
        assertThat(SeedSummary.nothing(SeedOutcome.CANCELLED).toText())
                .isEqualTo("Reseed cancelled. Nothing was deleted and nothing was inserted.");
    }

    @Test
    @DisplayName("a reseed says the timestamps were re-resolved")
    void reseedExplainsWhatMakesItDifferent() {
        // This line is the only place an operator is told why they reseed before a demo.
        assertThat(new SeedSummary(SeedOutcome.RESEEDED, ordered()).toText())
                .contains("reseeded")
                .contains("current clock");
    }

    @Test
    @DisplayName("no em dashes: this string is shown in the server console")
    void carriesNoEmDashes() {
        // PRD §4.1 forbids them in user-visible text, and the sweep in c2b9c0f treated log
        // and console strings as in scope.
        for (SeedOutcome outcome : SeedOutcome.values()) {
            assertThat(new SeedSummary(outcome, ordered()).toText())
                    .as("%s headline", outcome)
                    .doesNotContain("—");
        }
    }

    @Test
    @DisplayName("the counts cannot be edited through the summary")
    void rowsAreUnmodifiable() {
        Map<String, Integer> source = ordered();
        SeedSummary summary = new SeedSummary(SeedOutcome.LOADED, source);

        source.put("questions", 40);

        assertThat(summary.rowsByTable()).doesNotContainKey("questions");
        assertThatThrownBy(() -> summary.rowsByTable().put("questions", 40))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Map<String, Integer> ordered() {
        Map<String, Integer> rows = new LinkedHashMap<>();
        rows.put("subjects", 2);
        rows.put("courses", 4);
        rows.put("users", 12);
        return rows;
    }
}
