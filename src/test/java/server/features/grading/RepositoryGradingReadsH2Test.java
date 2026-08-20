package server.features.grading;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** The grading-reads contract on the fast in-memory engine. */
class RepositoryGradingReadsH2Test extends RepositoryGradingReadsContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
