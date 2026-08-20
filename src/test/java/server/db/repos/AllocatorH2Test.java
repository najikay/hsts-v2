package server.db.repos;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** The allocator contract on the fast in-memory engine. */
class AllocatorH2Test extends AllocatorContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
