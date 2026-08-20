package server.db.repos;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The study bot's queries and store on the real Flyway schema (E16).
 *
 * <p>Worth the second run for three of these in particular: the activity
 * aggregate's {@code year()/month()/day()} bucketing, the sources projection's
 * {@code length()}, and the bank read's correlated "latest version" subquery. All
 * three compile on either engine and are exactly the sort of HQL that behaves
 * differently underneath.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class BotFeatureRepositoryMySqlTest extends BotFeatureRepositoryContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
