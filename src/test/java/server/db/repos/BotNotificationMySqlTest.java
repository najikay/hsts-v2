package server.db.repos;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/** The bot and notification contract on the real Flyway schema. */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class BotNotificationMySqlTest extends BotNotificationContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
