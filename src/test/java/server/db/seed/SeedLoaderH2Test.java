package server.db.seed;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** The loader contract on the fast in-memory engine. */
class SeedLoaderH2Test extends SeedLoaderContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
