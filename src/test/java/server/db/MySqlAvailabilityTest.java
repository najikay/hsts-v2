package server.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Covers the skip-or-fail decision of {@link MySqlAvailability} without a database.
 *
 * <p>The decision matters because the two environments want opposite things from the
 * same suite: a developer without MySQL should get a clean skip, while CI must never
 * report green on a suite that silently did not run. {@code HSTS_REQUIRE_MYSQL} is what
 * separates them, and these tests pin all four combinations.
 */
class MySqlAvailabilityTest {

    @Test
    @DisplayName("a reachable server runs the suite, required or not")
    void reachableAlwaysRuns() {
        assertThat(MySqlAvailability.gate(true, false)).isTrue();
        assertThat(MySqlAvailability.gate(true, true)).isTrue();
    }

    @Test
    @DisplayName("without the flag, an absent MySQL skips quietly")
    void unreachableSkipsWhenNotRequired() {
        assertThat(MySqlAvailability.gate(false, false)).isFalse();
    }

    @Test
    @DisplayName("with the flag set, an absent MySQL fails the build instead of skipping")
    void unreachableFailsWhenRequired() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> MySqlAvailability.gate(false, true))
                .withMessageContaining(MySqlAvailability.REQUIRE_FLAG)
                .withMessageContaining("silently skipped");
    }

}
