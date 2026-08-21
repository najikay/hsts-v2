package server.db.repos;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** The E14 results reads on the fast in-memory engine. */
class ResultsRepositoryH2Test extends ResultsRepositoryContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
