package server.db.repos;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** The bank contract on the fast in-memory engine. */
class BankRepositoryH2Test extends BankRepositoryContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
