package server.db.seed;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** Document-versus-database on the fast in-memory engine. */
class SeedLoadedDbH2Test extends SeedLoadedDbContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
