package server.db.seed;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** The dataset contract on the fast in-memory engine. */
class SeedDatasetH2Test extends SeedDatasetContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
