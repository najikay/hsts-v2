package server.db.repos;

import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The approval repository contract on the fast in-memory suite.
 *
 * <p>H2 reproduces the entity mappings, which is what the four joins and the
 * {@code @Version} bump need, so almost everything in the contract means something here.
 * What it does not reproduce is the real Flyway schema and its foreign keys, so the test
 * that a superseded write cannot violate one lives in the MySQL leaf.
 */
class ApprovalRepositoryH2Test extends ApprovalRepositoryContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
