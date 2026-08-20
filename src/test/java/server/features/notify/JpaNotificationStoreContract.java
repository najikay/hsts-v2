package server.features.notify;

import common.dto.notify.NavRef;
import common.dto.notify.NotificationType;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import server.db.TestDatabase;
import server.db.TestSchema;
import server.db.Transactions;
import server.db.entities.Notification;
import server.db.entities.User;
import server.db.entities.UserRole;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JpaNotificationStore} against a real database, on both engines (E17.1 — E2
 * integration).
 *
 * <p>Runs the identical {@link NotificationStoreContract} the in-memory fixture passes, which
 * is the whole point of the store seam: {@code NotificationService} was written against the
 * interface, so the swap in {@code HSTSServer.defaultRouter} is only safe if the two
 * implementations really are interchangeable. Two leaves bind this to H2 and to MySQL, the
 * same {@code Template Method} shape {@code server.db.RepositoryTestBase} uses.
 *
 * <h2>Why it seeds users</h2>
 *
 * <p>{@code notifications.user_id} is a foreign key to {@code users} (V7). The map accepts any
 * number; the real schema does not, which is precisely the sort of difference a contract test
 * run on one implementation only would never surface. So the two user ids the contract asks
 * for are real rows here, inserted after a full {@link TestSchema#wipe} — full rather than
 * {@code DELETE FROM notifications}, because the shared MySQL schema is left seeded by
 * whichever repository test class ran before this one, and those rows point at users this
 * class is about to replace.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class JpaNotificationStoreContract extends NotificationStoreContract {

    /** Not a real hash: nothing here verifies a password, and a real one costs 100ms each. */
    private static final String FAKE_HASH = "$2a$10$notarealbcrypthashusedonlybythefixture000000000000000";

    private TestDatabase database;
    private JpaNotificationStore store;
    private long danaId;
    private long rinaId;

    /**
     * The template hook: which database this run uses.
     *
     * @return an open database; this class closes it when the test class finishes
     */
    protected abstract TestDatabase openDatabase();

    @BeforeAll
    final void openDatabaseOnce() {
        database = openDatabase();
        store = new JpaNotificationStore(database.factory());
    }

    @AfterAll
    final void closeDatabaseOnce() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    final void wipeAndSeedUsers() {
        TestSchema.wipe(factory());
        Transactions.runInTx(factory(), session -> {
            User dana = new User("dana.cohen", FAKE_HASH, "דנה כהן", UserRole.TEACHER, "214703951");
            User rina = new User("rina.barak", FAKE_HASH, "רינה ברק", UserRole.TEACHER, "248190639");
            session.persist(dana);
            session.persist(rina);
            session.flush();
            danaId = dana.getId();
            rinaId = rina.getId();
        });
    }

    @Override
    protected final NotificationStore store() {
        return store;
    }

    @Override
    protected final long userA() {
        return danaId;
    }

    @Override
    protected final long userB() {
        return rinaId;
    }

    @Test
    @DisplayName("the store writes the entity's own columns, ref_type and ref_id included")
    void writesTheColumnsTheEntityNames() {
        // The contract tests go in and out through the store, which would hide a mapping
        // that was consistently wrong in both directions. This one reads the row back as
        // an entity, because two of the DTO's names differ from the fields they land in:
        // NavRef.route() is refType (ref_type) and NavRef.entityId() is refId (ref_id).
        store.save(danaId, NotificationType.APPROVAL_REJECTED, "Rejected",
                "Needs another question.", NavRef.to("exam-versions", 77L), T0);

        Notification row = Transactions.inTx(factory(), session -> session
                .createQuery("from Notification", Notification.class)
                .getSingleResult());

        assertThat(row.getUserId()).isEqualTo(danaId);
        assertThat(row.getType()).as("the type is stored as its name()").isEqualTo("APPROVAL_REJECTED");
        assertThat(row.getTitle()).isEqualTo("Rejected");
        assertThat(row.getBody()).isEqualTo("Needs another question.");
        assertThat(row.getRefType()).as("NavRef.route() is stored in ref_type").isEqualTo("exam-versions");
        assertThat(row.getRefId()).as("NavRef.entityId() is stored in ref_id").isEqualTo(77L);
        assertThat(row.getReadAt()).as("read_at null is the whole definition of unread").isNull();
        assertThat(row.getCreatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("a reference to nothing writes two nulls, not two empty strings")
    void aReferenceToNothingIsTwoNulls() {
        store.save(danaId, NotificationType.INTEGRITY_ALERT, "Flagged", null, NavRef.none(), T0);

        Notification row = Transactions.inTx(factory(), session -> session
                .createQuery("from Notification", Notification.class)
                .getSingleResult());

        assertThat(row.getRefType()).isNull();
        assertThat(row.getRefId()).isNull();
        assertThat(row.getBody()).as("a null body stays null in the column; the DTO empties it").isNull();
    }

    @Test
    @DisplayName("mark-read is one scoped UPDATE, so a foreign id changes no row at all")
    void markReadTouchesNoRowItDoesNotOwn() {
        long hers = store.save(danaId, NotificationType.GRADE_PUBLISHED,
                "yours", "", NavRef.none(), T0);

        store.markRead(rinaId, hers, T0.plusSeconds(1));

        long unread = Transactions.inTx(factory(), session -> session
                .createQuery("select count(n) from Notification n where n.readAt is null", Long.class)
                .getSingleResult());
        assertThat(unread).as("ownership is in the WHERE clause, not in a check above it")
                .isEqualTo(1);
    }

    private SessionFactory factory() {
        return database.factory();
    }
}
