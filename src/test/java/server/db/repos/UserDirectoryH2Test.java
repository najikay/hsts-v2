package server.db.repos;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** The user directory contract on the fast in-memory engine. */
class UserDirectoryH2Test extends UserDirectoryContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
