package server.features.reports;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** The E15 production data seam on the fast in-memory suite. */
class JpaReportStoreH2Test extends JpaReportStoreContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
