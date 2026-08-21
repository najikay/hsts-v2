package server.console;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.TestDatabase;
import server.db.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The database card's probe, against a real pool (E19.2, F13.1).
 *
 * <p>The rest of {@link ConsoleHealth} is tested with the probe injected, which is
 * right for the card's logic and proves nothing about the probe itself. This class
 * runs the actual {@code SELECT 1} against a real Hibernate factory over a real
 * connection pool, because "the console says Up" is only worth anything if the
 * thing behind it genuinely took a connection and used it.
 *
 * <p>H2 rather than MySQL deliberately: the query is one the two engines agree
 * about completely, and the point here is the pool round trip, not the dialect.
 * The blind spots listed in {@code H2Support} are all about schema, and this
 * probe touches no schema.
 */
class ConsoleHealthProbeH2Test {

    @Test
    @DisplayName("SELECT 1 against a live pool answers up")
    void liveDatabaseIsUp() {
        try (TestDatabase database = TestDatabases.h2()) {
            assertThat(ConsoleHealth.selectOne(database.factory()).isUp())
                    .as("a connection was taken from the pool and used")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a closed pool answers down instead of throwing at the console")
    void closedPoolIsDown() {
        TestDatabase database = TestDatabases.h2();
        ConsoleHealth.DatabaseProbe probe = ConsoleHealth.selectOne(database.factory());
        assertThat(probe.isUp()).isTrue();

        database.close();

        assertThat(probe.isUp())
                .as("a console that fell over when the database did would be useless "
                        + "in exactly the situation it was built for")
                .isFalse();
    }

    @Test
    @DisplayName("the whole card reads Up over a real pool")
    void cardOverARealPool() {
        try (TestDatabase database = TestDatabases.h2()) {
            HealthSnapshot snapshot = ConsoleHealth.of(database.factory(),
                    new server.core.SessionManager(), null, java.time.Clock.systemUTC()).probe();

            assertThat(snapshot.databaseUp()).isTrue();
            assertThat(snapshot.databaseText()).isEqualTo("Up");
            assertThat(snapshot.hasProblem()).isFalse();
            assertThat(snapshot.providers())
                    .as("no bot chain wired, so no provider rows")
                    .isEmpty();
        }
    }
}
