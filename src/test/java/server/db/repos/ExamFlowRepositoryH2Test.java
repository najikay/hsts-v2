package server.db.repos;

import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The take-exam repository contract on the fast in-memory suite.
 *
 * <p>H2 reproduces the unique constraints the entities declare, which is what makes the
 * double-start test mean something here too. What it does not reproduce is the collation
 * and the real Flyway schema, so the case-insensitive code lookup is established in the
 * MySQL leaf rather than this one.
 */
class ExamFlowRepositoryH2Test extends ExamFlowRepositoryContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
