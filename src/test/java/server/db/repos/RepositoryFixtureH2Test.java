package server.db.repos;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** The fixture contract on the fast in-memory engine. */
class RepositoryFixtureH2Test extends RepositoryFixtureContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
