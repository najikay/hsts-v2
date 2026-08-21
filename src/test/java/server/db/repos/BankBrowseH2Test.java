package server.db.repos;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** The bank browse contract on the fast in-memory engine. */
class BankBrowseH2Test extends BankBrowseContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
