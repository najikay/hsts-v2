package server.db.repos;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** The study bot's queries and store on the fast in-memory engine (E16). */
class BotFeatureRepositoryH2Test extends BotFeatureRepositoryContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
