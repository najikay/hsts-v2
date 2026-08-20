package server.db.repos;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** The execution contract on the fast in-memory engine. */
class ExecutionRepositoryH2Test extends ExecutionRepositoryContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
