package server.features.exam;

import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The production data seam on the fast in-memory suite.
 *
 * <p>H2 reproduces the unique constraint the entities declare, so even here the
 * double-start refusal is a real refusal rather than a check the fixture performed.
 */
class JpaExamStoreH2Test extends JpaExamStoreContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
