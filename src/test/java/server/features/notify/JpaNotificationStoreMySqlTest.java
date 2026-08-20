package server.features.notify;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The notification store contract, JPA implementation, on the real Flyway schema.
 *
 * <p>This leaf is where the store meets the constraints H2 does not reproduce: the foreign
 * key from {@code notifications.user_id} to {@code users}, and the {@code utf8mb4_unicode_ci}
 * collation the Hebrew titles travel through. It is also the only place the {@code DATETIME(3)}
 * precision of {@code created_at} and {@code read_at} is real, which is what the
 * newest-first ordering and the tie-break on id depend on.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class JpaNotificationStoreMySqlTest extends JpaNotificationStoreContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
