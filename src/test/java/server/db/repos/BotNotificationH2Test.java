package server.db.repos;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** The bot and notification contract on the fast in-memory engine. */
class BotNotificationH2Test extends BotNotificationContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
