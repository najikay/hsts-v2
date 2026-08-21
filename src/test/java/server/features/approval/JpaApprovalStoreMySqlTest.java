package server.features.approval;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The production approval data seam on the real Flyway schema.
 *
 * <p>Nothing extra is asserted here: what this leaf adds is that every query in the seam
 * runs against the migrated schema with its foreign keys and its {@code utf8mb4} collation,
 * so a column name or a Hebrew exam name that only works under Hibernate's schema-gen fails
 * before a demo rather than during one.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class JpaApprovalStoreMySqlTest extends JpaApprovalStoreContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
