package server.features.notify;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** The notification store contract, JPA implementation, on the fast in-memory engine. */
class JpaNotificationStoreH2Test extends JpaNotificationStoreContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
