package server.features.release;

import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The release manager's data seam on the fast in-memory engine.
 *
 * <p>H2 reproduces the shapes: the joins, the status filters and the guarded transition all
 * mean the same thing here, so a wrong {@code where} clause fails in seconds rather than
 * waiting for a MySQL to be reachable.
 */
class JpaReleaseStoreH2Test extends JpaReleaseStoreContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
