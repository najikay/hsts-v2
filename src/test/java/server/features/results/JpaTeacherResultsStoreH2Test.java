package server.features.results;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** The E14 production data seam on the fast in-memory suite. */
class JpaTeacherResultsStoreH2Test extends JpaTeacherResultsStoreContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
