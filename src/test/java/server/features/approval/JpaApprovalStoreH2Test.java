package server.features.approval;

import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The production approval data seam on the fast in-memory suite.
 *
 * <p>H2 reproduces the entity mappings and {@code @Version}, so the flush that bumps the
 * optimistic lock is a real bump here rather than a check the fixture performed. The
 * two-connection race that {@code lock_version} exists for belongs to the MySQL leaf of
 * {@code ApprovalRepositoryContract}.
 */
class JpaApprovalStoreH2Test extends JpaApprovalStoreContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
