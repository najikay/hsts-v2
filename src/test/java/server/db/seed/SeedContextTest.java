package server.db.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The per-load tally sections write their results into (E2.15). */
class SeedContextTest {

    private static SeedContext context() {
        return new SeedContext(null, new SeedTimes(
                Clock.fixed(Instant.parse("2026-08-20T15:30:00Z"), ZoneOffset.UTC)));
    }

    @Test
    @DisplayName("counts from several sections writing one table add up")
    void countsAccumulate() {
        SeedContext context = context();

        context.recordInsert("users");
        context.recordInserts("users", 17);
        context.recordInserts("questions", 40);

        assertThat(context.inserted()).containsEntry("users", 18).containsEntry("questions", 40);
    }

    @Test
    @DisplayName("tables keep the order they were first written to")
    void orderIsInsertionOrder() {
        // The summary prints in this order, so it should read like the load: reference data
        // first, then people, then content.
        SeedContext context = context();

        context.recordInserts("subjects", 2);
        context.recordInserts("courses", 4);
        context.recordInserts("users", 18);
        context.recordInserts("subjects", 0);

        assertThat(context.inserted().keySet()).containsExactly("subjects", "courses", "users");
    }

    @Test
    @DisplayName("a section may report zero, and that is different from reporting nothing")
    void zeroIsRecorded() {
        // "I ran and everything was already there" is a real answer. A section that stayed
        // silent instead would be indistinguishable from one that never ran.
        SeedContext context = context();

        context.recordInserts("users", 0);

        assertThat(context.inserted()).containsEntry("users", 0);
    }

    @Test
    @DisplayName("a negative count is rejected rather than quietly subtracted")
    void negativeCountsAreRefused() {
        // Without this, a section with an off-by-one could cancel out another section's rows
        // and the summary would understate what is in the database.
        assertThatThrownBy(() -> context().recordInserts("users", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
    }

    @Test
    @DisplayName("the tally handed out is a copy")
    void insertedIsACopy() {
        SeedContext context = context();
        context.recordInserts("users", 18);

        context.inserted().put("questions", 999);

        assertThat(context.inserted()).doesNotContainKey("questions");
    }
}
